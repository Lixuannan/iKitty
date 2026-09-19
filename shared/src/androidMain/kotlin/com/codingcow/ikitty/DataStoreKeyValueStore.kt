package com.codingcow.ikitty

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStore 文件名必须保持 "cat_settings"：改了名字等于让老用户的设置全部回到默认。
private val Context.settingsDataStore by preferencesDataStore(name = "cat_settings")

/**
 * DataStore 支撑的 [KeyValueStore]。
 *
 * 只负责"把键值放进 DataStore"和"读出来"，不懂任何设置项的含义；
 * 默认值与回退在 commonMain 的 [SettingsRepository] 里。
 */
class DataStoreKeyValueStore(context: Context) : KeyValueStore {

    private val appContext = context.applicationContext

    override val values: Flow<Map<String, SettingValue>> =
        appContext.settingsDataStore.data.map { prefs -> prefs.toSettings() }

    override suspend fun put(entries: Map<String, SettingValue>) {
        appContext.settingsDataStore.edit { prefs ->
            entries.forEach { (name, value) ->
                when (value) {
                    is SettingValue.Str -> STRING_KEYS[name]?.let { prefs[it] = value.value }
                    is SettingValue.Num -> FLOAT_KEYS[name]?.let { prefs[it] = value.value }
                    is SettingValue.IntValue -> INT_KEYS[name]?.let { prefs[it] = value.value }
                    is SettingValue.Flag -> BOOL_KEYS[name]?.let { prefs[it] = value.value }
                }
            }
        }
    }

    private fun Preferences.toSettings(): Map<String, SettingValue> = buildMap {
        STRING_KEYS.forEach { (name, key) -> this@toSettings[key]?.let { put(name, SettingValue.Str(it)) } }
        FLOAT_KEYS.forEach { (name, key) -> this@toSettings[key]?.let { put(name, SettingValue.Num(it)) } }
        INT_KEYS.forEach { (name, key) -> this@toSettings[key]?.let { put(name, SettingValue.IntValue(it)) } }
        BOOL_KEYS.forEach { (name, key) -> this@toSettings[key]?.let { put(name, SettingValue.Flag(it)) } }
    }

    private companion object {
        val STRING_KEYS: Map<String, Preferences.Key<String>> = listOf(
            SettingsKeys.BASE_URL,
            SettingsKeys.API_KEY,
            SettingsKeys.MODEL,
            SettingsKeys.REASONING_EFFORT,
            SettingsKeys.THINKING,
            SettingsKeys.PROVIDER_ID,
            SettingsKeys.CAT_NAME,
            SettingsKeys.CAT_TRAITS,
            SettingsKeys.CAT_SPEECH_STYLE,
            SettingsKeys.CAT_FLAVOR,
            SettingsKeys.CAT_NOTES
        ).associateWith { stringPreferencesKey(it) }

        val FLOAT_KEYS: Map<String, Preferences.Key<Float>> = listOf(
            SettingsKeys.TEMPERATURE,
            SettingsKeys.TOP_P
        ).associateWith { floatPreferencesKey(it) }

        val INT_KEYS: Map<String, Preferences.Key<Int>> = listOf(
            SettingsKeys.MAX_TOKENS
        ).associateWith { intPreferencesKey(it) }

        val BOOL_KEYS: Map<String, Preferences.Key<Boolean>> = listOf(
            SettingsKeys.LOCATION_ENABLED
        ).associateWith { booleanPreferencesKey(it) }
    }
}

/** Android 侧默认设置仓库。 */
fun androidSettingsRepository(context: Context): SettingsRepository =
    SettingsRepository(DataStoreKeyValueStore(context))
