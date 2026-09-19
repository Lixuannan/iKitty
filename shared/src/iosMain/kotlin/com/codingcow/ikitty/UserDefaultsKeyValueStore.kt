package com.codingcow.ikitty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * `NSUserDefaults` 支撑的 [KeyValueStore]。
 *
 * 和 DataStore 不同，`NSUserDefaults` 没有响应式 API，所以这里自己持一份快照：
 * 设置只会通过 [put] 改动，因此写入后重读一次就足以让快照保持正确。
 *
 * 只负责存取，不懂设置项含义；默认值与回退在 [SettingsRepository]。
 */
class UserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : KeyValueStore {

    private val state = MutableStateFlow(read())

    override val values: Flow<Map<String, SettingValue>> = state.asStateFlow()

    override suspend fun put(entries: Map<String, SettingValue>) {
        entries.forEach { (name, value) ->
            when (value) {
                is SettingValue.Str -> defaults.setObject(value.value, name)
                is SettingValue.Num -> defaults.setDouble(value.value.toDouble(), name)
                is SettingValue.IntValue -> defaults.setInteger(value.value.toLong(), name)
                is SettingValue.Flag -> defaults.setBool(value.value, name)
            }
        }
        state.value = read()
    }

    /**
     * 只把"存过的键"放进快照。
     *
     * 不能对每个已知键都调一次 `stringForKey` 之类的方法：那会把没存过的键当成默认值，
     * [SettingsRepository] 就再也分不清"用户设成了 0"和"从来没设过"。
     */
    private fun read(): Map<String, SettingValue> = buildMap {
        STRING_KEYS.forEach { key ->
            defaults.stringForKey(key)?.let { put(key, SettingValue.Str(it)) }
        }
        FLOAT_KEYS.forEach { key ->
            if (defaults.objectForKey(key) != null) {
                put(key, SettingValue.Num(defaults.doubleForKey(key).toFloat()))
            }
        }
        INT_KEYS.forEach { key ->
            if (defaults.objectForKey(key) != null) {
                put(key, SettingValue.IntValue(defaults.integerForKey(key).toInt()))
            }
        }
        BOOL_KEYS.forEach { key ->
            if (defaults.objectForKey(key) != null) {
                put(key, SettingValue.Flag(defaults.boolForKey(key)))
            }
        }
    }

    private companion object {
        val STRING_KEYS = listOf(
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
        )
        val FLOAT_KEYS = listOf(SettingsKeys.TEMPERATURE, SettingsKeys.TOP_P)
        val INT_KEYS = listOf(SettingsKeys.MAX_TOKENS)
        val BOOL_KEYS = listOf(SettingsKeys.LOCATION_ENABLED)
    }
}

/** iOS 侧默认设置仓库。 */
fun iosSettingsRepository(): SettingsRepository =
    SettingsRepository(UserDefaultsKeyValueStore())
