package com.codingcow.ikitty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 同步的整体行为。
 *
 * 这里跑的是真实的 [SyncEngine] + [ChatLogStore]（FakeFileSystem）对着 [FakeSyncServer]，
 * 所以覆盖的是"推 → 服务端分配 → 拉 → 整体替换 → 图片补齐"这条完整链路，
 * 而不是某一个函数。
 */
class SyncEngineTest {

    private val fileSystem = FakeFileSystem()
    private val root = "/data".toPath()
    private val paths = AppPaths(root)

    private val server = FakeSyncServer()
    private val credentialsStore = FakeKeyValueStore()
    private val credentials = KeyValueSyncCredentialStore(credentialsStore) { "test-device" }

    private var localApiKey = "sk-local"
    private var appliedSettings: BackupSettings? = null
    private var appliedHadApiKey = false
    private val warnings = mutableListOf<String>()

    /** 把图片存在内存里，避免测试依赖像素处理。 */
    private val imageFiles = mutableMapOf<String, ByteArray>()
    private val images = object : SyncImageOps {
        override fun exists(imageId: String): Boolean = imageFiles.containsKey(imageId)
        override suspend fun read(imageId: String): ByteArray? = imageFiles[imageId]
        override suspend fun write(imageId: String, bytes: ByteArray): Boolean {
            imageFiles[imageId] = bytes
            return true
        }
    }

    /**
     * 本地日志。
     *
     * 在声明处就建好，而不是在 [newEngine] 里赋值：多个用例会先往日志里写消息、
     * 再建引擎，把它放在建引擎那一步会读到未初始化的属性。
     */
    private val log = ChatLogStore(
        fileSystem = fileSystem,
        path = paths.chatLog,
        ioDispatcher = Dispatchers.Unconfined,
        now = { 1_000L }
    )

    private fun newEngine(): SyncEngine =
        SyncEngine(
            apiProvider = { SyncApi(server, "https://sync.test") },
            credentials = credentials,
            log = log,
            images = images,
            encodeSettings = { includeApiKey, deviceId ->
                val settings = BackupSettings(currentConfig, currentPersona, true)
                EncodedSettings(
                    payload = SyncSettingsCodec.encode(settings, includeApiKey, deviceId),
                    fingerprint = "${currentConfig.model}|${includeApiKey}"
                )
            },
            currentApiKey = { localApiKey },
            applySettings = { settings, hasApiKey ->
                appliedSettings = settings
                appliedHadApiKey = hasApiKey
                if (settings != null) currentConfig = settings.config
            },
            onWarning = { warnings += it },
            now = { 5_000L },
            ioDispatcher = Dispatchers.Unconfined
        )

    /**
     * 跑一轮"先传后拉"，等价于手动模式下用户先后点两次按钮。
     *
     * 生产代码里这两个动作是分开的（上传不碰本地日志、下载不碰云端），这里把它们连起来，
     * 是因为下面这些用例验证的是"整条链路的结果"——分开写会把每一条断言都拆成两段，
     * 而它们关心的往往正是两次操作合起来之后的收敛状态。
     */
    private suspend fun SyncEngine.fullSync(): SyncReport = push() + pull()

    /** 把两轮的报告合成一份，供断言直接读。 */
    private operator fun SyncReport.plus(other: SyncReport): SyncReport = SyncReport(
        messagesPushed = messagesPushed + other.messagesPushed,
        kvPushed = kvPushed + other.kvPushed,
        pulled = pulled + other.pulled,
        rejected = rejected + other.rejected,
        settingsApplied = settingsApplied || other.settingsApplied,
        imagesDownloaded = imagesDownloaded + other.imagesDownloaded
    )

    private var currentConfig = ApiConfig(apiKey = "sk-local", model = "m1")
    private var currentPersona = CatPersona()

