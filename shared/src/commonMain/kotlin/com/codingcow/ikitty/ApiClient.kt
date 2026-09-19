package com.codingcow.ikitty

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

/** 一次模型回复。[reasoning] 是推理模型单独返回的思考过程，普通模型为空串。 */
data class ChatCompletion(
    val text: String,
    val reasoning: String,
    val totalTokens: Int?
)

/** 一次"测试连接"的结果，包含界面需要展示的全部信息。 */
data class TestOutcome(
    val latencyMillis: Long,
    val endpoint: String,
    val model: String,
    val reply: String,
    val reasoningChars: Int,
    /** 本次请求实际发出的参数名。 */
    val sentParams: List<String>,
    /** 当前模型不接受、因此被自动跳过的采样参数名。 */
    val skippedParams: List<String>,
    val totalTokens: Int?
)

/** 拉取模型列表的结果；[NotSupported] 表示该服务商没有这个接口，不是错误。 */
sealed interface ModelListOutcome {
    data class Available(val models: List<String>) : ModelListOutcome
    data object NotSupported : ModelListOutcome
}

/**
 * OpenAI 兼容服务商的客户端。
 *
 * 这里只有线上协议的语义：怎么拼请求、怎么解析响应、失败怎么措辞。
 * 真正发请求的能力由 [HttpTransport] 提供（Android 是 OkHttp，iOS 是 Ktor），
 * 所以同一份状态机与容错逻辑两端共用，不会出现"某一边的流式解析更宽松"。
 */
