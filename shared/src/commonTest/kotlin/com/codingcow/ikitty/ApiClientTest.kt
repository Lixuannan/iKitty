package com.codingcow.ikitty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 线上协议的解析与容错。
 *
 * 这些行为以前只由 Android 侧隐式保证，现在是两端共用的代码，所以用假传输把契约固定下来：
 * SSE 增量、服务商忽略 stream 的退化路径、HTTP 错误措辞、推理字段回退。
 */
class ApiClientTest {

    private class RecordedRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: String
    )

    private class FakeTransport(
        var response: HttpResponse = HttpResponse(200, "{}"),
        var streamLines: List<String> = emptyList()
    ) : HttpTransport {
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += RecordedRequest(url, headers, "")
            return response
        }

        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String
        ): HttpResponse {
            requests += RecordedRequest(url, headers, body)
            return response
        }

        override suspend fun postJsonStreaming(
            url: String,
            headers: Map<String, String>,
            body: String,
            onLine: (String) -> Unit
        ): HttpResponse {
            requests += RecordedRequest(url, headers, body)
            streamLines.forEach(onLine)
            return response
        }
    }

    private val config = ApiConfig(
        providerId = "deepseek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "sk-test",
        model = "deepseek-flash"
    )

    private fun client(transport: HttpTransport) = ApiClient(transport, Dispatchers.Unconfined)

    private fun jsonCompletion(content: String, reasoning: String? = null): String {
        val reasoningField = if (reasoning == null) "" else ""","reasoning_content":"$reasoning""""
        return """{"choices":[{"message":{"content":"$content"$reasoningField}}],"usage":{"total_tokens":7}}"""
    }

    @Test
    fun `a normal completion is parsed with usage`() = runTest {
        val transport = FakeTransport(HttpResponse(200, jsonCompletion("在呢")))
        val result = client(transport).chat(config, listOf(ChatMessage("user", "在吗")))

        assertEquals("在呢", result.text)
        assertEquals("", result.reasoning)
        assertEquals(7, result.totalTokens)
    }

    /** 推理模型可能只给 reasoning_content，content 是 null。 */
    @Test
    fun `reasoning stands in when content is empty`() = runTest {
        val transport = FakeTransport(
            HttpResponse(200, """{"choices":[{"message":{"content":null,"reasoning_content":"想了一下"}}]}""")
        )
        val result = client(transport).chat(config, listOf(ChatMessage("user", "?")))

        assertEquals("想了一下", result.text)
        assertEquals("想了一下", result.reasoning)
    }

    @Test
    fun `the request carries a bearer token and a json content type`() = runTest {
        val transport = FakeTransport(HttpResponse(200, jsonCompletion("好")))
        client(transport).chat(config, listOf(ChatMessage("user", "hi")))

        val request = transport.requests.single()
        assertEquals("https://api.deepseek.com/v1/chat/completions", request.url)
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertTrue(request.body.contains("\"model\":\"deepseek-flash\""))
    }

    /** 没有 Key 时不能发一个空的 Authorization 头（本地服务就靠这个）。 */
    @Test
    fun `a blank api key sends no authorization header`() = runTest {
        val transport = FakeTransport(HttpResponse(200, jsonCompletion("好")))
        client(transport).chat(config.copy(apiKey = ""), listOf(ChatMessage("user", "hi")))
        assertTrue(transport.requests.single().headers["Authorization"] == null)
    }

    @Test
    fun `http failures carry the provider detail and our hint`() = runTest {
        val transport = FakeTransport(
            HttpResponse(401, """{"error":{"message":"Invalid API key"}}""")
        )
        val failure = assertFailsWith<ApiException> {
            client(transport).chat(config, listOf(ChatMessage("user", "hi")))
        }
        assertTrue(failure.message!!.contains("HTTP 401"))
        assertTrue(failure.message!!.contains("Invalid API key"))
        assertTrue(failure.message!!.contains("API Key 无效"))
    }

    /** 网关返回 HTML 时不能崩，截断原文展示即可。 */
    @Test
    fun `a non-json error body is shown truncated`() = runTest {
        val transport = FakeTransport(HttpResponse(502, "<html>bad gateway</html>"))
        val failure = assertFailsWith<ApiException> {
            client(transport).chat(config, listOf(ChatMessage("user", "hi")))
        }
        assertTrue(failure.message!!.contains("HTTP 502"))
        assertTrue(failure.message!!.contains("bad gateway"))
    }

    @Test
    fun `a transport failure becomes a network error message`() = runTest {
        val transport = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>) =
                throw HttpTransportException("Connection refused")

            override suspend fun postJson(url: String, headers: Map<String, String>, body: String) =
                throw HttpTransportException("Connection refused")

            override suspend fun postJsonStreaming(
                url: String,
                headers: Map<String, String>,
                body: String,
                onLine: (String) -> Unit
            ) = throw HttpTransportException("Connection refused")
        }
        val failure = assertFailsWith<ApiException> {
            client(transport).chat(config, listOf(ChatMessage("user", "hi")))
        }
        assertTrue(failure.message!!.contains("网络请求失败"))
        assertTrue(failure.message!!.contains("Connection refused"))
    }

    @Test
    fun `streaming accumulates deltas and reports each one`() = runTest {
        val transport = FakeTransport(
            response = HttpResponse(200, ""),
            streamLines = listOf(
                """data: {"choices":[{"delta":{"content":"喵"}}]}""",
                "", // 心跳空行
                """data: {"choices":[{"delta":{"content":"～"}}],"usage":{"total_tokens":3}}""",
                "data: [DONE]"
            )
        )
        val deltas = mutableListOf<String>()
        val result = client(transport).chatStream(config, listOf(ChatMessage("user", "hi"))) {
            deltas += it
        }

        assertEquals(listOf("喵", "～"), deltas)
        assertEquals("喵～", result.text)
        assertEquals(3, result.totalTokens)
        assertTrue(transport.requests.single().body.contains("\"stream\":true"))
    }

    @Test
    fun `streaming keeps reasoning separate from the reply`() = runTest {
        val transport = FakeTransport(
            response = HttpResponse(200, ""),
            streamLines = listOf(
                """data: {"choices":[{"delta":{"reasoning_content":"思考"}}]}""",
                """data: {"choices":[{"delta":{"content":"答案"}}]}"""
            )
        )
        val result = client(transport).chatStream(config, listOf(ChatMessage("user", "hi"))) {}
        assertEquals("答案", result.text)
        assertEquals("思考", result.reasoning)
    }

    /**
     * 服务商忽略 `stream` 直接返回普通 JSON 时必须整体解析。
     * 这是线上真实存在的情况，退化成空回复会让用户以为模型坏了。
     */
    @Test
    fun `a provider that ignores stream falls back to whole body parsing`() = runTest {
        val transport = FakeTransport(
            response = HttpResponse(200, jsonCompletion("完整回复")),
            streamLines = listOf("") // 没有任何 SSE 事件
        )
        val deltas = mutableListOf<String>()
        val result = client(transport).chatStream(config, listOf(ChatMessage("user", "hi"))) {
            deltas += it
        }

        assertEquals("完整回复", result.text)
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `unparseable sse lines are skipped instead of aborting the reply`() = runTest {
        val transport = FakeTransport(
            response = HttpResponse(200, ""),
            streamLines = listOf(
                "data: {\"choices\":[", // 半截 JSON
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}"
            )
        )
        val result = client(transport).chatStream(config, listOf(ChatMessage("user", "hi"))) {}
        assertEquals("好", result.text)
    }

    @Test
    fun `an empty stream is reported as no content`() = runTest {
        val transport = FakeTransport(
            response = HttpResponse(200, ""),
            streamLines = listOf("""data: {"choices":[{"delta":{}}]}""")
        )
        val failure = assertFailsWith<ApiException> {
            client(transport).chatStream(config, listOf(ChatMessage("user", "hi"))) {}
        }
        assertTrue(failure.message!!.contains("没有返回任何内容"))
    }

    @Test
    fun `model list is parsed and sorted`() = runTest {
        val transport = FakeTransport(
            HttpResponse(200, """{"data":[{"id":"b-model"},{"id":"a-model"},{"id":""}]}""")
        )
        val outcome = client(transport).listModels(config)
        assertEquals(
            ModelListOutcome.Available(listOf("a-model", "b-model")),
            outcome
        )
    }

    /** 没有 /models 接口的服务商不该被当成错误。 */
    @Test
    fun `a missing models endpoint is not an error`() = runTest {
        val transport = FakeTransport(HttpResponse(404, ""))
        assertEquals(ModelListOutcome.NotSupported, client(transport).listModels(config))
    }

    @Test
    fun `test connection reports the model endpoint and sent params`() = runTest {
        val transport = FakeTransport(HttpResponse(200, jsonCompletion("在呢")))
        val outcome = client(transport).test(config)

        assertEquals("deepseek-flash", outcome.model)
        assertEquals("https://api.deepseek.com/v1/chat/completions", outcome.endpoint)
        assertEquals("在呢", outcome.reply)
        assertTrue(outcome.sentParams.contains("temperature"))
        assertTrue(outcome.latencyMillis >= 0)
    }
}
