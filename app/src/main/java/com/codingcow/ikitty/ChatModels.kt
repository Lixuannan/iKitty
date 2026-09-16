package com.codingcow.ikitty

/**
 * 一条发给服务商的消息。
 *
 * [images] 是已经编码好的数据 URL（`data:image/jpeg;base64,...`）。为空表示普通纯文本消息，
 * 此时请求体里的 `content` 就是一个字符串；非空时按 OpenAI 兼容的多模态 content 数组发送。
 * 这里刻意不关心具体服务商：图片走标准的 `image_url` 结构，而不是某家的私有字段。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val images: List<String> = emptyList()
)

/** 思考深度。OFF 表示不发送 reasoning_effort。 */
enum class ReasoningEffort(val wireValue: String?, val label: String) {
    OFF(null, "关闭"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    companion object {
        fun fromName(name: String?): ReasoningEffort =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * 一次请求实际会发出的参数。
 *
 * `null` / [ThinkingMode.AUTO] / [ReasoningEffort.OFF] 都表示该字段不出现在请求体里。
 */
data class ResolvedParams(
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int?,
    val thinking: ThinkingMode,
    val reasoningEffort: ReasoningEffort
)

data class ApiConfig(
    val providerId: String = "openai",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-5.5",
    val temperature: Float = 0.8f,
    val topP: Float = 1f,
    /** 0 表示不限制，请求里不发送 max_tokens。 */
    val maxTokens: Int = 0,
    val thinking: ThinkingMode = ThinkingMode.AUTO,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.OFF
) {
    /** 去掉尾部斜杠，方便拼接 endpoint。 */
    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/')

    fun provider(): ProviderSpec = ModelCatalog.provider(providerId)

    fun spec(): ModelSpec = ModelCatalog.resolve(providerId, model)

    /**
     * 显式的一步：把配置值收敛到当前模型支持的范围。
     *
     * 请求构建直接使用这里的结果，不再自己兜底，于是"模型不支持某参数"
     * 只在 [ModelSpec] 里判断一次。
     */
    fun resolvedFor(spec: ModelSpec): ResolvedParams = ResolvedParams(
        temperature = spec.temperature?.snap(temperature),
        topP = spec.topP?.snap(topP),
        maxTokens = spec.maxTokens?.let { param ->
            if (maxTokens <= 0) null else param.snap(maxTokens.toFloat()).toInt()
        },
        thinking = if (spec.reasoning is ReasoningSpec.Toggle) thinking else ThinkingMode.AUTO,
        reasoningEffort = when (spec.reasoning) {
            is ReasoningSpec.Effort ->
                reasoningEffort.takeIf { it != ReasoningEffort.OFF && spec.reasoning.supported.contains(it) }
                    ?: ReasoningEffort.OFF
            else -> ReasoningEffort.OFF
        }
    )
}
