package com.codingcow.ikitty

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 多模态请求体的结构契约。
 *
 * 图片必须走标准的 OpenAI 兼容 `content` 数组，而不是任何一家服务商的私有字段；
 * 纯文本消息的请求体保持和加入图片功能之前完全一致。
 *
 * 这是线上契约，所以断言写在 commonMain 侧：Android 与 iOS 只要共用这份代码，
 * 就不可能出现"两端发出的请求体不一样"。
 */
class MultimodalPayloadTest {

    private val spec = ModelCatalog.resolve("google", "gemini-3-flash")

    private fun config() = ApiConfig(providerId = "google", model = "gemini-3-flash")

    private fun firstMessage(payload: JsonObject): JsonObject =
        (payload["messages"] as JsonArray)[0] as JsonObject

    @Test
    fun `plain text keeps a string content and no stream flag`() {
        val built = buildChatPayload(config(), spec, listOf(ChatMessage("user", "你好")), stream = false)
        assertEquals(JsonPrimitive("你好"), firstMessage(built.payload)["content"])
        assertFalse(built.payload.containsKey("stream"))
    }

    @Test
    fun `images become content parts with image_url data urls`() {
        val message = ChatMessage(
            role = "user",
            content = "看看这两张",
            images = listOf("data:image/jpeg;base64,AAA", "data:image/jpeg;base64,BBB")
        )
        val built = buildChatPayload(config(), spec, listOf(message), stream = false)

        val parts = firstMessage(built.payload)["content"] as JsonArray
        assertEquals(3, parts.size)
        assertEquals("text", (parts[0] as JsonObject)["type"]?.asString())
        assertEquals("看看这两张", (parts[0] as JsonObject)["text"]?.asString())
        assertEquals("image_url", (parts[1] as JsonObject)["type"]?.asString())
        assertEquals(
            "data:image/jpeg;base64,AAA",
            ((parts[1] as JsonObject)["image_url"] as JsonObject)["url"]?.asString()
        )
        assertEquals(
            "data:image/jpeg;base64,BBB",
            ((parts[2] as JsonObject)["image_url"] as JsonObject)["url"]?.asString()
        )
    }

    @Test
    fun `an image-only message has no empty text part`() {
        val parts = chatContent(ChatMessage("user", "", listOf("data:image/jpeg;base64,AAA"))) as JsonArray
        assertEquals(1, parts.size)
        assertEquals("image_url", (parts[0] as JsonObject)["type"]?.asString())
    }

    @Test
    fun `streaming adds the stream flag without touching the text field`() {
        val built = buildChatPayload(config(), spec, listOf(ChatMessage("user", "hi")), stream = true)
        assertTrue(built.payload["stream"].toString() == "true")
        assertEquals("hi", firstMessage(built.payload)["content"]?.asString())
    }

    @Test
    fun `sent params still follow the model capability table`() {
        val built = buildChatPayload(config(), spec, listOf(ChatMessage("user", "hi")), stream = false)
        assertTrue(built.sentParams.contains("temperature"))
        assertTrue(built.sentParams.contains("top_p"))
    }

    /**
     * 数值精度契约。
     *
     * `0.8f.toDouble()` 是 `0.800000011920929`，直接发出去会被部分服务商拒绝；
     * `org.json` 又会把 `1.0` 写成 `1`。这两个行为都必须原样保留。
     */
    @Test
    fun `numeric params keep the old wire formatting`() {
        val built = buildChatPayload(config(), spec, listOf(ChatMessage("user", "hi")), stream = false)
        assertEquals("0.8", built.payload["temperature"].toString())
        assertEquals("1", built.payload["top_p"].toString())
    }

    /** `max_tokens` 为 0 表示"不限制"，此时请求体里不能出现这个字段。 */
    @Test
    fun `an unset max_tokens never reaches the wire`() {
        val built = buildChatPayload(config(), spec, listOf(ChatMessage("user", "hi")), stream = false)
        assertFalse(built.payload.containsKey("max_tokens"))
    }

    private fun kotlinx.serialization.json.JsonElement.asString(): String =
        (this as JsonPrimitive).content
}
