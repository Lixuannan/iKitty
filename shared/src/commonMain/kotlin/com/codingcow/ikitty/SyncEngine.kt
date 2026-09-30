package com.codingcow.ikitty

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 云端同步的编排：推本地的新消息与设置、拉云端的增量、按云端快照重建本地日志、补齐缺的图片。
 *
 * 冲突模型是**云端权威**（见 `docs/SYNC_DESIGN.md`）：本地日志不是"另一个副本"，
 * 而是"云端快照 + 尚未推送的本地消息"。所以这里没有任何合并算法——拉取就是整体替换，
 * 替换内容是"云端返回的消息 + 本地还没推上去的那些"。
 *
 * 与 [ChatEngine] 的分工：本类不碰界面状态、不决定"什么时候同步"。它只有两个入口
 * （[sync] 与 [deleteAll]），失败一律抛 [SyncException]；退避与提示由调用方处理。
 *
 * 三条不变量：
 * 1. 任何失败都**不删除**本地数据——只有一次完整的、成功的拉取才会替换日志；
 * 2. 本地写入永远先落盘，再谈上传；
 * 3. 同一个 [SyncEngine] 上的同步不会并发执行（[syncLock]），否则两次替换会互相覆盖。
 */
class SyncEngine(
    /**
     * 当前的 API 客户端。
     *
     * 做成"每次取一次"而不是构造期固定：同步服务的地址是用户可以在设置页填写的部署细节，
     * 填完之后应当**立刻**生效。一个 [SyncApi] 只是一层薄封装（地址 + 传输），
     * 每轮重建没有任何实际成本。
     */
    private val apiProvider: suspend () -> SyncApi,
    private val credentials: SyncCredentialStore,
    private val log: ChatLogStore,
    private val images: SyncImageOps,
    /**
     * 把当前设置编码成线上形态，并给出这一份的指纹。
     *
     * 两件事一起做是刻意的：分别取值的话，"编码用的设置"和"算指纹用的设置"可能是
     * 两个瞬间的快照，于是指纹与实际推上去的内容对不上，会出现"推了却没记住"或
     * "没变却反复推"这类只在并发下才出现的错。
     */
    private val encodeSettings: suspend (includeApiKey: Boolean, deviceId: String) -> EncodedSettings,
    /** 本机当前的 API Key；解码云端设置时用来兜住"云端那份没带 Key"。 */
    private val currentApiKey: suspend () -> String,
    /** 应用云端那份设置。[hasApiKey] 为 false 时实现方应保留本机已有的 Key。 */
    private val applySettings: suspend (settings: BackupSettings?, hasApiKey: Boolean) -> Unit,
    /**
     * 把老的随机图片名换成内容哈希名（首次同步前跑一次，幂等）。
     *
     * 做成注入的钩子而不是直接调 [migrateLegacyImageNames]：那需要图片目录与文件系统，
     * 而这两样已经由平台层持有，引擎不该再要一份。返回的警告会被转发到 [onWarning]。
     */
    private val migrateImages: suspend () -> List<String> = { emptyList() },
    /** 图片上传失败等非致命问题的记录处。 */
    private val onWarning: (String) -> Unit = {},
    private val now: () -> Long = { 0L },
    private val ioDispatcher: CoroutineDispatcher? = null
) {

    /** 同步是否可用：至少要配置了账号密钥。 */
    suspend fun isConfigured(): Boolean = credentials.accountKey().isNotBlank()

    /**
     * 跑一次完整同步。
     *
     * 顺序是 push → pull，而不是反过来：先把自己的新消息交上去，紧接着的 pull 就顺带
     * 拿到权威序号，一次往返就能让本地进入一致状态。反过来的话刚发的消息要等下一轮
     * 才会被服务端看到。
     */
    suspend fun sync(): SyncReport {
        syncLock.withLock {
            val accountKey = credentials.accountKey()
            if (accountKey.isBlank()) throw SyncException("还没有配置账号密钥")

            return inIo {
                // "这份数据要不要带 API Key"必须在**每一次同步开始时取一次**，然后整轮沿用。
                //
                // 让 `push` 自己分两次去读会话凭据（一次决定"要不要推"、一次决定"推什么"）
                // 会造成这样的空档：用户打开开关的那一刻，指纹是按"带 Key"算出来的，
                // 而真正编码时开关还是旧值——于是这一轮什么都没推，指纹却已经更新，
                // **Key 再也不会被上传**。
                val includeApiKey = credentials.includeApiKey()

                // 老记录缺消息身份时必须先固化，否则同一条记录每读一次就换一个 id，
                // 会被反复当成新消息推上去。
                if (log.migrateMissingIds()) {
                    onWarning("已为老记录补上消息身份，下次同步会上传它们")
                }
                // 老图片名换成内容哈希：云端是按哈希去重的，用随机名上传会让同一张图存很多份。
                migrateImages().forEach(onWarning)

                val before = credentials.sinceRev()
                val pushed = push(accountKey, includeApiKey)
                // 没有东西要推时，顺带要一次全量墓碑。
                //
                // 增量拉取看不到"很久以前被别的设备删掉的那条"（它的 rev 早已落在游标后面），
                // 所以只有在"这一轮没有消息要推"时才补这一次额外的询问——那是同步的主路径
                // （前台化、定时触发），而当用户刚发了消息时，多一次往返没必要。
                val pulled = pull(
                    accountKey = accountKey,
                    includeApiKey = includeApiKey,
                    tombstonesOnly = pushed.messagesPushed == 0 && before > 0
                )
                SyncReport(
                    messagesPushed = pushed.messagesPushed,
                    kvPushed = pushed.kvPushed,
                    pulled = pulled.pulledMessages,
                    rejected = pushed.rejected,
                    settingsApplied = pulled.settingsApplied,
                    imagesDownloaded = pulled.imagesDownloaded
                )
            }
        }
    }

    /** 清空云端。调用方必须先取得用户确认：没有账号找回，也没有回收站。 */
    suspend fun deleteAll() {
        syncLock.withLock {
            val accountKey = credentials.accountKey()
            if (accountKey.isBlank()) throw SyncException("还没有配置账号密钥")
            inIo {
                apiProvider().deleteAll(accountKey)
                // 本地撤回所有同步状态：云端已经空了，下一轮同步就是一次全新的首次同步。
                // 本地数据本身不动——"清空云端"不是"清空本机"。
                credentials.clearSyncState()
            }
        }
    }

    suspend fun setAccountKey(key: String) {
        credentials.setAccountKey(key)
        // 换 key 等于换云空间：游标与"推过什么""删过什么"的记忆必须作废，
        // 否则会拿旧游标和新账号的删除记忆去处理一个完全不同的云空间。
        credentials.clearSyncState()
    }

    suspend fun setIncludeApiKey(include: Boolean) = credentials.setIncludeApiKey(include)

    suspend fun includeApiKey(): Boolean = credentials.includeApiKey()

    suspend fun clearAccountKey() = credentials.clearAccountKey()

    // ---- push ----

    /**
     * 一次 push 实际送出去的东西。
     *
     * 分开记消息条数与 kv 条数，而不是只留服务端的 `applied`：`applied` 把"设置写了一次"
     * 和"消息推了十条"混成一个数字，界面就没法说清"到底同步了什么"。
     */
    private class PushOutcome(
        val messagesPushed: Int,
        val kvPushed: Int,
        val rejected: List<String>
    )

    private suspend fun push(accountKey: String, includeApiKey: Boolean): PushOutcome {
        val deviceId = credentials.deviceId()
        val local = log.all()

        val deletedIds = credentials.deletedMessageIds()
        // 待推 = 本地有、还没推成功过、不是"服务端明确说过已删除"的，也不是本地错误提示。
        //
        // 三条排除各自的理由：
        // - 推过的不用再推（服务端按 msg_id 幂等，但没必要浪费一趟）；
        // - 被删的不许再推，否则删掉的消息会复活；
        // - `localError` 是本地生成的报错提示（"呜……连接 API 出问题了"），它只对产生它的
        //   那台设备有意义，同步到别的设备只会让那边看到一条莫名其妙的错误。
        val pending = local.filter {
            it.msgId !in credentials.pushedMessageIds() &&
                it.msgId !in deletedIds &&
                !it.localError
        }

        // 设置只有在真的变了的时候才推：否则每轮同步都写一次 D1，
        // 还会把 cloudSettingsAt 抬高，让另一台设备的更新更难落地。
        val encoded = encodeSettings(includeApiKey, deviceId)
        val settingsChanged = encoded.fingerprint != credentials.settingsFingerprint()
        val kv = if (settingsChanged) {
            listOf(
                SyncKvItem(
                    key = SyncSettingsCodec.KV_KEY,
                    payload = encoded.payload,
                    updatedAt = settingsTimestamp()
                )
            )
        } else {
            emptyList()
        }

        // 每轮最多推一个批次；没推完的留给下一次触发（前台化、下一条消息、手动点）。
        // 不在这里循环推完：那会让一次同步的耗时不可预测，也让"取消"变得难以响应。
        // 可变列表：服务端回 413 时 [sendWithRetry] 会把它对半砍到能过为止，
        // 所以推完之后这里看到的是**实际发出去**的那一批。
        val batch = pending.take(SYNC_MAX_BATCH)
        if (batch.isEmpty() && kv.isEmpty()) return PushOutcome(0, 0, emptyList())

        val api = apiProvider()
        val push = sendWithRetry(accountKey, batch) { attemptBatch ->
            api.sync(
                accountKey,
                SyncRequest(
                    deviceId = deviceId,
                    sinceRev = credentials.sinceRev(),
                    messages = attemptBatch.map { it.toSyncMessage() },
                    kv = kv
                )
            )
        }
        val response = push.response
        // 只把**服务端真的收下**的那些记为已推：413 拆批之后，剩下的那些必须留到下一轮，
        // 否则它们既没进云端、又再也不会被推（"已推"集合是待推判定的唯一依据）。
        val accepted = push.acceptedMessages

        // 推成功就立刻记下来，不等 pull：一条"推成功但云端没回传"的消息（例如它的 msg
        // 在这期间被别的设备删了）如果不记，会在每次同步里被反复推送。
        credentials.addPushedMessageIds(accepted.map { it.msgId })
        if (kv.isNotEmpty()) {
            credentials.setSettingsFingerprint(encoded.fingerprint)
            credentials.setSettingsUpdatedAt(kv.first().updatedAt)
        }
        return PushOutcome(accepted.size, kv.size, response.rejected)
    }

    /**
     * 这一份设置的时间戳：**严格大于**上次推上去的那个。
     *
     * 为什么不直接用 `now()`：
     * - 用户把系统时间调回去再改设置时，新改动不能输给旧时间戳；
     * - 同一个时间戳连续推两次时，服务端（按"严格更早才拒绝"比较）会接受第二次覆盖，
     *   但客户端自己的 `cloudSettingsAt` 判据也会跟着抖动。让本地时间戳严格单调，
     *   "我推的这一份"在两侧都稳定可辨。
     */
    private suspend fun settingsTimestamp(): Long =
        maxOf(credentials.settingsUpdatedAt() + 1, now())

    // ---- pull ----

    private class PullOutcome(
        val pulledMessages: Int,
        val settingsApplied: Boolean,
        val imagesDownloaded: Int
    )

    private suspend fun pull(
        accountKey: String,
        includeApiKey: Boolean,
        tombstonesOnly: Boolean
    ): PullOutcome {
        val sinceRev = credentials.sinceRev()
        val api = apiProvider()
        // 拉取没有"批"可拆：它只有一个页大小，服务端也不会因为页大小回 413，
        // 所以走同一条重试路径，只是忽略"接受清单"。
        val response = sendWithRetry(accountKey, emptyList()) { _ ->
            api.sync(
                accountKey,
                SyncRequest(
                    deviceId = credentials.deviceId(),
                    sinceRev = credentials.sinceRev(),
                    pullLimit = SYNC_PULL_LIMIT,
                    tombstonesOnly = tombstonesOnly
                )
            )
        }.response

        val pageMessages = response.pull.messages
        credentials.setSinceRev(response.pull.rev)

        // 服务端这一页返回的消息一定在云端，记下来免得之后重复推。
        val pageIds = pageMessages.map { it.msgId }
        if (pageIds.isNotEmpty()) credentials.addPushedMessageIds(pageIds)

        // 服务端明确说了哪些被删了：记下来，本机也跟着删，并且以后不再推回去。
        val tombstones = response.pull.deletedMessageIds
        if (tombstones.isNotEmpty()) credentials.addDeletedMessageIds(tombstones)

        val deletedIds = credentials.deletedMessageIds()
        val local = log.all().filter { it.msgId !in deletedIds }

        // 整体替换：这一页的云端快照（权威序号）+ 本机剩下的记录。
        //
        // "本机剩下的"必须与"待推的"区分开：前者包括那些已经在云端、只是没出现在这一页
        // （更早的页或本轮没有变化的）消息——如果只把"待推的"留下来，一次增量拉取就会把
        // 本机历史上所有未被这一页覆盖的消息全删掉。
        //
        // 去重时以服务端那一份为准（`distinctBy` 保留先出现的），它的 seq 是权威值。
        val survivors = local.filter { it.msgId !in pageIds }
        val merged = (pageMessages.map { it.toStored() } + survivors)
            .distinctBy { it.msgId }
            .sortedWith(MESSAGE_ORDER)
        log.replaceAll(merged)

        val settingsApplied = applyPulledSettings(response.pull.kv, includeApiKey)
        val downloaded = syncImages(accountKey, response.pull.images)

        return PullOutcome(
            pulledMessages = pageMessages.size,
            settingsApplied = settingsApplied,
            imagesDownloaded = downloaded
        )
    }

    /**
     * 应用云端的设置。
     *
     * 只接受比"云端的已知版本"更新的那一份，并且不重复应用自己刚推上去的：
     * 判据是 `updatedAt > cloudSettingsAt`，而自己推的那一份在 push 阶段已经记过时间戳。
     */
    private suspend fun applyPulledSettings(kv: List<SyncKvItem>, includeApiKey: Boolean): Boolean {
        val item = kv.firstOrNull { it.key == SyncSettingsCodec.KV_KEY } ?: return false
        if (item.updatedAt <= credentials.cloudSettingsAt()) return false

        // 解码需要"本机已有的 Key"来兜住"云端那份没带 api_key"的情况。
        val existing = currentApiKey()
        val settings = SyncSettingsCodec.decode(item.payload, existing)
        if (settings == null) {
            onWarning("云端那份设置无法解析，已跳过")
            return false
        }

        applySettings(settings, SyncSettingsCodec.containsApiKey(item.payload))
        credentials.setCloudSettingsAt(item.updatedAt)
        // 应用之后本机设置已经变了，指纹也要跟着更新，否则下一轮会把自己刚应用的那份又推回去。
        credentials.setSettingsFingerprint(encodeSettings(includeApiKey, credentials.deviceId()).fingerprint)
        return true
    }

    // ---- 图片 ----

    /**
     * 把云端有、本地没有的图片补下来。
     *
     * 单张失败只记一条警告就继续：一张图没下来不该让整次同步失败，
     * 下次同步还会再试（清单是全量的，缺谁下次就知道）。
     */
    private suspend fun syncImages(accountKey: String, manifest: List<SyncRemoteImage>): Int {
        val missing = manifest.filter { !images.exists(it.imageId) }
        if (missing.isEmpty()) return 0

        val api = apiProvider()
        var downloaded = 0
        // 并发 3：够了（一张 200 KB），又不会把内存和移动网络同时压满。
        missing.chunked(IMAGE_CONCURRENCY).forEach { group ->
            ensureActive()
            val results = coroutineScope {
                group.map { image ->
                    async {
                        try {
                            val bytes = api.downloadImage(accountKey, image.imageId)
                            if (images.write(image.imageId, bytes)) 1 else 0
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onWarning("图片 ${image.imageId} 下载失败：${e.message ?: "未知错误"}")
                            0
                        }
                    }
                }.awaitAll()
            }
            downloaded += results.sum()
        }
        return downloaded
    }

    // ---- 重试 ----

    /**
     * 发送一次请求，并对两类**可自动处理**的失败做处理：
     * - 413（批太大）：把 [batch] 对半砍掉一半再发，直到通过或只剩一条；
     * - 429 / 5xx / 网络错误：指数退避 + 抖动后重试。
     *
     * 401 与 400 直接抛出：那是"需要用户介入"或"请求本身有问题"，重试没有意义。
     *
     * [batch] 会被就地裁剪。拆批必须真的把批变小：只重试同一份请求体的话，
     * 服务端会一直回 413，重试次数耗尽之后用户看到的是"同步失败"，
     * 而真正的原因（这一批太大）被埋掉了。
     */
    /**
     * 一条请求对应的"这一次到底送出去了什么"。
     *
     * [acceptedMessages] 是**所有成功的子批**合起来的结果：413 拆批之后，早先那半批
     * 已经在服务端了，如果只回报最后一次的内容，那半批就会被永久当成"待推"而反复上传。
     */
    private class PushAttempt(
        val response: SyncResponse,
        val acceptedMessages: List<StoredMessage>
    )

    /**
     * 发送一次请求，并对两类**可自动处理**的失败做处理：
     * - 413（批太大）：把批对半砍，两半各自重试（不占用退避次数，服务端已经明说了该怎么做）；
     * - 429 / 5xx / 网络错误：指数退避 + 抖动后重试。
     *
     * 401 与 400 直接抛出：那是"需要用户介入"或"请求本身有问题"，重试没有意义。
     *
     * 拆批必须真的把批变小：只重试同一份请求体的话，服务端会一直回 413，
     * 重试次数耗尽之后用户看到的是"同步失败"，而真正的原因（这一批太大）被埋掉了。
     */
    private suspend fun sendWithRetry(
        accountKey: String,
        startingBatch: List<StoredMessage>,
        attempt: suspend (batch: List<StoredMessage>) -> SyncResponse
    ): PushAttempt {
        // 拆批之后每半批仍然保留完整的退避重试能力，所以两件事是分开的：
        // 外层负责"批变小"，内层负责"同一批重试"。
        suspend fun sendBatch(batch: List<StoredMessage>): PushAttempt {
            var lastError: SyncException? = null
            for (round in 0 until MAX_ATTEMPTS) {
                try {
                    return PushAttempt(attempt(batch), batch)
                } catch (e: SyncException) {
                    if (e.reauthorize) {
                        credentials.clearAccountKey()
                        throw e
                    }
                    if (e.batchTooLarge) {
                        if (batch.size <= 1) {
                            // 一条都过不去：那不是批大小的问题，而是这条消息本身太大。
                            throw SyncException("有一条消息太大，服务端拒收了；它仍保留在本机")
                        }
                        val firstHalf = batch.subList(0, batch.size / 2).toList()
                        val secondHalf = batch.subList(batch.size / 2, batch.size).toList()
                        val first = sendBatch(firstHalf)
                        val second = sendBatch(secondHalf)
                        return PushAttempt(second.response, first.acceptedMessages + second.acceptedMessages)
                    }
                    lastError = e
                    val backoff = e.retryAfterSeconds?.times(1000L) ?: (BASE_BACKOFF_MILLIS shl round)
                    if (round == MAX_ATTEMPTS - 1) break
                    delay(jitter(backoff))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = SyncException("同步失败：${e.message ?: "未知错误"}")
                    if (round == MAX_ATTEMPTS - 1) break
                    delay(jitter(BASE_BACKOFF_MILLIS shl round))
                }
            }
            throw lastError ?: SyncException("同步失败")
        }
        return sendBatch(startingBatch)
    }

    /**
     * 加抖动，避免多台设备在同一时刻失败后同步重试。
     *
     * 用时间做种子而不是随机数生成器：抖动只需要"不整齐"，不需要密码学强度，
     * 少一个要注入的依赖。
     */
    private fun jitter(base: Long): Long {
        val spread = base / 4
        if (spread <= 0L) return base
        return base - spread + (now() % (spread * 2 + 1))
    }

    private suspend fun ensureActive() {
        if (!coroutineContext.isActive) throw CancellationException("同步已取消")
    }

    private suspend fun <T> inIo(block: suspend () -> T): T =
        if (ioDispatcher == null) block() else withContext(ioDispatcher) { block() }

    private val syncLock = Mutex()

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_BACKOFF_MILLIS = 1_000L

        /** 图片下载并发度。 */
        const val IMAGE_CONCURRENCY = 3

        /** 与服务端权威序号一致的排序：seq 是全局单调的，时间与 id 只用于兜底。 */
        val MESSAGE_ORDER = compareBy<StoredMessage>({ it.seq }, { it.createdAt }, { it.msgId })
    }
}

