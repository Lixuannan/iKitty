package com.example.aicat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 服务端或网络层错误；[message] 已经是可以直接展示给用户的中文描述。 */
class ApiException(message: String) : Exception(message)

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

class ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** 正常聊天请求（非流式）。记忆整理等需要完整 JSON 的场景用它。 */
    suspend fun chat(config: ApiConfig, messages: List<ChatMessage>): ChatCompletion =
        withContext(Dispatchers.IO) {
            val spec = config.spec()
            val built = buildChatPayload(config, spec, messages, stream = false)
            parseCompletion(post(config, config.provider().chatPath, built))
        }

    /**
     * 流式聊天请求：每收到一段增量文本就调用一次 [onDelta]。
     *
     * 兼容两种情况：服务商支持 SSE 时逐段回调；服务商忽略 `stream` 直接返回普通 JSON 时，
     * 整体解析后一次性返回，回调不会触发。返回值与 [chat] 相同，调用方不必区分。
     *
     * 协程被取消时会取消底层 HTTP 调用，不会让读取线程挂住。
     */
    suspend fun chatStream(
        config: ApiConfig,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): ChatCompletion = withContext(Dispatchers.IO) {
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
    suspend fun test(config: ApiConfig): TestOutcome = withContext(Dispatchers.IO) {
        val spec = config.spec()
        val built = buildChatPayload(config, spec, TEST_MESSAGES, stream = false)
        val startedAt = System.nanoTime()
        val completion = parseCompletion(post(config, config.provider().chatPath, built))
        TestOutcome(
            latencyMillis = (System.nanoTime() - startedAt) / 1_000_000,
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
    suspend fun listModels(config: ApiConfig): ModelListOutcome = withContext(Dispatchers.IO) {
        val path = config.provider().modelsPath ?: return@withContext ModelListOutcome.NotSupported
        val baseUrl = config.normalizedBaseUrl()
        if (baseUrl.isBlank()) throw ApiException("请先填写 Base URL")

        val request = Request.Builder()
            .url(baseUrl + path)
            .addHeader("Content-Type", "application/json")
            .apply { addAuthHeader(config) }
            .get()
            .build()

        execute(request).use { response ->
            // 有些服务商没有 /models，404 / 405 说明"不支持"，不应提示为错误。
            if (response.code == 404 || response.code == 405) {
                return@withContext ModelListOutcome.NotSupported
            }
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpFailure(response.code, body)

            val data = try {
                JSONObject(body).optJSONArray("data")
            } catch (_: JSONException) {
                // 返回体不是 JSON（网关页面等），下面的 null 分支会给出统一提示。
                null
            } ?: throw ApiException("返回内容里没有 data 数组")

            val models = buildList {
                for (index in 0 until data.length()) {
                    val id = data.optJSONObject(index)?.stringOrEmpty("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }.sorted()
            ModelListOutcome.Available(models)
        }
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

        val request = Request.Builder()
            .url(baseUrl + config.provider().chatPath)
            .addHeader("Content-Type", "application/json")
            .apply { addAuthHeader(config) }
            .post(built.payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(request)
        val job = currentCoroutineContext()[Job]
        val cancellation = job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw httpFailure(response.code, response.body?.string().orEmpty())
                }
                val source = response.body?.source() ?: throw ApiException("返回内容为空")
                val text = StringBuilder()
                val reasoning = StringBuilder()
                // 服务商可能忽略 stream 参数，这里留一份原文用于整体解析。
                val wholeBody = StringBuilder()
                var totalTokens: Int? = null
                var sawEvent = false

                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    wholeBody.append(line).append('\n')
                    if (!line.startsWith(SSE_PREFIX)) continue
                    val data = line.removePrefix(SSE_PREFIX).trim()
                    if (data.isEmpty() || data == SSE_DONE) continue
                    sawEvent = true
                    val chunk = try {
                        JSONObject(data)
                    } catch (_: JSONException) {
                        continue
                    }
                    chunk.optJSONObject("usage")?.optInt("total_tokens")?.takeIf { it > 0 }
                        ?.let { totalTokens = it }

                    val delta = chunk.optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val piece = delta.stringOrEmpty("content")
                    val thought = delta.stringOrEmpty("reasoning_content")
                        .ifEmpty { delta.stringOrEmpty("reasoning") }
                    if (thought.isNotEmpty()) reasoning.append(thought)
                    if (piece.isNotEmpty()) {
                        text.append(piece)
                        onDelta(piece)
                    }
                }

                if (!sawEvent) return parseCompletion(wholeBody.toString())
                if (text.isEmpty() && reasoning.isEmpty()) {
                    throw ApiException("模型没有返回任何内容")
                }
                return ChatCompletion(
                    text = text.toString().ifEmpty { reasoning.toString() },
                    reasoning = reasoning.toString(),
                    totalTokens = totalTokens
                )
            }
        } catch (e: IOException) {
            // 取消请求会让阻塞中的读取抛 IOException，这里还原成取消，避免被当成网络错误。
            if (!currentCoroutineContext().isActive) throw CancellationException("流式请求已取消")
            throw ApiException("网络请求失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            cancellation?.dispose()
        }
    }

    private fun post(config: ApiConfig, path: String, built: BuiltRequest): String {
        val baseUrl = config.normalizedBaseUrl()
        if (baseUrl.isBlank()) throw ApiException("请先填写 Base URL")

        val request = Request.Builder()
            .url(baseUrl + path)
            .addHeader("Content-Type", "application/json")
            .apply { addAuthHeader(config) }
            .post(built.payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        execute(request).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpFailure(response.code, body)
            return body
        }
    }

    /** OkHttp 的网络层失败（DNS、超时、连接被拒等）统一转成可展示的提示。 */
    private fun execute(request: Request) = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        throw ApiException("网络请求失败：${e.message ?: e.javaClass.simpleName}")
    }

    private fun parseCompletion(body: String): ChatCompletion {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw ApiException("返回内容不是合法 JSON：${body.take(200)}")
        }

        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw ApiException("返回内容里没有 choices")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw ApiException("返回内容里没有 message")

        // 推理模型把思考过程放在 reasoning_content；content 可能是 null。
        val text = message.stringOrEmpty("content")
        val reasoning = message.stringOrEmpty("reasoning_content").ifEmpty { message.stringOrEmpty("reasoning") }
        if (text.isEmpty() && reasoning.isEmpty()) throw ApiException("模型没有返回任何内容")

        return ChatCompletion(
            text = text.ifEmpty { reasoning },
            reasoning = reasoning,
            totalTokens = json.optJSONObject("usage")?.optInt("total_tokens")?.takeIf { it > 0 }
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
        return try {
            val error = JSONObject(body).optJSONObject("error")
            error?.stringOrEmpty("message")?.takeIf { it.isNotEmpty() }
                ?: error?.stringOrEmpty("code")?.takeIf { it.isNotEmpty() }
                ?: JSONObject(body).stringOrEmpty("message").takeIf { it.isNotEmpty() }
                ?: body.take(200)
        } catch (_: JSONException) {
            // 错误体不是 JSON（网关 HTML 等），直接截断原文展示。
            body.take(200)
        }
    }

    private fun Request.Builder.addAuthHeader(config: ApiConfig): Request.Builder = apply {
        if (config.apiKey.isNotBlank()) {
            addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val TEST_MESSAGES = listOf(ChatMessage("user", "只回复两个字：在呢"))
        const val SSE_PREFIX = "data:"
        const val SSE_DONE = "[DONE]"
    }
}

private fun ApiConfig.endpointFor(path: String): String = normalizedBaseUrl() + path

/** 字段值为 null 时 optString 会返回字符串 "null"，这里统一收敛成空串。 */
internal fun JSONObject.stringOrEmpty(key: String): String =
    if (isNull(key)) "" else optString(key).trim()

/** 待发送的请求体，以及本次实际写入的参数名（供"测试连接"展示）。 */
internal class BuiltRequest(val payload: JSONObject, val sentParams: List<String>)

/**
 * 按模型能力拼请求体：只有 [ModelSpec] 声明支持的参数才会出现。
 *
 * 图片走标准的 OpenAI 兼容多模态结构（`content` 数组里的 `image_url` + data URL），
 * 不针对任何一家服务商做特殊处理；纯文本消息的 `content` 仍然是普通字符串，
 * 与加入图片功能之前完全一致。
 *
 * 独立成顶层函数是为了能在纯 JVM 单元测试里直接验证请求体结构。
 */
internal fun buildChatPayload(
    config: ApiConfig,
    spec: ModelSpec,
    messages: List<ChatMessage>,
    stream: Boolean
): BuiltRequest {
    val model = config.model.trim()
    if (model.isEmpty()) throw ApiException("请先填写模型名称")

    val resolved = config.resolvedFor(spec)
    val sent = mutableListOf<String>()
    val payload = JSONObject().apply { put("model", model) }

    resolved.temperature?.let { value ->
        payload.put("temperature", spec.temperature?.jsonNumber(value) ?: value.toDouble())
        sent += "temperature"
    }
    resolved.topP?.let { value ->
        payload.put("top_p", spec.topP?.jsonNumber(value) ?: value.toDouble())
        sent += "top_p"
    }
    resolved.maxTokens?.let {
        payload.put("max_tokens", it)
        sent += "max_tokens"
    }

    when (spec.reasoning) {
        is ReasoningSpec.Toggle -> when (resolved.thinking) {
            ThinkingMode.AUTO -> Unit
            ThinkingMode.ON -> {
                payload.put("thinking", JSONObject().put("type", "enabled"))
                sent += "thinking"
            }
            ThinkingMode.OFF -> {
                payload.put("thinking", JSONObject().put("type", "disabled"))
                sent += "thinking"
            }
        }
        is ReasoningSpec.Effort -> resolved.reasoningEffort.wireValue?.let {
            payload.put("reasoning_effort", it)
            sent += "reasoning_effort"
        }
        ReasoningSpec.AlwaysOn, ReasoningSpec.Unsupported -> Unit
    }

    if (stream) payload.put("stream", true)

    payload.put("messages", JSONArray().apply {
        messages.forEach { message ->
            put(JSONObject().apply {
                put("role", message.role)
                put("content", chatContent(message))
            })
        }
    })

    return BuiltRequest(payload, sent)
}

/**
 * 一条消息在 `content` 字段里的取值。
 *
 * 没有图片时是纯字符串；有图片时是 `[{type:"text"},{type:"image_url"}]`。
 * 只有图片、没有文字时不写空 text 段——部分服务商会拒绝空的文本块。
 */
internal fun chatContent(message: ChatMessage): Any {
    if (message.images.isEmpty()) return message.content
    return JSONArray().apply {
        if (message.content.isNotBlank()) {
            put(JSONObject().put("type", "text").put("text", message.content))
        }
        message.images.forEach { url ->
            put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", url))
            )
        }
    }
}
