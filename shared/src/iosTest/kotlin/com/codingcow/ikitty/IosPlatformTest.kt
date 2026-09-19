package com.codingcow.ikitty

import kotlinx.coroutines.flow.first
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

    @AfterTest
    fun tearDown() {
        val defaults = NSUserDefaults.standardUserDefaults
        cleanupKeys.forEach { defaults.removeObjectForKey(it) }
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
}