    /**
     * 每个用例都从一张白纸开始。
     *
     * 这些字段是每个测试实例一份，但 JUnit 是否复用实例是实现细节——把"服务端已有内容、
     * 本地已有消息、上一轮推过哪些 id"显式清掉，测试就不会因为执行顺序而变绿或变红。
     */
    @BeforeTest
    fun setUp() = runTest {
        server.clearAll()
        server.authorized = true
        server.requests.clear()
        server.failuresBeforeSuccess = 0
        server.maxBatchMessages = null
        imageFiles.clear()
        appliedSettings = null
        appliedHadApiKey = false
        warnings.clear()
        currentConfig = ApiConfig(apiKey = "sk-local", model = "m1")
        currentPersona = CatPersona()
        if (fileSystem.exists(paths.chatLog)) fileSystem.delete(paths.chatLog)
        credentialsStore.clear()
        credentials.clearSyncState()
        credentials.setAccountKey("account-key-1234567890")
    }

    @AfterTest
    fun tearDown() {
        fileSystem.checkNoOpenFiles()
    }

    private fun message(msgId: String, content: String, seq: Long, images: List<String> = emptyList()) =
        StoredMessage(
            seq = seq,
            role = StoredMessage.ROLE_USER,
            content = content,
            createdAt = seq * 1000,
            images = images,
            msgId = msgId
        )

    // ---- 首次同步 ----

    @Test
    fun `first sync uploads local messages and assigns server sequence`() = runTest {
        log.append(message("m1", "你好", 1))
        log.append(message("m2", "在吗", 2))

        val report = newEngine().fullSync()

        assertEquals(2, report.messagesPushed)
        assertEquals(1L, server.serverSeqOf("m1"))
        assertEquals(2L, server.serverSeqOf("m2"))

        // 服务端分配的序号要回写到本地：否则下次启动还是旧的草稿序号。
        val stored = log.all()
        assertEquals(listOf(1L, 2L), stored.map { it.seq })
        assertEquals(listOf("m1", "m2"), stored.map { it.msgId })
    }

    @Test
    fun `second sync does not re-upload what the server already has`() = runTest {
        log.append(message("m1", "你好", 1))
        val engine = newEngine()
        engine.fullSync()
        server.requests.clear()

        val report = engine.fullSync()

        assertEquals(0, report.messagesPushed, "已经推过的消息不该再出现在请求里")
        assertTrue(server.requests.all { it.messages.isEmpty() })
    }

    @Test
    fun `pulling brings down messages written by another device`() = runTest {
        // 另一台设备写了两条。
        server.seedMessages(
            listOf(
                SyncMessage("other-1", 0, StoredMessage.ROLE_USER, "早上好", 100),
                SyncMessage("other-2", 0, StoredMessage.ROLE_ASSISTANT, "早～", 200)
            )
        )

        val engine = newEngine()
        val report = engine.fullSync()

        assertEquals(0, report.messagesPushed, "本地没有待推的消息")
        assertEquals(listOf("other-1", "other-2"), log.all().map { it.msgId })
        assertEquals(listOf(1L, 2L), log.all().map { it.seq }, "本地要采纳服务端序号")
    }

    // ---- 待推与合并 ----

    @Test
    fun `local pending messages survive a pull`() = runTest {
        log.append(message("local-1", "还没推的", 1))

        // 服务端已经有一条别的设备的消息。
        server.seedMessages(listOf(SyncMessage("other-1", 0, StoredMessage.ROLE_USER, "云端已有", 100)))

        val engine = newEngine()
        engine.fullSync()

        val ids = log.all().map { it.msgId }
        // 云端的那条排前面，本地待推的排后面；两者都不丢。
        assertEquals(listOf("other-1", "local-1"), ids)
    }

