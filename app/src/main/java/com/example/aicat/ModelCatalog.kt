package com.example.aicat

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val CUSTOM_PROVIDER_ID = "custom"

private val KILO_WINDOW = Regex("(\\d+)\\s*k")
private val MEGA_WINDOW = Regex("(\\d+)\\s*m")

/**
 * 从模型名推断上下文窗口。
 *
 * 名字里直接写了窗口大小的（`moonshot-v1-8k`、`xxx-128k`、`xxx-1m`）按名字取，
 * 其余给一个保守的通用值；内置能力表里的模型会用显式值覆盖这里。
 */
fun defaultContextWindow(modelId: String): Int {
    val name = modelId.lowercase(Locale.US)
    KILO_WINDOW.find(name)?.let { return it.groupValues[1].toInt() * 1024 }
    MEGA_WINDOW.find(name)?.let { return it.groupValues[1].toInt() * 1024 * 1024 }
    return 32_768
}

/**
 * 思考开关的三态。
 *
 * [AUTO] 表示请求里不发送思考相关字段，由服务商决定默认行为。
 */
enum class ThinkingMode(val label: String) {
    AUTO("自动"),
    ON("开启"),
    OFF("关闭");

    companion object {
        fun fromName(name: String?): ThinkingMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * 数值型可调参数的取值区间。
 *
 * [min] 为 0 且 [decimals] 为 0 的参数（如 max_tokens）用 0 表示"不发送"。
 */
data class NumberParam(
    val min: Float,
    val max: Float,
    val step: Float,
    val default: Float,
    val decimals: Int
) {
    /** Slider 需要的中间档位数（两端之间的离散点数量）。 */
    val sliderSteps: Int get() = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0)

    fun clamp(value: Float): Float = value.coerceIn(min, max)

    /** 收敛到合法范围并按精度取整，避免把 0.30000001 这类浮点误差发给服务端。 */
    fun snap(value: Float): Float {
        val factor = 10.0.pow(decimals)
        return ((clamp(value) * factor).roundToInt() / factor).toFloat()
    }

    fun format(value: Float): String = String.format(Locale.US, "%.${decimals}f", clamp(value))

    /**
     * 请求体里用的数值：在 Double 上按精度取整。
     *
     * 直接 `float.toDouble()` 会把 0.8f 写成 0.800000011920929，
     * 某些服务商会因为这种精度噪声直接拒绝请求。
     */
    fun jsonNumber(value: Float): Double {
        val factor = 10.0.pow(decimals)
        return (clamp(value).toDouble() * factor).roundToLong() / factor
    }
}

/**
 * 模型对推理 / 思考能力的控制方式。
 *
 * 这是"同一个开关在不同服务商下走不同字段"的唯一决策点：
 * GLM 走 `thinking.type`，OpenAI 推理系列走 `reasoning_effort`，
 * DeepSeek-R1 / GLM-Z1 则由模型本身决定、没有开关。
 */
sealed interface ReasoningSpec {
    /** 模型不区分思考模式，也没有开关。 */
    data object Unsupported : ReasoningSpec

    /** 模型始终思考，无法关闭，也不接受 reasoning_effort。 */
    data object AlwaysOn : ReasoningSpec

    /** 通过 GLM 的 `thinking.type` 开关。 */
    data class Toggle(val defaultOn: Boolean) : ReasoningSpec

