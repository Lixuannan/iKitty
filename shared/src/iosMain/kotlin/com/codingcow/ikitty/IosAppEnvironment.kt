package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * iOS 侧的依赖装配。
 *
 * 把"用哪些实现"集中在一处，Swift 侧只需要拿到一个 [engine] 和一个 [observer]，
 * 不必知道 AppPaths / 存储 / 传输是怎么拼起来的，也不必从 Swift 构造 Kotlin 的
 * `CoroutineScope`。
 *
 * 作用域跑在 `Dispatchers.Main` 上：界面状态因此总是在主线程更新，SwiftUI 可以直接用。
 * 真正的文件与网络操作各自切到自己的调度器，不会占住主线程。
 *
 * 像素操作（解码、降采样、EXIF 摆正、JPEG 编码）留在 Swift 侧用 CoreGraphics 做，
 * Kotlin 只负责把处理好的字节存到应用私有目录并编成数据 URL。
 */
class IosAppEnvironment {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val paths = iosAppPaths()
    private val fileSystem = iosFileSystem()
    private val ioDispatcher = iosIoDispatcher()
    /** 只建一个传输：聊天与同步共用连接池、超时与取消语义。 */
    private val transport = KtorTransport()
    private val api = ApiClient(transport, Dispatchers.Default)
    private val images = IosImageStore(fileSystem, paths.imagesDir, ioDispatcher)

    /** 聊天日志：同步要直接重写同一个文件，所以在这里建一次、两处共用。 */
    private val chatLog = ChatLogStore(
        fileSystem = fileSystem,
        path = paths.chatLog,
        ioDispatcher = ioDispatcher,
        now = ::nowMillis
    )

    val engine: ChatEngine = ChatEngine(
        api = api,
        settings = iosSettingsRepository(),
        log = chatLog,
        memoryStore = CatMemoryStore(
            fileSystem = fileSystem,
            path = paths.catMemory,
            ioDispatcher = ioDispatcher
        ),
        extractor = MemoryExtractor(api),
        imageDataUrls = { names -> images.dataUrls(names) },
        locationSource = IpLocationSource(KtorTransport()),
        invalidateImageCache = { images.invalidateCache() },
        scope = scope
    )

    /**
     * 同步用的 `NSUserDefaults` 套件：与用户设置分开存放，换账号时整块作废。
     *
     * 服务地址与账号密钥都在设置页里填，不编译进代码：这是自托管应用，
     * 每个人的 Worker 地址都不同。
     */
    private val syncDefaults = NSUserDefaults(suiteName = SYNC_DEFAULTS_SUITE)
        ?: NSUserDefaults.standardUserDefaults

    /**
     * 凭据的同步写入路径。与 [syncFacade] 用**同一个**套件：落盘与读取必须是同一份数据，
     * 否则"保存成功"与"同步时读到的凭据"会各说各话。
     */
    private val credentialsWriter = SyncCredentialWriter(syncDefaults)

    private val syncFacade: SyncFacade = createSyncFacade(
        engine = engine,
        transport = transport,
        log = chatLog,
        credentialsStore = UserDefaultsKeyValueStore(syncDefaults),
        images = IosSyncImages(images),
        ioDispatcher = ioDispatcher,
        now = ::nowMillis,
        migrateImages = {
            val result = migrateLegacyImageNames(
                fileSystem = fileSystem,
                imagesDir = paths.imagesDir,
                log = chatLog,
                ioDispatcher = ioDispatcher
            )
            if (result.renamedFiles > 0) {
                listOf("已把 ${result.renamedFiles} 张旧图片改名为内容哈希")
            } else {
                emptyList()
            }
        }
    )

    val observer = ChatEngineObserver(engine, scope, syncFacade.status)

    /** 上传并等它结束；返回给用户看的一句话（nil 表示没什么要说的）。 */
    suspend fun pushAndWait(): String? = awaitSync { syncFacade.push() }

    /** 下载并等它结束；返回给用户看的一句话（nil 表示没什么要说的）。 */
    suspend fun pullAndWait(): String? = awaitSync { syncFacade.pull() }

