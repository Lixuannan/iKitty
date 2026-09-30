package com.codingcow.ikitty

import android.content.Context
import androidx.datastore.core.DataStore
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
 * 同步凭据用的 DataStore 文件。
 *
 * 与 `cat_settings` 分开是刻意的：同步凭据（账号密钥、服务地址、推过哪些消息）
 * 的生命周期与用户设置完全不同——换账号要整块作废，而设置不该被牵连。
 * 混在同一个文件里还会让"清空设置"和"退出云空间"变成两个互相影响的操作。
 */
private val Context.syncDataStore by preferencesDataStore(name = SYNC_DATASTORE_NAME)

/** 同步凭据的 DataStore 文件名。改了等于让已配对的用户重新配对一次。 */
const val SYNC_DATASTORE_NAME = "sync_credentials"

/**
 * DataStore 支撑的 [KeyValueStore]。
 *
 * 有两个命名空间，必须显式选一个：
 * - **设置**（默认）：只认 [SettingsKeys] 里登记过的键与类型。写一个没登记的键会被丢掉，
 *   这是刻意的——它挡住"拼错键名导致设置静默丢失"，而且能保住"没存过"与"存过默认值"
 *   的区别（旧版本没有 providerId 时靠这个区别反推服务商）。
 * - **同步凭据**：键是 [SyncKeys] 那批，全是字符串，没有默认值语义。
 *
 * 不允许调用方传任意名字：DataStore 的委托只能在顶层创建，动态文件名做不到。
 */
class DataStoreKeyValueStore(context: Context, name: String = SETTINGS_DATASTORE_NAME) : KeyValueStore {

    private val appContext = context.applicationContext
    private val namespace = if (name == SYNC_DATASTORE_NAME) Namespace.Sync else Namespace.Settings

    private val target: DataStore<Preferences>
        get() = if (namespace == Namespace.Sync) appContext.syncDataStore else appContext.settingsDataStore

    override val values: Flow<Map<String, SettingValue>> =
        target.data.map { prefs ->
            when (namespace) {
                Namespace.Settings -> prefs.toSettings()
                Namespace.Sync -> prefs.toSyncStrings()
            }
        }

    override suspend fun put(entries: Map<String, SettingValue>) {
        target.edit { prefs ->
            entries.forEach { (name, value) ->
                when (namespace) {
                    Namespace.Settings -> writeSetting(prefs, name, value)
                    // 同步凭据全是字符串；非字符串也按字面量存，读取侧统一当成字符串。
                    Namespace.Sync -> prefs[stringPreferencesKey(name)] = value.asLiteral()
                }
            }
        }
    }

    private fun writeSetting(prefs: androidx.datastore.preferences.core.MutablePreferences, name: String, value: SettingValue) {
        when (value) {
            is SettingValue.Str -> STRING_KEYS[name]?.let { prefs[it] = value.value }
            is SettingValue.Num -> FLOAT_KEYS[name]?.let { prefs[it] = value.value }
            is SettingValue.IntValue -> INT_KEYS[name]?.let { prefs[it] = value.value }
            is SettingValue.Flag -> BOOL_KEYS[name]?.let { prefs[it] = value.value }
        }
    }

    private fun Preferences.toSettings(): Map<String, SettingValue> = buildMap {
        STRING_KEYS.forEach { (name, key) -> this@toSettings[key]?.let { put(name, SettingValue.Str(it)) } }
        FLOAT_KEYS.forEach { (name, key) -> this@toSettings[key]?.let { put(name, SettingValue.Num(it)) } }
        INT_KEYS.forEach { (name, key) -> this@toSettings[key]?.let { put(name, SettingValue.IntValue(it)) } }
        BOOL_KEYS.forEach { (name, key) -> this@toSettings[key]?.let { put(name, SettingValue.Flag(it)) } }
    }

    /** 同步命名空间：把所有字符串键读出来。只有 [SyncKeys] 会写这个文件。 */
    private fun Preferences.toSyncStrings(): Map<String, SettingValue> = buildMap {
        asMap().forEach { (key, value) ->
            if (value is String) put(key.name, SettingValue.Str(value))
        }
    }

    private enum class Namespace { Settings, Sync }

    private companion object {
        const val SETTINGS_DATASTORE_NAME = "cat_settings"

        // 键的清单来自 commonMain 的 StoredKeyRegistry：两端读的必须是同一批键名。
        // 这里曾经各自抄了一份只有 SettingsKeys 的清单，iOS 那份的后果是同步凭据读不回来。
        val STRING_KEYS: Map<String, Preferences.Key<String>> =
            StoredKeyRegistry.string.associateWith { stringPreferencesKey(it) }

        val FLOAT_KEYS: Map<String, Preferences.Key<Float>> =
            StoredKeyRegistry.floats.associateWith { floatPreferencesKey(it) }

        val INT_KEYS: Map<String, Preferences.Key<Int>> =
            StoredKeyRegistry.ints.associateWith { intPreferencesKey(it) }

        val BOOL_KEYS: Map<String, Preferences.Key<Boolean>> =
            StoredKeyRegistry.flags.associateWith { booleanPreferencesKey(it) }
    }
}

private fun SettingValue.asLiteral(): String = when (this) {
    is SettingValue.Str -> value
    is SettingValue.Num -> value.toString()
    is SettingValue.IntValue -> value.toString()
    is SettingValue.Flag -> value.toString()
}

/** Android 侧默认设置仓库。 */
fun androidSettingsRepository(context: Context): SettingsRepository =
    SettingsRepository(DataStoreKeyValueStore(context))
