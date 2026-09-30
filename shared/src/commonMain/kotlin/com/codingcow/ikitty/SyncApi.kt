package com.codingcow.ikitty

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 云端同步的 HTTP 客户端。
 *
 * 只负责协议的两件事：拼请求、把响应翻译成 [SyncException] 或数据模型。
 * **不负责**决定"什么时候同步、失败了怎么退避、本地日志怎么改"——那是 [SyncEngine] 的事。
 * 与 [ApiClient] 的分工一致：这一层不懂业务，只懂协议。
 *
 * [baseUrl] 是 Worker 的根地址（例如 `https://ikitty-sync.example.workers.dev`）。
 */
class SyncApi(
    /**
     * 完整 HTTP 契约（含原始字节收发，图片要用）。
     *
     * 直接要求 [HttpTransport] 而不是另立一个"同步专用传输"接口：两者方法一一对应，
     * 多一层适配只会多一处要跟着改的地方，而类型错误本该在编译期暴露。
     */
    private val transport: HttpTransport,
    private val baseUrl: String
) {

    /**
     * 推拉合一。
     *
     * [sinceRev] 是上一次完整拉到的位置；服务端在同一个响应里带回本轮推送后的增量。
     */
    suspend fun sync(
        accountKey: String,
        request: SyncRequest
    ): SyncResponse {
        val json = call(accountKey) {
            transport.postJson(
                url = "$root/sync",
                headers = headers(accountKey),
                body = request.toJson().toString()
            )
        }
        return SyncResponse.fromJson(json)
    }

    /** 清空云端。**不可撤销**，调用方必须先让用户确认。 */
    suspend fun deleteAll(accountKey: String) {
        call(accountKey) {
            transport.postJson(
                url = "$root/sync/deleteAll",
                headers = headers(accountKey),
                body = "{}"
            )
        }
    }

    /**
     * 上传一张已经归一化的 JPEG。
     *
     * [imageId] 必须等于内容的 SHA-256 前 32 位（`img_<hex>.jpg`）：服务端会重新计算并比对，
     * 不一致直接 400。客户端先算好再传，是为了让本地文件名、D1 登记、R2 对象三者天然一致。
     */
    suspend fun uploadImage(accountKey: String, imageId: String, bytes: ByteArray): SyncRemoteImage =
        call(accountKey) {
            transport.postBytes(
                url = "$root/sync/image",
                headers = headers(accountKey) + mapOf("X-Image-Id" to imageId),
                contentType = "image/jpeg",
                body = bytes
            )
        }.let { json ->
            SyncRemoteImage(
                imageId = json.stringOrEmpty("imageId").ifBlank { imageId },
                bytes = json.longOrZero("bytes").takeIf { it > 0 } ?: bytes.size.toLong()
            )
        }

    /** 下载一张图片。内容寻址，所以同一个 id 永远对应同一份字节。 */
    suspend fun downloadImage(accountKey: String, imageId: String): ByteArray {
        val response = try {
            transport.getBytes(
                url = "$root/sync/image?id=$imageId",
                headers = headers(accountKey)
            )
        } catch (e: HttpTransportException) {
            throw SyncException("网络请求失败：${e.message ?: "未知错误"}")
        }
        if (!response.isSuccessful) {
            throw failure(response.code, response.body.decodeToString())
        }
        return response.body
    }

    private val root: String get() = baseUrl.trimEnd('/')

    private fun headers(accountKey: String): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Authorization" to "Bearer ${accountKey.trim()}"
    )

    /**
     * 统一的失败翻译。
     *
     * 401 标成 [SyncException.reauthorize]，413 标成 [SyncException.batchTooLarge]，
     * 429 带上 Retry-After：这三类是客户端**能自动处理**的，所以必须与"就是坏了"分开。
     */
    private fun failure(code: Int, body: String): SyncException {
        val detail = parseJsonObjectOrNull(body)?.stringOrEmpty("error").orEmpty()
        return when (code) {
            401, 403 -> SyncException(
                "账号密钥无效或已失效，请重新填写（本地数据未受影响）",
                reauthorize = true
            )
            413 -> SyncException("这一批太大，需要拆开重试", batchTooLarge = true)
            429 -> SyncException(
                "同步太频繁，稍后再试",
                retryAfterSeconds = parseJsonObjectOrNull(body)?.intOrZero("retryAfterSeconds")
                    ?.takeIf { it > 0 } ?: 60
            )
            400 -> SyncException(
                when (detail) {
                    "unsupported_proto" -> "服务端不支持这个协议版本，请更新应用"
                    "image_id_mismatch" -> "图片内容与标识不一致，已跳过这张图"
                    else -> "服务端拒绝了这次同步请求"
                }
            )
            in 500..599 -> SyncException("服务端暂时不可用，稍后再试")
            else -> SyncException("同步失败（HTTP $code）")
        }
    }

    /**
     * 发一次请求并把响应解析成 JSON 对象。
     *
     * 网络层异常只在这里被翻译一次：调用方看到的永远要么是数据，要么是 [SyncException]。
     */
    private suspend inline fun call(
        accountKey: String,
        request: () -> HttpResponse
    ): JsonObject {
        if (accountKey.isBlank()) throw SyncException("还没有配置账号密钥")
        val response = try {
            request()
        } catch (e: HttpTransportException) {
            throw SyncException("网络请求失败：${e.message ?: "未知错误"}")
        }
        if (!response.isSuccessful) {
            val detail = parseJsonObjectOrNull(response.body)?.stringOrEmpty("error").orEmpty()
            // 400 的细分原因只在这里可得，所以单独处理一次再落到通用翻译。
            if (response.code == 400 && detail == "unsupported_proto") {
                throw SyncException("服务端不支持这个协议版本，请更新应用")
            }
            throw failure(response.code, response.body)
        }
        return parseJsonObjectOrNull(response.body)
            ?: throw SyncException("服务端返回了无法解析的内容")
    }

    /** 给界面用的能力探测：Worker 是否可达、协议版本是否匹配。 */
    suspend fun health(): Boolean = try {
        transport.get("$root/health", emptyMap()).isSuccessful
    } catch (_: HttpTransportException) {
        false
    }
}

