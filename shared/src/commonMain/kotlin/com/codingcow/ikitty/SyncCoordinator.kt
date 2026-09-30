package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 同步对界面暴露的状态。
 *
 * 用"用户能采取的行动"来分层（与 [SyncException] 同一套思路），而不是把异常原样抛给界面：
 * - [NeedsAccountKey] 要求用户去设置页填密钥；
 * - [Failed] 只是这次没成，本地数据完好，点名了原因。
 */
sealed interface SyncStatus {
    /** 还没填同步服务地址（或填的是空串）：界面提示用户去设置页填。 */
    data object Disabled : SyncStatus
    data object Idle : SyncStatus
    data class Working(val label: String) : SyncStatus
    data class Done(val message: String) : SyncStatus
    data class Failed(val message: String) : SyncStatus
    data class NeedsAccountKey(val message: String) : SyncStatus
}

/**
 * 同步的门面：界面只跟它打交道，不直接碰 [SyncEngine] 或本地日志。
 *
 * 职责有三块，都是"界面做不了"或"不该在两处各写一遍"的：
 * 1. **触发策略**：前台化、消息落盘后防抖、用户手动点，全部汇到 [syncNow]；
 * 2. **把异常翻译成状态**：[SyncException] → [SyncStatus]，文案只在这里写一次；
 * 3. **同步之后的收尾**：设置要重新读进 [ChatEngine]，否则界面还在用旧配置。
 *
 * [syncNow] 是 **suspend** 的：调用方自己决定在哪个作用域、哪个调度器上跑它。
 * 落盘（写地址与密钥）与上传是两件事，**不要在同一个入口里串起来**——那会让
 * "保存设置"变成一个可能耗时几十秒的网络操作，界面只能干等。
 *
 * 不做系统级后台调度（那需要各平台的原生 API：WorkManager / BGTaskScheduler）；
 * 触发点仍限定在应用还活着的时候：前台化、消息落盘后防抖、用户手动点。
 */
interface SyncFacade {
    val status: StateFlow<SyncStatus>

    /** 是否配置了账号密钥。界面据此决定"开启同步"还是"显示已开启"。 */
    suspend fun isConfigured(): Boolean

    /** 当前的同步服务地址；空串表示还没配置。 */
    suspend fun serviceUrl(): String

    /**
     * 设置同步服务地址（部署好的 Worker 根地址）。
     *
     * 存进凭据而不是编译进常量：Worker 地址是每个自建实例各不相同的部署细节，
     * 而这是个自托管的开源应用——把某个人的域名写进代码里，别人就没法用了。
     */
    suspend fun setServiceUrl(url: String)

    suspend fun syncNow()

    /**
     * 跑一轮同步，并**把这一轮的终态直接返回**。
     *
     * 与 [syncNow] 的区别只在"结果怎么给"：[syncNow] 把终态写进 [status]，调用方要靠
     * "状态变了"去推断结束。当这一轮的前后状态**相等**（例如本来就没配服务地址，
     * 触发前后都是 [SyncStatus.Disabled]）时状态流不会重新发射，那个推断就永远不会发生——
     * 调用方只能一直等到超时。这个方法由发起者自己拿到返回值，不依赖任何状态发射。
     *
     * 已经有一轮在跑时等它那一轮的终态，不另起一轮：两轮替换会互相覆盖日志，而调用方要的
     * 只是"这次同步到底成没成"。凭据没配好时不做任何网络请求，直接返回对应的终态。
     */
    suspend fun syncNowAndAwait(): SyncStatus

    suspend fun setAccountKey(key: String)

    /** 当前账号密钥，供设置页回填（输入框是密码样式，不回填用户会以为没配过）。 */
    suspend fun accountKey(): String

    suspend fun clearAccountKey()

    /**
     * 是否把 API Key 同步到云端。
     *
     * 只改开关，**不触发同步**：把"写一个布尔值"和"发一轮网络请求"压在同一个调用里，
     * 调用方就没法把落盘与上传分开——iOS 的设置页因此卡在主线程上等整轮同步跑完。
     * 需要把新开关立刻反映到云端时，由调用方在写完凭据之后显式调用一次 [syncNow]。
     */
    suspend fun setIncludeApiKey(include: Boolean)

    suspend fun includeApiKey(): Boolean

    /** 清空云端（不可撤销）。本地数据不受影响。 */
    suspend fun deleteCloudData()

