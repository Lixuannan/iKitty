package com.codingcow.ikitty

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 带图片的消息落盘契约：重启后聊天记录里的图片不能丢。 */
class StoredMessageTest {

    private fun message(
        seq: Long,
        content: String,
        images: List<String> = emptyList(),
        error: Boolean = false
    ) = StoredMessage(seq, StoredMessage.ROLE_USER, content, seq * 1000, error, images)

    @Test
    fun `images survive a json round trip`() {
        val original = message(1, "看看这两张", listOf("a.jpg", "b.jpg"))
        assertEquals(original, StoredMessage.fromJson(original.toJson().toString()))
    }

    @Test
    fun `an image-only message is valid and round trips`() {
        val original = message(2, "", listOf("a.jpg"))
        val parsed = StoredMessage.fromJson(original.toJson().toString())!!
        assertEquals("", parsed.content)
        assertEquals(listOf("a.jpg"), parsed.images)
    }

    @Test
    fun `a text-only message omits the images field`() {
        assertFalse(message(3, "你好").toJson().containsKey("images"))
    }

    @Test
    fun `a message with neither text nor images is rejected`() {
        assertNull(StoredMessage.fromJson("""{"seq":1,"role":"user","content":""}"""))
    }

    @Test
    fun `blank image names are dropped`() {
        val parsed = StoredMessage.fromJson(
            """{"seq":1,"role":"user","content":"x","images":["a.jpg","","  "]}"""
        )!!
        assertEquals(listOf("a.jpg"), parsed.images)
    }

    @Test
    fun `toWire resolves images and drops the ones that are gone`() {
        val wire = message(4, "hi", listOf("a.jpg", "gone.jpg")).toWire { name ->
            if (name == "a.jpg") "data:image/jpeg;base64,AA" else null
        }
        assertEquals("hi", wire.content)
        assertEquals(listOf("data:image/jpeg;base64,AA"), wire.images)
    }

    @Test
    fun `a plain text message keeps a plain wire content`() {
        val wire = message(5, "只有文字").toWire()
        assertEquals("只有文字", wire.content)
        assertEquals(emptyList<String>(), wire.images)
    }

    /** 坏行、空行、非对象、缺 role——JSONL 里任何一行出问题都只能跳过，不能让读取中断。 */
    @Test
    fun `unparseable lines return null instead of throwing`() {
        assertNull(StoredMessage.fromJson("<html>gateway</html>"))
        assertNull(StoredMessage.fromJson(""))
        assertNull(StoredMessage.fromJson("[1,2,3]"))
        assertNull(StoredMessage.fromJson("""{"content":"没有角色"}"""))
    }

    /**
     * 落盘字节契约。
     *
     * 与 `org.json` 的实现相比，这里比对的是**结构**而不是字符串：两个实现的键顺序
     * 本来就不同（Android 按插入顺序、参考实现按哈希），键集合与取值才是真正的契约。
     * 同样重要的是"条件写入"：`error` / `images` 只在需要时出现。
     */
    @Test
    fun `json shape is the on-disk contract`() {
        val withImages = StoredMessage(
            seq = 1, role = "user", content = "看看这两张", createdAt = 1000,
            images = listOf("a.jpg", "b.jpg")
        ).toJson()
        assertEquals(
            buildJsonObject {
                put("seq", 1)
                put("role", "user")
                put("content", "看看这两张")
                put("at", 1000)
                put(
                    "images",
                    buildJsonArray {
                        add(JsonPrimitive("a.jpg"))
                        add(JsonPrimitive("b.jpg"))
                    }
                )
            },
            withImages
        )

        val plain = StoredMessage(3, "assistant", "你好", 3000).toJson()
        assertEquals(
            buildJsonObject {
                put("seq", 3)
                put("role", "assistant")
                put("content", "你好")
                put("at", 3000)
            },
            plain
        )

        val failed = StoredMessage(4, "assistant", "网络请求失败", 4000, localError = true).toJson()
        assertEquals(JsonPrimitive(true), failed["error"])
        assertFalse(plain.containsKey("error"))
    }
}
