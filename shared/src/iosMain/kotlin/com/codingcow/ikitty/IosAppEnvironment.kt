package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSData
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
    private val api = iosApiClient()
    private val images = IosImageStore(fileSystem, paths.imagesDir, ioDispatcher)

    val engine: ChatEngine = ChatEngine(
        api = api,
        settings = iosSettingsRepository(),
        log = ChatLogStore(
            fileSystem = fileSystem,
            path = paths.chatLog,
            ioDispatcher = ioDispatcher,
            now = ::nowMillis
        ),
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

    val observer = ChatEngineObserver(engine, scope)

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
     * 只改"是否允许用 IP 推测城市"。
     *
     * 定位默认是开的，所以必须有一个关掉它的入口：每刷新一次都会把用户的 IP
     * 交给第三方，不给开关就是隐私问题。
     */
    fun updateLocationEnabled(enabled: Boolean) {
        engine.saveSettings(engine.config.value, engine.persona.value, enabled)
    }

    /** 只改角色设定；其余设定沿用现有值。 */
    fun updatePersona(
        name: String,
        notes: String,
        traits: List<CatTrait>,
        speechStyle: CatSpeechStyle,
        flavor: CatFlavor
    ) {
        engine.saveSettings(
            engine.config.value,
            engine.persona.value.copy(
                name = name.trim(),
                notes = notes.trim(),
                // 上限在共享代码里，界面不该自己再判一次。
                traits = traits.take(CatPersona.MAX_TRAITS).toSet(),
                speechStyle = speechStyle,
                flavor = flavor
            ),
            engine.locationEnabled.value
        )
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


    /**
     * 保存模型服务的全部可调字段。
     *
     * Kotlin 的默认参数导出到 Swift 之后会变成必填，让 Swift 去构造一个带十几个参数的
     * `ApiConfig` 既啰嗦又容易漏字段；这里把用户真正会动的字段收敛成参数。
     */
    fun updateConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        thinking: ThinkingMode,
        reasoningEffort: ReasoningEffort
    ) {
        val normalized = baseUrl.trim().trimEnd('/')
        engine.saveSettings(
            engine.config.value.copy(
                baseUrl = normalized,
                apiKey = apiKey.trim(),
                model = model.trim(),
                // 换了地址之后预设也要跟着换，否则参数能力表会和实际服务商对不上。
                providerId = ModelCatalog.providerIdForBaseUrl(normalized) ?: CUSTOM_PROVIDER_ID,
                temperature = temperature,
                topP = topP,
                maxTokens = maxTokens,
                thinking = thinking,
                reasoningEffort = reasoningEffort
            ),
            engine.persona.value,
            engine.locationEnabled.value
        )
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