    /** 通过 `reasoning_effort` 控制。 */
    data class Effort(val supported: List<ReasoningEffort>) : ReasoningSpec
}

/**
 * 单个模型的能力描述。
 *
 * `null` 的采样参数表示该模型不接受它，请求里不会出现对应字段，
 * 设置页也不会渲染对应控件——这是"不同模型可设置参数不同"的唯一来源。
 */
data class ModelSpec(
    val providerId: String,
    val modelId: String,
    val label: String,
    val temperature: NumberParam?,
    val topP: NumberParam?,
    val maxTokens: NumberParam?,
    val reasoning: ReasoningSpec,
    val note: String? = null,
    /**
     * 模型上下文窗口（输入 + 输出），是 [ContextAssembler] 算预算的依据。
     *
     * 默认值按模型名解析（`moonshot-v1-8k` / `-32k` / `-1m` 这类）；
     * 内置表里知道确切数字的模型显式覆盖它。
     */
    val contextWindow: Int = defaultContextWindow(modelId)
) {
    /** 请求里可能出现的参数名，用于设置页与测试结果展示。 */
    val supportedParamNames: List<String> = buildList {
        if (temperature != null) add("temperature")
        if (topP != null) add("top_p")
        if (maxTokens != null) add("max_tokens")
        when (reasoning) {
            is ReasoningSpec.Toggle -> add("thinking")
            is ReasoningSpec.Effort -> add("reasoning_effort")
            ReasoningSpec.AlwaysOn, ReasoningSpec.Unsupported -> Unit
        }
    }

    /** 当前模型不接受的采样参数，设置页用来提示"已自动跳过"。 */
    val unsupportedSamplingParams: List<String> = buildList {
        if (temperature == null) add("temperature")
        if (topP == null) add("top_p")
    }
}

/** 服务商接入信息：地址、鉴权、模型列表与内置模型。 */
data class ProviderSpec(
    val id: String,
    val name: String,
    val baseUrl: String,
    val keyHint: String,
    val models: List<String>,
    val authRequired: Boolean = true,
    /** `null` 表示该服务商不提供模型列表接口。 */
    val modelsPath: String? = "/models",
    val chatPath: String = "/chat/completions",
    val note: String? = null
) {
    val defaultModel: String get() = models.firstOrNull().orEmpty()
}

/**
 * 服务商与模型能力目录。
 *
 * 内置的精确能力表优先服务于 GLM 与 DeepSeek；其它服务商和用户手填的模型名
 * 走 [ModelCatalog.resolve] 的启发式判断，保证任何 OpenAI 兼容端点都能用。
 */
object ModelCatalog {

    private val CUSTOM = ProviderSpec(
        id = CUSTOM_PROVIDER_ID,
        name = "自定义（OpenAI 兼容）",
        baseUrl = "",
        keyHint = "按服务商要求填写，本地服务可留空",
        models = emptyList(),
        note = "任何提供 /chat/completions 的服务都可以填在这里。"
    )

    private val GLM_MODELS = listOf(
        "glm-4.5",
        "glm-4.6",
        "glm-4.5-air",
        "glm-4.5-flash",
        "glm-4-plus",
        "glm-4-air",
        "glm-4-flash",
        "glm-4-long",
        "glm-z1-air",
        "glm-z1-flash"
    )

    private val EFFORT_MODEL = Regex("^(o[1-9](-|$)|gpt-5)", RegexOption.IGNORE_CASE)
    private val ALWAYS_THINKING_MODEL =
        Regex("(reasoner|reasoning|thinking|(^|[-_/])r1([-_/]|$)|z1)", RegexOption.IGNORE_CASE)
    private val GLM_THINKING_MODEL = Regex("glm-4\\.[5-9]", RegexOption.IGNORE_CASE)

    /** 顺序即设置页下拉框顺序；GLM 与 DeepSeek 排在最前。 */
    val providers: List<ProviderSpec> = listOf(
        ProviderSpec(
            id = "zhipu",
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            keyHint = "xxxxx.xxxxx",
            models = GLM_MODELS,
            note = "GLM-4.5 / 4.6 用 thinking 开关控制深度思考，GLM-4 系列不区分思考模式。"
        ),
        ProviderSpec(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            keyHint = "sk-...",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            note = "deepseek-chat 走普通采样参数；deepseek-reasoner 始终思考，不接受 temperature / top_p。"
        ),
        ProviderSpec(
            id = "zai",
            name = "Z.AI（GLM 国际站）",
            baseUrl = "https://api.z.ai/api/paas/v4",
            keyHint = "xxxxx.xxxxx",
            models = GLM_MODELS,
            note = "与智谱同源，模型与参数能力一致。"
        ),
        ProviderSpec(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            keyHint = "sk-...",
            models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "o4-mini")
        ),
        ProviderSpec(
            id = "moonshot",
            name = "Moonshot / Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            keyHint = "sk-...",
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")
        ),
        ProviderSpec(
            id = "dashscope",
            name = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            keyHint = "sk-...",
            models = listOf("qwen-plus", "qwen-turbo", "qwen-max")
        ),
        ProviderSpec(
            id = "siliconflow",
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            keyHint = "sk-...",
            models = listOf("Qwen/Qwen2.5-7B-Instruct", "deepseek-ai/DeepSeek-V3")
        ),
        ProviderSpec(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            keyHint = "sk-or-...",
            models = listOf("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet")
        ),
        ProviderSpec(
            id = "ollama",
            name = "Ollama 本地",
            baseUrl = "http://10.0.2.2:11434/v1",
            keyHint = "本地服务可以留空",
            models = listOf("llama3.2", "qwen2.5", "gemma2"),
            authRequired = false,
            note = "10.0.2.2 是 Android 模拟器访问宿主机的地址；真机请改成电脑的局域网 IP。"
        ),
        CUSTOM
    )

