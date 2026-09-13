package com.example.aicat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 用出口 IP 换一个城市名。
 *
 * 不需要任何权限，也不用用户点同意，但代价必须说清楚：
 * - 精度只到城市，运营商 NAT 和 VPN 都会让它偏得很远；
 * - 每次刷新都把用户 IP 交给第三方，所以设置里留了开关。
 *
 * 刷新失败一律静默：拿不到位置只是少一句背景，不该影响聊天。
 */
class IpLocationSource(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val ttlMillis: Long = TTL_MILLIS,
    private val client: OkHttpClient = defaultClient()
) : LocationSource {

    @Volatile
    private var place: Place? = null

    override fun cached(): Place? = place

    override fun isFresh(now: Long): Boolean {
        val current = place ?: return false
        return now - current.fetchedAt < ttlMillis
    }

    override suspend fun refresh(now: Long) = withContext(Dispatchers.IO) {
        if (isFresh(now)) return@withContext
        for (endpoint in endpoints) {
            val fetched = runCatching { fetch(endpoint, now) }.getOrNull()?.takeIf { !it.isEmpty }
            if (fetched != null) {
                place = fetched
                return@withContext
            }
        }
        // 全部失败就保留上一次的结果：过期的城市名也比"不知道"强。
    }

    private fun fetch(endpoint: String, now: Long): Place? {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return parseIpPlace(response.body?.string().orEmpty(), now)
        }
    }

    private companion object {
        /**
         * 按顺序尝试，第一个给出城市的胜出：
         * 1. ip-api — 唯一返回中文地名的（`lang=zh-CN`），但免费档只有 http；
         * 2. ipwho.is — https，免费额度宽松；
         * 3. ipapi.co — https，共享出口 IP 上经常被限流，所以放最后。
         *
         * 都不需要 key。多个候选只在前一个失败时才会被请求，用来兜住单个服务挂掉的情况。
         */
        val DEFAULT_ENDPOINTS = listOf(
            "http://ip-api.com/json/?lang=zh-CN&fields=status,message,country,regionName,city",
            "https://ipwho.is/",
            "https://ipapi.co/json/"
        )

        /** 半小时刷新一次：城市不会几分钟就变，没必要反复把 IP 送出去。 */
        const val TTL_MILLIS = 30 * 60 * 1000L

        /** 定位是可选功能，超时必须短：它只值一次快速尝试。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 解析 IP 定位接口的返回。
 *
 * 两家的字段名不一样（`regionName` / `region`、`country` / `country_name`），
 * 这里一次兼容；失败状态、出错标记和不认识的返回体都返回 null。
 */
internal fun parseIpPlace(body: String, now: Long): Place? {
    val json = try {
        JSONObject(body)
    } catch (_: JSONException) {
        return null
    }
    if (json.optString("status").equals("fail", ignoreCase = true)) return null
    if (json.optBoolean("error", false)) return null
    return Place(
        city = json.stringOrEmpty("city"),
        region = json.stringOrEmpty("regionName").ifEmpty { json.stringOrEmpty("region") },
        country = json.stringOrEmpty("country").ifEmpty { json.stringOrEmpty("country_name") },
        fetchedAt = now
    )
}
