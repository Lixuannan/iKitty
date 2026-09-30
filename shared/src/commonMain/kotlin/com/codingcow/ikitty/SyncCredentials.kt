package com.codingcow.ikitty

import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * 同步凭据与游标。
 *
 * 与 [SettingsRepository] 分开：这些值不是"用户设置"，而是**一台设备与一个云空间的关系**，
 * 生命周期也不同（换账号要整体重置，设置不需要）。同一个 [KeyValueStore] 契约，
 * 但平台各自用一个独立的存储文件/命名空间，避免同步状态混进 `SettingsKeys` 那批跨端配置里。
 */
interface SyncCredentialStore {
    /** 账号密钥（Bearer token）。空串表示还没配置，此时同步整体关闭。 */
    suspend fun accountKey(): String

    suspend fun setAccountKey(key: String)

    /**
     * 同步服务的根地址（部署好的 Worker）。
     *
     * 与账号密钥一起存：两者共同构成"连到哪个云空间"，换掉任何一个都应该重置同步状态。
     * 分开存是为了让界面能提示"地址填了但密钥还没填"这种中间状态。
     */
    suspend fun serviceUrl(): String

    suspend fun setServiceUrl(url: String)

    /** 本机安装 id：只为观测与日志，不参与协议语义。 */
    suspend fun deviceId(): String

    /** 已经完整拉到的 rev。下一轮请求用它当 `sinceRev`。 */
    suspend fun sinceRev(): Long

    suspend fun setSinceRev(rev: Long)

    /** 上一次把设置推上去的时间戳，LWW 判据。 */
    suspend fun settingsUpdatedAt(): Long

    suspend fun setSettingsUpdatedAt(at: Long)

    /**
     * 已经成功推到云端的消息 id（快照）。
     *
     * 用"推成功的 id 集合"而不是"文件里前 N 条"来判断待推项：前者与实际发生的事**一一对应**，
     * 后者依赖"日志里恰好前 N 条是云端来的"这个隐含不变量，只要有重复行或坏行就会错位，
     * 而错位的后果是把已经推过的消息反复上传。
     */
    suspend fun pushedMessageIds(): Set<String>

    /**
     * 追加一批刚刚推送成功的 id。
     *
     * 由 [SyncEngine] 在 push 成功之后**立刻**调用，不等 pull：否则一条"推成功但云端
     * 不返回"的消息（例如它的 msg 后来被删掉）会被认为还没推，导致每次同步都在推它。
     */
    suspend fun addPushedMessageIds(ids: Collection<String>)

    /**
     * 服务端明确告知已删除的消息 id。
     *
     * 必须记住"服务端删过什么"，否则本地还留着那条消息时，每次同步都会把它当成待推项又推回去。
     * 只用 [pushedMessageIds] 是不够的：那条消息推成功过、在集合里，但它已经不在云端了，
     * 于是"没在云端"与"还没推"会混成一件事。
     */
    suspend fun deletedMessageIds(): Set<String>

    suspend fun addDeletedMessageIds(ids: Collection<String>)

    /**
     * 上一次成功推上去的设置指纹。
     *
     * 用"指纹"而不是"是否要推"的标志位：标志位需要在**每次保存设置时**被置上，
     * 而保存设置的路径有好几条（设置页、备份导入、同步应用云端设置），漏一条就会
     * 永远不再上传设置。比较指纹不需要任何一处记得"标记脏了"。
     */
    suspend fun settingsFingerprint(): String

    suspend fun setSettingsFingerprint(fingerprint: String)

    /**
     * 云端那一份设置的 `updatedAt`。
     *
     * 与本地自己的推送时间戳分开：否则"我刚推上去"和"云端有一份更新的"会被混成同一件事，
     * 结果是每次同步都把自己刚推的那份再应用一遍。
     */
    suspend fun cloudSettingsAt(): Long

    suspend fun setCloudSettingsAt(at: Long)

    /** 是否把 API Key 一并同步到云端。默认关闭。 */
    suspend fun includeApiKey(): Boolean

    suspend fun setIncludeApiKey(include: Boolean)

    /**
     * 作废与"当前这个云空间"绑定的全部状态：游标、推过什么、删过什么、设置指纹。
     *
     * 换账号密钥或清空云端之后必须调用：留着旧状态会让新账号上的同步从一开始就是错的
     * （用旧游标去拉、把新账号的消息当成"已删"）。
     * **不动** [accountKey] 与 [deviceId]：前者由调用方自己设置，后者是这台安装的身份。
     */
    suspend fun clearSyncState()

