package com.codingcow.ikitty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSUserDefaults

/**
 * `NSUserDefaults` 支撑的 [KeyValueStore]。
 *
 * **每次读都重新问一次 `NSUserDefaults`**，不缓存快照。缓存看起来更省事，但只要有一个
 * 写入绕过了 [put]（iOS 的 `SyncCredentialWriter` 就是——它必须同步落盘，不能挂起），
 * 缓存里那份就成了旧值，而且**没有任何东西会通知它失效**：
 *
 * - 设置页回显上一次保存的地址与密钥；
 * - `syncNow()` 拿旧地址、旧密钥去发请求；
 * - 换了密钥、游标也归零了，`sinceRev()` 却还停在旧云空间的值上。
 *
 * 这三种症状都在"保存了但读取还是旧的"这一条里。`NSUserDefaults` 自己的读取是在内存里
 * 完成的，重读的代价可以忽略。
 *
 * 只负责存取，不懂设置项含义；默认值与回退在 [SettingsRepository]。
 */
class UserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : KeyValueStore {

    override val values: Flow<Map<String, SettingValue>> = flow { emit(read()) }

    override suspend fun put(entries: Map<String, SettingValue>) {
        entries.forEach { (name, value) ->
            when (value) {
                is SettingValue.Str -> defaults.setObject(value.value, name)
                is SettingValue.Num -> defaults.setDouble(value.value.toDouble(), name)
                is SettingValue.IntValue -> defaults.setInteger(value.value.toLong(), name)
                is SettingValue.Flag -> defaults.setBool(value.value, name)
            }
        }
    }

    /**
     * 只把"存过的键"放进快照。
     *
     * 不能对每个已知键都调一次 `stringForKey` 之类的方法：那会把没存过的键当成默认值，
     * [SettingsRepository] 就再也分不清"用户设成了 0"和"从来没设过"。
     *
     * 键的清单来自 [StoredKeyRegistry]——**不要在这里另列一份**。这里曾经只列了
     * [SettingsKeys]，于是同步凭据（[SyncKeys]）写得进去、读不出来：设置页回显空白、
     * `isConfigured()` 恒为 false，云端同步整体不可用。
     */
    private fun read(): Map<String, SettingValue> = buildMap {
        StoredKeyRegistry.string.forEach { key ->
            defaults.stringForKey(key)?.let { put(key, SettingValue.Str(it)) }
        }
        StoredKeyRegistry.floats.forEach { key ->
            if (defaults.objectForKey(key) != null) {
                put(key, SettingValue.Num(defaults.doubleForKey(key).toFloat()))
            }
        }
        StoredKeyRegistry.ints.forEach { key ->
            if (defaults.objectForKey(key) != null) {
                put(key, SettingValue.IntValue(defaults.integerForKey(key).toInt()))
            }
        }
        StoredKeyRegistry.flags.forEach { key ->
            if (defaults.objectForKey(key) != null) {
                put(key, SettingValue.Flag(defaults.boolForKey(key)))
            }
        }
    }
}

/** iOS 侧默认设置仓库。 */
fun iosSettingsRepository(): SettingsRepository =
    SettingsRepository(UserDefaultsKeyValueStore())