    private val MODEL_SPECS: Map<Pair<String, String>, ModelSpec> = buildMap {
        putAll(specsByKey(glmSpecs("zhipu")))
        // Z.AI 与智谱同源，复用同一套模型能力，只换 providerId。
        putAll(specsByKey(glmSpecs("zai")))
        putAll(specsByKey(deepSeekSpecs()))
        putAll(specsByKey(openAiSpecs()))
    }

    fun provider(id: String): ProviderSpec = providers.firstOrNull { it.id == id } ?: CUSTOM

    /** 根据 Base URL 反查服务商，用于 URL 被改动后自动切换预设。 */
    fun providerIdForBaseUrl(url: String): String? {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isEmpty()) return null
        return providers.firstOrNull { it.baseUrl.isNotEmpty() && it.baseUrl == normalized }?.id
    }

    /**
     * 解析某个模型的能力：内置表 → 模型名启发式 → 通用兜底。
     *
     * 用户手填或从 /models 拉到的模型名不会命中内置表，此时靠名字判断能力，
     * 例如以 glm 开头的模型用 GLM 的取值范围，命中 reasoner / z1 的按始终思考处理。
     */
    fun resolve(providerId: String, modelId: String): ModelSpec {
        val model = modelId.trim()
        MODEL_SPECS[providerId to model]?.let { return it }
        return genericSpec(providerId, model)
    }

    private fun specsByKey(specs: List<ModelSpec>): List<Pair<Pair<String, String>, ModelSpec>> =
        specs.map { (it.providerId to it.modelId) to it }

    private fun genericSpec(providerId: String, modelId: String): ModelSpec {
        val reasoning = when {
            EFFORT_MODEL.containsMatchIn(modelId) ->
                ReasoningSpec.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH))
            ALWAYS_THINKING_MODEL.containsMatchIn(modelId) -> ReasoningSpec.AlwaysOn
            GLM_THINKING_MODEL.containsMatchIn(modelId) -> ReasoningSpec.Toggle(defaultOn = true)
            else -> ReasoningSpec.Unsupported
        }
        val reasoningOnly = reasoning == ReasoningSpec.AlwaysOn
        // GLM 的 temperature 上限是 1.0，其余 OpenAI 兼容端点普遍是 2.0。
        val temperatureMax = if (modelId.startsWith("glm", ignoreCase = true) || providerId == "moonshot") 1f else 2f
        return ModelSpec(
            providerId = providerId,
            modelId = modelId,
            label = modelId.ifBlank { "未指定模型" },
            temperature = if (reasoningOnly) null else NumberParam(0f, temperatureMax, 0.1f, 0.8f, 1),
            topP = if (reasoningOnly) null else NumberParam(0.01f, 1f, 0.01f, 1f, 2),
            maxTokens = tokens(8192, 512),
            reasoning = reasoning,
            note = "没有该模型的内置参数表，按通用 OpenAI 兼容规则处理。"
        )
    }

    // ---- 内置模型能力表 ----

    private fun deepSeekSpecs(): List<ModelSpec> {
        val provider = "deepseek"
        return listOf(
            ModelSpec(
                providerId = provider,
                modelId = "deepseek-chat",
                label = "DeepSeek Chat（V3）",
                temperature = temperature(2f, 1f),
                topP = topP(1f),
                maxTokens = tokens(8192, 512),
                reasoning = ReasoningSpec.Unsupported,
                contextWindow = 64_000
            ),
            ModelSpec(
                providerId = provider,
                modelId = "deepseek-reasoner",
                label = "DeepSeek Reasoner（R1）",
                temperature = null,
                topP = null,
                maxTokens = tokens(65536, 1024),
                reasoning = ReasoningSpec.AlwaysOn,
                note = "始终思考，不接受 temperature / top_p；max_tokens 同时限制思考与回答。",
                contextWindow = 64_000
            )
        )
    }

    // GLM 全系 128K 窗口，只有 glm-4-long 是 1M。
    private fun glmSpecs(providerId: String): List<ModelSpec> = listOf(
        ModelSpec(
            providerId = providerId,
            modelId = "glm-4.5",
            label = "GLM-4.5",
            temperature = temperature(1f, 1f),
            topP = topP(0.95f),
            maxTokens = tokens(32768, 1024),
            reasoning = ReasoningSpec.Toggle(defaultOn = true),
            note = "默认开启思考；关闭思考后建议把 temperature 调到 0.6 左右。"
        ),
        ModelSpec(
            providerId = providerId,
            modelId = "glm-4.6",
            label = "GLM-4.6",
            temperature = temperature(1f, 1f),
            topP = topP(0.95f),
            maxTokens = tokens(32768, 1024),
            reasoning = ReasoningSpec.Toggle(defaultOn = true)
        ),
        ModelSpec(
            providerId = providerId,
            modelId = "glm-4.5-air",
            label = "GLM-4.5-Air",
            temperature = temperature(1f, 1f),
            topP = topP(0.95f),
            maxTokens = tokens(32768, 1024),
            reasoning = ReasoningSpec.Toggle(defaultOn = false),
            note = "默认不开启思考，需要时手动打开。"
        ),
        ModelSpec(
            providerId = providerId,
            modelId = "glm-4.5-flash",
            label = "GLM-4.5-Flash",
            temperature = temperature(1f, 1f),
            topP = topP(0.95f),
            maxTokens = tokens(32768, 1024),
            reasoning = ReasoningSpec.Toggle(defaultOn = false)
        ),
        ModelSpec(
            providerId = providerId,
            modelId = "glm-4-plus",
            label = "GLM-4-Plus",
            temperature = temperature(1f, 0.95f),
            topP = topP(0.7f),
            maxTokens = tokens(4095, 256),
            reasoning = ReasoningSpec.Unsupported
        ),
        ModelSpec(
            providerId = providerId,
            modelId = "glm-4-air",
            label = "GLM-4-Air",
            temperature = temperature(1f, 0.95f),
            topP = topP(0.7f),
            maxTokens = tokens(4095, 256),
            reasoning = ReasoningSpec.Unsupported
        ),
        ModelSpec(
            providerId = providerId,
            modelId = "glm-4-flash",
            label = "GLM-4-Flash",
            temperature = temperature(1f, 0.95f),
            topP = topP(0.7f),
            maxTokens = tokens(4095, 256),
            reasoning = ReasoningSpec.Unsupported
        ),
        ModelSpec(
            providerId = providerId,
            modelId = "glm-4-long",
            label = "GLM-4-Long",
            temperature = temperature(1f, 0.95f),
            topP = topP(0.7f),
            maxTokens = tokens(4095, 256),
            reasoning = ReasoningSpec.Unsupported
        ),
        ModelSpec(
            providerId = providerId,
            modelId = "glm-z1-air",
            label = "GLM-Z1-Air",
            temperature = temperature(1f, 0.6f),
            topP = topP(0.95f),
            maxTokens = tokens(4095, 256),
            reasoning = ReasoningSpec.AlwaysOn,
            note = "思考型模型，无法关闭思考。"
        ),
        ModelSpec(
            providerId = providerId,
            modelId = "glm-z1-flash",
            label = "GLM-Z1-Flash",
            temperature = temperature(1f, 0.6f),
            topP = topP(0.95f),
            maxTokens = tokens(4095, 256),
            reasoning = ReasoningSpec.AlwaysOn
        )
    ).map { spec ->
        spec.copy(contextWindow = if (spec.modelId == "glm-4-long") 1_000_000 else 128_000)
    }

    private fun openAiSpecs(): List<ModelSpec> {
        val provider = "openai"
        fun standard(modelId: String, label: String, maxTokens: Int, window: Int) = ModelSpec(
            providerId = provider,
            modelId = modelId,
            label = label,
            temperature = temperature(2f, 1f),
            topP = topP(1f),
            maxTokens = tokens(maxTokens, 1024),
            reasoning = ReasoningSpec.Unsupported,
            contextWindow = window
        )
        return listOf(
            standard("gpt-4o-mini", "GPT-4o mini", 16384, 128_000),
            standard("gpt-4o", "GPT-4o", 16384, 128_000),
            standard("gpt-4.1-mini", "GPT-4.1 mini", 32768, 1_000_000),
            standard("gpt-4.1", "GPT-4.1", 32768, 1_000_000),
            ModelSpec(
                providerId = provider,
                modelId = "o4-mini",
                label = "o4-mini",
                temperature = null,
                topP = null,
                maxTokens = tokens(100000, 1024),
                reasoning = ReasoningSpec.Effort(
                    listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
                ),
                note = "推理模型只接受 reasoning_effort，不接受 temperature / top_p。",
                contextWindow = 200_000
            )
        )
    }

    private fun temperature(max: Float, default: Float) = NumberParam(0f, max, 0.1f, default, 1)
    private fun topP(default: Float) = NumberParam(0.01f, 1f, 0.01f, default, 2)
    private fun tokens(max: Int, step: Int) = NumberParam(0f, max.toFloat(), step.toFloat(), 0f, 0)
}
