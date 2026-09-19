package com.codingcow.ikitty

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IP 定位的编排：多端点兜底、TTL 缓存、失败静默、超时换下一个。
 *
 * 解析本身的容错已经在 [IpPlaceParseTest] 里锁住了，这里只管"什么时候发请求、
 * 拿到的结果留多久、失败之后旧值还在不在"。
 */
class IpLocationSourceTest {

    private class FakeTransport(
        private val handler: suspend (String) -> HttpResponse
    ) : HttpTransport {
        val requested = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requested += url
            return handler(url)
        }

        override suspend fun postJson(url: String, headers: Map<String, String>, body: String) =
            error("定位不该发 POST")

        override suspend fun postJsonStreaming(
            url: String,
            headers: Map<String, String>,
            body: String,
            onLine: (String) -> Unit
        ) = error("定位不该发流式 POST")
    }

    private val hangzhou = """{"status":"success","city":"杭州","regionName":"浙江省","country":"中国"}"""

    @Test
    fun `the first endpoint that answers wins`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("ip-api") -> HttpResponse(500, "")
                url.contains("ipwho") -> HttpResponse(200, hangzhou)
                else -> error("不该请求第三个端点")
            }
        }
        val source = IpLocationSource(transport, endpoints = listOf("http://ip-api.com/x", "https://ipwho.is/", "https://ipapi.co/y"))

        source.refresh(now = 1_000L)

        assertEquals("杭州", source.cached()?.city)
        assertEquals(2, transport.requested.size)
    }

    /** 已经新鲜就不再发请求：城市不会几分钟就变，没必要反复把 IP 送出去。 */
    @Test
    fun `a fresh value stops further requests`() = runTest {
        val transport = FakeTransport { HttpResponse(200, hangzhou) }
        val source = IpLocationSource(transport, endpoints = listOf("https://ipwho.is/"))

        source.refresh(now = 1_000L)
        assertTrue(source.isFresh(1_000L))
        source.refresh(now = 1_000L + IpLocationSource.TTL_MILLIS - 1)
        assertEquals(1, transport.requested.size)

        // 过了 TTL 就重新去拿。
        source.refresh(now = 1_000L + IpLocationSource.TTL_MILLIS)
        assertEquals(2, transport.requested.size)
    }

    @Test
    fun `an expired value is not fresh`() = runTest {
        val transport = FakeTransport { HttpResponse(200, hangzhou) }
        val source = IpLocationSource(transport, endpoints = listOf("https://ipwho.is/"))
        assertTrue(!source.isFresh(1_000L))
        source.refresh(now = 1_000L)
        assertTrue(!source.isFresh(1_000L + IpLocationSource.TTL_MILLIS + 1))
    }

    /** 全部端点都失败时保留上一次的结果：过期的城市名也比"不知道"强。 */
    @Test
    fun `a total failure keeps the previous place`() = runTest {
        var failing = false
        val transport = FakeTransport { if (failing) HttpResponse(503, "") else HttpResponse(200, hangzhou) }
        val source = IpLocationSource(transport, endpoints = listOf("https://ipwho.is/"))

        source.refresh(now = 1_000L)
        assertEquals("杭州", source.cached()?.city)

        failing = true
        source.refresh(now = 1_000L + IpLocationSource.TTL_MILLIS)
        assertEquals("杭州", source.cached()?.city)
    }

    /** 一个解析不出城市的返回体不算成功，要继续试下一个端点。 */
    @Test
    fun `an unusable body falls through to the next endpoint`() = runTest {
        val transport = FakeTransport { url ->
            if (url.contains("ipwho")) HttpResponse(200, "<html>gateway</html>")
            else HttpResponse(200, hangzhou)
        }
        val source = IpLocationSource(transport, endpoints = listOf("https://ipwho.is/", "https://ipapi.co/json/"))

        source.refresh(now = 1_000L)

        assertEquals("杭州", source.cached()?.city)
        assertEquals(2, transport.requested.size)
    }

    /** 一个端点卡住不能拖住整次刷新：超时就换下一个。 */
    @Test
    fun `a hanging endpoint is abandoned after the timeout`() = runTest {
        val transport = FakeTransport { url ->
            if (url.contains("ipwho")) {
                delay(Long.MAX_VALUE)
                HttpResponse(200, hangzhou)
            } else {
                HttpResponse(200, hangzhou)
            }
        }
        val source = IpLocationSource(
            transport,
            endpoints = listOf("https://ipwho.is/", "https://ipapi.co/json/"),
            timeoutMillis = 8_000L
        )

        source.refresh(now = 1_000L)

        assertEquals("杭州", source.cached()?.city)
        assertEquals(2, transport.requested.size)
    }

    /** 网络层异常同样只是"这个端点不行"，不能把异常抛给聊天。 */
    @Test
    fun `a transport failure is swallowed`() = runTest {
        val transport = FakeTransport { throw HttpTransportException("Connection refused") }
        val source = IpLocationSource(transport, endpoints = listOf("https://ipwho.is/"))

        source.refresh(now = 1_000L)

        assertNull(source.cached())
    }
}
