package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
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
 * "设置保存不进去"这条缺陷的契约测试。
 *
 * 保存设置曾经有一个只在 iOS 上显形的竞态：`ChatEngine.saveSettings` 只把值交给存储，
 * **不更新内存状态**，而内存状态靠"存储的 values 流在写入后重发"来跟上。
 * Android 的 DataStore 会重发，iOS 的 `NSUserDefaults` 包装曾只发射一次，于是：
 *
 * - 设置页依次调用 `updateConfig` / `updatePersona` / `updateLocationEnabled`，每一个都读
 *   `engine.config.value` 再改一个字段。内存状态永远不更新，所以后一次调用拿着**旧快照**
 *   把前一次刚写的字段覆盖回去——最后落盘的是"旧 API Key + 旧名字 + 新定位开关"。
 * - 用户在界面上看到的就是"改了 Key 和名字，一保存就变回去"。
 *
 * 所以这里用 [OneShotStore]（只发射一次、写入不重发，正是 iOS 曾经的行为）来锁住：
 * 保存的**唯一依据是引擎自己的内存状态**，与存储介质是否重发无关。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPersistenceTest {

    /**
     * 只发射一次、写完不重发的键值存储。
     *
     * 不是"拙劣的假实现"：iOS 的 `NSUserDefaults` 没有变更通知，包装成冷流就是一次性的。
     * 两份实现里只有 Android 的 DataStore 会重发，所以这条测试特意选不会重发的那一边。
     */
    private class OneShotStore : KeyValueStore {
        private val backing = mutableMapOf<String, SettingValue>()

        override val values: Flow<Map<String, SettingValue>> = flow { emit(backing.toMap()) }

        override suspend fun put(entries: Map<String, SettingValue>) {
            backing.putAll(entries)
        }

        fun snapshot(): Map<String, SettingValue> = backing.toMap()
    }

    private class Fixture {
        val fileSystem = FakeFileSystem()
        val paths = AppPaths("/data".toPath())
        val log = ChatLogStore(fileSystem, paths.chatLog, Dispatchers.Unconfined) { NOW }
        val store = OneShotStore()
        val settings = SettingsRepository(store)
        val api = ApiClient(NoopTransport, Dispatchers.Unconfined)

        companion object {
            const val NOW = 1_700_000_000_000L
        }
    }

    private fun engineTest(body: suspend TestScope.(ChatEngine, Fixture) -> Unit) = runTest {
        val fixture = Fixture()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val engine = ChatEngine(
                api = fixture.api,
                settings = fixture.settings,
                log = fixture.log,
                memoryStore = CatMemoryStore(fixture.fileSystem, fixture.paths.catMemory, Dispatchers.Unconfined),
                extractor = MemoryExtractor(fixture.api),
                imageDataUrls = { emptyMap() },
                locationSource = null,
                invalidateImageCache = {},
                scope = scope
            )
            engine.start()
            advanceUntilIdle()
            body(engine, fixture)
        } finally {
            scope.cancel()
        }
    }

    /**
     * 保存之后内存状态必须立刻是新值，即使存储流再也不发射。
     *
     * 内存状态是界面与后续保存的唯一依据：它落后一格，下一次"读-改-写"就会丢掉刚存的字段。
     */
    @Test
    fun `saving settings updates the in-memory state even when the store never re-emits`() =
        engineTest { engine, fixture ->
            val config = engine.config.value.copy(apiKey = "sk-brand-new", model = "cat-9")
            val persona = engine.persona.value.copy(name = "团子", notes = "不要聊工作")

            engine.saveSettings(config, persona, locationEnabled = false)
            advanceUntilIdle()

            assertEquals("sk-brand-new", engine.config.value.apiKey, "内存里的 API Key 应当立刻更新")
            assertEquals("cat-9", engine.config.value.model)
            assertEquals("团子", engine.persona.value.name, "内存里的名字应当立刻更新")
            assertEquals("不要聊工作", engine.persona.value.notes)
            assertFalse(engine.locationEnabled.value, "内存里的定位开关应当立刻更新")

            // 落盘也必须发生：内存对了但没写下去，重启就丢。
            val persisted = fixture.store.snapshot()
            assertEquals(
                "sk-brand-new",
                (persisted[SettingsKeys.API_KEY] as? SettingValue.Str)?.value,
                "API Key 要落盘"
            )
            assertEquals(
                "团子",
                (persisted[SettingsKeys.CAT_NAME] as? SettingValue.Str)?.value,
                "名字要落盘"
            )
            assertEquals(
                SettingValue.Flag(false),
                persisted[SettingsKeys.LOCATION_ENABLED],
                "定位开关要落盘"
            )
        }

    /**
     * iOS 设置页保存时依次调用三个"只改一块"的入口；后一次不能把前一次刚改的字段带回去。
     *
     * 这是用户实际走的那条路径：改 API Key、改名字，点保存，然后发现两个都变回了旧值。
     * 顺序与设置页里的调用顺序一致（配置 → 角色 → 定位）。
     */
    @Test
    fun `a sequence of partial saves composes instead of clobbering earlier fields`() =
        engineTest { engine, fixture ->
            // 1) 只改模型服务。
            engine.saveSettings(
                engine.config.value.copy(apiKey = "sk-sequence", baseUrl = "https://example.test/v1"),
                engine.persona.value,
                engine.locationEnabled.value
            )
            // 2) 只改角色设定。
            engine.saveSettings(
                engine.config.value,
                engine.persona.value.copy(name = "小白", speechStyle = CatSpeechStyle.CONCISE),
                engine.locationEnabled.value
            )
            // 3) 只改定位开关。
            engine.saveSettings(engine.config.value, engine.persona.value, locationEnabled = false)
            advanceUntilIdle()

            assertEquals("sk-sequence", engine.config.value.apiKey, "后一次保存不能把 Key 冲回去")
            assertEquals("https://example.test/v1", engine.config.value.baseUrl)
            assertEquals("小白", engine.persona.value.name, "后一次保存不能把名字冲回去")
            assertEquals(CatSpeechStyle.CONCISE, engine.persona.value.speechStyle)
            assertFalse(engine.locationEnabled.value)

            val persisted = fixture.store.snapshot()
            assertEquals("sk-sequence", (persisted[SettingsKeys.API_KEY] as? SettingValue.Str)?.value)
            assertEquals("小白", (persisted[SettingsKeys.CAT_NAME] as? SettingValue.Str)?.value)
            assertEquals(SettingValue.Flag(false), persisted[SettingsKeys.LOCATION_ENABLED])
        }

    /**
     * 一次保存就是**一次**原子写入：三块设置要么一起进存储，要么都不进。
     *
     * 三次分开写会让半个快照在存储里短暂可见，而同一时刻正在跑的同步可能正好读到它，
     * 把"改了一半"的设置推上云。
     */
    @Test
    fun `one save writes the whole settings snapshot in a single put`() = engineTest { _, fixture ->
        val writes = mutableListOf<Map<String, SettingValue>>()
        val counting = object : KeyValueStore {
            override val values: Flow<Map<String, SettingValue>> = flow { emit(emptyMap()) }
            override suspend fun put(entries: Map<String, SettingValue>) {
                writes += entries
                fixture.store.put(entries)
            }
        }

        // 直接用仓库验证写次数：引擎持有的是构造期注入的那个仓库，所以这里单独建一个。
        val repository = SettingsRepository(counting)
        repository.save(ApiConfig(), CatPersona(), locationEnabled = true)

        assertEquals(1, writes.size, "整份设置必须在一次 put 里写完")
        assertTrue(writes.single().keys.containsAll(SETTINGS_KEYS), "一份快照要包含全部设置键")
    }
}

/** 设置相关的全部键；[StoredKeyRegistry] 里还混着同步凭据，这里只取设置那部分。 */
private val SETTINGS_KEYS = listOf(
    SettingsKeys.BASE_URL, SettingsKeys.API_KEY, SettingsKeys.MODEL,
    SettingsKeys.TEMPERATURE, SettingsKeys.TOP_P, SettingsKeys.MAX_TOKENS,
    SettingsKeys.THINKING, SettingsKeys.REASONING_EFFORT, SettingsKeys.PROVIDER_ID,
    SettingsKeys.CAT_NAME, SettingsKeys.CAT_TRAITS, SettingsKeys.CAT_SPEECH_STYLE,
    SettingsKeys.CAT_FLAVOR, SettingsKeys.CAT_NOTES, SettingsKeys.LOCATION_ENABLED
)

/** 这条测试不真的发请求；任何调用都直接失败，避免掩盖意外路径。 */
private object NoopTransport : JsonHttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        HttpResponse(500, "{}")

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String
    ): HttpResponse = HttpResponse(500, "{}")

    override suspend fun postJsonStreaming(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit
    ): HttpResponse = HttpResponse(500, "{}")
}
