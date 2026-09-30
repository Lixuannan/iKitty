package com.codingcow.ikitty

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设置在云端的表示。
 *
 * 为什么自己写一份而不是复用 [settingsToJson]：
 * - 字段名要**与设置键名一致**（`baseUrl`、`catName`…），因为同步的是"状态"，
 *   键名是跨端契约；备份用的是另一套更啰嗦但更抗误解的名字，两张格式不该互相绑死；
 * - 需要单独表达"这次同步要不要带上 API Key"这件事。
 *
 * 字段集合必须与 [SettingsRepository] 的读写集合保持一致，并有测试守着。
 */
object SyncSettingsCodec {

    const val KV_KEY = "settings"

    /** 与 [SettingsKeys] 相同的字符串；改任何一个都等于让老用户的云端设置读不回来。 */
    private const val BASE_URL = "base_url"
    private const val API_KEY = "api_key"
    private const val MODEL = "model"
    private const val TEMPERATURE = "temperature"
    private const val TOP_P = "top_p"
    private const val MAX_TOKENS = "max_tokens"
    private const val REASONING_EFFORT = "reasoning_effort"
    private const val THINKING = "thinking"
    private const val PROVIDER_ID = "provider_id"
    private const val CAT_NAME = "cat_name"
    private const val CAT_TRAITS = "cat_traits"
    private const val CAT_SPEECH_STYLE = "cat_speech_style"
    private const val CAT_FLAVOR = "cat_flavor"
    private const val CAT_NOTES = "cat_notes"
    private const val LOCATION_ENABLED = "location_enabled"

    /** 这一份设置是从哪台设备推上去的，便于排查"为什么云端是这台机器的设置"。 */
    private const val UPDATED_BY = "updatedBy"

    /**
     * 编码一份设置。
     *
     * [includeApiKey] 为 false 时**整个字段不出现**（而不是写成空串）：空串在解码侧
     * 与"没有这个字段"无法区分，会把"不同步 Key"和"Key 是空的"混成一件事。
     */
    fun encode(settings: BackupSettings, includeApiKey: Boolean, deviceId: String): String {
        val config = settings.config
        val persona = settings.persona
        return buildJsonObject {
            put(BASE_URL, config.normalizedBaseUrl())
            if (includeApiKey) put(API_KEY, config.apiKey.trim())
            put(MODEL, config.model.trim())
            put(TEMPERATURE, jsonNumber(config.temperature.toDouble()))
            put(TOP_P, jsonNumber(config.topP.toDouble()))
            put(MAX_TOKENS, config.maxTokens)
            put(THINKING, config.thinking.name)
            put(REASONING_EFFORT, config.reasoningEffort.name)
            put(PROVIDER_ID, config.providerId)

            put(CAT_NAME, persona.name)
            put(CAT_TRAITS, persona.traits.encodeTraits())
            put(CAT_SPEECH_STYLE, persona.speechStyle.name)
            put(CAT_FLAVOR, persona.flavor.name)
            put(CAT_NOTES, persona.notes)

            put(LOCATION_ENABLED, settings.locationEnabled)
            put(UPDATED_BY, deviceId)
        }.toString()
    }

    /**
     * 解码云端设置。
     *
     * [currentApiKey] 是本地已有的 Key：云端那份**没有带** `api_key` 字段时沿用它。
     * 这是"API Key 同步开关"的关键语义——关掉开关只表示"我不上传我的 Key"，
     * 不应该顺手把云端已有的 Key 抹掉。
     *
     * 缺字段一律回退到默认值，这样旧版本推上去的设置也能读。
     */
    fun decode(payload: String, currentApiKey: String): BackupSettings? {
        val json = parseJsonObjectOrNull(payload) ?: return null

        val configDefaults = ApiConfig()
        val baseUrl = json.stringOrEmpty(BASE_URL).ifBlank { configDefaults.baseUrl }
        val config = ApiConfig(
            baseUrl = baseUrl,
            apiKey = if (json.containsKey(API_KEY)) json.optString(API_KEY) else currentApiKey,
            model = json.stringOrEmpty(MODEL).ifBlank { configDefaults.model },
            temperature = json.floatOrNull(TEMPERATURE) ?: configDefaults.temperature,
            topP = json.floatOrNull(TOP_P) ?: configDefaults.topP,
            // 用"键在不在"区分"没存过"和"存了 0"：0 表示不限制，是一个有意义的值。
            maxTokens = json.intOrNull(MAX_TOKENS) ?: configDefaults.maxTokens,
            thinking = enumByName<ThinkingMode>(json.optString(THINKING)) ?: configDefaults.thinking,
            reasoningEffort = ReasoningEffort.fromName(json.optString(REASONING_EFFORT)),
            // 这条回退顺序与 SettingsRepository 一致：老数据没有 providerId 时按地址反推。
            providerId = json.stringOrEmpty(PROVIDER_ID)
                .ifBlank { ModelCatalog.providerIdForBaseUrl(baseUrl) ?: CUSTOM_PROVIDER_ID }
        )

        val personaDefaults = CatPersona()
        val persona = CatPersona(
            name = json.stringOrEmpty(CAT_NAME).ifBlank { personaDefaults.name },
            traits = parseTraits(
                json.optString(CAT_TRAITS).takeIf { it.isNotBlank() },
                personaDefaults.traits
            ),
            speechStyle = enumByName<CatSpeechStyle>(json.optString(CAT_SPEECH_STYLE))
                ?: personaDefaults.speechStyle,
            flavor = enumByName<CatFlavor>(json.optString(CAT_FLAVOR)) ?: personaDefaults.flavor,
            notes = json.optString(CAT_NOTES)
        )

        return BackupSettings(config, persona, json.booleanOr(LOCATION_ENABLED, true))
    }

    /** 云端那一份里有没有带 API Key。用于给用户解释"为什么这台设备不用重填 Key"。 */
    fun containsApiKey(payload: String): Boolean =
        parseJsonObjectOrNull(payload)?.containsKey(API_KEY) == true
}

private fun JsonObject.floatOrNull(key: String): Float? =
    optString(key).takeIf { it.isNotBlank() }?.toFloatOrNull()

private fun JsonObject.intOrNull(key: String): Int? =
    optString(key).takeIf { it.isNotBlank() }?.toDoubleOrNull()?.toInt()