class ApiClient(
    private val transport: HttpTransport,
    private val ioDispatcher: CoroutineDispatcher
) {
    /** 正常聊天请求（非流式）。记忆整理等需要完整 JSON 的场景用它。 */
    suspend fun chat(config: ApiConfig, messages: List<ChatMessage>): ChatCompletion =
        withContext(ioDispatcher) {
            val spec = config.spec()
            val built = buildChatPayload(config, spec, messages, stream = false)
            parseCompletion(post(config, config.provider().chatPath, built))
        }

    /**
     * 流式聊天请求：每收到一段增量文本就调用一次 [onDelta]。
     *
     * 兼容两种情况：服务商支持 SSE 时逐段回调；服务商忽略 `stream` 直接返回普通 JSON 时，
     * 整体解析后一次性返回，回调不会触发。返回值与 [chat] 相同，调用方不必区分。
     */
    suspend fun chatStream(
        config: ApiConfig,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): ChatCompletion = withContext(ioDispatcher) {
        val spec = config.spec()
        val built = buildChatPayload(config, spec, messages, stream = true)
        executeStream(config, built, onDelta)
    }

    /**
     * 测试连接：用和真实聊天完全相同的参数发一条极短请求。
     *
     * 这样只要测试通过，就说明当前地址 / Key / 模型 / 采样参数这一整套配置可用，
     * 而不是只验证了地址能连通。
     */
    suspend fun test(config: ApiConfig): TestOutcome = withContext(ioDispatcher) {
        val spec = config.spec()
        val built = buildChatPayload(config, spec, TEST_MESSAGES, stream = false)
        val started = TimeSource.Monotonic.markNow()
        val completion = parseCompletion(post(config, config.provider().chatPath, built))
        TestOutcome(
            latencyMillis = started.elapsedNow().inWholeMilliseconds,
            endpoint = config.endpointFor(config.provider().chatPath),
            model = config.model.trim(),
            reply = completion.text,
            reasoningChars = completion.reasoning.length,
            sentParams = built.sentParams,
            skippedParams = spec.unsupportedSamplingParams,
            totalTokens = completion.totalTokens
        )
    }

    /** 拉取服务商的模型列表（OpenAI 兼容的 GET /models）。 */
    suspend fun listModels(config: ApiConfig): ModelListOutcome = withContext(ioDispatcher) {
        val path = config.provider().modelsPath ?: return@withContext ModelListOutcome.NotSupported
        val baseUrl = config.normalizedBaseUrl()
        if (baseUrl.isBlank()) throw ApiException("请先填写 Base URL")

        val response = get(baseUrl + path, config)
        // 有些服务商没有 /models，404 / 405 说明"不支持"，不应提示为错误。
        if (response.code == 404 || response.code == 405) {
            return@withContext ModelListOutcome.NotSupported
        }
        if (!response.isSuccessful) throw httpFailure(response.code, response.body)

        // 返回体不是 JSON（网关页面等）时给出统一提示。
        val data = parseJsonObjectOrNull(response.body)?.arrayOrNull("data")
            ?: throw ApiException("返回内容里没有 data 数组")

        val models = data
            .mapNotNull { element -> (element as? JsonObject)?.stringOrEmpty("id") }
            .filter { it.isNotBlank() }
            .sorted()
        ModelListOutcome.Available(models)
    }

    // ---- 响应读取 ----

    /**
     * 执行一次流式请求。
     *
     * SSE 每行形如 `data: {...}`，以 `data: [DONE]` 结束。任何解析不出 JSON 的行都跳过，
     * 保证个别心跳行不会中断整个回复。
     */
    private suspend fun executeStream(
        config: ApiConfig,
        built: BuiltRequest,
        onDelta: (String) -> Unit
    ): ChatCompletion {
        val baseUrl = config.normalizedBaseUrl()
        if (baseUrl.isBlank()) throw ApiException("请先填写 Base URL")

        val text = StringBuilder()
        val reasoning = StringBuilder()
        var totalTokens: Int? = null
        var sawEvent = false

        // 用局部函数而不是带标签的 lambda：这里有多处提前返回，标签写法容易看漏一处。
        fun handleLine(line: String) {
            if (!line.startsWith(SSE_PREFIX)) return
            val data = line.removePrefix(SSE_PREFIX).trim()
            if (data.isEmpty() || data == SSE_DONE) return
            sawEvent = true
            val chunk = parseJsonObjectOrNull(data) ?: return
            chunk.objectOrNull("usage")?.intOrZero("total_tokens")?.takeIf { it > 0 }
                ?.let { totalTokens = it }

            val first = chunk.arrayOrNull("choices")?.firstOrNull() as? JsonObject ?: return
            val delta = first.objectOrNull("delta") ?: return
            val piece = delta.stringOrEmpty("content")
            val thought = delta.stringOrEmpty("reasoning_content")
                .ifEmpty { delta.stringOrEmpty("reasoning") }
            if (thought.isNotEmpty()) reasoning.append(thought)
            if (piece.isNotEmpty()) {
                text.append(piece)
                onDelta(piece)
            }
        }

        val response = try {
            transport.postJsonStreaming(
                url = baseUrl + config.provider().chatPath,
                headers = headersFor(config),
                body = built.payload.toString(),
                onLine = ::handleLine
            )
        } catch (e: HttpTransportException) {
            // 取消请求会让阻塞中的读取抛 IOException，这里还原成取消，避免被当成网络错误。
            if (!currentCoroutineContext().isActive) throw CancellationException("流式请求已取消")
            throw ApiException("网络请求失败：${e.message ?: "未知错误"}")
        }

        if (!response.isSuccessful) throw httpFailure(response.code, response.body)
        // 服务商可能忽略 stream 参数，直接返回了一个完整的普通响应。
        if (!sawEvent) return parseCompletion(response.body)
        if (text.isEmpty() && reasoning.isEmpty()) {
            throw ApiException("模型没有返回任何内容")
        }
        return ChatCompletion(
            text = text.toString().ifEmpty { reasoning.toString() },
            reasoning = reasoning.toString(),
            totalTokens = totalTokens
        )
    }

    private suspend fun post(config: ApiConfig, path: String, built: BuiltRequest): String {
        val baseUrl = config.normalizedBaseUrl()
        if (baseUrl.isBlank()) throw ApiException("请先填写 Base URL")
        val response = try {
            transport.postJson(baseUrl + path, headersFor(config), built.payload.toString())
        } catch (e: HttpTransportException) {
            throw ApiException("网络请求失败：${e.message ?: "未知错误"}")
        }
        if (!response.isSuccessful) throw httpFailure(response.code, response.body)
        return response.body
    }

    private suspend fun get(url: String, config: ApiConfig): HttpResponse = try {
        transport.get(url, headersFor(config))
    } catch (e: HttpTransportException) {
        throw ApiException("网络请求失败：${e.message ?: "未知错误"}")
    }

    private fun headersFor(config: ApiConfig): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        if (config.apiKey.isNotBlank()) {
            put("Authorization", "Bearer ${config.apiKey.trim()}")
        }
    }

    private fun parseCompletion(body: String): ChatCompletion {
        val json = parseJsonObjectOrNull(body)
            ?: throw ApiException("返回内容不是合法 JSON：${body.take(200)}")

        val choices = json.arrayOrNull("choices")
        if (choices == null || choices.isEmpty()) throw ApiException("返回内容里没有 choices")
        val first = choices.firstOrNull() as? JsonObject
        val message = first?.objectOrNull("message")
            ?: throw ApiException("返回内容里没有 message")

        // 推理模型把思考过程放在 reasoning_content；content 可能是 null。
        val text = message.stringOrEmpty("content")
        val reasoning = message.stringOrEmpty("reasoning_content").ifEmpty { message.stringOrEmpty("reasoning") }
        if (text.isEmpty() && reasoning.isEmpty()) throw ApiException("模型没有返回任何内容")

        return ChatCompletion(
            text = text.ifEmpty { reasoning },
            reasoning = reasoning,
            totalTokens = json.objectOrNull("usage")?.intOrZero("total_tokens")?.takeIf { it > 0 }
        )
    }

    private fun httpFailure(code: Int, body: String): ApiException {
        val detail = parseErrorMessage(body)
        val hint = when (code) {
            400, 422 -> "请求参数被拒绝，试试恢复默认参数或调小 max_tokens"
            401, 403 -> "API Key 无效，或没有该模型的权限"
            404 -> "接口地址或模型名称不存在"
            429 -> "触发限流或额度不足"
            in 500..599 -> "服务端暂时不可用，稍后再试"
            else -> null
        }
        val text = buildString {
            append("HTTP ").append(code)
            if (!detail.isNullOrBlank()) append("：").append(detail)
            if (hint != null) append("（").append(hint).append("）")
        }
        return ApiException(text)
    }

    /** GLM / DeepSeek / OpenAI 的错误体都是 `{"error": {...}}`，字段名略有差异。 */
    private fun parseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val json = parseJsonObjectOrNull(body)
        // 错误体不是 JSON（网关 HTML 等），直接截断原文展示。
            ?: return body.take(200)
        val error = json.objectOrNull("error")
        return error?.stringOrEmpty("message")?.takeIf { it.isNotEmpty() }
            ?: error?.stringOrEmpty("code")?.takeIf { it.isNotEmpty() }
            ?: json.stringOrEmpty("message").takeIf { it.isNotEmpty() }
            ?: body.take(200)
    }

    private companion object {
        val TEST_MESSAGES = listOf(ChatMessage("user", "只回复两个字：在呢"))
        const val SSE_PREFIX = "data:"
        const val SSE_DONE = "[DONE]"
    }
}

private fun ApiConfig.endpointFor(path: String): String = normalizedBaseUrl() + path