/** `POST /sync` 的请求体。 */
data class SyncRequest(
    val deviceId: String,
    val sinceRev: Long,
    val messages: List<SyncMessage> = emptyList(),
    val kv: List<SyncKvItem> = emptyList(),
    val deletedMessageIds: List<String> = emptyList(),
    val pullLimit: Int = SYNC_PULL_LIMIT,
    /**
     * 请求"把所有墓碑重新说一遍"。
     *
     * 删除用的是 rev，而客户端下次拉取时那条墓碑的 rev 已经落在游标后面了，
     * 增量拉取永远看不到它。所以本地有旧消息的客户端要定期要一次全量墓碑。
     */
    val tombstonesOnly: Boolean = false
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("proto", SYNC_PROTO)
        put("deviceId", deviceId)
        put("sinceRev", clampRev(sinceRev))
        put("pullLimit", pullLimit)
        if (tombstonesOnly) put("tombstonesOnly", true)
        if (messages.isNotEmpty()) put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
        if (kv.isNotEmpty()) {
            put(
                "kv",
                buildJsonArray {
                    kv.forEach { item ->
                        add(
                            buildJsonObject {
                                put("key", item.key)
                                put("payload", item.payload)
                                put("updatedAt", item.updatedAt)
                            }
                        )
                    }
                }
            )
        }
        if (deletedMessageIds.isNotEmpty()) {
            put(
                "deleted",
                buildJsonArray {
                    deletedMessageIds.forEach { id ->
                        add(buildJsonObject { put("kind", "message"); put("msgId", id) })
                    }
                }
            )
        }
    }
}

/** `POST /sync` 的响应体。 */
data class SyncResponse(
    val rev: Long,
    val applied: Int,
    val rejected: List<String>,
    val assigned: Map<String, Long>,
    val pull: SyncPullPage
) {
    companion object {
        /** 解析响应；结构不对时抛 [SyncException]，绝不返回半个结果。 */
        fun fromJson(json: JsonObject): SyncResponse {
            val pull = json.objectOrNull("pull")
                ?: throw SyncException("服务端返回的内容缺少 pull 字段")

            // 拒绝原因拼成一句可直接展示的话：界面不需要知道它来自哪个字段。
            val rejected = json.arrayOrNull("rejected").orEmptyList().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj.stringOrEmpty("msgId").ifBlank { obj.stringOrEmpty("key") }
                val reason = obj.stringOrEmpty("reason")
                when {
                    id.isNotEmpty() && reason.isNotEmpty() -> "$id（$reason）"
                    id.isNotEmpty() -> id
                    reason.isNotEmpty() -> reason
                    else -> null
                }
            }

            val assigned = buildMap {
                json.objectOrNull("assigned")?.forEach { (key, value) ->
                    val seq = (value as? JsonPrimitive)?.content?.toLongOrNull()
                    if (seq != null) put(key, seq)
                }
            }

            return SyncResponse(
                rev = json.longOrZero("rev"),
                applied = json.intOrZero("applied"),
                rejected = rejected,
                assigned = assigned,
                pull = SyncPullPage(
                    messages = pull.arrayOrNull("messages").toSyncMessages(),
                    kv = pull.arrayOrNull("kv").toSyncKvItems(),
                    deletedMessageIds = pull.arrayOrNull("deleted").orEmptyList().mapNotNull { element ->
                        val obj = element as? JsonObject ?: return@mapNotNull null
                        obj.stringOrEmpty("msgId").takeIf { it.isNotEmpty() }
                    },
                    rev = pull.longOrZero("rev").let(::clampRev),
                    headRev = pull.longOrZero("headRev").let(::clampRev),
                    images = pull.arrayOrNull("images").toSyncImages()
                )
            )
        }
    }
}
