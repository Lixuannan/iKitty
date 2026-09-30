package com.codingcow.ikitty

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 云端同步的线上模型与编解码。
 *
 * 与 [JsonSupport] 保持同一套约定：显式 builder、不用 `@Serializable`、读取一律容错
 * （缺字段回退、坏元素跳过，绝不抛异常）。协议版本见 `docs/SYNC_DESIGN.md` §4。
 */

/** 协议版本。服务端遇到不认识的版本会回 400，客户端据此提示"应用需要更新"。 */
const val SYNC_PROTO = 1

/** 单次请求最多推多少条消息；与服务端 `max_batch_messages` 对齐。 */
const val SYNC_MAX_BATCH = 500

/** 单次拉取最多多少行；服务端另有上限，这是客户端主动要的更小的页。 */
const val SYNC_PULL_LIMIT = 500

/** 一条消息的线上形态。 */
data class SyncMessage(
    val msgId: String,
    val seq: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val localError: Boolean = false,
    val images: List<String> = emptyList()
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("msgId", msgId)
        put("seq", seq)
        put("role", role)
        put("content", content)
        put("createdAt", createdAt)
        // 与 StoredMessage 一样只在为真时写，减少请求体里无意义的字段。
        if (localError) put("localError", true)
        if (images.isNotEmpty()) {
            put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
        }
    }

    companion object {
        fun fromJson(json: JsonObject): SyncMessage? {
            val msgId = json.stringOrEmpty("msgId")
            val role = json.stringOrEmpty("role")
            val images = json.arrayOrNull("images").stringItems()
            if (msgId.isEmpty() || role.isEmpty()) return null
            val content = json.optString("content")
            // 与 StoredMessage.fromJson 同一套校验：纯图片消息允许空正文。
            //
            // 只丢"完全没有内容"的条目；**不判断 role 合不合法**——那是服务端的职责。
            // 客户端在这里悄悄丢掉一个 role 不认识的条目，用户就永远看不到服务端的拒绝原因，
            // 只会觉得"我这条消息同步到别的设备上了却没有"。
            if (content.isEmpty() && images.isEmpty()) return null
            return SyncMessage(
                msgId = msgId,
                seq = json.longOrZero("seq"),
                role = role,
                content = content,
                createdAt = json.longOrZero("createdAt"),
                localError = json.booleanOr("localError", false),
                images = images
            )
        }
    }
}

/** 一条可变的键值状态（目前只有 `settings`）。 */
data class SyncKvItem(val key: String, val payload: String, val updatedAt: Long)

/** 图片清单里的一项；只有 id 和字节数，用于判断本地缺哪些。 */
data class SyncRemoteImage(val imageId: String, val bytes: Long)

/** 一次拉取的结果。 */
data class SyncPullPage(
    val messages: List<SyncMessage>,
    val kv: List<SyncKvItem>,
    /**
     * 服务端明确告知"这些消息被删了"。
     *
     * 必须有这个显式信号：消息是按 `rev` 增量拉的，所以"这次响应里没有某条消息"
     * 既可能是被删了，也可能是它属于更早的一页。靠后者推断删除，会把正常的历史全部误删。
     */
    val deletedMessageIds: List<String>,
    /** 本次实际覆盖到的最大 rev；下一轮请求用它当 `sinceRev`。 */
    val rev: Long,
    /** 服务端当前水位；`rev < headRev` 说明还有数据没拉完。 */
    val headRev: Long,
    val images: List<SyncRemoteImage>
)

/** 推送结果：服务端接受的条数与逐条拒绝原因。 */
data class SyncPushResult(
    val applied: Int,
    val rejected: List<String>,
    val assigned: Map<String, Long>
)

/**
 * 云端同步不可用的原因，**按用户能采取的行动分类**，而不是按 HTTP 状态码分类。
 *
 * 这样界面文案只写一次：客户端永远不需要"根据 401 猜该怎么办"。
 */
class SyncException(
    message: String,
    /** 凭据无效（401）：应提示重新填写账号密钥，且**不要**动本地数据。 */
    val reauthorize: Boolean = false,
    /** 单批过大（413）：把批大小对半拆开重试。 */
    val batchTooLarge: Boolean = false,
    /** 被限流（429）：按 [retryAfterSeconds] 退避。 */
    val retryAfterSeconds: Int? = null
) : Exception(message)

/** 把 `sinceRev` 归一成一个合法的非负整数。 */
internal fun clampRev(value: Long): Long = if (value < 0L) 0L else value

internal fun JsonArray?.toSyncMessages(): List<SyncMessage> =
    this.orEmptyList().mapNotNull { (it as? JsonObject)?.let(SyncMessage::fromJson) }

internal fun JsonArray?.toSyncKvItems(): List<SyncKvItem> = buildList {
    for (element in this@toSyncKvItems.orEmptyList()) {
        val obj = element as? JsonObject ?: continue
        val key = obj.stringOrEmpty("key")
        if (key.isEmpty()) continue
        add(
            SyncKvItem(
                key = key,
                payload = obj.optString("payload"),
                updatedAt = obj.longOrZero("updatedAt")
            )
        )
    }
}

internal fun JsonArray?.toSyncImages(): List<SyncRemoteImage> = buildList {
    for (element in this@toSyncImages.orEmptyList()) {
        val obj = element as? JsonObject ?: continue
        val id = obj.stringOrEmpty("imageId")
        if (id.isEmpty()) continue
        add(SyncRemoteImage(id, obj.longOrZero("bytes")))
    }
}