    /**
     * 一次拉取没有覆盖到刚推上去的消息时，本机那条**保留自己的草稿 seq**，于是同一份日志里
     * 会出现两个相同的 seq。这不是数据丢失，但界面绝不能拿 seq 当列表 key：Compose 的 key
     * 重复会直接抛 `IllegalArgumentException: Key ... was already used` 崩掉进程（也就是
     * "开启云同步后频繁闪退"）。列表身份只能是 [StoredMessage.msgId]——这里盯住"身份唯一"。
     */
    @Test
    fun `merge keeps a unique identity even when a local draft seq collides with a cloud seq`() = runTest {
        // 云端已经有满满一页：本机刚推的那条落在这一页之外，只能以草稿 seq 留在本地。
        server.seedMessages(
            (1..SYNC_PULL_LIMIT).map { index ->
                SyncMessage("cloud-$index", 0, StoredMessage.ROLE_USER, "云端 $index", index * 1000L)
            }
        )
        log.append(message("local-1", "本机草稿", 1))

        newEngine().fullSync()

        val stored = log.all()
        assertEquals(SYNC_PULL_LIMIT + 1, stored.size, "云端一页与本机草稿都要在")
        assertEquals(stored.size, stored.map { it.msgId }.toSet().size, "msgId 必须是唯一身份")
        assertTrue(
            stored.groupingBy { it.seq }.eachCount().any { it.value > 1 },
            "本机草稿的 seq 会与云端某条撞车，所以界面不能拿 seq 当列表 key"
        )
    }

    @Test
    fun `a message deleted on the server is not resurrected by the next sync`() = runTest {
        log.append(message("m1", "会被删掉", 1))
        log.append(message("m2", "留着", 2))
        val engine = newEngine()
        engine.fullSync()
        assertEquals(listOf("m1", "m2"), log.all().map { it.msgId })

        // 另一台设备把 m1 删掉了。
        server.seedDeletion("m1")

        val report = engine.fullSync()

        assertEquals(listOf("m2"), log.all().map { it.msgId }, "本地的 m1 要跟着云端一起消失")
        assertTrue(
            server.requests.drop(1).flatMap { it.messages }.none { it.msgId == "m1" },
            "删掉的消息不能再被推回去"
        )
        assertEquals(0, report.messagesPushed)
    }

    @Test
    fun `legacy records without msgId get a stable identity before uploading`() = runTest {
        // 手写一行"老格式"记录：没有 id 字段。
        fileSystem.createDirectories(paths.chatLog.parent!!)
        fileSystem.write(paths.chatLog) {
            writeUtf8("""{"seq":1,"role":"user","content":"老记录","at":1000}""" + "\n")
        }

        val engine = newEngine()
        engine.fullSync()
        val firstId = log.all().single().msgId
        assertTrue(firstId.isNotBlank())

        // 再同步一次不该产生第二条消息：识别身份必须已经固化到磁盘上。
        engine.fullSync()
        assertEquals(1, server.messages.size, "同一条老记录不能被推两次")
        assertEquals(firstId, server.messages.single().msgId)
    }

    // ---- 图片 ----

    @Test
    fun `images referenced by pulled messages are downloaded once`() = runTest {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val id = imageIdFor(bytes)
        server.imageBytes[id] = bytes
        server.images[id] = bytes.size
        server.seedMessages(
            listOf(
                SyncMessage(
                    msgId = "with-image",
                    seq = 0,
                    role = StoredMessage.ROLE_USER,
                    content = "",
                    createdAt = 100,
                    images = listOf(id)
                )
            )
        )

        val engine = newEngine()
        val report = engine.fullSync()

        assertEquals(1, report.imagesDownloaded)
        assertTrue(images.exists(id), "图片要落到本地")
        assertTrue(report.summary()!!.contains("图片 1 张"))

        // 再同步一次不该重新下载。
        val second = engine.fullSync()
        assertEquals(0, second.imagesDownloaded)
    }

    // ---- 设置 ----

