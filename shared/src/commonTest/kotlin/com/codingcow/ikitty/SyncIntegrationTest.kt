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

        /**
         * 这台设备看到的墙钟，喂给同步的 `now()`。
         *
         * 必须**每台设备各有一份**、并且由测试显式推进：设置的 LWW 判据是墙钟时间戳，
         * 两台设备共用一个返回值会让"谁更新"变成"谁最后写"——那样测出来的就不是
         * "新设置覆盖旧设置"，而是服务端在同一毫秒内的覆盖顺序。
         */
        var clock = NOW

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
    private fun CoroutineScope.buildDevice(id: String, clockStart: Long = NOW): Device {
        val device = Device(id)
        device.clock = clockStart
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
            // 每台设备一个**独立的、显式推进的**墙钟：同毫秒的两份设置谁赢是服务端的
            // 覆盖顺序，不是这条测试要验的东西。见 [Device.clock]。
            now = { device.clock },
            // 真实实现要 5 秒防抖；测试里立刻同步，避免用例依赖虚拟时间推进。
            debounceMillis = 0L
        )
        return device
    }

    /** 把某台设备的墙钟往前拨，让它下一次推上去的设置**严格更新**。 */
    private fun Device.advanceClock(millis: Long = 1_000L) {
        clock += millis
    }

    /**
     * 只改"是否把 API Key 一并同步"不该触发同步。
     *
     * 门面里那个 setter 原来会顺手调一次 [SyncFacade.syncNow]。后果是每一条"写凭据"的路径
     * 都会发两轮请求（第一轮用的还是旧密钥），而 iOS 的设置页会把这个网络操作等在主线程上——
     * 也就是那个"点保存就卡住"的来源。同步现在只能由调用方在**凭据全部落盘之后**显式发起。
     */
    @Test
    fun `toggling the api key switch does not start a sync`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val marker = "只改开关不该被推上去的消息"
            val device = scope.buildDevice("device-a")
            // 直接写日志，不走 send 那条路径：这条用例要观测的是"改开关有没有发起同步"，
            // 而内容落盘后本来就会触发一次防抖同步，会把观测搅浑。
            device.log.append(
                StoredMessage(
                    seq = 1,
                    role = StoredMessage.ROLE_USER,
                    content = marker,
                    createdAt = NOW,
                    msgId = newMessageId(now = NOW)
                )
            )
            device.facade.enable()
            advanceUntilIdle()
            assertTrue(server.messages.none { it.content == marker }, "这条消息不该已经在云端")

            device.facade.setIncludeApiKey(true)
            advanceUntilIdle()

            assertTrue(
                server.messages.none { it.content == marker },
                "只改开关不该发起同步：消息被推上去就说明同步跑了"
            )

            // 显式同步一次之后它才该上去——证明网络通路本身是好的，上一条断言不是因为别的原因通过。
            device.facade.syncNow()
            advanceUntilIdle()
            assertTrue(server.messages.any { it.content == marker }, "显式调用 syncNow 之后消息要上云")
        } finally {
            scope.cancel()
        }
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
            // 拉完再 start：start 会读盘并把历史灌进引擎，这里要观察的正是同步写下的那份记录。
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

            // A 先同步一次：模拟"这台设备早就配好了"。它会在云端写下当前的默认设置，
            // 之后墙钟继续往前走——用户改设置总是发生在别的设备上一次同步之后，
            // 而不是同一毫秒里。
            deviceA.facade.syncNow()
            deviceA.advanceClock(60_000L)

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

            // 设备 B：新设备。它的墙钟落在 A"上一次同步"与"A 改设置"之间，于是：
            // - B 这一轮推上去的是本机默认设置（本地设置从没被用户改过）；
            // - 但 A 那份**更新**，按 LWW 该由 A 赢，所以 B 拉下来之后本机该变成 A 的设置。
            //
            // 两边的墙钟刻意错开：时间戳相等时服务端按到达顺序覆盖，那时测出来的是覆盖顺序
            // 而不是 LWW。这条用例盯的是"更新的那份能落到本机"。
            val deviceB = scope.buildDevice("device-b", clockStart = deviceA.clock - 30_000L)
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
            // 也要拨到 A 那份之后：设置是 LWW，只有更新的那一份才盖得住云端 A 写的设置。
            deviceB.advanceClock(120_000L)
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

    /**
     * 新设备的第一轮同步必须**先拉设置、再决定推不推**，不能拿本机默认值去覆盖云端。
     *
     * 这条用例把"新设备"的真实时钟关系固定下来：一台刚装好的手机的墙钟**一定比另一台设备
     * 上一次同步更新**。服务端的设置是 LWW（`updated_at >=` 才覆盖），于是只要新设备先把
     * 自己的默认设置推上去，它就一定赢——另一台设备下一次同步再把设置拉回来，用户看到的
     * 就是"改好的名字、API Key 每换一台设备就没了，每次都要重填"。
     *
     * 所以这里让 B 的墙钟比 A **晚**，而不是像 [settings and api key follow the switch] 那样
     * 靠"B 的钟更早"来掩盖这个问题。
     */
    @Test
    fun `a brand new device adopts cloud settings instead of overwriting them with defaults`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            // ---- 设备 A：把角色与 Key 改好并同步 ----
            val deviceA = scope.buildDevice("device-a")
            deviceA.engine.start()
            advanceUntilIdle()
            deviceA.facade.enable()
            deviceA.facade.setIncludeApiKey(true)
            deviceA.engine.saveSettings(
                ApiConfig(baseUrl = "https://example.test/v1", apiKey = "sk-cloud", model = "cat-1"),
                CatPersona(name = "团子", notes = "不要聊工作"),
                true
            )
            advanceUntilIdle()
            deviceA.facade.syncNow()
            advanceUntilIdle()

            val cloudBefore = server.kv.getValue("settings").payload
            assertTrue(cloudBefore.contains("团子"), "前置条件：云端要已经有 A 的设置")

            // ---- 设备 B：全新安装，从没改过设置，墙钟比 A 晚 ----
            val deviceB = scope.buildDevice("device-b", clockStart = deviceA.clock + 600_000L)
            deviceB.engine.start()
            advanceUntilIdle()
            deviceB.facade.enable()
            deviceB.facade.syncNow()
            advanceUntilIdle()

            assertEquals("团子", deviceB.engine.persona.value.name, "新设备要继承云端的角色名字")
            assertEquals("不要聊工作", deviceB.engine.persona.value.notes)
            assertEquals("sk-cloud", deviceB.engine.config.value.apiKey, "新设备要继承云端的 API Key")
            assertEquals("cat-1", deviceB.engine.config.value.model)

            val cloudAfter = server.kv.getValue("settings").payload
            assertTrue(
                cloudAfter.contains("团子"),
                "云端设置不能被新设备的默认值覆盖，否则每台设备都要重填一遍"
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * 只把「同步 API Key」的开关打开、别的设置一个都不改，也要把 Key 推上云。
     *
     * 设置的指纹刻意不含 API Key，所以"只打开开关"在指纹上看不出变化。如果只按指纹判断
     * "要不要推"，那把一直没上过云的 Key 永远推不上去——用户打开开关、同步成功，云端却
     * 还是没有 Key，换一台设备又得手填。这条用例盯的就是这一步。
     */
    @Test
    fun `turning the api key sync on uploads a key the cloud did not have`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val device = scope.buildDevice("device-a")
            device.engine.start()
            advanceUntilIdle()
            device.facade.enable()

            // 先把设置（含 Key）存好，但开关还关着：这一轮同步不会带 Key。
            device.engine.saveSettings(
                ApiConfig(baseUrl = "https://example.test/v1", apiKey = "sk-later", model = "cat-1"),
                CatPersona(name = "团子"),
                true
            )
            advanceUntilIdle()
            device.facade.syncNow()
            advanceUntilIdle()
            assertFalse(
                SyncSettingsCodec.containsApiKey(server.kv.getValue("settings").payload),
                "前置条件：开关关着时云端不该有 Key"
            )

            // 只打开开关，不改任何别的设置。
            device.facade.setIncludeApiKey(true)
            advanceUntilIdle()
            device.facade.syncNow()
            advanceUntilIdle()

            val payload = server.kv.getValue("settings").payload
            assertTrue(
                SyncSettingsCodec.containsApiKey(payload),
                "打开开关之后云端应当拿到 Key"
            )
            assertEquals("sk-later", SyncSettingsCodec.decode(payload, "")!!.config.apiKey)
        } finally {
            scope.cancel()
        }
    }
}
