package com.codingcow.ikitty

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS 平台层的真实实现测试。
 *
 * 这些用例跑在**真的 iOS 运行时**上（`:shared:iosSimulatorArm64Test` 通过模拟器执行），
 * 所以 `NSUserDefaults`、`NSFileManager`、以及 okio 的 Native 实现都是真的被调用到的，
 * 不是靠 JVM 上的替身。放在 `iosTest` 而不是 `commonTest`，是因为它们用的是 iOS 专有 API。
 *
 * 覆盖的是那些"JVM 上测不到、只有跑起来才知道"的部分：目录真的建出来了吗、
 * 键值真的写进 NSUserDefaults 了吗、图片真的落盘并编成数据 URL 了吗。
 */
class IosPlatformTest {

    private val fileSystem = iosFileSystem()
    private val paths = iosAppPaths()

    private val cleanupKeys = listOf(
        SettingsKeys.BASE_URL, SettingsKeys.API_KEY, SettingsKeys.MODEL,
        SettingsKeys.TEMPERATURE, SettingsKeys.TOP_P, SettingsKeys.MAX_TOKENS,
        SettingsKeys.THINKING, SettingsKeys.REASONING_EFFORT, SettingsKeys.PROVIDER_ID,
        SettingsKeys.CAT_NAME, SettingsKeys.CAT_TRAITS, SettingsKeys.CAT_SPEECH_STYLE,
        SettingsKeys.CAT_FLAVOR, SettingsKeys.CAT_NOTES, SettingsKeys.LOCATION_ENABLED
    )

    /** 同步凭据走的是一套独立的键；写完要一起清掉，免得影响别的用例。 */
    private val syncKeys = listOf(
        SyncKeys.ACCOUNT_KEY, SyncKeys.SERVICE_URL, SyncKeys.DEVICE_ID, SyncKeys.SINCE_REV,
        SyncKeys.SETTINGS_UPDATED_AT, SyncKeys.INCLUDE_API_KEY, SyncKeys.PUSHED_IDS,
        SyncKeys.DELETED_IDS, SyncKeys.SETTINGS_FINGERPRINT, SyncKeys.CLOUD_SETTINGS_AT
    )

    @AfterTest
    fun tearDown() {
        val defaults = NSUserDefaults.standardUserDefaults
        cleanupKeys.forEach { defaults.removeObjectForKey(it) }
        syncKeys.forEach { defaults.removeObjectForKey(it) }
    }

    /** Application Support 不会自动创建，所以这一步必须真的发生了。 */
    @Test
    fun `app paths create the root directory and use the canonical layout`() {
        assertTrue(fileSystem.exists(paths.root), "根目录没有建出来：${paths.root}")
        assertTrue(
            paths.chatLog.toString().endsWith("iKitty/chat/chat_log.jsonl"),
            "聊天记录路径不对：${paths.chatLog}"
        )
        assertTrue(
            paths.catMemory.toString().endsWith("iKitty/chat/cat_memory.json"),
            "记忆路径不对：${paths.catMemory}"
        )
        // 跑两次不能抛异常（目录已存在时要能跳过创建）。
        iosAppPaths()
    }

    /** 设置真的写进了 NSUserDefaults，并且能读回来——包括"存了 0"和"没存过"的区别。 */
    @Test
    fun `settings round trip through user defaults`() = runTest {
        val repository = SettingsRepository(UserDefaultsKeyValueStore())

        repository.save(
            ApiConfig(
                providerId = "custom",
                baseUrl = "http://127.0.0.1:11434/v1",
                apiKey = "sk-ios",
                model = "qwen3",
                temperature = 0.35f,
                topP = 0.9f,
                maxTokens = 2048,
                thinking = ThinkingMode.ON,
                reasoningEffort = ReasoningEffort.HIGH
            )
        )
        repository.save(CatPersona(name = "团子", traits = setOf(CatTrait.LAZY), notes = "不要聊工作"))
        repository.saveLocationEnabled(false)

        val config = repository.config.first()
        assertEquals("http://127.0.0.1:11434/v1", config.baseUrl)
        assertEquals("sk-ios", config.apiKey)
        assertEquals("qwen3", config.model)
        assertEquals(0.35f, config.temperature)
        assertEquals(2048, config.maxTokens)
        assertEquals(ThinkingMode.ON, config.thinking)
        assertEquals(ReasoningEffort.HIGH, config.reasoningEffort)

        val persona = repository.persona.first()
        assertEquals("团子", persona.name)
        assertEquals(setOf(CatTrait.LAZY), persona.traits)
        assertEquals("不要聊工作", persona.notes)

        assertTrue(!repository.locationEnabled.first())
    }

