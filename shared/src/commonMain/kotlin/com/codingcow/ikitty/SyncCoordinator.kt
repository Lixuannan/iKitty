package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * 1. **触发**：只有用户显式点「上传」或「下载」两个入口，没有前台化、没有落盘防抖；
 * 2. **把异常翻译成状态**：[SyncException] → [SyncStatus]，文案只在这里写一次；
 * 3. **同步之后的收尾**：设置要重新读进 [ChatEngine]，否则界面还在用旧配置。
 *
 * [push] 与 [pull] 都是 **suspend** 的，并且会**一直等到这一轮结束**才返回：调用方在这段时间
 * 里显示一块阻塞的进度动画，用户点完就知道成没成。它们把终态同时写进 [status] 与返回值。
 *
 * 上传与下载是两个独立动作：拉取会整体替换本地日志，把它藏在"上传"里，会让一次本该只写
 * 云端的操作顺带改写本机。
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

    /**
     * 把本机的新消息与设置传到云端，**等它结束**，返回这一轮的终态。
     *
     * 不拉取：本地日志不会被这次调用改写。凭据没配好时不做任何网络请求，直接返回对应的终态。
     */
    suspend fun push(): SyncStatus

    /**
     * 把云端快照下载到本机（整体替换本地日志、继承设置、补齐图片），**等它结束**。
     *
     * 不上传：本机还没推上去的消息会留在本地，等待下一次 [push]。
     */
    suspend fun pull(): SyncStatus

    suspend fun setAccountKey(key: String)

    /** 当前账号密钥，供设置页回填（输入框是密码样式，不回填用户会以为没配过）。 */
    suspend fun accountKey(): String

    suspend fun clearAccountKey()

    /**
     * 是否把 API Key 同步到云端。
     *
     * 只改开关，**不触发同步**：把"写一个布尔值"和"发一轮网络请求"压在同一个调用里，
     * 调用方就没法把落盘与上传分开，用户也就没法在写完之后自己决定什么时候上传。
     */
    suspend fun setIncludeApiKey(include: Boolean)

    suspend fun includeApiKey(): Boolean

    /** 清空云端（不可撤销）。本地数据不受影响。 */
    suspend fun deleteCloudData()
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
    now: () -> Long = { 0L },
    /** 首次同步前把老图片名换成内容哈希；由平台层提供（需要图片目录与文件系统）。 */
    migrateImages: suspend () -> List<String> = { emptyList() }
): SyncFacade = DefaultSyncFacade(
    engine = engine,
    transport = transport,
    log = log,
    credentialsStore = credentialsStore,
    images = images,
    ioDispatcher = ioDispatcher,
    now = now,
    migrateImages = migrateImages
)

private class DefaultSyncFacade(
    private val engine: ChatEngine,
    private val transport: HttpTransport,
    log: ChatLogStore,
    credentialsStore: KeyValueStore,
    images: SyncImageOps,
    private val ioDispatcher: CoroutineDispatcher,
    now: () -> Long,
    migrateImages: suspend () -> List<String>
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
        // 走引擎而不是直接写凭据：换地址 = 换云空间，游标与"设置已对账"的记忆必须一起作废，
        // 而"换了才作废"的判断收在引擎里，免得每个调用方各写一遍。见 [SyncEngine.setServiceUrl]。
        syncEngine.setServiceUrl(url)
        refreshServiceUrl()
        _status.value = if (serviceUrl.isBlank()) SyncStatus.Disabled else SyncStatus.Idle
    }

    override suspend fun push(): SyncStatus {
        if (!prepare()) return _status.value
        return runSync(
            working = "正在上传…",
            emptyText = "没有需要上传的内容",
            refreshEngine = false
        ) { syncEngine.push() }
    }

    override suspend fun pull(): SyncStatus {
        if (!prepare()) return _status.value
        return runSync(
            working = "正在从云端下载…",
            emptyText = "云端没有新内容",
            // 拉取整体替换了本地日志：界面与 nextSeq 必须跟着重读，否则顺序停在旧快照。
            refreshEngine = true
        ) { syncEngine.pull() }
    }

    /**
     * 凭据检查：能同步返回 true；否则把状态置成对应的终态并返回 false。
     *
     * [push] 与 [pull] 必须用同一套判断，否则"能不能同步"会在两条路径上漂移。
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
     * 跑一轮上传或下载，把终态同时写进 [status] 与返回值。
     *
     * 调用方必须先经过 [prepare]；这里只负责"跑 + 翻译异常"，不再判断能不能跑。
     * [emptyText] 是"这一轮没发生任何变化"时给用户的一句交代——不能什么都不显示，
     * 那样点了按钮之后界面毫无反应，看起来像没生效。
     */
    private suspend fun runSync(
        working: String,
        emptyText: String,
        refreshEngine: Boolean,
        block: suspend () -> SyncReport
    ): SyncStatus {
        _status.value = SyncStatus.Working(working)
        val result = try {
            val report = block()
            if (refreshEngine) engine.syncCompleted()
            val warning = drainWarnings()
            val summary = report.summary()
            when {
                warning != null -> SyncStatus.Done(warning)
                summary != null -> SyncStatus.Done(summary)
                else -> SyncStatus.Done(emptyText)
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
