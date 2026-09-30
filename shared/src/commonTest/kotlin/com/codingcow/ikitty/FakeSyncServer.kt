package com.codingcow.ikitty

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一个"照着协议实现"的假 Worker。
 *
 * 为什么不只断言客户端发出去的 JSON：那样只能验证"发的东西和我想的一样"，
 * 验证不了"发的东西和协议一致、并且拿回来的数据能正确落到本地"。这里按
 * `docs/SYNC_DESIGN.md` §4 与 `worker/src/index.js` 的语义实现一份最小服务端，
 * 于是 [SyncEngine] 的整条路径（推 → 拉 → 重写日志 → 补图片）都能在纯测试里跑完。
 *
 * 模拟的是**服务端权威**那部分关键行为：服务端自己分配 `seq` 与 `rev`、
 * 按 `msg_id` 幂等、`kv` 用 `updatedAt` 做 LWW。与真 Worker 的差别只在存储介质。
 */
class FakeSyncServer(
    /** 模拟 401：账号密钥不匹配时返回。 */
    var authorized: Boolean = true
) : HttpTransport {

    /** 服务端持有的消息，按分配顺序排列。 */
    val messages = mutableListOf<SyncMessage>()

    /** 服务端持有的 kv。 */
    val kv = linkedMapOf<String, SyncKvItem>()

    /** 服务端持有的图片登记：imageId -> 字节数。 */
    val images = linkedMapOf<String, Int>()

    /** 上传过的图片字节。 */
    val imageBytes = mutableMapOf<String, ByteArray>()

    /** 每条消息当前的 rev；拉取游标按它过滤。 */
    private val messageRev = mutableMapOf<String, Long>()
    private val kvRev = mutableMapOf<String, Long>()

    /** 墓碑：被删掉的 msgId → 那次删除的 rev。 */
    private val tombstones = mutableMapOf<String, Long>()

    var nextSeq = 0L
    var rev = 0L

    /** 收到的请求，按顺序记下来，用于断言"推了什么"。 */
    val requests = mutableListOf<SyncRequest>()

    /** 让测试可以模拟若干次 5xx，验证重试。 */
    var failuresBeforeSuccess = 0

    /**
     * 单批消息条数上限；超过就回 413，与真 Worker 的 `max_batch_messages` 对应。
     * 设成 null 表示不限。
     */
    var maxBatchMessages: Int? = null

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String
    ): HttpResponse {
        if (!authorized) return HttpResponse(401, """{"error":"invalid_key"}""")
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            return HttpResponse(500, """{"error":"internal_error"}""")
        }
        return when {
            url.endsWith("/sync/deleteAll") -> {
                clearAll()
                HttpResponse(200, """{"ok":true}""")
            }
            url.endsWith("/sync") -> {
                val json = parseJsonObjectOrNull(body) ?: return HttpResponse(400, """{"error":"bad_json"}""")
                val cap = maxBatchMessages
                val count = json.arrayOrNull("messages")?.size ?: 0
                if (cap != null && count > cap) {
                    return HttpResponse(413, """{"error":"batch_too_large"}""")
                }
                val text = handleSync(json).toString()
                responses += text
                HttpResponse(200, text)
            }
            else -> HttpResponse(404, """{"error":"not_found"}""")
        }
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        if (url.endsWith("/health")) HttpResponse(200, """{"ok":true}""") else HttpResponse(404, "{}")

    /**
     * 流式路径。
     *
     * 这条假服务端面向的是同步，不实现模型的 SSE；返回一个空对象会让聊天侧的解析
     * 抛"返回内容里没有 choices"——这正是想要的：测试里一旦有人真去调模型，
     * 会立刻以明显的错误失败，而不是悄悄拿到一份看似成功的空回复。
     */
    override suspend fun postJsonStreaming(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit
    ): HttpResponse = HttpResponse(200, "{}")

    override suspend fun postBytes(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        body: ByteArray
    ): HttpResponse {
        if (!authorized) return HttpResponse(401, """{"error":"invalid_key"}""")
        // 与真 Worker 一样：声明的 id 必须等于内容哈希，否则拒绝。
        val expected = imageIdFor(body)
        if (headers["X-Image-Id"] != expected) {
            return HttpResponse(400, """{"error":"image_id_mismatch","expected":"$expected"}""")
        }
        imageBytes[expected] = body
        images[expected] = body.size
        return HttpResponse(200, """{"imageId":"$expected","bytes":${body.size}}""")
    }

    override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytesResponse {
        if (!authorized) return HttpBytesResponse(401, ByteArray(0))
        val id = url.substringAfter("id=", "")
        val bytes = imageBytes[id] ?: return HttpBytesResponse(404, ByteArray(0))
        return HttpBytesResponse(200, bytes)
    }

    /**
     * 直接往服务端塞一批"别的设备已经写好的"消息。
     *
     * 由这里统一分配 seq 与 rev：测试不该自己维护这两个计数器，否则每个用例都要
     * 复述一遍服务端的记账规则，写错时还会以为是客户端的问题。
     */
    fun seedMessages(incoming: List<SyncMessage>) {
        incoming.forEach { message ->
            nextSeq++
            rev++
            messages += message.copy(seq = nextSeq)
            messageRev[message.msgId] = rev
        }
    }

    /** 直接往服务端塞一份 kv（例如"别的设备改过设置"）。 */
    fun seedKv(item: SyncKvItem) {
        rev++
        kv[item.key] = item
        kvRev[item.key] = rev
    }

    /** 模拟另一台设备删掉了某条消息：删掉数据，留下墓碑。 */
    fun seedDeletion(msgId: String) {
        if (messages.removeAll { it.msgId == msgId }) {
            messageRev.remove(msgId)
            rev++
            tombstones[msgId] = rev
        }
    }

    fun clearAll() {
        messages.clear()
        kv.clear()
        images.clear()
        imageBytes.clear()
        messageRev.clear()
        kvRev.clear()
        tombstones.clear()
        responses.clear()
        nextSeq = 0L
        rev = 0L
    }

    /**
     * 一条消息在服务端的序号，仅用于测试断言。
     *
     * 找不到返回 null，而不是 0：0 是一个合法的"还没同步"的客户端草稿值。
     */
    fun serverSeqOf(msgId: String): Long? = messages.firstOrNull { it.msgId == msgId }?.seq

    /** 当前有哪些墓碑，仅用于测试断言。 */
    fun tombstoneIds(): List<String> = tombstones.keys.toList()

    /** 每个响应体的原文，便于定位是服务端还是客户端的账对不上。 */
    val responses = mutableListOf<String>()

    private fun handleSync(request: JsonObject): JsonObject {
        val parsed = parseRequest(request)
        requests += parsed

        var applied = 0
        val rejected = mutableListOf<JsonObject>()
        val assigned = mutableMapOf<String, Long>()

        parsed.messages.forEach { incoming ->
            if (incoming.role != StoredMessage.ROLE_USER && incoming.role != StoredMessage.ROLE_ASSISTANT) {
                rejected += reject(incoming.msgId, "bad_role")
                return@forEach
            }
            // 与真 Worker 的约定一致：纯文字要求正文非空（空白也算空），
            // 只有图片的消息才允许空正文。客户端不预先过滤这些，所以拒绝路径必须能测到。
            if (incoming.content.isBlank() && incoming.images.isEmpty()) {
                rejected += reject(incoming.msgId, "empty_content")
                return@forEach
            }
            // 幂等：已经存在的 msg_id 不再分配序号，也不改变原有 seq。
            if (messages.any { it.msgId == incoming.msgId }) {
                applied++
                return@forEach
            }
            nextSeq++
            rev++
            val stored = incoming.copy(seq = nextSeq)
            messages += stored
            messageRev[incoming.msgId] = rev
            assigned[incoming.msgId] = nextSeq
            applied++
        }

        parsed.kv.forEach { incoming ->
            val existing = kv[incoming.key]
            // LWW：只有"严格更早"的那份被拒绝（与真 Worker 一致）。
            if (existing != null && incoming.updatedAt < existing.updatedAt) return@forEach
            rev++
            kv[incoming.key] = incoming
            kvRev[incoming.key] = rev
            applied++
        }

        parsed.deletedMessageIds.forEach { id ->
            messages.removeAll { it.msgId == id }
            messageRev.remove(id)
            rev++
            // 与真 Worker 一致：墓碑总是写下来，重复删除也是一条变更。
            tombstones[id] = rev
            applied++
        }

        val messagePage = messages.filter { (messageRev[it.msgId] ?: 0L) > parsed.sinceRev }
        val kvPage = kv.values.filter { (kvRev[it.key] ?: 0L) > parsed.sinceRev }
        // 与真 Worker 一致：墓碑全量请求把全部墓碑重新说一遍，不推进游标。
        val deletedPage = if (parsed.tombstonesOnly) {
            tombstones.toMap()
        } else {
            tombstones.filterValues { it > parsed.sinceRev }
        }
        val headRev = rev
        val coveredRev = if (parsed.tombstonesOnly) parsed.sinceRev else maxOf(
            parsed.sinceRev,
            messagePage.maxOfOrNull { messageRev[it.msgId] ?: 0L } ?: 0L,
            kvPage.maxOfOrNull { kvRev[it.key] ?: 0L } ?: 0L,
            deletedPage.values.maxOrNull() ?: 0L
        )

        return buildJsonObject {
            put("proto", SYNC_PROTO)
            put("rev", headRev)
            put("applied", applied)
            put("rejected", buildJsonArray { rejected.forEach { add(it) } })
            put("assigned", buildJsonObject { assigned.forEach { (id, seq) -> put(id, seq) } })
            put(
                "pull",
                buildJsonObject {
                    put("sinceRev", parsed.sinceRev)
                    put("rev", coveredRev)
                    put("headRev", headRev)
                    put("hasMore", !parsed.tombstonesOnly && messagePage.size > parsed.pullLimit)
                    put("messages", buildJsonArray { messagePage.take(parsed.pullLimit).forEach { add(it.toJson()) } })
                    put(
                        "kv",
                        buildJsonArray {
                            kvPage.take(parsed.pullLimit).forEach { item ->
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
                    put(
                        "deleted",
                        buildJsonArray {
                            deletedPage.forEach { (msgId, deleteRev) ->
                                add(buildJsonObject { put("msgId", msgId); put("rev", deleteRev) })
                            }
                        }
                    )
                    put(
                        "images",
                        buildJsonArray {
                            images.forEach { (id, bytes) ->
                                add(buildJsonObject { put("imageId", id); put("bytes", bytes) })
                            }
                        }
                    )
                }
            )
        }
    }

    private fun parseRequest(json: JsonObject): SyncRequest = SyncRequest(
        deviceId = json.stringOrEmpty("deviceId"),
        sinceRev = json.longOrZero("sinceRev"),
        messages = json.arrayOrNull("messages").toSyncMessages(),
        kv = json.arrayOrNull("kv").toSyncKvItems(),
        deletedMessageIds = json.arrayOrNull("deleted").orEmptyList()
            .mapNotNull { (it as? JsonObject)?.stringOrEmpty("msgId") },
        pullLimit = json.intOrZero("pullLimit").takeIf { it > 0 } ?: SYNC_PULL_LIMIT,
        tombstonesOnly = json.booleanOr("tombstonesOnly", false)
    )

    private fun reject(msgId: String, reason: String): JsonObject = buildJsonObject {
        put("msgId", msgId)
        put("reason", reason)
    }
}
