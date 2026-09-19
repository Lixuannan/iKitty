package com.codingcow.ikitty

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import io.ktor.client.statement.HttpResponse as KtorResponse

/**
 * iOS 侧的 [HttpTransport] 实现，走 Ktor 的 Darwin 引擎（封装 `NSURLSession`）。
 *
 * 契约与 Android 的 [OkHttpTransport] 完全一致：返回状态码 + 正文，网络层失败抛
 * [HttpTransportException]。上层的拼装与解析是同一份 commonMain 代码。
 *
 * 超时对齐 Android 侧：连接 20s、空闲读 90s。**不设整体请求超时**——
 * 流式回复可能持续几分钟，设了会把正常的长回答当成超时掐断。
 */
class KtorTransport(private val client: HttpClient = defaultClient()) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        client.get(url) { applyHeaders(headers) }.toHttpResponse()

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String
    ): HttpResponse = client.post(url) {
        applyHeaders(headers)
        contentType(ContentType.Application.Json)
        setBody(body)
    }.toHttpResponse()

    override suspend fun postJsonStreaming(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit
    ): HttpResponse {
        val response = client.post(url) {
            applyHeaders(headers)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        // 与 OkHttp 侧一样：逐行回调，同时保留完整正文用于"服务商忽略 stream"的退化解析。
        val whole = StringBuilder()
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readUTF8Line() ?: break
            whole.append(line).append('\n')
            onLine(line)
        }
        return HttpResponse(response.status.value, whole.toString())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders(
        headers: Map<String, String>
    ) {
        headers.forEach { (name, value) -> header(name, value) }
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(Darwin) {
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 90_000
                requestTimeoutMillis = null
            }
        }
    }
}

private suspend fun KtorResponse.toHttpResponse(): HttpResponse =
    HttpResponse(status.value, bodyAsText())

/** iOS 侧默认客户端：Ktor Darwin 传输 + iOS 的 IO 调度器。 */
fun iosApiClient(): ApiClient = ApiClient(KtorTransport(), Dispatchers.Default)