    @Test
    fun `settings are uploaded once and then only when they change`() = runTest {
        val engine = newEngine()
        engine.fullSync()
        assertEquals(1, server.kv.size, "第一次同步要带上设置")
        assertEquals("m1", SyncSettingsCodec.decode(server.kv.getValue("settings").payload, "")!!.config.model)

        server.requests.clear()
        engine.fullSync()
        assertTrue(
            server.requests.all { it.kv.isEmpty() },
            "设置没变就不该反复上传"
        )

        // 改一个会被同步的字段。
        currentConfig = currentConfig.copy(model = "m2")
        engine.fullSync()
        assertTrue(server.requests.any { it.kv.isNotEmpty() }, "设置变了要重推")
        assertEquals("m2", SyncSettingsCodec.decode(server.kv.getValue("settings").payload, "")!!.config.model)
    }

    @Test
    fun `api key goes to the cloud only when the switch is on`() = runTest {
        credentials.setIncludeApiKey(false)
        val engine = newEngine()
        engine.fullSync()

        val withoutKey = server.kv.getValue("settings").payload
        assertFalse(SyncSettingsCodec.containsApiKey(withoutKey), "开关关着时不该上传 Key")
        assertFalse(withoutKey.contains("sk-local"))

        // 打开开关并改一次设置，Key 才上去。
        credentials.setIncludeApiKey(true)
        currentConfig = currentConfig.copy(model = "m2")
        engine.fullSync()
        val withKey = server.kv.getValue("settings").payload
        assertTrue(SyncSettingsCodec.containsApiKey(withKey))
        assertTrue(withKey.contains("sk-local"))
    }

    @Test
    fun `turning the switch off does not wipe the key already in the cloud`() = runTest {
        credentials.setIncludeApiKey(true)
        val engine = newEngine()
        engine.fullSync()
        assertTrue(server.kv.getValue("settings").payload.contains("sk-local"))

        // 关掉开关再改一次设置：只覆盖非敏感字段。
        credentials.setIncludeApiKey(false)
        currentConfig = currentConfig.copy(model = "m2")
        engine.fullSync()
        val payload = server.kv.getValue("settings").payload
        assertTrue(payload.contains("m2"), "非敏感字段要更新")
        assertFalse(SyncSettingsCodec.containsApiKey(payload), "但不该再带 api_key 字段")
    }

    @Test
    fun `newer cloud settings are applied locally`() = runTest {
        server.seedMessages(listOf(SyncMessage("m1", 0, StoredMessage.ROLE_USER, "hi", 1)))
        // 云端有一份更新的设置（时间戳远大于本地推送时间）。
        val payload = SyncSettingsCodec.encode(
            BackupSettings(ApiConfig(model = "cloud-model", apiKey = "sk-cloud"), CatPersona(), true),
            includeApiKey = true,
            deviceId = "other-device"
        )
        server.seedKv(SyncKvItem("settings", payload, updatedAt = 9_999_999L))

        val engine = newEngine()
        val report = engine.fullSync()

        assertTrue(report.settingsApplied)
        assertNotNull(appliedSettings)
        assertEquals("cloud-model", appliedSettings!!.config.model)
        assertEquals("sk-cloud", appliedSettings!!.config.apiKey)
        assertTrue(appliedHadApiKey)
    }

    @Test
    fun `cloud settings without api key keep the local one`() = runTest {
        val payload = SyncSettingsCodec.encode(
            BackupSettings(ApiConfig(model = "cloud-model", apiKey = ""), CatPersona(), true),
            includeApiKey = false,
            deviceId = "other-device"
        )
        server.seedKv(SyncKvItem("settings", payload, updatedAt = 9_999_999L))

        newEngine().fullSync()

        assertNotNull(appliedSettings)
        assertEquals("cloud-model", appliedSettings!!.config.model)
        assertEquals("sk-local", appliedSettings!!.config.apiKey, "云端没带 Key 时要保留本机的")
        assertFalse(appliedHadApiKey)
    }

    // ---- 失败与状态 ----