    /**
     * 跑一轮上传或下载并**等它结束**。
     *
     * 等的是 [SyncFacade.push] / [SyncFacade.pull] 的**返回值**，而不是"状态变了"：状态没变时
     * （例如没配服务地址，前后都是 `Disabled`）状态流不会重新发射，靠它推断结束会一直等到
     * 超时——那正是"点保存就卡住"的来源。
     *
     * 超时是**兜底**，不是主要的取消手段：超时只放弃"等"，这一轮同步本身仍在跑，状态行
     * 照旧会更新到最终结果。它挡的是"网络一直不回应"时设置页永远关不掉。
     */
    private suspend fun awaitSync(block: suspend () -> SyncStatus): String? {
        // 在界面作用域上发起：同步的状态更新因此仍在主线程，SwiftUI 可以直接消费。
        val started: Deferred<SyncStatus> = scope.async { block() }
        val status = withTimeoutOrNull(SYNC_WAIT_TIMEOUT_MILLIS) { started.await() }
            ?: return "同步超时（$SYNC_WAIT_TIMEOUT_SECONDS 秒）；可以再点一次重试"
        return when (status) {
            is SyncStatus.Done -> status.message
            is SyncStatus.Failed -> status.message
            is SyncStatus.NeedsAccountKey -> status.message
            // Disabled / Idle / Working：没有可说的结果（Working 只可能出现在超时之外，
            // 那时上面已经返回了）。
            else -> null
        }
    }

    /**
     * 当前同步凭据（地址、密钥、开关）。**不挂起**：设置页要用它回填输入框。
     *
     * 不回填的话 `SecureField` 每次打开都是空白，用户会以为没配过而重新填一遍。
     */
    fun currentSyncCredentials(): SyncCredentialWriter.Applied = credentialsWriter.current()

    /**
     * 写同步凭据。**不挂起、不等待网络**：`NSUserDefaults` 的写入在 Kotlin/Native 上就是
     * 直接调用 Foundation，函数返回时值已经落盘。返回一句给用户看的话（nil 表示没什么要说的）。
     *
     * 上传不在这里：它由 [pushAndWait] / [pullAndWait] 单独做。两者分开之后，落盘永远是
     * "函数返回即生效"，不会因为网络好坏而时快时慢。
     *
     * 密钥太短之类的本地校验失败必须说出来，而不是静默保存一个服务端一定会拒的值；
     * 这种情况下**什么都不写**，用户看到的仍旧是上一次的配置。
     */
    fun applySyncCredentials(serviceUrl: String, accountKey: String, includeApiKey: Boolean): String? {
        // 与 KeyValueSyncCredentialStore 用同一个下限（commonMain 的 MIN_ACCOUNT_KEY_LENGTH）：
        // 不一致会让用户拿到一个看不懂的 401。
        val trimmedKey = accountKey.trim()
        if (trimmedKey.isNotEmpty() && trimmedKey.length < MIN_ACCOUNT_KEY_LENGTH) {
            return "账号密钥至少 $MIN_ACCOUNT_KEY_LENGTH 位（现在是 ${trimmedKey.length} 位），没有保存"
        }
        credentialsWriter.apply(serviceUrl = serviceUrl, accountKey = trimmedKey, includeApiKey = includeApiKey)
        return null
    }

    fun clearSyncAccountKey() {
        credentialsWriter.clearAccountKey()
    }

    /**
     * 清空云端（不可撤销）。本地数据不受影响。
     *
     * 调用方**必须先**用 [applySyncCredentials] 把当前凭据落盘：否则清的是上一次保存的
     * 地址与账号，可能清到别人的云空间上。删除与同步走同一条作用域，结果经状态行回报。
     */
    fun deleteCloudData() {
        scope.launch { syncFacade.deleteCloudData() }
    }

    private companion object {
        const val SYNC_DEFAULTS_SUITE = "com.codingcow.ikitty.sync"

        /**
         * 前台等同步的上限。
         *
         * 同步自己的重试预算是 3 次、指数退避（`SyncEngine.MAX_ATTEMPTS`），最坏情况下
         * 光退避就有 1+2 秒；再加上连接与读超时，一次正常的失败要在十几秒内出结果。
         * 这个值比那更宽，只在"网络彻底不回应"时才兜住——那时设置页不能永远关不掉。
         */
        const val SYNC_WAIT_TIMEOUT_MILLIS = 60_000L
        const val SYNC_WAIT_TIMEOUT_SECONDS = 60
    }

    /**
     * 保存一张已经归一化好的 JPEG（Swift 侧用 CoreGraphics 处理好），返回本机文件名。
     *
     * 返回 null 表示这张图没存进去（例如存储空间不足），调用方应当跳过它。
     */
    suspend fun saveImage(data: NSData): String? = images.saveData(data)

