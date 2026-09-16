package com.codingcow.ikitty

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
        "glm-5.3",
        "glm-5.3-flash"
    )

    private val EFFORT_MODEL = Regex("^(o[1-9](-|$)|gpt-5)", RegexOption.IGNORE_CASE)
    private val ALWAYS_THINKING_MODEL =
        Regex("(reasoner|reasoning|thinking|(^|[-_/])r1([-_/]|$)|z1)", RegexOption.IGNORE_CASE)

    /** GLM-4.5 / 4.6 走 `thinking.type` 开关。 */
    private val GLM_THINKING_MODEL = Regex("glm-4\\.[5-9]", RegexOption.IGNORE_CASE)

    /** GLM-5 起改用 `reasoning_effort`。 */
    private val GLM_EFFORT_MODEL = Regex("glm-[5-9]\\.", RegexOption.IGNORE_CASE)

    private val EFFORT_LEVELS =
        listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)

    /** 顺序即设置页下拉框顺序：国内常用的 GLM / DeepSeek 在最前，聚合平台与本地在后。 */
    val providers: List<ProviderSpec> = listOf(
        ProviderSpec(
            id = "zhipu",
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            keyHint = "xxxxx.xxxxx",
            models = GLM_MODELS,
            note = "GLM-5.3 用 reasoning_effort 控制思考深度。"
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
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            keyHint = "sk-...",
            models = listOf("deepseek-v4-pro", "deepseek-flash"),
            note = "V4 Pro 偏推理与编码，Flash 偏速度与成本。"
        ),
        ProviderSpec(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            keyHint = "sk-...",
            models = listOf("gpt-5.5", "gpt-5.3-codex"),
            note = "两个都是推理模型，只接受 reasoning_effort，不发送 temperature / top_p。"
        ),
        ProviderSpec(
            id = "anthropic",
            name = "Anthropic Claude",
            baseUrl = "https://api.anthropic.com/v1",
            keyHint = "sk-ant-...",
            models = listOf("claude-opus-4.7", "claude-sonnet-4.6"),
            note = "走 Anthropic 的 OpenAI 兼容层，鉴权仍是 Authorization: Bearer。"
        ),
        ProviderSpec(
            id = "google",
            name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            keyHint = "AIza...",
            models = listOf("gemini-3.1-pro", "gemini-3-flash"),
            note = "Gemini 的 OpenAI 兼容端点，API Key 直接放在 Bearer 里。"
        ),
        ProviderSpec(
            id = "xai",
            name = "xAI Grok",
            baseUrl = "https://api.x.ai/v1",
            keyHint = "xai-...",
            models = listOf("grok-4")
        ),
        ProviderSpec(
            id = "dashscope",
            name = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            keyHint = "sk-...",
            models = listOf("qwen3.6-max", "qwen3-coder-next")
        ),
        ProviderSpec(
            id = "moonshot",
            name = "Moonshot / Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            keyHint = "sk-...",
            models = listOf("kimi-k3")
        ),
        ProviderSpec(
            id = "minimax",
            name = "MiniMax",
            baseUrl = "https://api.minimaxi.com/v1",
            keyHint = "eyJ...",
            models = listOf("MiniMax-M3"),
            note = "国际站地址；国内站请改成 https://api.minimax.chat/v1。"
        ),
        ProviderSpec(
            id = "doubao",
            name = "字节豆包（火山方舟）",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            keyHint = "...",
            models = listOf("doubao-seed-2.0-pro"),
            note = "方舟也支持填推理接入点 ID（ep-... 开头），直接填模型名同样可以。"
        ),
        ProviderSpec(
            id = "hunyuan",
            name = "腾讯混元",
            baseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
            keyHint = "sk-...",
            models = listOf("hunyuan-turbos")
        ),
        ProviderSpec(
            id = "ernie",
            name = "百度文心（千帆）",
            baseUrl = "https://qianfan.baidubce.com/v2",
            keyHint = "bce-v3/...",
            models = listOf("ernie-x1.1")
        ),
        ProviderSpec(
            id = "mistral",
            name = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            keyHint = "...",
            models = listOf("mistral-small-4")
        ),
        ProviderSpec(
            id = "siliconflow",
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            keyHint = "sk-...",
            models = listOf("meta-llama/Llama-4-Maverick-17B-128E-Instruct")
        ),
        ProviderSpec(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            keyHint = "sk-or-...",
            models = listOf("meta-llama/llama-4-maverick")
        ),
        ProviderSpec(
            id = "ollama",
            name = "Ollama 本地",
            baseUrl = "http://10.0.2.2:11434/v1",
            keyHint = "本地服务可以留空",
            models = listOf("llama4:maverick"),
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
        putAll(specsByKey(anthropicSpecs()))
        putAll(specsByKey(googleSpecs()))
        putAll(specsByKey(xaiSpecs()))
        putAll(specsByKey(dashScopeSpecs()))
        putAll(specsByKey(moonshotSpecs()))
        putAll(specsByKey(minimaxSpecs()))
        putAll(specsByKey(doubaoSpecs()))
        putAll(specsByKey(hunyuanSpecs()))
        putAll(specsByKey(ernieSpecs()))
        putAll(specsByKey(mistralSpecs()))
        // Meta 的 Llama 只以开放权重形式发布，没有官方托管 API，经聚合平台/本地运行时接入。
        putAll(specsByKey(metaSpecs()))
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
            EFFORT_MODEL.containsMatchIn(modelId) -> ReasoningSpec.Effort(EFFORT_LEVELS)
            GLM_EFFORT_MODEL.containsMatchIn(modelId) -> ReasoningSpec.Effort(EFFORT_LEVELS)
            ALWAYS_THINKING_MODEL.containsMatchIn(modelId) -> ReasoningSpec.AlwaysOn
            GLM_THINKING_MODEL.containsMatchIn(modelId) -> ReasoningSpec.Toggle(defaultOn = true)
            else -> ReasoningSpec.Unsupported
        }
        val reasoningOnly = reasoning == ReasoningSpec.AlwaysOn
        // GLM / Moonshot / Anthropic 的 temperature 上限是 1.0，其余 OpenAI 兼容端点普遍是 2.0。
        val temperatureMax = when {
            modelId.startsWith("glm", ignoreCase = true) -> 1f
            providerId == "moonshot" || providerId == "anthropic" -> 1f
            else -> 2f
        }
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

    /**
     * 内置模型条目的通用构造：默认值统一取 temperature 0.8 / top_p 1.0 / 输出长度不限制。
     *
     * 上下文窗口按各系列的公开值填写——GLM-5.3 与 MiniMax M3 的 1M 已核实，
     * 其余取同系列上一代的保守值；官方数字有变化时改这里即可。
     * 没有内置条目的模型仍走 [genericSpec] 的 32K 兜底。
     */
    private fun builtIn(
        providerId: String,
        modelId: String,
        label: String,
        window: Int,
        temperatureMax: Float? = 2f,
        reasoning: ReasoningSpec = ReasoningSpec.Unsupported,
        maxOutput: Int = 8192,
        note: String? = null
    ): ModelSpec {
        // 始终思考的模型，以及显式传 null 的模型（如 GPT-5 系列），都不接受 temperature / top_p。
        val samplingAllowed = temperatureMax != null && reasoning != ReasoningSpec.AlwaysOn
        return ModelSpec(
            providerId = providerId,
            modelId = modelId,
            label = label,
            temperature = if (samplingAllowed) temperature(temperatureMax!!, 0.8f) else null,
            topP = if (samplingAllowed) topP(1f) else null,
            maxTokens = tokens(maxOutput, 512),
            reasoning = reasoning,
            note = note,
            contextWindow = window
        )
    }

    private fun deepSeekSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "deepseek",
            modelId = "deepseek-v4-pro",
            label = "DeepSeek V4 Pro",
            window = 128_000,
            note = "面向推理与编码。"
        ),
        builtIn(
            providerId = "deepseek",
            modelId = "deepseek-flash",
            label = "DeepSeek Flash",
            window = 128_000,
            note = "低成本的快速档。"
        )
    )

    /** GLM-5.3 起改用 reasoning_effort 控制思考深度。 */
    private fun glmSpecs(providerId: String): List<ModelSpec> = listOf(
        builtIn(
            providerId = providerId,
            modelId = "glm-5.3",
            label = "GLM-5.3",
            window = 1_000_000,
            temperatureMax = 1f,
            reasoning = ReasoningSpec.Effort(EFFORT_LEVELS),
            maxOutput = 32768,
            note = "面向 Coding / Agent，用 reasoning_effort 控制思考深度。"
        ),
        builtIn(
            providerId = providerId,
            modelId = "glm-5.3-flash",
            label = "GLM-5.3-Flash",
            window = 200_000,
            temperatureMax = 1f,
            reasoning = ReasoningSpec.Effort(EFFORT_LEVELS),
            maxOutput = 32768,
            note = "轻量档，适合需要快速响应的场景。"
        )
    )

    private fun openAiSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "openai",
            modelId = "gpt-5.5",
            label = "GPT-5.5",
            window = 400_000,
            temperatureMax = null,
            reasoning = ReasoningSpec.Effort(EFFORT_LEVELS),
            maxOutput = 32768,
            note = "推理模型只接受 reasoning_effort，不接受 temperature / top_p。"
        ),
        builtIn(
            providerId = "openai",
            modelId = "gpt-5.3-codex",
            label = "GPT-5.3 Codex",
            window = 400_000,
            temperatureMax = null,
            reasoning = ReasoningSpec.Effort(EFFORT_LEVELS),
            maxOutput = 32768,
            note = "面向编码的推理模型，同样只接受 reasoning_effort。"
        )
    )

    private fun anthropicSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "anthropic",
            modelId = "claude-opus-4.7",
            label = "Claude Opus 4.7",
            window = 200_000,
            temperatureMax = 1f,
            note = "综合与 Agent 场景。"
        ),
        builtIn(
            providerId = "anthropic",
            modelId = "claude-sonnet-4.6",
            label = "Claude Sonnet 4.6",
            window = 200_000,
            temperatureMax = 1f,
            note = "编码与日常场景，速度与成本更均衡。"
        )
    )

    private fun googleSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "google",
            modelId = "gemini-3.1-pro",
            label = "Gemini 3.1 Pro",
            window = 1_000_000,
            note = "综合与多模态场景。"
        ),
        builtIn(
            providerId = "google",
            modelId = "gemini-3-flash",
            label = "Gemini 3 Flash",
            window = 1_000_000,
            note = "多模态的快速档。"
        )
    )

    private fun xaiSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "xai",
            modelId = "grok-4",
            label = "Grok 4",
            window = 256_000,
            note = "综合与推理场景。"
        )
    )

    private fun dashScopeSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "dashscope",
            modelId = "qwen3.6-max",
            label = "Qwen3.6-Max",
            window = 256_000,
            note = "综合与中文场景。"
        ),
        builtIn(
            providerId = "dashscope",
            modelId = "qwen3-coder-next",
            label = "Qwen3-Coder-Next",
            window = 256_000,
            note = "面向编码。"
        )
    )

    private fun moonshotSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "moonshot",
            modelId = "kimi-k3",
            label = "Kimi K3",
            window = 256_000,
            temperatureMax = 1f,
            note = "长上下文与编码。"
        )
    )

    private fun minimaxSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "minimax",
            modelId = "MiniMax-M3",
            label = "MiniMax M3",
            window = 1_000_000,
            temperatureMax = 1f,
            note = "1M 上下文、原生多模态，面向推理与编码。"
        )
    )

    private fun doubaoSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "doubao",
            modelId = "doubao-seed-2.0-pro",
            label = "Doubao Seed 2.0 Pro",
            window = 256_000,
            temperatureMax = 1f,
            note = "综合与多模态场景。"
        )
    )

    private fun hunyuanSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "hunyuan",
            modelId = "hunyuan-turbos",
            label = "混元 TurboS",
            window = 128_000,
            note = "综合与中文场景。"
        )
    )

    private fun ernieSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "ernie",
            modelId = "ernie-x1.1",
            label = "文心 ERNIE-X1.1",
            window = 128_000,
            temperatureMax = 1f,
            note = "中文与 Agent 场景。"
        )
    )

    private fun mistralSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "mistral",
            modelId = "mistral-small-4",
            label = "Mistral Small 4",
            window = 128_000,
            temperatureMax = 1f,
            note = "开放权重系列。"
        )
    )

    /** Meta 的 Llama 没有官方托管 API，同一个权重经聚合平台与本地运行时接入，模型 ID 各不相同。 */
    private fun metaSpecs(): List<ModelSpec> = listOf(
        builtIn(
            providerId = "openrouter",
            modelId = "meta-llama/llama-4-maverick",
            label = "Llama 4 Maverick",
            window = 1_000_000,
            note = "开放权重，经 OpenRouter 接入。"
        ),
        builtIn(
            providerId = "siliconflow",
            modelId = "meta-llama/Llama-4-Maverick-17B-128E-Instruct",
            label = "Llama 4 Maverick",
            window = 1_000_000,
            note = "开放权重，经硅基流动接入。"
        ),
        builtIn(
            providerId = "ollama",
            modelId = "llama4:maverick",
            label = "Llama 4 Maverick（本地）",
            window = 1_000_000,
            note = "本地运行时，实际可用窗口取决于机器显存。"
        )
    )

    private fun temperature(max: Float, default: Float) = NumberParam(0f, max, 0.1f, default, 1)
    private fun topP(default: Float) = NumberParam(0.01f, 1f, 0.01f, default, 2)
    private fun tokens(max: Int, step: Int) = NumberParam(0f, max.toFloat(), step.toFloat(), 0f, 0)
}