    /** 应用前台化、聊天有新内容时调用；内部按需防抖。 */
    fun notifyContentChanged()

    fun dispose()
}

/**
 * 建一个 [SyncFacade]。
 *
 * [baseUrl] 是部署好的 Worker 根地址；为空时整个同步处于 [SyncStatus.Disabled]，
 * 界面只显示"未配置同步服务"，不会产生任何网络请求。
 * [credentialsStore] 由平台给出（Android 是独立的 DataStore 文件，iOS 是 NSUserDefaults），
 * 与用户设置分开存放。
 */
fun createSyncFacade(
    engine: ChatEngine,
    /**
     * 同步用的 HTTP 传输（含原始字节收发，图片要用）。
     *
     * 由平台层在装配处直接给出，而不是从 [ChatEngine] 里掏：聊天那一条只依赖窄契约，
     * 让引擎"顺带"提供一个可能不满足完整契约的对象，会在运行期才炸——也就是这条参数
     * 存在的理由。平台层本来就已经持有它（同一个对象同时喂给 [ApiClient] 与这里）。
     */
    transport: HttpTransport,
    log: ChatLogStore,
    credentialsStore: KeyValueStore,
    images: SyncImageOps,
    ioDispatcher: CoroutineDispatcher,
    scope: CoroutineScope,
    now: () -> Long = { 0L },
    /** 首次同步前把老图片名换成内容哈希；由平台层提供（需要图片目录与文件系统）。 */
    migrateImages: suspend () -> List<String> = { emptyList() },
    /** 防抖时长；测试里可以调小。 */
    debounceMillis: Long = SYNC_DEBOUNCE_MILLIS
): SyncFacade = DefaultSyncFacade(
    engine = engine,
    transport = transport,
    log = log,
    credentialsStore = credentialsStore,
    images = images,
    ioDispatcher = ioDispatcher,
    scope = scope,
    now = now,
    migrateImages = migrateImages,
    debounceMillis = debounceMillis
)

/** 助手回复落盘后等这么久再同步：一轮对话里通常只有一次上行的必要。 */
const val SYNC_DEBOUNCE_MILLIS = 5_000L