    /**
     * 云端返回了 401：说明密钥已失效。
     *
     * 只清密钥，**不清 sinceRev 与设置时间戳**：用户很可能是填错了 key，
     * 重填之后不该把本地状态当成全新的第一次同步（那会重复推一遍全部消息——
     * 服务端幂等能兜住，但没必要）。
     */
    suspend fun clearAccountKey()
}

/**
 * 账号密钥的长度下限。
 *
 * 与服务端 `worker/src/index.js` 的 `MIN_KEY_LENGTH` 必须一致：不一致的后果是
 * 客户端接受了、服务端回一个看不懂的 401，用户只会以为"同步坏了"。
 *
 * 这个值很低是刻意的（好记的口令也是合法选择），但它**不提供安全**：
 * `account_id` 是 key 的哈希、可离线枚举，短密钥等于把云端数据公开给愿意扫一遍的人。
 * 界面因此会在密钥偏短时给出警告，见 `SyncKeyStrength`。
 */
const val MIN_ACCOUNT_KEY_LENGTH = 5

/**
 * 这个长度的密钥会被界面提醒"太短"。
 *
 * 16 位随机串是"扫不动"的起点（约 95 bit）；低于它的密钥靠的是别人没想到去扫，
 * 而不是靠强度。
 */
const val RECOMMENDED_ACCOUNT_KEY_LENGTH = 16

/** 账号密钥的强度档位，只用于界面提示。 */
enum class SyncKeyStrength {
    /** 空串：还没填。 */
    EMPTY,

    /** 太短：能被离线枚举，云端数据实际上不设防。 */
    TOO_SHORT,

    /** 够长但不建议：仍然是用户自己想出来的串。 */
    WEAK,

    /** 建议的强度。 */
    STRONG;

    companion object {
        fun of(key: String): SyncKeyStrength = when {
            key.isBlank() -> EMPTY
            key.length < MIN_ACCOUNT_KEY_LENGTH -> TOO_SHORT
            key.length < RECOMMENDED_ACCOUNT_KEY_LENGTH -> WEAK
            else -> STRONG
        }
    }
}

/** [SyncCredentialStore] 的键名。改了等于让老用户重新配对一次。 */
object SyncKeys {
    const val ACCOUNT_KEY = "sync_account_key"
    const val SERVICE_URL = "sync_service_url"
    const val DEVICE_ID = "sync_device_id"
    const val SINCE_REV = "sync_since_rev"
    const val SETTINGS_UPDATED_AT = "sync_settings_updated_at"
    const val INCLUDE_API_KEY = "sync_include_api_key"
    const val PUSHED_IDS = "sync_pushed_ids"
    const val DELETED_IDS = "sync_deleted_ids"
    const val SETTINGS_FINGERPRINT = "sync_settings_fingerprint"
    const val CLOUD_SETTINGS_AT = "sync_cloud_settings_at"
}

/**
 * 用任意 [KeyValueStore] 实现的凭据存储。
 *
 * 不自己造一套"按字符串读写"的存储接口：`SettingValue` 这个封闭类型已经把漏处理的取值
 * 变成编译错误，多一套平行接口只会多一处要维护的映射。
 *
 * 缺省值集中在 [Defaults]：[sinceRev] 缺省为 0 表示"从零开始拉"，语义正确，
 * 不需要在读取时区分"没存过"和"存过 0"。
 */
