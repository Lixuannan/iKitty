package com.codingcow.ikitty

import kotlinx.datetime.TimeZone
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

    /** 固定时区，断言才不依赖跑测试的机器。2023-11-15 06:13 星期三（UTC+8）。 */
    private val shanghai = TimeZone.of("Asia/Shanghai")
    private val AT = 1_700_000_000_000L

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
        val wire = StoredMessage(
            seq = 4,
            role = StoredMessage.ROLE_USER,
            content = "hi",
            createdAt = AT,
            images = listOf("a.jpg", "gone.jpg")
        ).toWire(shanghai) { name ->
            if (name == "a.jpg") "data:image/jpeg;base64,AA" else null
        }
        // 正文前面带上这条消息自己的时间：历史进请求时每条都如此。
        assertEquals("[2023-11-15 06:13 星期三] hi", wire.content)
        assertEquals(listOf("data:image/jpeg;base64,AA"), wire.images)
    }

    /**
     * 「每条消息都带时间戳」的请求侧契约。
     *
     * 模型必须能分清哪句是什么时候说的，才不会再沿用几轮前自己报过的时间。
     */
    @Test
    fun `every prompt message carries its own timestamp`() {
        val first = StoredMessage(1, "user", "你好", AT)
        val second = StoredMessage(2, "assistant", "在呢", AT + 60_000L)

        assertEquals("[2023-11-15 06:13 星期三] 你好", first.contentForPrompt(shanghai))
        assertEquals("[2023-11-15 06:14 星期三] 在呢", second.contentForPrompt(shanghai))
    }

    /** 只有图片的消息也要拿到前缀，否则模型不知道这张图是什么时候发的。 */
    @Test
    fun `an image-only message still carries the time`() {
        val onlyImage = StoredMessage(3, "user", "", AT, images = listOf("a.jpg"))
        assertEquals("[2023-11-15 06:13 星期三]", onlyImage.contentForPrompt(shanghai))
    }

    /** 老记录没有 `at`（解析成 0）时不能凭空补一个「1970 年」。 */
    @Test
    fun `a message without a stored time gets no prefix`() {
        val legacy = StoredMessage(4, "user", "老记录", createdAt = 0L)
        assertEquals("老记录", legacy.contentForPrompt(shanghai))
        assertEquals("老记录", legacy.toWire(shanghai).content)
    }

    @Test
    fun `a plain text message keeps its images empty on the wire`() {
        val wire = message(5, "只有文字").toWire(shanghai)
        assertEquals("[1970-01-01 08:00 星期四] 只有文字", wire.content)
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