    @Test
    fun `a 401 clears the account key and asks the user to re-pair`() = runTest {
        server.authorized = false
        val engine = newEngine()

        val error = runCatching { engine.fullSync() }.exceptionOrNull()

        assertTrue(error is SyncException)
        assertTrue((error as SyncException).reauthorize)
        assertEquals("", credentials.accountKey(), "失效的密钥要清掉，免得每次同步都撞 401")
    }

    @Test
    fun `a transient failure is retried`() = runTest {
        log.append(message("m1", "重试一下", 1))
        server.failuresBeforeSuccess = 2

        val report = newEngine().fullSync()

        assertEquals(1, report.messagesPushed)
        // 这一轮是与空云空间的首次对账，请求序列是：
        // 1) 上传消息——被 5xx 拒了两次，第三次成功（被拒绝的那次没有进入服务端的请求记录，
        //    因为它连 JSON 都没被解析）；
        // 2) 问一次云端有没有设置（首次对账的探问，见 `SyncEngine.push`）；
        // 3) 云端那份是空的，于是把本机设置推上去；
        // 4) 下载一次。
        assertEquals(4, server.requests.size, "重试成功、首次对账探问并补推设置、再下载一次")
    }

    @Test
    fun `rejected messages are reported but do not fail the batch`() = runTest {
        log.append(message("good", "正常", 1))
        // 服务端不接受的 role（当前协议只有 user / assistant）。客户端**不预先过滤**它：
        // 判定 role 是否合法是服务端的事，客户端只负责把拒绝原因如实回报给用户。
        log.append(message("bad", "内容本身没问题", 2).copy(role = "tool"))

        val report = newEngine().fullSync()

        // 两条都送出去了（`messagesPushed` 数的是"发了多少"），其中一条被服务端拒绝。
        assertEquals(2, report.messagesPushed)
        assertEquals(1, report.rejected.size)
        assertEquals(listOf("good"), server.messages.map { it.msgId })
        assertTrue(report.rejected.single().contains("bad"))
        assertTrue(report.summary()!!.contains("1 条被拒绝"))
        // 被拒的那条留在本地：用户的输入不该因为服务端不收就消失。
        assertTrue(log.all().any { it.msgId == "bad" })
    }

    @Test
    fun `a batch rejected as too large is split and retried`() = runTest {
        // 四条消息，但服务端只接受两条一批。
        repeat(4) { index ->
            log.append(message("bulk-" + (index + 1), "第 " + (index + 1) + " 条", (index + 1).toLong()))
        }
        server.maxBatchMessages = 2

        val report = newEngine().fullSync()

        // 第一次是整批（4 条）被 413 拒掉（那一批没有进入服务端的请求记录），
        // 砍半之后两次各 2 条通过；末尾还有一次拉取（它是没有消息的那种请求）。
        val pushes = server.requests.filter { it.messages.isNotEmpty() }
        assertEquals(listOf(2, 2), pushes.map { it.messages.size })
        assertEquals(4, server.messages.size, "服务端最终要拿到全部四条")
        assertEquals(4, credentials.pushedMessageIds().size, "拆批之后四条都要记为已推")
    }

    @Test
    fun `an undeliverable single message fails loudly instead of silently`() = runTest {
        log.append(message("huge", "一条过大的消息", 1))
        // 连一条都放不下：服务端对所有批都回 413。
        server.maxBatchMessages = 0

        val error = runCatching { newEngine().fullSync() }.exceptionOrNull()

        assertTrue(error is SyncException)
        assertTrue(error.message!!.contains("太大"), "要说清是这条消息的问题，而不是笼统的同步失败")
        // 它仍然留在本地：服务端不收不该让用户的内容消失。
        assertTrue(log.all().any { it.msgId == "huge" })
    }

