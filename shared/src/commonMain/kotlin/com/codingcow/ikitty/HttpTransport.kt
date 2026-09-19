package com.codingcow.ikitty

/** 一次 HTTP 响应。业务只需要状态码和正文，不需要头。 */
class HttpResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * HTTP 调用本身失败：DNS、连接被拒、超时、TLS、读写中断。
 *
 * 刻意与 [ApiException] 分开：这一层只报"网络没打通"，把它翻译成给用户看的中文
 * 是 [ApiClient] 的职责。这样换平台实现（OkHttp / Ktor）不会改变用户看到的措辞。
 */
class HttpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 平台无关的 HTTP 契约。
 *
 * 两条链路都只用到这三种请求，所以契约刻意做窄：
 * - 非流式 POST（聊天、记忆整理、测试连接）；
 * - GET（模型列表）；
 * - 流式 POST（SSE 聊天）。
 *
 * 取消语义：协程被取消时必须让底层调用也停下来，而不是让读取线程挂住。
 *
 * [postJsonStreaming] 把完整正文累积后放进 [HttpResponse.body]：服务商可能忽略
 * `stream` 直接返回普通 JSON，那时调用方要靠这份原文整体解析。
 */
interface HttpTransport {

    suspend fun get(url: String, headers: Map<String, String>): HttpResponse

    suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse

    suspend fun postJsonStreaming(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit
    ): HttpResponse
}
