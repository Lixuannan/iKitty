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
import kotlinx.coroutines.withTimeoutOrNull
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
 * - 手动触发的上传与下载各自把结果写进状态，并且真的读写同一份文件；
 * - 下载会重写本地日志并让界面看到（`syncCompleted`）；
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
     * 门面在引擎之后创建。手动模式下引擎不再回调同步，所以不需要可空引用，也不需要
     * 防抖参数——上传与下载只能由测试显式调用。
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
            scope = this
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
            // 每台设备一个**独立的、显式推进的**墙钟：同毫秒的两份设置谁赢是服务端的
            // 覆盖顺序，不是这条测试要验的东西。见 [Device.clock]。
            now = { device.clock }
        )
        return device
    }

    /**
     * 用户的两次点击：先「上传到云端」，再「从云端下载」。
     *
     * 生产代码里它们是两个独立动作，这里连起来是因为下面这些用例验证的是"两台设备收敛到
     * 同一份记录"这个结果——分两段写会把每条断言都拆开，而它们关心的正是合起来的状态。
     */
    private suspend fun SyncFacade.syncBoth() {
        push()
        pull()
    }

    /** 把某台设备的墙钟往前拨，让它下一次推上去的设置**严格更新**。 */
    private fun Device.advanceClock(millis: Long = 1_000L) {
        clock += millis
    }

    /**
     * 只改"是否把 API Key 一并同步"不该触发同步。
     *
     * 门面里那个 setter 曾经会顺手推一次。后果是每一条"写凭据"的路径都会发出网络请求，
     * 而 iOS 的设置页会把这个操作等在主线程上——也就是那个"点保存就卡住"的来源。
     * 手动模式下上传只能由调用方在**凭据全部落盘之后**显式发起。
     */
    @Test
    fun `toggling the api key switch does not start a sync`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val marker = "只改开关不该被推上去的消息"
            val device = scope.buildDevice("device-a")
            // 直接写日志，不走 send 那条路径：这条用例要观测的只是"改开关有没有发起同步"。
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

            // 显式上传一次之后它才该上去——证明网络通路本身是好的，上一条断言不是因为别的原因通过。
            device.facade.push()
            advanceUntilIdle()
            assertTrue(server.messages.any { it.content == marker }, "显式点上传之后消息要上云")
        } finally {
            scope.cancel()
        }
    }

    private suspend fun SyncFacade.enable() {
        setServiceUrl("https://sync.test")
        setAccountKey(accountKey)
    }

    /**
     * 没配服务地址时 [SyncFacade.push] 必须**立刻**返回 `Disabled`。
     *
     * 这是"填完 API Key 点保存就卡死"的根因回归。旧实现等的是"状态与触发前不同"，而触发
     * 前后都是同一个 `Disabled`——`data object` 相等、StateFlow 不会重新发射，于是永远等不到，
     * 调用方只能熬到超时（iOS 设置页那 60 秒里整页禁用）。用 `withTimeoutOrNull` 把它变成
     * "等超时就失败"的断言：旧写法在这里返回 null 而不是 Disabled。
     */
    @Test
    fun `an unconfigured sync returns its status at once instead of waiting for a change`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val device = scope.buildDevice("device-a")
            val status = withTimeoutOrNull(1_000L) { device.facade.push() }
            assertEquals(
                SyncStatus.Disabled,
                status,
                "没配服务地址要立刻返回 Disabled，而不是等到超时"
            )
            assertEquals(SyncStatus.Disabled, device.facade.status.value)
        } finally {
            scope.cancel()
        }
    }

    /** 填了地址但没填密钥同样是 [SyncStatus.NeedsAccountKey]，同样不能等网络。 */
    @Test
    fun `a service url without an account key returns needs-account-key at once`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val device = scope.buildDevice("device-a")
            device.facade.setServiceUrl("https://sync.test")
            val status = withTimeoutOrNull(1_000L) { device.facade.push() }
            assertTrue(status is SyncStatus.NeedsAccountKey, "要立刻返回 NeedsAccountKey，实际是 $status")
        } finally {
            scope.cancel()
        }
    }

    /**
     * 配好凭据时返回值就是这一轮的终态，且与写进 [SyncFacade.status] 的那份一致。
     *
     * 先写一条本地消息再上传：这样这一轮确实推了东西，终态是 `Done` 而不是"没什么可说的"
     * `Idle`——两种都是终态，但只有 `Done` 能证明返回值来自**这一轮**的结果。
     */
    @Test
    fun `a configured sync returns the terminal status it also publishes`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val marker = "要上云的一条"
            val device = scope.buildDevice("device-a")
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
            val status = withTimeoutOrNull(5_000L) { device.facade.push() }
            assertTrue(status is SyncStatus.Done, "推了一条消息，终态应当是 Done，实际是 $status")
            assertEquals(status, device.facade.status.value, "返回值与状态行不能各说各话")
            // 断言的是外部状态（云端真的收到了），不是组件自己的汇报。
            assertTrue(server.messages.any { it.content == marker }, "消息要真的到了云端")
        } finally {
            scope.cancel()
        }
    }

    /** 本机产生一条用户消息：写成引擎会写的那种记录，等测试自己显式上传。 */
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
            deviceA.facade.syncBoth()

            assertEquals(
                listOf("第一句", "第二句"),
                server.messages.map { it.content },
                "本机消息要按顺序推上云端"
            )
            assertEquals(listOf(1L, 2L), deviceA.log.all().map { it.seq }, "本地要采纳服务端分配的序号")

            // ---- 设备 B：首次同步把云端记录拉下来 ----
            val deviceB = scope.buildDevice("device-b")
            deviceB.facade.enable()
            deviceB.facade.syncBoth()
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
            deviceA.facade.syncBoth()
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
            deviceA.facade.syncBoth()

            assertTrue(
                server.kv.getValue("settings").payload.contains("sk-secret"),
                "打开开关后 API Key 要进云端"
            )

            // 设备 B：新设备。它会把 A 那份设置拉下来——A 那份更新，按 LWW 该由 A 赢。
            //
            // 两边的墙钟刻意错开：时间戳相等时服务端按到达顺序覆盖，那时测出来的是覆盖顺序
            // 而不是 LWW。这条用例盯的是"更新的那份能落到本机"。
            val deviceB = scope.buildDevice("device-b", clockStart = deviceA.clock - 30_000L)
            deviceB.engine.start()
            advanceUntilIdle()
            deviceB.facade.enable()
            deviceB.facade.syncBoth()
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
            deviceB.facade.syncBoth()

            val payload = server.kv.getValue("settings").payload
            assertTrue(payload.contains("cat-2"), "非敏感字段要更新")
            assertFalse(SyncSettingsCodec.containsApiKey(payload), "关掉开关后不该再带 api_key")
        } finally {
            scope.cancel()
        }
    }

    /**
     * 新设备点「上传到云端」不能拿本机默认值覆盖云端设置。
     *
     * 这台设备的墙钟**一定比另一台设备上一次同步更新**。服务端的设置是 LWW，于是只要新设备
     * 把自己的默认设置推上去，它就一定赢——另一台设备下一次下载再把设置拉回来，用户看到的
     * 就是"改好的名字、API Key 每换一台设备就没了，每次都要重填"。
     *
     * 手动模式下的规则是：还没和这个云空间对过设置的账时，上传**先问一次云端有没有设置**，
     * 有就一个字段都不推。所以 B 即使先点上传、再点下载，云端那份也原样保留，而 B 在下载之后
     * 继承到 A 的设置。
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
            deviceA.facade.syncBoth()
            advanceUntilIdle()

            val cloudBefore = server.kv.getValue("settings").payload
            assertTrue(cloudBefore.contains("团子"), "前置条件：云端要已经有 A 的设置")

            // ---- 设备 B：全新安装，从没改过设置，墙钟比 A 晚 ----
            // 先上传（B 本地还没有任何消息，这一步实际只做设置对账），再下载继承。
            val deviceB = scope.buildDevice("device-b", clockStart = deviceA.clock + 600_000L)
            deviceB.engine.start()
            advanceUntilIdle()
            deviceB.facade.enable()
            deviceB.facade.push()
            advanceUntilIdle()

            val cloudAfterPush = server.kv.getValue("settings").payload
            assertEquals(
                "团子",
                SyncSettingsCodec.decode(cloudAfterPush, "")!!.persona.name,
                "新设备的上传不能覆盖云端设置，否则每台设备都要重填一遍"
            )

            deviceB.facade.pull()
            advanceUntilIdle()

            assertEquals("团子", deviceB.engine.persona.value.name, "新设备要继承云端的角色名字")
            assertEquals("不要聊工作", deviceB.engine.persona.value.notes)
            assertEquals("sk-cloud", deviceB.engine.config.value.apiKey, "新设备要继承云端的 API Key")
            assertEquals("cat-1", deviceB.engine.config.value.model)
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

            // 先把设置（含 Key）存好，但开关还关着：这一轮上传不会带 Key。
            device.engine.saveSettings(
                ApiConfig(baseUrl = "https://example.test/v1", apiKey = "sk-later", model = "cat-1"),
                CatPersona(name = "团子"),
                true
            )
            advanceUntilIdle()
            device.facade.push()
            advanceUntilIdle()
            assertFalse(
                SyncSettingsCodec.containsApiKey(server.kv.getValue("settings").payload),
                "前置条件：开关关着时云端不该有 Key"
            )

            // 只打开开关，不改任何别的设置。
            device.facade.setIncludeApiKey(true)
            advanceUntilIdle()
            device.facade.push()
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

    /**
     * 每次动作前重新落盘一遍凭据，不能把云空间状态清掉。
     *
     * 设置页的两个按钮都是"先写三个草稿、再跑这一轮"，而写入是无条件发生的。如果写入等于
     * 换云空间，`SETTINGS_SYNCED` 与游标会在每一轮开始前被抹掉：本地改过的设置会被当成
     * "云端已有一份"而永远推不上去，用户看到的是"改了名字和 Key，点上传却说我得先下载"。
     */
    @Test
    fun `re-saving credentials before every upload does not block later settings uploads`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val device = scope.buildDevice("device-a")
            device.engine.start()
            advanceUntilIdle()

            // 与设置页一致：写地址、写开关、写密钥，然后才发起这一轮。
            device.facade.enable()
            device.facade.setIncludeApiKey(true)
            device.facade.push()
            advanceUntilIdle()
            device.advanceClock(60_000L)

            // 改完设置再走同一条路径：重新落盘凭据 → 上传。
            device.engine.saveSettings(
                ApiConfig(baseUrl = "https://example.test/v1", apiKey = "sk-1", model = "cat-2"),
                CatPersona(name = "团子"),
                true
            )
            advanceUntilIdle()
            device.facade.enable()
            device.facade.setIncludeApiKey(true)
            device.facade.push()
            advanceUntilIdle()

            val payload = SyncSettingsCodec.decode(server.kv.getValue("settings").payload, "")!!
            assertEquals("cat-2", payload.config.model, "重写凭据之后设置仍要能上传")
            assertEquals("团子", payload.persona.name)
        } finally {
            scope.cancel()
        }
    }
}