private class DefaultSyncFacade(
    private val engine: ChatEngine,
    private val transport: HttpTransport,
    log: ChatLogStore,
    credentialsStore: KeyValueStore,
    images: SyncImageOps,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    now: () -> Long,
    migrateImages: suspend () -> List<String>,
    private val debounceMillis: Long
) : SyncFacade {

    private val credentials = KeyValueSyncCredentialStore(credentialsStore)

    /**
     * 服务地址在运行时才确定，所以 [SyncApi] 不能是构造期字段。
     *
     * 每轮同步都重建一个：它只是一层薄封装（baseUrl + 传输），没有连接池或缓存，
     * 重建的成本可以忽略，换来的是"在设置页填完地址立刻生效"，不需要重启应用。
     */
    private fun api(): SyncApi = SyncApi(transport = transport, baseUrl = serviceUrl)

    private var serviceUrl: String = ""

    /**
     * 待展示的警告。
     *
     * 写入发生在同步协程里（Android 是 IO 调度器），读取发生在状态更新时，
     * 所以是跨线程访问。这里**不加锁**：同一时刻只有一个同步在跑（[SyncEngine] 内部串行化），
     * 写者是单线程；读者把它整体取走再清空，最坏情况是丢掉一条提示文案——
     * 而这几个字本来就是"顺带告诉用户一声"，不值得为它引入一个平台相关的锁
     * （commonMain 里没有 `synchronized`，为一个列表去引 atomicfu 更不划算）。
     */
    private val warnings = mutableListOf<String>()

    /**
     * 记一条给用户看的警告。
     *
     * 刻意**不是** suspend：它是在 [SyncEngine] 的同步循环中间被调用的，让那里多一个
     * 挂起点只会把"同步中途"的状态机复杂化，而它要做的只是一次内存追加。
     */
    private fun collectWarning(message: String) {
        // 只留最近几条：这些是给用户看的一句话提示，攒成日志没有意义。
        warnings += message
        while (warnings.size > MAX_WARNINGS) warnings.removeAt(0)
    }

    private val syncEngine = SyncEngine(
        // 取的是当前 serviceUrl，不是构造期的那个值：地址可以在设置页里改。
        apiProvider = { api() },
        credentials = credentials,
        log = log,
        images = images,
        encodeSettings = { includeApiKey, deviceId ->
            // 取值、编码、算指纹必须在同一个瞬间完成：分别取会让指纹与推上去的内容对不上。
            val config = engine.config.value
            val persona = engine.persona.value
            val locationEnabled = engine.locationEnabled.value
            EncodedSettings(
                payload = SyncSettingsCodec.encode(
                    BackupSettings(config, persona, locationEnabled),
                    includeApiKey = includeApiKey,
                    deviceId = deviceId
                ),
                fingerprint = settingsFingerprint(config, persona, locationEnabled)
            )
        },
        currentApiKey = { engine.config.value.apiKey },
        applySettings = { settings, _ ->
            if (settings != null) {
                // 一次写入：`saveSettings` 会同步更新内存状态，所以云端设置立刻对界面生效，
                // 不需要（也不能）再"从存储重读一遍"——那会读到还没写完的旧值。
                engine.saveSettings(settings.config, settings.persona, settings.locationEnabled)
            }
        },
        migrateImages = migrateImages,
        onWarning = { message -> collectWarning(message) },
        now = now,
        ioDispatcher = ioDispatcher
    )

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Disabled)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var debounceJob: Job? = null

    /**
     * 把服务地址读进内存。
     *
     * 每个入口先调用它：地址可能刚被用户在设置页改过，缓存住会导致"改了地址却还在往旧地址发"。
     */
    private suspend fun refreshServiceUrl(): Boolean {
        serviceUrl = credentials.serviceUrl()
        return serviceUrl.isNotBlank()
    }

    override suspend fun isConfigured(): Boolean = refreshServiceUrl() && syncEngine.isConfigured()

    override suspend fun serviceUrl(): String {
        refreshServiceUrl()
        return serviceUrl
    }

    override suspend fun setServiceUrl(url: String) {
        credentials.setServiceUrl(url)
        refreshServiceUrl()
        _status.value = if (serviceUrl.isBlank()) SyncStatus.Disabled else SyncStatus.Idle
    }

    override suspend fun syncNow() {
        if (!prepare()) return
        // 已经在跑就不再起第二个：两次替换会互相覆盖日志。这也是 SyncEngine 内部加锁的原因，
        // 但界面层提前挡掉可以避免状态来回跳。
        //
        // 这也让 [syncNow] 成为一个**不阻塞调用方**的入口：本类被界面放在后台作用域上跑，
        // 而"已经在跑"时它什么都不做就返回，重复触发不会排队堆积。
        if (_status.value is SyncStatus.Working) return
        runSync()
    }

    override suspend fun syncNowAndAwait(): SyncStatus {
        if (!prepare()) return _status.value
        // 恰巧有一轮在跑（防抖触发的、或上一次保存触发的）就等它：两个调用方等的是同一轮，
        // 结果对用户是同一件事，也不该为此再发一轮请求。
        if (_status.value is SyncStatus.Working) {
            return _status.first { it !is SyncStatus.Working }
        }
        return runSync()
    }

    /**
     * 凭据检查：能同步返回 true；否则把状态置成对应的终态并返回 false。
     *
     * 抽出来是因为 [syncNow] 与 [syncNowAndAwait] 必须用同一套判断，否则"能不能同步"
     * 会在两条路径上漂移——iOS 侧那条等待路径就曾因为漏掉这个分支而卡到超时。
     */
    private suspend fun prepare(): Boolean {
        if (!refreshServiceUrl()) {
            _status.value = SyncStatus.Disabled
            return false
        }
        if (!syncEngine.isConfigured()) {
            _status.value = SyncStatus.NeedsAccountKey("还没有填写账号密钥")
            return false
        }
        return true
    }

    /**
     * 真正跑一轮同步并把终态同时写进 [status] 与返回值。
     *
     * 调用方必须先经过 [prepare]；这里只负责"跑 + 翻译异常"，不再判断能不能跑。
     */
    private suspend fun runSync(): SyncStatus {
        _status.value = SyncStatus.Working("正在同步…")
        val result = try {
            val report = syncEngine.sync()
            // 同步已经把日志重写过，界面要跟上；否则顺序与序号都停在旧快照。
            engine.syncCompleted()
            val warning = drainWarnings()
            val summary = report.summary()
            when {
                warning != null -> SyncStatus.Done(warning)
                summary != null -> SyncStatus.Done("同步完成：$summary")
                else -> SyncStatus.Idle
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SyncException) {
            if (e.reauthorize) {
                SyncStatus.NeedsAccountKey(e.message ?: "账号密钥无效")
            } else {
                SyncStatus.Failed(e.message ?: "同步失败")
            }
        } catch (e: Exception) {
            SyncStatus.Failed(e.message ?: "同步失败")
        }
        _status.value = result
        return result
    }

    override suspend fun accountKey(): String = credentials.accountKey()

    override suspend fun setAccountKey(key: String) {
        val trimmed = key.trim()
        // 太短就在本地拒掉：服务端也会 401，但那条 401 的文案是"密钥无效或已失效"，
        // 用户只会以为同步坏了，而不是"我填的只有 4 位"。
        if (trimmed.isNotEmpty() && trimmed.length < MIN_ACCOUNT_KEY_LENGTH) {
            _status.value = SyncStatus.Failed(
                "账号密钥至少 $MIN_ACCOUNT_KEY_LENGTH 位（现在是 ${trimmed.length} 位）"
            )
            return
        }
        syncEngine.setAccountKey(trimmed)
        // 换密钥等于换云空间：之前"已经推上去"的记忆全部作废，否则会拿旧状态去新账号上判断。
        refreshServiceUrl()
        _status.value = if (serviceUrl.isBlank()) SyncStatus.Disabled else SyncStatus.Idle
    }

    override suspend fun clearAccountKey() {
        syncEngine.clearAccountKey()
        refreshServiceUrl()
        _status.value = if (serviceUrl.isBlank()) SyncStatus.Disabled else SyncStatus.Idle
    }

    override suspend fun setIncludeApiKey(include: Boolean) {
        syncEngine.setIncludeApiKey(include)
        // 只落盘。这里刻意**不**顺带同步：调用方的路径是"写完凭据再同步一次"，
        // 在里面再起一轮会让同一份凭据写两次、发两次请求，而第一次用的还是旧密钥。
    }

    override suspend fun includeApiKey(): Boolean = syncEngine.includeApiKey()

    override suspend fun deleteCloudData() {
        try {
            if (!refreshServiceUrl()) {
                _status.value = SyncStatus.Disabled
                return
            }
            syncEngine.deleteAll()
            _status.value = SyncStatus.Done("云端数据已清空，本机记录未受影响")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SyncException) {
            _status.value = SyncStatus.Failed(e.message ?: "清空失败")
        }
    }

    override fun notifyContentChanged() {
        debounceJob?.cancel()
        // 防抖在 scope 上跑（Android 是 viewModelScope，iOS 是应用作用域），
        // 应用退出时会被一起取消，不会留下悬挂的定时任务。
        debounceJob = scope.launch {
            delay(debounceMillis)
            syncNow()
        }
    }

    override fun dispose() {
        debounceJob?.cancel()
        debounceJob = null
    }

    private fun drainWarnings(): String? {
        if (warnings.isEmpty()) return null
        val text = warnings.toList()
        warnings.clear()
        return text.joinToString("；")
    }

    /**
     * 设置的指纹：只包含**会被同步的那些字段**。
     *
     * 不含 API Key：Key 变不变由同步开关决定是否上行，把它算进指纹会导致"只改了 Key"
     * 触发一次设置推送，而开关关着时那次推送只是写一遍同样不含 Key 的内容。
     *
     * 开关本身也不进指纹：**打开开关**这一动作由 `SyncEngine` 用"云端那份还有没有 Key"
     * 单独判断（见 `SyncCredentialStore.cloudSettingsHasApiKey`），这样"只把开关打开"
     * 也能把那把一直没上过云的 Key 推上去，同时**关闭**开关不会因此触发一次抹掉云端 Key 的推送。
     */
    private fun settingsFingerprint(
        config: ApiConfig,
        persona: CatPersona,
        locationEnabled: Boolean
    ): String = buildString {
            append(config.providerId).append('|')
            append(config.normalizedBaseUrl()).append('|')
            append(config.model).append('|')
            append(config.temperature).append('|')
            append(config.topP).append('|')
            append(config.maxTokens).append('|')
            append(config.thinking.name).append('|')
            append(config.reasoningEffort.name).append('|')
            append(persona.name).append('|')
            append(persona.traits.encodeTraits()).append('|')
            append(persona.speechStyle.name).append('|')
            append(persona.flavor.name).append('|')
            append(persona.notes).append('|')
            append(locationEnabled)
    }

    private companion object {
        const val MAX_WARNINGS = 8
    }
}
