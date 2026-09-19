package com.codingcow.ikitty

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实网络往返的集成测试。
 *
 * 其他测试都用假传输验证解析逻辑；这里起一个真的 HTTP 服务，让 [ChatEngine] 走完整路径：
 * 真实 socket → OkHttp 的流式读取 → SSE 解析 → 落盘。这是唯一能真正验证
 * "传输层 + 客户端 + 编排"接得上的测试，而且不需要模拟器或设备。
 *
 * 同时它复测了计划 Phase 5 里"服务商忽略 stream 直接返回普通 JSON"的退化路径。
 */
class ChatEngineIntegrationTest {

    private val root: Path = createTempDirectory("ikitty-it").toOkioPath()
    private val fileSystem: FileSystem = FileSystem.SYSTEM
    private val paths = AppPaths(root)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** 服务端收到的最后一次请求体，用来断言线上契约。 */
    @Volatile
    private var lastRequestBody: String? = null

    private var started = false

    @AfterTest
    fun tearDown() {
        scope.cancel()
        if (started) server.stop(0)
        Files.walk(root.toNioPathCompat()).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun startServer(handler: (HttpExchange) -> Unit) {
        server.createContext("/v1/chat/completions") { exchange ->
            lastRequestBody = exchange.requestBody.readBytes().decodeToString()
            handler(exchange)
        }
        server.start()
        started = true
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.address.port}/v1"

    private fun respondSse(exchange: HttpExchange, deltas: List<String>) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        // 长度 0 表示用 chunked：流式响应本来就该是分块到达的。
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { out ->
            deltas.forEach { piece ->
                val payload = """data: {"choices":[{"delta":{"content":"$piece"}}]}"""
                out.write((payload + "\n\n").encodeToByteArray())
                out.flush()
            }
            out.write("data: [DONE]\n\n".encodeToByteArray())
        }
    }

    private fun respondJson(exchange: HttpExchange, body: String, code: Int = 200) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun engine(): ChatEngine {
        val api = okHttpApiClient()
        val settings = SettingsRepository(object : KeyValueStore {
            override val values = MutableStateFlow(
                mapOf(
                    SettingsKeys.BASE_URL to SettingValue.Str(baseUrl()),
                    SettingsKeys.API_KEY to SettingValue.Str("sk-integration"),
                    SettingsKeys.MODEL to SettingValue.Str("deepseek-flash"),
                    SettingsKeys.PROVIDER_ID to SettingValue.Str("deepseek")
                )
            )

            override suspend fun put(entries: Map<String, SettingValue>) = Unit
        })
        return ChatEngine(
            api = api,
            settings = settings,
            log = ChatLogStore(fileSystem, paths.chatLog, Dispatchers.IO) { NOW },
            memoryStore = CatMemoryStore(fileSystem, paths.catMemory, Dispatchers.IO),
            extractor = MemoryExtractor(api),
            imageDataUrls = { emptyMap() },
            locationSource = null,
            invalidateImageCache = {},
            scope = scope
        )
    }

    /** 发出一次真实请求，等到对话进入稳定状态（不再忙、且已有回复）。 */
    private fun sendAndWait(engine: ChatEngine): List<StoredMessage> = runBlocking {
        engine.start()
        waitFor("开场白") { engine.messages.value.isNotEmpty() }
        engine.send("你好")
        waitFor("回复") { !engine.busy.value && engine.messages.value.size >= 3 }
        engine.messages.value
    }

    private fun waitFor(what: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        error("等不到$what（超时 ${timeoutMillis}ms）")
    }

    private fun persistedLog(): List<StoredMessage> = runBlocking {
        ChatLogStore(fileSystem, paths.chatLog, Dispatchers.IO) { NOW }.tail(10)
    }

    @Test
    fun `a streamed reply over a real socket is parsed and persisted`() {
        startServer { respondSse(it, listOf("喵", "～", "在呢")) }
        val engine = engine()

        val messages = sendAndWait(engine)

        assertEquals(listOf("你好", "喵～在呢"), messages.takeLast(2).map { it.content })
        assertTrue(messages.none { it.localError })
        // 落盘之后重启还看得到。
        assertEquals(listOf("你好", "喵～在呢"), persistedLog().takeLast(2).map { it.content })
        // 装配结果里应当只有 system + 本轮 user：开场白是 assistant，不能作为首条发出。
        assertEquals(2, engine.contextPlan.value?.messages?.size)
        assertEquals("system", engine.contextPlan.value?.messages?.first()?.role)
    }

    /** 服务商忽略 stream 参数时，退回整体解析，而不是当成空回复。 */
    @Test
    fun `a provider that ignores stream still yields a reply`() {
        startServer { exchange ->
            respondJson(
                exchange,
                """{"choices":[{"message":{"content":"完整回复"}}],"usage":{"total_tokens":5}}"""
            )
        }
        val engine = engine()

        val messages = sendAndWait(engine)

        assertEquals("完整回复", messages.last().content)
        assertTrue(!messages.last().localError)
    }

    @Test
    fun `an http error surfaces as a local error line`() {
        startServer { exchange ->
            respondJson(exchange, """{"error":{"message":"Invalid API key"}}""", code = 401)
        }
        val engine = engine()

        val messages = sendAndWait(engine)

        val error = messages.last()
        assertTrue(error.localError)
        assertTrue(error.content.contains("API Key 无效"), error.content)
        assertTrue(!engine.busy.value)
    }

    /** 请求体必须是能用的 JSON，并且带上模型、stream 与 system 提示词。 */
    @Test
    fun `the request body follows the wire contract`() {
        startServer { respondSse(it, listOf("好")) }
        val engine = engine()
        sendAndWait(engine)

        val body = lastRequestBody ?: error("服务端没有收到请求")
        val json = parseJsonObjectOrNull(body) ?: error("请求体不是合法 JSON：$body")
        assertEquals("deepseek-flash", json.stringOrEmpty("model"))
        assertEquals("true", json["stream"].toString())

        val messages = json.arrayOrNull("messages") ?: error("缺少 messages")
        val first = messages.first() as JsonObject
        assertEquals("system", first.stringOrEmpty("role"))
        assertTrue(first.stringOrEmpty("content").contains("你叫"), first.stringOrEmpty("content").take(80))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

/** `java.nio.file.Path` 与 okio 的 `Path` 之间取底层的桥接，仅测试用。 */
private fun Path.toNioPathCompat(): java.nio.file.Path = java.nio.file.Paths.get(toString())
