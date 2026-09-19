package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * 图片（Phase 8）在 iOS 上还没接，所以 `imageDataUrls` 返回空表 ——
 * 历史里的图片暂时不会进请求。这是显式留空，不是静默降级。
 */
class IosAppEnvironment {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val paths = iosAppPaths()
    private val fileSystem = iosFileSystem()
    private val ioDispatcher = iosIoDispatcher()
    private val api = iosApiClient()

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
        imageDataUrls = { emptyMap() },
        locationSource = IpLocationSource(KtorTransport()),
        invalidateImageCache = {},
        scope = scope
    )

    val observer = ChatEngineObserver(engine, scope)

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