    /**
     * 某张本机图片的绝对路径，供 Swift 直接 `UIImage(contentsOfFile:)` 显示缩略图。
     *
     * 不返回数据 URL 给界面：那是发给服务商的形式，界面没必要先 base64 再解码一遍。
     */
    fun imageFilePath(name: String): String = (paths.imagesDir / name).toString()

    /**
     * 设置页提交的一份完整草稿。
     *
     * Swift 不方便构造带十几个参数的 Kotlin 函数调用，也不该把"哪些字段属于配置、哪些属于
     * 角色"这个划分再抄一遍，所以整份草稿用一个类型带过来。
     */
    class SettingsDraft(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val temperature: Float,
        val topP: Float,
        val maxTokens: Int,
        val thinking: ThinkingMode,
        val reasoningEffort: ReasoningEffort,
        val catName: String,
        val catNotes: String,
        val traits: List<CatTrait>,
        val speechStyle: CatSpeechStyle,
        val flavor: CatFlavor,
        val locationEnabled: Boolean
    )

    /**
     * 保存设置页的整份草稿。
     *
     * **一次调用写完全部设置**，而不是让 Swift 分三次改三个子集。分成三次时，每一次都要
     * "读当前值、改一个字段、写回"，于是后一次会拿着**上一次写入之前**的快照，把前一次刚改的
     * 字段覆盖回去——iOS 上表现就是"改了 API Key 和名字，一保存就变回旧值"。
     * 引擎侧的 [ChatEngine.saveSettings] 已经保证了内存状态的同步更新，这里再把提交点收敛成
     * 一个，读-改-写就只发生一次、拿到的一定是最新快照。
     */
    fun saveSettings(draft: SettingsDraft) {
        val normalized = draft.baseUrl.trim().trimEnd('/')
        val config = engine.config.value.copy(
            baseUrl = normalized,
            apiKey = draft.apiKey.trim(),
            model = draft.model.trim(),
            // 换了地址之后预设也要跟着换，否则参数能力表会和实际服务商对不上。
            providerId = ModelCatalog.providerIdForBaseUrl(normalized) ?: CUSTOM_PROVIDER_ID,
            temperature = draft.temperature,
            topP = draft.topP,
            maxTokens = draft.maxTokens,
            thinking = draft.thinking,
            reasoningEffort = draft.reasoningEffort
        )
        val persona = engine.persona.value.copy(
            name = draft.catName.trim(),
            notes = draft.catNotes.trim(),
            // 上限在共享代码里，界面不该自己再判一次。
            traits = draft.traits.take(CatPersona.MAX_TRAITS).toSet(),
            speechStyle = draft.speechStyle,
            flavor = draft.flavor
        )
        engine.saveSettings(config, persona, draft.locationEnabled)
    }

    // ---- 设置界面需要的适配层 ----
    // Swift 不方便对 Kotlin 的 sealed interface 做模式匹配，也不方便构造长参数的数据类，
    // 所以把"当前模型支持什么"和"测试结果"都摊平成普通对象。

    /** 数值参数的取值范围，直接驱动 SwiftUI 的 Slider / Stepper。 */
    class NumberRange(
        val min: Float,
        val max: Float,
        val step: Float,
        val initial: Float,
        val decimals: Int
    )

    /** 当前模型支持哪些参数——设置页据此决定渲染哪些控件、以及各参数的上下限。 */
    class ModelCapabilities(
        val temperature: NumberRange?,
        val topP: NumberRange?,
        val maxTokens: NumberRange?,
        /** 走 `thinking.type` 开关时为 true。 */
        val hasThinkingToggle: Boolean,
        val thinkingDefaultOn: Boolean,
        /** 非空表示走 `reasoning_effort`，列出可选档位。 */
        val reasoningLevels: List<ReasoningEffort>
    )