/**
 * 图片的读写能力。
 *
 * 独立于 `ImageStore`（那是平台类）：同步只需要"在不在、读出来、写进去"三件事，
 * 而上传前的归一化、界面缩略图、编码缓存都与同步无关。
 */
interface SyncImageOps {
    fun exists(imageId: String): Boolean

    suspend fun read(imageId: String): ByteArray?

    /** 写入一张从云端下来的图片；返回是否写成功。 */
    suspend fun write(imageId: String, bytes: ByteArray): Boolean
}

/**
 * 一份要推上去的设置：线上形态的 payload + 这一份的指纹。
 *
 * 两者一起产出，调用方就无法"用 A 的内容配 B 的指纹"。
 */
class EncodedSettings(val payload: String, val fingerprint: String)

/** 一次同步做了些什么，用于给用户一句可读的交代。 */
data class SyncReport(
    /** 本次推上去的消息条数。 */
    val messagesPushed: Int,
    /** 本次推上去的 kv 条数（只有设置变了才是 1）。 */
    val kvPushed: Int,
    /** 本次拉下来的消息条数。 */
    val pulled: Int,
    val rejected: List<String>,
    val settingsApplied: Boolean,
    val imagesDownloaded: Int
) {
    val changedSomething: Boolean
        get() = messagesPushed > 0 || kvPushed > 0 || pulled > 0 || settingsApplied || imagesDownloaded > 0

    /** 给界面用的一句话；没什么可说的就返回 null，由调用方决定不显示。 */
    fun summary(): String? {
        if (!changedSomething && rejected.isEmpty()) return null
        val parts = buildList {
            if (messagesPushed > 0) add("上传 $messagesPushed 条")
            if (pulled > 0) add("下载 $pulled 条")
            if (imagesDownloaded > 0) add("图片 $imagesDownloaded 张")
            if (settingsApplied) add("设置已更新")
            if (rejected.isNotEmpty()) add("${rejected.size} 条被拒绝")
        }
        return parts.joinToString("、")
    }
}

private fun StoredMessage.toSyncMessage(): SyncMessage = SyncMessage(
    msgId = msgId,
    seq = seq,
    role = role,
    content = content,
    createdAt = createdAt,
    localError = localError,
    images = images
)

private fun SyncMessage.toStored(): StoredMessage = StoredMessage(
    seq = seq,
    role = role,
    content = content,
    createdAt = createdAt,
    localError = localError,
    images = images,
    msgId = msgId
)