class KeyValueSyncCredentialStore(
    private val store: KeyValueStore,
    /** 首次创建时生成的安装 id；注入是为了能在测试里固定它。 */
    private val newDeviceId: () -> String = { newMessageId() }
) : SyncCredentialStore {

    private suspend fun raw(key: String): String =
        (store.values.first()[key] as? SettingValue.Str)?.value.orEmpty()

    private suspend fun putString(key: String, value: String) {
        store.put(mapOf(key to SettingValue.Str(value)))
    }

    override suspend fun accountKey(): String = raw(SyncKeys.ACCOUNT_KEY).trim()

    override suspend fun setAccountKey(key: String) = putString(SyncKeys.ACCOUNT_KEY, key.trim())

    override suspend fun serviceUrl(): String = raw(SyncKeys.SERVICE_URL).trim().trimEnd('/')

    override suspend fun setServiceUrl(url: String) {
        putString(SyncKeys.SERVICE_URL, url.trim().trimEnd('/'))
    }

    override suspend fun clearAccountKey() = putString(SyncKeys.ACCOUNT_KEY, "")

    /**
     * 安装 id 在首次读取时生成并落盘。
     *
     * 生成后必须**立刻写回**：如果只存在内存里，重启后会变成新设备，
     * 服务端侧看到的来源会漂移。写入用 `first()` 读一次再写，多个调用者并发时
     * 最多产生两个 id，最后一个胜出——这个代价可以接受，不值得为它加锁。
     */
    override suspend fun deviceId(): String {
        val existing = raw(SyncKeys.DEVICE_ID)
        if (existing.isNotBlank()) return existing
        val created = newDeviceId()
        putString(SyncKeys.DEVICE_ID, created)
        return created
    }

    override suspend fun sinceRev(): Long = raw(SyncKeys.SINCE_REV).toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    override suspend fun setSinceRev(rev: Long) {
        putString(SyncKeys.SINCE_REV, clampRev(rev).toString())
    }

    override suspend fun settingsUpdatedAt(): Long = raw(SyncKeys.SETTINGS_UPDATED_AT).toLongOrNull() ?: 0L

    override suspend fun setSettingsUpdatedAt(at: Long) {
        putString(SyncKeys.SETTINGS_UPDATED_AT, at.coerceAtLeast(0L).toString())
    }

    override suspend fun pushedMessageIds(): Set<String> = decodeIds(raw(SyncKeys.PUSHED_IDS))

    override suspend fun addPushedMessageIds(ids: Collection<String>) {
        val incoming = ids.filter { it.isNotBlank() }
        if (incoming.isEmpty()) return
        val merged = LinkedHashSet(pushedMessageIds())
        merged.addAll(incoming)
        putString(SyncKeys.PUSHED_IDS, encodeIds(merged.toList(), MAX_PUSHED_IDS))
    }

    override suspend fun deletedMessageIds(): Set<String> = decodeIds(raw(SyncKeys.DELETED_IDS))

    override suspend fun addDeletedMessageIds(ids: Collection<String>) {
        val incoming = ids.filter { it.isNotBlank() }
        if (incoming.isEmpty()) return
        val merged = LinkedHashSet(deletedMessageIds())
        merged.addAll(incoming)
        putString(SyncKeys.DELETED_IDS, encodeIds(merged.toList(), MAX_DELETED_IDS))
    }

    /**
     * **宽容**地读那个开关。
     *
     * 两种表示都要认：Android 的同步命名空间把所有值都按字面量存成字符串，
     * 而 iOS 的 `NSUserDefaults` 支持布尔，存下去再读回来就是 `Flag`。
     * 只认其中一种的后果是另一端的开关永远读成 false——"打开同步 API Key"看起来生效了，
     * 下一次同步却仍然不带 Key。
     */
    override suspend fun includeApiKey(): Boolean = when (val value = store.values.first()[SyncKeys.INCLUDE_API_KEY]) {
        is SettingValue.Flag -> value.value
        is SettingValue.Str -> value.value == "true"
        else -> false
    }

    override suspend fun setIncludeApiKey(include: Boolean) {
        putString(SyncKeys.INCLUDE_API_KEY, include.toString())
    }

    override suspend fun settingsFingerprint(): String = raw(SyncKeys.SETTINGS_FINGERPRINT)

    override suspend fun setSettingsFingerprint(fingerprint: String) {
        putString(SyncKeys.SETTINGS_FINGERPRINT, fingerprint)
    }

    override suspend fun cloudSettingsAt(): Long = raw(SyncKeys.CLOUD_SETTINGS_AT).toLongOrNull() ?: 0L

    override suspend fun setCloudSettingsAt(at: Long) {
        putString(SyncKeys.CLOUD_SETTINGS_AT, at.coerceAtLeast(0L).toString())
    }

    override suspend fun clearSyncState() {
        store.put(
            mapOf(
                SyncKeys.SINCE_REV to SettingValue.Str("0"),
                SyncKeys.SETTINGS_UPDATED_AT to SettingValue.Str("0"),
                SyncKeys.CLOUD_SETTINGS_AT to SettingValue.Str("0"),
                SyncKeys.SETTINGS_FINGERPRINT to SettingValue.Str(""),
                SyncKeys.PUSHED_IDS to SettingValue.Str(""),
                SyncKeys.DELETED_IDS to SettingValue.Str("")
            )
        )
    }

    /**
     * 编码成 JSON 数组。
     *
     * 超出 [limit] 时**丢掉最老的**：代价是那些老消息可能被再推一次，
     * 而服务端按 msg_id 的幂等会兜住它，不会产生重复消息。
     */
    private fun encodeIds(ids: List<String>, limit: Int): String {
        val trimmed = if (ids.size > limit) ids.takeLast(limit) else ids
        return buildJsonArray { trimmed.forEach { add(JsonPrimitive(it)) } }.toString()
    }

    private fun decodeIds(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        val array = try {
            Json.parseToJsonElement(raw) as? JsonArray
        } catch (_: SerializationException) {
            null
        } ?: return emptySet()
        return array.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { id -> id.isNotBlank() } }.toSet()
    }

    private companion object {
        const val MAX_PUSHED_IDS = 20_000
        const val MAX_DELETED_IDS = 5_000
    }
}
