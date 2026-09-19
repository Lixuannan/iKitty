package com.codingcow.ikitty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 设置项的稳定键名。
 *
 * 这些字符串是**跨端契约**：Android 的 DataStore 与 iOS 的 NSUserDefaults 用同一批名字，
 * 改名等于让老用户的设置"回到默认值"。键名与存储介质无关，所以放在 commonMain。
 */
object SettingsKeys {
    const val BASE_URL = "base_url"
    const val API_KEY = "api_key"
    const val MODEL = "model"
    const val TEMPERATURE = "temperature"
    const val TOP_P = "top_p"
    const val MAX_TOKENS = "max_tokens"
    const val REASONING_EFFORT = "reasoning_effort"
    const val THINKING = "thinking"
    const val PROVIDER_ID = "provider_id"

    const val CAT_NAME = "cat_name"
    const val CAT_TRAITS = "cat_traits"
    const val CAT_SPEECH_STYLE = "cat_speech_style"
    const val CAT_FLAVOR = "cat_flavor"
    const val CAT_NOTES = "cat_notes"

    const val LOCATION_ENABLED = "location_enabled"
}

/**
 * 一项设置的取值。
 *
 * 用封闭类型而不是 `Any`：键值存储的实现必须能穷举处理每一种，漏掉一种会在编译期报错，
 * 而不是在运行期悄悄丢数据。
 */
sealed interface SettingValue {
    data class Str(val value: String) : SettingValue
    data class Num(val value: Float) : SettingValue
    data class IntValue(val value: Int) : SettingValue
    data class Flag(val value: Boolean) : SettingValue
}

/**
 * 键值持久化的契约。
 *
 * 只要求两件事：能读出一个快照流，能写入一批键值。介质由平台决定
 * （Android 是 DataStore，iOS 是 NSUserDefaults），键名与语义由 [SettingsRepository] 决定。
 */
interface KeyValueStore {
    /** 当前快照，并在变化时重新发射。 */
    val values: Flow<Map<String, SettingValue>>

    suspend fun put(entries: Map<String, SettingValue>)
}

/**
 * 设置的读写语义。
 *
 * 默认值、缺字段回退、旧版本迁移（没有 providerId 时按 Base URL 反推）都只在这里定义一次，
 * 两端不会各自理解一遍"设置该怎么解释"。
 */
class SettingsRepository(private val store: KeyValueStore) {

    val config: Flow<ApiConfig> = store.values.map { it.toApiConfig() }

    /** 猫猫的角色设定；缺字段时回退到 [CatPersona] 的默认值。 */
    val persona: Flow<CatPersona> = store.values.map { it.toPersona() }

    /** 是否允许用 IP 推测城市。默认开启，关掉之后不会再向外发定位请求。 */
    val locationEnabled: Flow<Boolean> =
        store.values.map { it.boolean(SettingsKeys.LOCATION_ENABLED, true) }

    suspend fun save(config: ApiConfig) {
        store.put(
            mapOf(
                SettingsKeys.BASE_URL to SettingValue.Str(config.normalizedBaseUrl()),
                SettingsKeys.API_KEY to SettingValue.Str(config.apiKey.trim()),
                SettingsKeys.MODEL to SettingValue.Str(config.model.trim()),
                SettingsKeys.TEMPERATURE to SettingValue.Num(config.temperature),
                SettingsKeys.TOP_P to SettingValue.Num(config.topP),
                SettingsKeys.MAX_TOKENS to SettingValue.IntValue(config.maxTokens),
                SettingsKeys.THINKING to SettingValue.Str(config.thinking.name),
                SettingsKeys.REASONING_EFFORT to SettingValue.Str(config.reasoningEffort.name),
                SettingsKeys.PROVIDER_ID to SettingValue.Str(config.providerId)
            )
        )
    }

    suspend fun save(persona: CatPersona) {
        store.put(
            mapOf(
                SettingsKeys.CAT_NAME to SettingValue.Str(persona.name.trim()),
                SettingsKeys.CAT_TRAITS to SettingValue.Str(persona.traits.encodeTraits()),
                SettingsKeys.CAT_SPEECH_STYLE to SettingValue.Str(persona.speechStyle.name),
                SettingsKeys.CAT_FLAVOR to SettingValue.Str(persona.flavor.name),
                SettingsKeys.CAT_NOTES to SettingValue.Str(persona.notes.trim())
            )
        )
    }

    suspend fun saveLocationEnabled(enabled: Boolean) {
        store.put(mapOf(SettingsKeys.LOCATION_ENABLED to SettingValue.Flag(enabled)))
    }
}

private fun Map<String, SettingValue>.string(key: String): String? =
    (this[key] as? SettingValue.Str)?.value

private fun Map<String, SettingValue>.float(key: String): Float? =
    (this[key] as? SettingValue.Num)?.value

private fun Map<String, SettingValue>.int(key: String): Int? =
    (this[key] as? SettingValue.IntValue)?.value

private fun Map<String, SettingValue>.boolean(key: String, fallback: Boolean): Boolean =
    (this[key] as? SettingValue.Flag)?.value ?: fallback

private fun Map<String, SettingValue>.toApiConfig(): ApiConfig {
    val defaults = ApiConfig()
    val baseUrl = string(SettingsKeys.BASE_URL) ?: defaults.baseUrl
    return ApiConfig(
        baseUrl = baseUrl,
        apiKey = string(SettingsKeys.API_KEY) ?: defaults.apiKey,
        model = string(SettingsKeys.MODEL) ?: defaults.model,
        temperature = float(SettingsKeys.TEMPERATURE) ?: defaults.temperature,
        topP = float(SettingsKeys.TOP_P) ?: defaults.topP,
        maxTokens = int(SettingsKeys.MAX_TOKENS) ?: defaults.maxTokens,
        thinking = ThinkingMode.fromName(string(SettingsKeys.THINKING)),
        reasoningEffort = ReasoningEffort.fromName(string(SettingsKeys.REASONING_EFFORT)),
        // 旧版本没有存过 providerId，按 Base URL 反推一次。
        providerId = string(SettingsKeys.PROVIDER_ID)
            ?: ModelCatalog.providerIdForBaseUrl(baseUrl)
            ?: CUSTOM_PROVIDER_ID
    )
}

private fun Map<String, SettingValue>.toPersona(): CatPersona {
    val defaults = CatPersona()
    return CatPersona(
        name = string(SettingsKeys.CAT_NAME) ?: defaults.name,
        traits = parseTraits(string(SettingsKeys.CAT_TRAITS), defaults.traits),
        speechStyle = enumByName<CatSpeechStyle>(string(SettingsKeys.CAT_SPEECH_STYLE))
            ?: defaults.speechStyle,
        flavor = enumByName<CatFlavor>(string(SettingsKeys.CAT_FLAVOR)) ?: defaults.flavor,
        notes = string(SettingsKeys.CAT_NOTES) ?: defaults.notes
    )
}
