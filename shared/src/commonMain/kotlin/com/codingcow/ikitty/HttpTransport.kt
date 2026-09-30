package com.codingcow.ikitty

/** 一次 HTTP 响应。业务只需要状态码和正文，不需要头。 */
class HttpResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * 一次以原始字节收发的 HTTP 响应。
 *
 * 单独一个类型而不是复用 [HttpResponse]：图片响应一旦经过一次 `decodeToString()`
 * 就已经损坏，用不同返回类型让调用方无法误用。
 */
class HttpBytesResponse(val code: Int, val body: ByteArray) {
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
 * 两条链路实际用到的 JSON 请求只有三种，所以这部分契约刻意做窄：
 * - 非流式 POST（聊天、记忆整理、测试连接、同步信封）；
 * - GET（模型列表、健康检查）；
 * - 流式 POST（SSE 聊天）。
 *
 * 取消语义：协程被取消时必须让底层调用也停下来，而不是让读取线程挂住。
 *
 * [postJsonStreaming] 把完整正文累积后放进 [HttpResponse.body]：服务商可能忽略
 * `stream` 直接返回普通 JSON，那时调用方要靠这份原文整体解析。
 */
interface JsonHttpTransport {

    suspend fun get(url: String, headers: Map<String, String>): HttpResponse

    suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse

    suspend fun postJsonStreaming(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit
    ): HttpResponse
}

/**
 * 平台无关的 HTTP 契约（完整版：JSON + 原始字节）。
 *
 * 拆成 [JsonHttpTransport] + 两个字节方法，是因为**聊天只用得上 JSON**：把字节方法塞进
 * 同一个接口会让每个只关心聊天的实现（包括测试里的假传输）都要写两段永远不执行的代码。
 * 同步需要收发图片，才需要完整契约。
 */
interface HttpTransport : JsonHttpTransport {

    /**
     * 以原始字节 POST，并显式声明内容类型。
     *
     * 独立于 [postJson] 而不是"把它当字符串发"：图片是二进制，
     * 任何一次 UTF-8 编解码都会破坏它。内容类型由调用方给出，
     * 因为这一层不该知道"什么业务发什么类型"。
     */
    suspend fun postBytes(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        body: ByteArray
    ): HttpResponse

    /**
     * 以原始字节 GET。
     *
     * 与 [get] 分开是因为 [HttpResponse.body] 是 `String`：图片响应经过一次
     * `decodeToString()` 就毁了。返回类型也不同，调用方无法误用。
     */
    suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytesResponse
}
