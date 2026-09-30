package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 聊天编排的契约。
 *
 * 这段逻辑以前只活在 Android 的 ViewModel 里、没有测试兜底（迁移计划的风险 R9），
 * 现在 iOS 也要依赖它，所以把关键行为固定下来：消息落盘、流式回复、
 * 失败时保留半截回复、清空记录时序号从头开始。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatEngineTest {

    private class FakeStore : KeyValueStore {
        private val state = MutableStateFlow<Map<String, SettingValue>>(emptyMap())
        override val values: Flow<Map<String, SettingValue>> = state
        override suspend fun put(entries: Map<String, SettingValue>) {
            state.value = state.value + entries
        }
    }

    private class FakeTransport(
        var response: HttpResponse = HttpResponse(200, "{}"),
        var streamLines: List<String> = emptyList(),
        /** 流出这么多行之后抛网络错误；-1 表示不失败。用来验证"半截回复"的处理。 */
        var failAfterLines: Int = -1
    ) : JsonHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>) = response

        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String
        ): HttpResponse = if (failAfterLines >= 0) {
            throw HttpTransportException("Connection refused")
        } else {
            response
        }

        override suspend fun postJsonStreaming(
            url: String,
            headers: Map<String, String>,
            body: String,
            onLine: (String) -> Unit
        ): HttpResponse {
            if (failAfterLines >= 0) {
                streamLines.take(failAfterLines).forEach(onLine)
                throw HttpTransportException("Connection refused")
            }
            streamLines.forEach(onLine)
            return response
        }
    }

    private class Fixture {
        val fileSystem = FakeFileSystem()
        val paths = AppPaths("/data".toPath())
        val transport = FakeTransport()
        val log = ChatLogStore(fileSystem, paths.chatLog, Dispatchers.Unconfined) { NOW }
        val memoryStore = CatMemoryStore(fileSystem, paths.catMemory, Dispatchers.Unconfined)
        val api = ApiClient(transport, Dispatchers.Unconfined)
        val settings = SettingsRepository(FakeStore())

        companion object {
            const val NOW = 1_700_000_000_000L
        }
    }

    /**
     * 编排自己会持有长命的收集协程（设置流、情绪回落），所以单独给一个作用域，
     * 用完取消；测试体仍然在 `runTest` 的调度器上，虚拟时间可控。
     */
    private fun engineTest(body: suspend TestScope.(ChatEngine, Fixture) -> Unit) = runTest {
        val fixture = Fixture()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val engine = ChatEngine(
                api = fixture.api,
                settings = fixture.settings,
                log = fixture.log,
                memoryStore = fixture.memoryStore,
                extractor = MemoryExtractor(fixture.api),
                imageDataUrls = { emptyMap() },
                locationSource = null,
                invalidateImageCache = {},
                scope = scope
            )
            body(engine, fixture)
        } finally {
            scope.cancel()
        }
    }

    private fun sse(vararg pieces: String): List<String> =
        pieces.map { """data: {"choices":[{"delta":{"content":"$it"}}]}""" }

    /** 直接写进日志的一条用户消息；记忆整理读的是日志，不是内存列表。 */
    private fun userMessage(seq: Long, content: String) = StoredMessage(
        seq = seq,
        role = StoredMessage.ROLE_USER,
        content = content,
        createdAt = Fixture.NOW,
        msgId = newMessageId(now = Fixture.NOW)
    )

    /**
     * 空记录启动时**不再自动补一条开场白**。
     *
     * 开场白曾经是一条落盘的助手消息，于是每台新设备都会自己造一条再同步上云，
     * 久而久之云端堆满重复的问候。现在它只是界面在"聊天为空"时渲染的一段文字，
     * 由 `CatPersona.welcome()` 给出，不进 `messages`。
     */
    @Test
    fun `an empty log stays empty on start`() = engineTest { engine, fixture ->
        engine.start()
        advanceUntilIdle()

        assertTrue(engine.messages.value.isEmpty(), "开场白不再是消息，空记录就该是空的")
        assertTrue(fixture.log.tail(10).isEmpty(), "开场白不落盘")
        assertTrue(!engine.busy.value)
    }

    @Test
    fun `sending appends the user message and the streamed reply`() = engineTest { engine, fixture ->
        fixture.transport.streamLines = sse("喵", "～")
        engine.start()
        advanceUntilIdle()

        engine.send("你好")
        advanceUntilIdle()

        val messages = engine.messages.value
        assertEquals(2, messages.size) // 用户 + 回复
        assertEquals("你好", messages[0].content)
        assertEquals(StoredMessage.ROLE_USER, messages[0].role)
        assertEquals("喵～", messages[1].content)
        assertEquals(StoredMessage.ROLE_ASSISTANT, messages[1].role)
        assertTrue(!engine.busy.value)
        assertEquals(null, engine.streamingReply.value)

        // 都要真的落盘，重启之后才看得到。
        assertEquals(
            listOf("你好", "喵～"),
            fixture.log.tail(10).map { it.content }.takeLast(2)
        )
    }

    /** 已经流出来的半截回复不能凭空消失，后面再补一条错误提示。 */
    @Test
    fun `a failing stream keeps the partial reply and adds an error line`() =
        engineTest { engine, fixture ->
            fixture.transport.streamLines = sse("说到一半")
            fixture.transport.failAfterLines = 1
            engine.start()
            advanceUntilIdle()

            engine.send("在吗")
            advanceUntilIdle()

            val messages = engine.messages.value
            val partial = messages[messages.size - 2]
            val error = messages.last()
            assertEquals("说到一半", partial.content)
            assertTrue(!partial.localError)
            assertTrue(error.localError)
            assertTrue(error.content.contains("出问题了"))
            assertTrue(!engine.busy.value)
            assertEquals(null, engine.streamingReply.value)
        }

    @Test
    fun `a blank send is ignored`() = engineTest { engine, _ ->
        engine.start()
        advanceUntilIdle()
        val before = engine.messages.value.size

        engine.send("   ")
        advanceUntilIdle()

        assertEquals(before, engine.messages.value.size)
    }

    /**
     * 清空记录时序号必须从头开始，否则新消息会续在旧序号后面，
     * 提取游标也会把新消息当成"早就整理过"。
     */
    @Test
    fun `clearing the log empties it and restarts the sequence`() =
        engineTest { engine, fixture ->
            fixture.transport.streamLines = sse("好")
            engine.start()
            advanceUntilIdle()
            engine.send("你好")
            advanceUntilIdle()
            assertEquals(2, engine.messages.value.size)

            engine.clearMessages()
            advanceUntilIdle()

            assertTrue(engine.messages.value.isEmpty(), "清空之后一条都不该剩，也不再补开场白")
            assertTrue(fixture.log.tail(10).isEmpty())

            // 下一条消息的序号必须从 1 重新开始。
            engine.send("再来")
            advanceUntilIdle()
            assertEquals(1L, engine.messages.value.first().seq)
        }

    /** 输入框有内容就进入 LISTENING，清空则回到 IDLE。 */
    @Test
    fun `input changes drive the listening mood`() = engineTest { engine, _ ->
        engine.start()
        advanceUntilIdle()

        engine.onInputChanged("在吗")
        assertEquals(CatMood.LISTENING, engine.mood.value)
        engine.onInputChanged("")
        assertEquals(CatMood.IDLE, engine.mood.value)
    }

    /** 整理失败只落错误状态，游标不前进——下次整理会把同一批消息再读一遍，所以能自愈。 */
    @Test
    fun `a failed memory extraction records the error without advancing the cursor`() =
        engineTest { engine, fixture ->
            fixture.transport.response = HttpResponse(200, "这不是 JSON")
            // 整理读的是**日志**（不是内存里的消息列表），所以先真的写一条进去。
            fixture.log.append(userMessage(seq = 1, content = "我叫小明"))
            engine.start()
            advanceUntilIdle()

            engine.extractMemoryNow()
            advanceUntilIdle()

            assertTrue(!engine.memoryStatus.value.running)
            assertTrue(engine.memoryStatus.value.lastError != null)
            assertEquals(0L, engine.memory.value.lastExtractedSeq)
        }

    @Test
    fun `a successful memory extraction merges facts and advances the cursor`() =
        engineTest { engine, fixture ->
            val facts = buildJsonObject {
                put(
                    "facts",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("category", "主人")
                                put("key", "名字")
                                put("value", "小明")
                            }
                        )
                    }
                )
            }.toString()
            fixture.transport.response = HttpResponse(
                200,
                buildJsonObject {
                    put(
                        "choices",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("message", buildJsonObject { put("content", facts) })
                                }
                            )
                        }
                    )
                }.toString()
            )
            engine.start()
            advanceUntilIdle()

            // 整理读的是**日志**（不是内存里的消息列表），所以先真的写一条进去。
            fixture.log.append(userMessage(seq = 1, content = "我叫小明"))

            engine.extractMemoryNow()
            advanceUntilIdle()

            assertEquals("小明", engine.memory.value.facts.single().value)
            assertTrue(engine.memory.value.lastExtractedSeq > 0L)
            assertEquals(null, engine.memoryStatus.value.lastError)
            // 记忆必须落盘，重启后还在。
            assertTrue(fixture.memoryStore.load().facts.isNotEmpty())
        }
}
