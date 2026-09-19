package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Android / JVM 侧的 [HttpTransport] 实现。
 *
 * 用的是原来 `ApiClient` 里的那套 OkHttp 配置与取消方式，逐行搬过来，行为不变：
 * 三个超时、`call.cancel()` 取消、`IOException` 归一成 [HttpTransportException]。
 *
 * 只有这一层知道 OkHttp；线上的拼装与解析在 commonMain 的 [ApiClient] 里。
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        execute(
            Request.Builder()
                .url(url)
                .apply { addHeaders(headers) }
                .get()
                .build()
        )

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String
    ): HttpResponse = execute(postRequest(url, headers, body))

    override suspend fun postJsonStreaming(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit
    ): HttpResponse = withContext(ioDispatcher) {
        val call = client.newCall(postRequest(url, headers, body))
        // 协程被取消时取消底层调用，否则阻塞中的读取会把线程挂住。
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val source = response.body?.source()
                val whole = StringBuilder()
                if (source != null) {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        whole.append(line).append('\n')
                        onLine(line)
                    }
                }
                HttpResponse(response.code, whole.toString())
            }
        } catch (e: IOException) {
            throw HttpTransportException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            cancellation?.dispose()
        }
    }

    private suspend fun execute(request: Request): HttpResponse = withContext(ioDispatcher) {
        val call = client.newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                HttpResponse(response.code, response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw HttpTransportException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            cancellation?.dispose()
        }
    }

    private fun postRequest(url: String, headers: Map<String, String>, body: String): Request =
        Request.Builder()
            .url(url)
            .apply { addHeaders(headers) }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun Request.Builder.addHeaders(headers: Map<String, String>): Request.Builder = apply {
        headers.forEach { (name, value) -> addHeader(name, value) }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** 与原实现一致：连接 20s、写 30s、读 90s。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}

/** Android 侧的默认客户端：OkHttp 传输 + `Dispatchers.IO`。 */
fun androidApiClient(): ApiClient = ApiClient(OkHttpTransport(), Dispatchers.IO)

/**
 * Android 侧的定位来源。
 *
 * 和原实现一样用一个独立的客户端：定位是可选的附加功能，不该和聊天共用连接池。
 * 超时由 [IpLocationSource] 用 `withTimeoutOrNull` 统一控制。
 */
fun androidLocationSource(): LocationSource = IpLocationSource(OkHttpTransport())
