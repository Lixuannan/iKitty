package com.codingcow.ikitty

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "cat_settings")

/** 用 DataStore 持久化 API 设置。 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val apiKey = stringPreferencesKey("api_key")
        val model = stringPreferencesKey("model")
        val temperature = floatPreferencesKey("temperature")
        val topP = floatPreferencesKey("top_p")
        val maxTokens = intPreferencesKey("max_tokens")
        val reasoningEffort = stringPreferencesKey("reasoning_effort")
        val thinking = stringPreferencesKey("thinking")
        val providerId = stringPreferencesKey("provider_id")

        val catName = stringPreferencesKey("cat_name")
        val catTraits = stringPreferencesKey("cat_traits")
        val catSpeechStyle = stringPreferencesKey("cat_speech_style")
        val catFlavor = stringPreferencesKey("cat_flavor")
        val catNotes = stringPreferencesKey("cat_notes")

        val locationEnabled = booleanPreferencesKey("location_enabled")
    }

    val config: Flow<ApiConfig> = appContext.settingsDataStore.data.map { prefs ->
        val defaults = ApiConfig()
        val baseUrl = prefs[Keys.baseUrl] ?: defaults.baseUrl
        ApiConfig(
            baseUrl = baseUrl,
            apiKey = prefs[Keys.apiKey] ?: defaults.apiKey,
            model = prefs[Keys.model] ?: defaults.model,
            temperature = prefs[Keys.temperature] ?: defaults.temperature,
            topP = prefs[Keys.topP] ?: defaults.topP,
            maxTokens = prefs[Keys.maxTokens] ?: defaults.maxTokens,
            thinking = ThinkingMode.fromName(prefs[Keys.thinking]),
            reasoningEffort = ReasoningEffort.fromName(prefs[Keys.reasoningEffort]),
            // 旧版本没有存过 providerId，按 Base URL 反推一次。
            providerId = prefs[Keys.providerId]
                ?: ModelCatalog.providerIdForBaseUrl(baseUrl)
                ?: CUSTOM_PROVIDER_ID
        )
    }

    /** 猫猫的角色设定；缺字段时回退到 [CatPersona] 的默认值。 */
    val persona: Flow<CatPersona> = appContext.settingsDataStore.data.map { prefs ->
        val defaults = CatPersona()
        CatPersona(
            name = prefs[Keys.catName] ?: defaults.name,
            traits = parseTraits(prefs[Keys.catTraits], defaults.traits),
            speechStyle = enumByName<CatSpeechStyle>(prefs[Keys.catSpeechStyle]) ?: defaults.speechStyle,
            flavor = enumByName<CatFlavor>(prefs[Keys.catFlavor]) ?: defaults.flavor,
            notes = prefs[Keys.catNotes] ?: defaults.notes
        )
    }

    /** 是否允许用 IP 推测城市。默认开启，关掉之后不会再向外发定位请求。 */
    val locationEnabled: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[Keys.locationEnabled] ?: true
    }

    suspend fun save(config: ApiConfig) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[Keys.baseUrl] = config.normalizedBaseUrl()
            prefs[Keys.apiKey] = config.apiKey.trim()
            prefs[Keys.model] = config.model.trim()
            prefs[Keys.temperature] = config.temperature
            prefs[Keys.topP] = config.topP
            prefs[Keys.maxTokens] = config.maxTokens
            prefs[Keys.thinking] = config.thinking.name
            prefs[Keys.reasoningEffort] = config.reasoningEffort.name
            prefs[Keys.providerId] = config.providerId
        }
    }

    suspend fun save(persona: CatPersona) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[Keys.catName] = persona.name.trim()
            prefs[Keys.catTraits] = persona.traits.encodeTraits()
            prefs[Keys.catSpeechStyle] = persona.speechStyle.name
            prefs[Keys.catFlavor] = persona.flavor.name
            prefs[Keys.catNotes] = persona.notes.trim()
        }
    }

    suspend fun saveLocationEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { prefs ->
            prefs[Keys.locationEnabled] = enabled
        }
    }
}
