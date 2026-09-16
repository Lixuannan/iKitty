package com.codingcow.ikitty

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多模态请求体的结构契约。
 *
 * 图片必须走标准的 OpenAI 兼容 `content` 数组，而不是任何一家服务商的私有字段；
 * 纯文本消息的请求体保持和加入图片功能之前完全一致。
 */
class MultimodalPayloadTest {

    private val spec = ModelCatalog.resolve("google", "gemini-3-flash")

    private fun config() = ApiConfig(providerId = "google", model = "gemini-3-flash")

    private fun firstMessage(payload: org.json.JSONObject) =
        payload.getJSONArray("messages").getJSONObject(0)

    @Test
    fun `plain text keeps a string content and no stream flag`() {
        val built = buildChatPayload(config(), spec, listOf(ChatMessage("user", "你好")), stream = false)
        assertEquals("你好", firstMessage(built.payload).getString("content"))
        assertFalse(built.payload.has("stream"))
    }

    @Test
    fun `images become content parts with image_url data urls`() {
        val message = ChatMessage(
            role = "user",
            content = "看看这两张",
            images = listOf("data:image/jpeg;base64,AAA", "data:image/jpeg;base64,BBB")
        )
        val built = buildChatPayload(config(), spec, listOf(message), stream = false)

        val parts = firstMessage(built.payload).getJSONArray("content")
        assertEquals(3, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("看看这两张", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AAA",
            parts.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
        assertEquals(
            "data:image/jpeg;base64,BBB",
            parts.getJSONObject(2).getJSONObject("image_url").getString("url")
        )
    }

    @Test
    fun `an image-only message has no empty text part`() {
        val parts = chatContent(ChatMessage("user", "", listOf("data:image/jpeg;base64,AAA"))) as JSONArray
        assertEquals(1, parts.length())
        assertEquals("image_url", parts.getJSONObject(0).getString("type"))
    }

    @Test
    fun `streaming adds the stream flag without touching the text field`() {
        val built = buildChatPayload(config(), spec, listOf(ChatMessage("user", "hi")), stream = true)
        assertTrue(built.payload.getBoolean("stream"))
        assertEquals("hi", firstMessage(built.payload).getString("content"))
    }

    @Test
    fun `sent params still follow the model capability table`() {
        val built = buildChatPayload(config(), spec, listOf(ChatMessage("user", "hi")), stream = false)
        assertTrue(built.sentParams.contains("temperature"))
        assertTrue(built.sentParams.contains("top_p"))
    }
}
