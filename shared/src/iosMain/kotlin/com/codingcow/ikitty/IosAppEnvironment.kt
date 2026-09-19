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

    /** 只改角色设定的名字与补充说明；其余设定沿用现有值。 */
    fun updatePersona(name: String, notes: String) {
        engine.saveSettings(
            engine.config.value,
            engine.persona.value.copy(name = name.trim(), notes = notes.trim()),
            engine.locationEnabled.value
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
     * 只改"连接"这三个字段并保存。
     *
     * Kotlin 的默认参数在导出到 Swift 之后会变成必填，让 Swift 去构造一个带十几个参数的
     * `ApiConfig` 既啰嗦又容易漏字段；这里把"用户真正会动的字段"收敛成一个入口，
     * 其余设置沿用现有值。
     */
    fun updateConnection(baseUrl: String, apiKey: String, model: String) {
        val current = engine.config.value
        val normalized = baseUrl.trim().trimEnd('/')
        engine.saveSettings(
            current.copy(
                baseUrl = normalized,
                apiKey = apiKey.trim(),
                model = model.trim(),
                // 换了地址之后预设也要跟着换，否则参数能力表会和实际服务商对不上。
                providerId = ModelCatalog.providerIdForBaseUrl(normalized) ?: CUSTOM_PROVIDER_ID
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
