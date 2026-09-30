package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验收标准 1 与 2 的直接证据：**真实的 [ChatEngine] + [createSyncFacade]** 贯穿两个"设备"。
 *
 * [SyncEngineTest] 测的是同步内部（推、拉、重写、墓碑）；这里测的是接线：
 * - `ChatEngine` 的消息落盘之后真的会触发同步（`onContentChanged`）；
 * - 同步真的会重写本地日志并让界面看到（`syncCompleted`）；
 * - 两台设备各自一个 `ChatEngine`，经由同一个 [FakeSyncServer] 收敛到同一份记录。
 *
 * 两台设备的差异都做成了参数：文件系统、设置存储、图片存储、设备 id。这正是平台层
 * 实际注入的东西，所以这条测试跑到的地方与真机一致，只有存储介质换成了内存。
 *
 * 不驱动模型的流式回复：这里要验证的是"本地产生的消息怎么上云、怎么下落"，
 * 一条假的模型回复只会给用例引入与主题无关的噪音。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncIntegrationTest {

    private companion object {
        /** 固定墙钟：断言不依赖跑测试的机器。 */
        const val NOW = 1_700_000_000_000L
    }

    private val server = FakeSyncServer()
    private val accountKey = "integration-account-key-123456"

    /** 一台"设备"：引擎 + 门面，接线方式与 Android / iOS 的真实接线逐条对应。 */
    private class Device(val id: String) {
        val fileSystem = FakeFileSystem()
        val paths = AppPaths("/data".toPath())
        val log = ChatLogStore(fileSystem, paths.chatLog, Dispatchers.Unconfined) { NOW }

        val settingsStore = FakeStore()
        val settings = SettingsRepository(settingsStore)
        val credentialsStore = FakeStore()

        /** 同步下来的图片；这条测试只关心它在不在，不关心像素。 */
        val images = mutableMapOf<String, ByteArray>()

        lateinit var engine: ChatEngine
        lateinit var facade: SyncFacade
    }

    private class FakeStore : KeyValueStore {
        private val state = MutableStateFlow<Map<String, SettingValue>>(emptyMap())
        override val values: Flow<Map<String, SettingValue>> = state
        override suspend fun put(entries: Map<String, SettingValue>) {
            state.value = state.value + entries
        }
    }

    /**
     * 组装一台设备。
     *
     * 门面在引擎之后创建，但引擎的回调要在门面就绪后才可能被调用 —— 与 Android / iOS
     * 用可空引用解决的是同一个先后顺序问题，这里用 `lateinit`。
     */
    private fun CoroutineScope.buildDevice(id: String): Device {
        val device = Device(id)
        // 与平台层一样：同一个传输同时喂给 ApiClient 与同步门面。
        // `FakeSyncServer` 本身就是完整的 `HttpTransport`，所以它的 JSON 路径也够聊天用
        // （这条测试里聊天并不真的发请求）。
        val api = ApiClient(server, Dispatchers.Unconfined)
        device.engine = ChatEngine(
            api = api,
            settings = device.settings,
            log = device.log,
            memoryStore = CatMemoryStore(device.fileSystem, device.paths.catMemory, Dispatchers.Unconfined),
            extractor = MemoryExtractor(api),
            imageDataUrls = { emptyMap() },
            locationSource = null,
            invalidateImageCache = {},
            scope = this,
            // 与平台层一样：本地内容落盘后通知同步。
            onContentChanged = { device.facade.notifyContentChanged() }
        )
        device.facade = createSyncFacade(
            engine = device.engine,
            transport = server,
            log = device.log,
            credentialsStore = device.credentialsStore,
            images = object : SyncImageOps {
                override fun exists(imageId: String) = device.images.containsKey(imageId)
                override suspend fun read(imageId: String) = device.images[imageId]
                override suspend fun write(imageId: String, bytes: ByteArray): Boolean {
                    device.images[imageId] = bytes
                    return true
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
            scope = this,
            // 真实实现要 5 秒防抖；测试里立刻同步，避免依赖虚拟时间推进。
            debounceMillis = 0L
        )
        return device
    }

    private suspend fun SyncFacade.enable() {
        setServiceUrl("https://sync.test")
        setAccountKey(accountKey)
    }

    /** 本机产生一条用户消息：写成引擎会写的那种记录，再走引擎的"内容变了"通知。 */
    private suspend fun Device.say(content: String, seq: Long) {
        log.append(
            StoredMessage(
                seq = seq,
                role = StoredMessage.ROLE_USER,
                content = content,
                createdAt = NOW + seq,
                msgId = newMessageId(now = NOW + seq)
            )
        )
        // 这一步是"落盘后通知同步"的等价物；真实路径由 ChatEngine.append 触发同一个回调。
        engine.notifyContentChanged()
    }

    @Test
    fun `messages written on one device show up on the other`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            // ---- 设备 A：写两条本地消息并同步 ----
            val deviceA = scope.buildDevice("device-a")
            deviceA.facade.enable()
            deviceA.say("第一句", 1)
            deviceA.say("第二句", 2)
            advanceUntilIdle()
            // 防抖为 0 时上面的通知已经同步过；再显式跑一次让断言不依赖时序。
            deviceA.facade.syncNow()

            assertEquals(
                listOf("第一句", "第二句"),
                server.messages.map { it.content },
                "本机消息要按顺序推上云端"
            )
            assertEquals(listOf(1L, 2L), deviceA.log.all().map { it.seq }, "本地要采纳服务端分配的序号")

            // ---- 设备 B：首次同步把云端记录拉下来 ----
            val deviceB = scope.buildDevice("device-b")
            deviceB.facade.enable()
            deviceB.facade.syncNow()
            // 拉完再 start：空日志会在 start 时补一条开场白，那是本地行为、不该掺进断言。
            deviceB.engine.start()
            advanceUntilIdle()

            assertEquals(
                listOf("第一句", "第二句"),
                deviceB.engine.messages.value.map { it.content },
                "设备 B 要看到 A 写的全部消息，顺序一致"
            )
            assertEquals(
                server.messages.map { it.seq },
                deviceB.log.all().map { it.seq },
                "设备 B 的本地序号要与服务端权威值一致"
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `settings and api key follow the switch`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val deviceA = scope.buildDevice("device-a")
            // start 之后再改设置：`engine.config` 是由设置流推着走的（与真机一致），
            // 不 start 的话 `_config` 会停在默认值，同步上去的就是一份空配置。
            deviceA.engine.start()
            advanceUntilIdle()
            deviceA.facade.enable()
            // 顺序要紧：先打开开关，再改设置。
            // 反过来的话，第一轮推送用的还是"开关关着"的那一份快照，而指纹已经记成了
            // "开关打开"的状态——于是这一轮什么都没推，设置却再也不会被重推。
            deviceA.facade.setIncludeApiKey(true)
            advanceUntilIdle()

            deviceA.engine.saveSettings(
                ApiConfig(baseUrl = "https://example.test/v1", apiKey = "sk-secret", model = "cat-1"),
                CatPersona(),
                true
            )
            advanceUntilIdle()
            deviceA.facade.syncNow()

            assertTrue(
                server.kv.getValue("settings").payload.contains("sk-secret"),
                "打开开关后 API Key 要进云端"
            )

            // 设备 B：拿到云端设置。
            val deviceB = scope.buildDevice("device-b")
            deviceB.engine.start()
            advanceUntilIdle()
            deviceB.facade.enable()
            deviceB.facade.syncNow()
            advanceUntilIdle()

            assertEquals("cat-1", deviceB.engine.config.value.model, "云端设置要落到设备 B")
            assertEquals("sk-secret", deviceB.engine.config.value.apiKey)
            assertEquals("https://example.test/v1", deviceB.engine.config.value.baseUrl)

            // 设备 B 关掉开关再改一次设置：云端已有的 Key 不能被抹掉。
            deviceB.facade.setIncludeApiKey(false)
            deviceB.engine.saveSettings(
                deviceB.engine.config.value.copy(model = "cat-2"),
                CatPersona(),
                true
            )
            advanceUntilIdle()
            deviceB.facade.syncNow()

            val payload = server.kv.getValue("settings").payload
            assertTrue(payload.contains("cat-2"), "非敏感字段要更新")
            assertFalse(SyncSettingsCodec.containsApiKey(payload), "关掉开关后不该再带 api_key")
        } finally {
            scope.cancel()
        }
    }
}