    @Test
    fun `local error notices are never uploaded`() = runTest {
        // 本地生成报错提示：它只对产生它的设备有意义，同步出去只会让别的设备看到
        // 一条莫名其妙的错误。
        log.append(
            StoredMessage(
                seq = 1,
                role = StoredMessage.ROLE_ASSISTANT,
                content = "呜……连接 API 的时候出问题了",
                createdAt = 1000,
                localError = true,
                msgId = "notice"
            )
        )

        newEngine().fullSync()

        assertTrue(server.messages.isEmpty(), "错误提示不该出现在云端")
    }

    @Test
    fun `clearSyncState forgets the cursor when switching accounts`() = runTest {
        log.append(message("m1", "你好", 1))
        val engine = newEngine()
        engine.fullSync()
        assertTrue(credentials.sinceRev() > 0)

        engine.setAccountKey("another-key")
        assertEquals("another-key", credentials.accountKey())
        assertEquals(0L, credentials.sinceRev(), "换账号后必须从头拉")
        assertTrue(credentials.pushedMessageIds().isEmpty())
    }

    /**
     * 同一份凭据重写一遍不能把云空间状态清掉。
     *
     * 设置页每点一次「上传到云端」/「从云端下载」都会先把输入框里的地址与密钥重新落盘，
     * 而写入本身是无条件发生的。如果"写入"等于"换云空间"，游标与 `SETTINGS_SYNCED` 会在
     * 每一轮开始前被抹掉——表现是每轮都从头拉，而且因为"云端已有设置"永远不推本机设置。
     */
    @Test
    fun `rewriting the same credentials keeps the cloud state`() = runTest {
        val engine = newEngine()
        engine.fullSync()
        val rev = credentials.sinceRev()
        assertTrue(rev > 0)

        engine.setServiceUrl(credentials.serviceUrl())
        engine.setAccountKey(credentials.accountKey())

        assertEquals(rev, credentials.sinceRev(), "同一份凭据重写不该清掉游标")
        assertTrue(credentials.settingsSynced(), "也不该抹掉'设置已对账'")
    }

    @Test
    fun `switching to another service url forgets the cloud state`() = runTest {
        val engine = newEngine()
        engine.fullSync()
        assertTrue(credentials.sinceRev() > 0)

        engine.setServiceUrl("https://another.test")

        assertEquals(0L, credentials.sinceRev(), "换地址等于换云空间，必须从头拉")
        assertTrue(credentials.pushedMessageIds().isEmpty())
        assertFalse(credentials.settingsSynced())
    }

    @Test
    fun `deleteAll clears the cloud but keeps local data`() = runTest {
        log.append(message("m1", "你好", 1))
        val engine = newEngine()
        engine.fullSync()

        engine.deleteAll()

        assertTrue(server.messages.isEmpty())
        assertTrue(server.kv.isEmpty())
        assertEquals(listOf("m1"), log.all().map { it.msgId }, "本机记录不受影响")
        assertEquals(0L, credentials.sinceRev())
    }

    @Test
    fun `nothing is sent when there is no account key`() = runTest {
        val engine = newEngine()
        credentials.clearAccountKey()

        val error = runCatching { engine.fullSync() }.exceptionOrNull()

        assertTrue(error is SyncException)
        assertNull(server.requests.firstOrNull(), "没有密钥时一个请求都不该发出去")
    }
}

/**
 * 内存里的 [KeyValueStore]。
 *
 * 不引 DataStore 的实现：同步凭据的读写语义（缺省值、空串、布尔字面量）在这里
 * 都能覆盖，而真正的持久化由各平台自己的实现负责，不该由一个测试替它担保。
 */
private class FakeKeyValueStore : KeyValueStore {
    private val state = MutableStateFlow<Map<String, SettingValue>>(emptyMap())
    override val values: Flow<Map<String, SettingValue>> = state.asStateFlow()

    override suspend fun put(entries: Map<String, SettingValue>) {
        state.value = state.value + entries
    }

    /** 测试之间要能清空；真实的实现各自负责持久化，不需要这个入口。 */
    fun clear() {
        state.value = emptyMap()
    }
}