    /**
     * 按**草稿**里的地址与模型算能力表，而不是按已保存的配置。
     *
     * 设置页要在用户改完模型名、还没保存时就显示出"这个模型支持哪些参数"，
     * 所以这里显式接收地址与模型名。服务商优先按地址反查，查不到才沿用当前预设。
     */
    fun capabilitiesFor(baseUrl: String, model: String): ModelCapabilities {
        val current = engine.config.value
        val normalized = baseUrl.trim().trimEnd('/')
        val providerId = ModelCatalog.providerIdForBaseUrl(normalized) ?: current.providerId
        val spec = ModelCatalog.resolve(providerId, model.trim())
        val reasoning = spec.reasoning
        return ModelCapabilities(
            temperature = spec.temperature?.toRange(),
            topP = spec.topP?.toRange(),
            maxTokens = spec.maxTokens?.toRange(),
            hasThinkingToggle = reasoning is ReasoningSpec.Toggle,
            thinkingDefaultOn = (reasoning as? ReasoningSpec.Toggle)?.defaultOn ?: false,
            reasoningLevels = (reasoning as? ReasoningSpec.Effort)?.supported ?: emptyList()
        )
    }

    private fun NumberParam.toRange() = NumberRange(min, max, step, default, decimals)

    /** 测试连接的结果，展平成一句可直接展示的话。 */
    class ConnectionTestResult(val ok: Boolean, val message: String)

    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): ConnectionTestResult {
        val candidate = engine.config.value.copy(
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim(),
            model = model.trim()
        )
        return engine.testConnection(candidate).fold(
            onSuccess = { outcome ->
                ConnectionTestResult(
                    ok = true,
                    message = "连接正常（${outcome.latencyMillis} ms）：" +
                        outcome.reply.replace('\n', ' ').take(40)
                )
            },
            onFailure = { error -> ConnectionTestResult(false, error.message ?: "测试失败") }
        )
    }

    /** 拉取模型列表的结果。 */
    class ModelListResult(val ok: Boolean, val models: List<String>, val message: String)

    suspend fun fetchModels(baseUrl: String, apiKey: String): ModelListResult {
        val candidate = engine.config.value.copy(
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim()
        )
        return engine.listModels(candidate).fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is ModelListOutcome.Available ->
                        ModelListResult(true, outcome.models, "共 ${outcome.models.size} 个模型")
                    ModelListOutcome.NotSupported ->
                        ModelListResult(false, emptyList(), "该服务商没有模型列表接口")
                }
            },
            onFailure = { error -> ModelListResult(false, emptyList(), error.message ?: "获取失败") }
        )
    }

    // ---- 备份与恢复 ----

    private val archive = BackupArchive(
        fileSystem = fileSystem,
        paths = paths,
        appVersion = appVersion(),
        ioDispatcher = ioDispatcher
    ) { nowMillis() }

    /**
     * 导出到应用私有目录里的一个临时文件，返回它的绝对路径。
     *
     * 返回路径而不是字节：Swift 用 `ShareLink` 直接分享这个文件即可，
     * 不必把整份归档在 Kotlin 与 Swift 之间来回拷一遍。
     */
    suspend fun exportBackupToFile(): String {
        val export = archive.export(
            BackupSettings(engine.config.value, engine.persona.value, engine.locationEnabled.value)
        )
        val dir = paths.root / "export"
        if (fileSystem.exists(dir)) fileSystem.deleteRecursively(dir)
        fileSystem.createDirectories(dir)
        val target = dir / defaultBackupFileName(nowMillis())
        fileSystem.write(target) { write(export.bytes) }
        return target.toString()
    }

    /**
     * 用一份归档整体覆盖本机数据，返回可以直接展示的结果文案。
     *
     * 校验通过之前不会动本机任何数据；设置也跟着归档一起恢复，然后整体重读。
     */
    suspend fun importBackup(data: NSData): String {
        val contents = archive.stage(data.toByteArray())
        val failedImages = archive.commit(contents)
        engine.saveSettings(
            contents.settings.config,
            contents.settings.persona,
            contents.settings.locationEnabled
        )
        engine.reloadFromDisk()

        val summary = contents.summary
        val warning = if (failedImages > 0) "有 $failedImages 张图片写入失败，可能存储空间不足。" else ""
        return "已导入 ${summary.messageCount} 条消息、${summary.imageCount} 张图片、" +
            "${summary.factCount} 条记忆和设置。$warning"
    }


    /** 应用退出时调用；之后这个环境不可再用。 */
    fun dispose() {
        scope.cancel()
    }
}

@OptIn(ExperimentalTime::class)
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** 应用版本名，写进备份清单里，方便日后排查"这份备份是谁导出的"。 */
private fun appVersion(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
        ?.takeIf { it.isNotBlank() }
        ?: "0.0.0"
