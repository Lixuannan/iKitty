package com.codingcow.ikitty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

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
        assertFalse(message(3, "你好").toJson().has("images"))
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
}
