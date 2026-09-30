package com.codingcow.ikitty

import platform.Foundation.NSUserDefaults

/**
 * 同步凭据的**同步写入**入口：不挂起、不等待网络。
 *
 * 为什么需要它，而不是直接用 `SyncFacade.setServiceUrl` 那三个 `suspend` 写入：
 *
 * - 那三个都是 `suspend`，从 Swift 看是 `async throws`。设置页要"先落盘、再关页面"，
 *   就得在主 actor 的 `Task` 里 `await` 它们——而随后那次同步会被排在同一个保存动作后面，
 *   于是主线程一直等到整轮同步（网络 + 重写聊天日志）结束，界面表现为卡死。
 * - Kotlin/Native 里没有"同步等一个 suspend 函数"的正确姿势：`runBlocking` 在主线程上
 *   会与 `Dispatchers.Main` 的续体互相等（它排干的 run loop 与协程的 `dispatch_async`
 *   续体不是同一条路径），正是要避免的那类死锁。
 *
 * 所以落盘走这里：`NSUserDefaults` 的写入在 Kotlin/Native 上是直接调用 Foundation，
 * 函数返回时值就已经写进 store 了，Swift 侧不需要 `await`。上传仍然由
 * `IosAppEnvironment.syncNow()` 在后台作用域上完成。
 *
 * 键名复用 [SyncKeys]：它只在 [KeyValueSyncCredentialStore] 里被解释一次，
 * 这里如果另抄一份，"改了键名等于让老用户重新配对"这条约束就会在两边漂移。
 *
 * 只写**凭据**（地址、密钥、开关）。游标、推过什么、删过什么由 [clearSyncState] 统一
 * 归零——那些值的生命周期与"用户点了保存"无关，但在换云空间时必须一起作废。
 */
class SyncCredentialWriter(
    private val defaults: NSUserDefaults,
    /**
     * 安装 id 的生成规则。与 [KeyValueSyncCredentialStore] 一致（随机消息 id），
     * 注入只是为了能在测试里固定它。
     */
    private val newDeviceId: () -> String = { newMessageId() }
) {

    /** 一次写入之后，界面上应当显示的那份凭据。 */
    class Applied(
        val accountKey: String,
        val serviceUrl: String,
        val includeApiKey: Boolean
    )

    /**
     * 本机安装 id：首次读取时生成并**立刻落盘**。
     *
     * 与 [KeyValueSyncCredentialStore.deviceId] 是同一份值、同一个键。这里再实现一次是因为
     * [apply] 要保证"写完之后马上起的后台同步读到的是同一个 id"，而挂起路径拿不到它。
     */
    fun deviceId(): String {
        val existing = defaults.stringForKey(SyncKeys.DEVICE_ID).orEmpty()
        if (existing.isNotBlank()) return existing
        val created = newDeviceId()
        persist(mapOf(SyncKeys.DEVICE_ID to SettingValue.Str(created)))
        return created
    }

    /** 当前落盘的凭据；供设置页同步回填，不需要 `await`。 */
    fun current(): Applied = Applied(
        accountKey = defaults.stringForKey(SyncKeys.ACCOUNT_KEY).orEmpty(),
        serviceUrl = defaults.stringForKey(SyncKeys.SERVICE_URL).orEmpty().trimEnd('/'),
        includeApiKey = defaults.boolForKey(SyncKeys.INCLUDE_API_KEY)
    )

    /**
     * 写地址、开关与密钥，返回落盘后的那三个值。
     *
     * 顺序是刻意的：**密钥最后写**。密钥一变就等于换了云空间，在那之前把
     * [clearSyncState] 跑完，任何调用方都不可能只写一半。
     *
     * 密钥与上一次**相同**时不动游标：用户只是改了地址或开关，不该被当成换账号——
     * 那会让本轮同步从零开始重拉一遍全部历史。
     */
    fun apply(serviceUrl: String, accountKey: String, includeApiKey: Boolean): Applied {
        val trimmedUrl = serviceUrl.trim().trimEnd('/')
        val trimmedKey = accountKey.trim()
        val previousKey = defaults.stringForKey(SyncKeys.ACCOUNT_KEY).orEmpty()
        val switchingKey = trimmedKey != previousKey

        // 先写地址、开关与身份：它们与云空间无关，无论如何都要落盘。
        persist(
            mapOf(
                SyncKeys.SERVICE_URL to SettingValue.Str(trimmedUrl),
                SyncKeys.INCLUDE_API_KEY to SettingValue.Flag(includeApiKey),
                SyncKeys.DEVICE_ID to SettingValue.Str(deviceId())
            )
        )

        if (trimmedKey.isEmpty()) {
            // 空密钥的语义是"解除绑定"，不是"存了一个空密钥"。
            defaults.removeObjectForKey(SyncKeys.ACCOUNT_KEY)
        } else {
            persist(mapOf(SyncKeys.ACCOUNT_KEY to SettingValue.Str(trimmedKey)))
        }
        if (switchingKey) clearSyncState()

        return Applied(accountKey = trimmedKey, serviceUrl = trimmedUrl, includeApiKey = includeApiKey)
    }

    /**
     * 解除本机与云空间的绑定：删掉密钥，并把与那个云空间绑定的状态整体归零。
     *
     * 不删地址：用户很可能只是想换一个密钥，地址还是同一个 Worker。
     */
    fun clearAccountKey() {
        defaults.removeObjectForKey(SyncKeys.ACCOUNT_KEY)
        clearSyncState()
    }

    /**
     * 作废与"当前这个云空间"绑定的全部状态。
     *
     * 与 [KeyValueSyncCredentialStore.clearSyncState] 写的是同一批键值，只是不走挂起路径。
     * 字段对齐要手动维持——两处不一致的后果是"换了密钥但游标还在"，新账号上的同步会从
     * 一个错误的起点开始。
     */
    private fun clearSyncState() {
        persist(
            mapOf(
                SyncKeys.SINCE_REV to SettingValue.Str("0"),
                SyncKeys.SETTINGS_UPDATED_AT to SettingValue.Str("0"),
                SyncKeys.CLOUD_SETTINGS_AT to SettingValue.Str("0"),
                SyncKeys.SETTINGS_FINGERPRINT to SettingValue.Str(""),
                // 换云空间之后"已经对过设置账"不再成立，下一轮要重新先拉后推。
                SyncKeys.SETTINGS_SYNCED to SettingValue.Str("false"),
                SyncKeys.CLOUD_SETTINGS_HAS_API_KEY to SettingValue.Str("false"),
                SyncKeys.PUSHED_IDS to SettingValue.Str(""),
                SyncKeys.DELETED_IDS to SettingValue.Str("")
            )
        )
    }

    private fun persist(entries: Map<String, SettingValue>) {
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
    }
}
