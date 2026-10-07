package com.codingcow.ikitty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import platform.Foundation.NSUserDefaults

/**
 * `NSUserDefaults` 支撑的 [KeyValueStore]。
 *
 * 两个性质一起成立，缺一不可：
 *
 * 1. **每次收集都重新问一次 `NSUserDefaults`**，不缓存快照。缓存看起来更省事，但只要有一个
 *    写入绕过了 [put]（iOS 的 `SyncCredentialWriter` 就是——它必须同步落盘，不能挂起），
 *    缓存里那份就成了旧值，而且**没有任何东西会通知它失效**：
 *    - 设置页回显上一次保存的地址与密钥；
 *    - 点「上传到云端」时拿旧地址、旧密钥去发请求；
 *    - 换了密钥、游标也归零了，`sinceRev()` 却还停在旧云空间的值上。
 *    `NSUserDefaults` 自己的读取是在内存里完成的，重读的代价可以忽略。
 * 2. **写入之后要重新发射一次**。这是 [KeyValueStore.values] 的契约（"当前快照，并在变化时
 *    重新发射"），也是 Android 的 DataStore 一直以来的行为。iOS 这份曾经是个只发射一次的冷流，
 *    于是依赖"写入后重发"的调用方在 iOS 上行为不同：设置了新值，别的收集者还以为没变。
 *    这里用一个自增的版本号驱动 `map`，每次收集都会现读一次，写入后所有收集者都会重读。
 *
 * 只负责存取，不懂设置项含义；默认值与回退在 [SettingsRepository]。
 */
class UserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : KeyValueStore {

    /**
     * 写入计数器，`values` 的重发信号。
     *
     * 只当"有变化"用，值本身没有意义；用自增而不是布尔，是为了连续两次写入都能各自触发一次
     * 发射（StateFlow 会丢掉与当前值相同的发射）。
     */
    private val revision = MutableStateFlow(0)

    override val values: Flow<Map<String, SettingValue>> = revision.map { read() }

    override suspend fun put(entries: Map<String, SettingValue>) {
        entries.forEach { (name, value) ->
            when (value) {
                is SettingValue.Str -> defaults.setObject(value.value, name)
                is SettingValue.Num -> defaults.setDouble(value.value.toDouble(), name)
                is SettingValue.IntValue -> defaults.setInteger(value.value.toLong(), name)
                is SettingValue.Flag -> defaults.setBool(value.value, name)
            }
        }
        // 让这次写入立刻落到磁盘，而不是等系统挑个时机。设置页随后就关了，
        // 而用户对"保存"的预期是"现在就已经存好了"。
        defaults.synchronize()
        revision.update { it + 1 }
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