    /** 没存过的键必须回退到默认值，而不是变成空串或 0。 */
    @Test
    fun `an untouched user defaults store yields the built-in defaults`() = runTest {
        val repository = SettingsRepository(UserDefaultsKeyValueStore())
        assertEquals(ApiConfig(), repository.config.first())
        assertEquals(CatPersona(), repository.persona.first())
        assertTrue(repository.locationEnabled.first())
    }

    /** 图片落盘 + 编成数据 URL，这条链路在 iOS 上用的是 okio 的 Native 实现。 */
    @Test
    fun `the image store writes a file and returns a data url`() = runTest {
        val store = IosImageStore(fileSystem, paths.imagesDir, iosIoDispatcher())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11, 0x22)

        val name = store.save(jpeg)
        assertTrue(name != null, "保存失败")
        assertTrue(name!!.startsWith("img_") && name.endsWith(".jpg"), name)
        assertTrue(fileSystem.exists(paths.imagesDir / name), "文件没落盘")

        val urls = store.dataUrls(listOf(name))
        assertEquals(jpegDataUrl(jpeg), urls[name])

        // 读不到的图片直接跳过，而不是抛异常或给出一个坏 URL。
        assertTrue(store.dataUrls(listOf("不存在.jpg")).isEmpty())

        fileSystem.delete(paths.imagesDir / name)
    }

    /** 空字节数不是一张图，不该产生一个文件。 */
    @Test
    fun `the image store rejects empty bytes`() = runTest {
        val store = IosImageStore(fileSystem, paths.imagesDir, iosIoDispatcher())
        assertNull(store.save(ByteArray(0)))
    }

    /** 缓存失效之后必须重新从文件读，否则备份导入后会继续用旧内容。 */
    @Test
    fun `invalidating the cache forces a re-read`() = runTest {
        val store = IosImageStore(fileSystem, paths.imagesDir, iosIoDispatcher())
        val first = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)
        val name = store.save(first)!!
        assertEquals(jpegDataUrl(first), store.dataUrls(listOf(name))[name])

        // 备份导入会把同名图片覆盖成别的字节。
        val second = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02)
        fileSystem.write(paths.imagesDir / name) { write(second) }
        // 不清缓存的话读到的还是旧的。
        assertEquals(jpegDataUrl(first), store.dataUrls(listOf(name))[name])

        store.invalidateCache()
        assertEquals(jpegDataUrl(second), store.dataUrls(listOf(name))[name])

        fileSystem.delete(paths.imagesDir / name)
    }

    /**
     * 读取必须看到**刚写进去**的值，即使写入绕过了 [UserDefaultsKeyValueStore.put]。
     *
     * 这就是"保存了但读回来还是旧的"那条回归：凭据的同步写入（`SyncCredentialWriter`）
     * 直接写 `NSUserDefaults`，而 store 曾经在构造时缓存一份快照——于是设置页回显旧地址、
     * `syncNow()` 拿旧密钥、换了账号游标却还停在旧云空间的值上。现在 `values` 每次都重读。
     */
    @Test
    fun `reads see values written straight to user defaults`() = runTest {
        val defaults = NSUserDefaults.standardUserDefaults
        val store = UserDefaultsKeyValueStore(defaults)
        assertEquals(emptyMap(), store.values.first(), "前置条件：这些键此时应当是空的")

        // 绕过 put，模拟凭据那条同步写入路径。
        defaults.setObject("out-of-band", SyncKeys.SERVICE_URL)

        assertEquals(
            "out-of-band",
            (store.values.first()[SyncKeys.SERVICE_URL] as? SettingValue.Str)?.value,
            "重读必须看到直接写进 NSUserDefaults 的值"
        )
    }

    /**
     * 同步凭据的写入是**同步**的：函数返回时值已经在 `NSUserDefaults` 里。
     *
     * 这条盯的是"设置页点保存会卡死"那个缺陷的修复点：凭据落盘不再经过 Kotlin 的 suspend 桥
     * （Swift 侧也就不需要 `await`），上传由后台作用域负责。所以这里刻意**不开协程**去等
     * 任何东西——写完之后立刻用另一条路径读回来，读得到才算数。
     */
    @Test
    fun `sync credentials are written synchronously and read back by the facade store`() {
        val defaults = NSUserDefaults.standardUserDefaults
        val writer = SyncCredentialWriter(defaults) { "test-device" }

        val applied = writer.apply(
            serviceUrl = "  https://sync.example.workers.dev/  ",
            accountKey = "  my-key-123  ",
            includeApiKey = true
        )
        assertEquals("https://sync.example.workers.dev", applied.serviceUrl, "地址末尾的斜杠要去掉")
        assertEquals("my-key-123", applied.accountKey, "密钥要去掉首尾空白")

        // 用门面真正读凭据的那条路径复查：写入与读取必须是同一份数据。
        val credentials = KeyValueSyncCredentialStore(UserDefaultsKeyValueStore(defaults))
        assertEquals("https://sync.example.workers.dev", runBlocking { credentials.serviceUrl() })
        assertEquals("my-key-123", runBlocking { credentials.accountKey() })
        assertTrue(runBlocking { credentials.includeApiKey() }, "开关要落盘")
        assertEquals("test-device", runBlocking { credentials.deviceId() }, "安装 id 与门面读到的必须是同一个")
    }

    /** 空密钥的语义是"解除绑定"，而不是"存了一个空密钥"。 */
    @Test
    fun `clearing the account key unbinds the cloud space but keeps the address`() {
        val defaults = NSUserDefaults.standardUserDefaults
        val writer = SyncCredentialWriter(defaults) { "test-device" }
        writer.apply(serviceUrl = "https://sync.example.workers.dev", accountKey = "my-key-123", includeApiKey = false)

        writer.clearAccountKey()

        val credentials = KeyValueSyncCredentialStore(UserDefaultsKeyValueStore(defaults))
        assertEquals("", runBlocking { credentials.accountKey() })
        assertEquals("https://sync.example.workers.dev", runBlocking { credentials.serviceUrl() })
    }

    /**
     * 换密钥会把与旧云空间绑定的状态整体作废，而只改地址或开关时不动游标。
     *
     * 游标没归零的后果是"新账号上的同步从一个错误的起点开始"；反过来，明明只是改了地址
     * 却把游标清零，会让每一轮都重拉全部历史。两条都要盯住。
     */
    @Test
    fun `switching the key resets the cloud space state and a same-key write does not`() {
        val defaults = NSUserDefaults.standardUserDefaults
        val writer = SyncCredentialWriter(defaults) { "test-device" }
        val credentials = KeyValueSyncCredentialStore(UserDefaultsKeyValueStore(defaults))

        writer.apply(serviceUrl = "https://sync.example.workers.dev", accountKey = "first-key-123", includeApiKey = false)
        runBlocking {
            credentials.setSinceRev(42)
            credentials.addPushedMessageIds(listOf("m1", "m2"))
        }

        // 同一个密钥、只改地址：游标与"推过什么"都要留着。
        writer.apply(serviceUrl = "https://other.example.workers.dev", accountKey = "first-key-123", includeApiKey = true)
        assertEquals(42L, runBlocking { credentials.sinceRev() }, "没换密钥就不该动游标")
        assertEquals(
            setOf("m1", "m2"),
            runBlocking { credentials.pushedMessageIds() },
            "没换密钥就不该忘掉推过什么"
        )

        // 换密钥：整块作废。
        writer.apply(serviceUrl = "https://other.example.workers.dev", accountKey = "second-key-456", includeApiKey = true)
        assertEquals(0L, runBlocking { credentials.sinceRev() }, "换了密钥必须从零开始拉")
        assertTrue(runBlocking { credentials.pushedMessageIds() }.isEmpty(), "换了密钥不该记得旧账号推过什么")
    }
}
