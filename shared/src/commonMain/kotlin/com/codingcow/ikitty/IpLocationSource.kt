package com.codingcow.ikitty

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * 用出口 IP 换一个城市名。
 *
 * 不需要任何权限，也不用用户点同意，但代价必须说清楚：
 * - 精度只到城市，运营商 NAT 和 VPN 都会让它偏得很远；
 * - 每次刷新都把用户 IP 交给第三方，所以设置里留了开关。
 *
 * 刷新失败一律静默：拿不到位置只是少一句背景，不该影响聊天。
 *
 * 网络走 [HttpTransport]，所以两端共用同一份"试几个端点、超时就换下一个"的逻辑；
 * 时长的收紧用 `withTimeoutOrNull` 表达，而不是靠各家 HTTP 客户端的超时参数，
 * 这样 OkHttp 与 Ktor 的行为是一致的。
 */
class IpLocationSource(
    private val transport: HttpTransport,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val ttlMillis: Long = TTL_MILLIS,
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS
) : LocationSource {

    @Volatile
    private var place: Place? = null

    override fun cached(): Place? = place

    override fun isFresh(now: Long): Boolean {
        val current = place ?: return false
        return now - current.fetchedAt < ttlMillis
    }

    override suspend fun refresh(now: Long) {
        if (isFresh(now)) return
        for (endpoint in endpoints) {
            val fetched = withTimeoutOrNull(timeoutMillis) { attempt(endpoint, now) }
            if (fetched != null && !fetched.isEmpty) {
                place = fetched
                return
            }
        }
        // 全部失败就保留上一次的结果：过期的城市名也比"不知道"强。
    }

    /**
     * 单个端点的一次尝试。
     *
     * 取消必须原样抛出：`TimeoutCancellationException` 也是 `CancellationException`，
     * 如果在这里被吞掉，外层 `withTimeoutOrNull` 就再也醒不过来了。
     */
    private suspend fun attempt(endpoint: String, now: Long): Place? = try {
        val response = transport.get(endpoint, mapOf("Accept" to "application/json"))
        if (response.isSuccessful) parseIpPlace(response.body, now) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 这个端点不行就换下一个。
        null
    }

    companion object {
        /**
         * 按顺序尝试，第一个给出城市的胜出：
         * 1. ip-api — 唯一返回中文地名的（`lang=zh-CN`），但免费档只有 http；
         * 2. ipwho.is — https，免费额度宽松；
         * 3. ipapi.co — https，共享出口 IP 上经常被限流，所以放最后。
         *
         * 都不需要 key。多个候选只在前一个失败时才会被请求，用来兜住单个服务挂掉的情况。
         *
         * 第一个端点是明文 http：Android 侧由 `network_security_config` 放行，
         * iOS 侧由 Info.plist 的 ATS 例外放行。去掉它会让两端都拿不到中文城市名。
         */
        val DEFAULT_ENDPOINTS = listOf(
            "http://ip-api.com/json/?lang=zh-CN&fields=status,message,country,regionName,city",
            "https://ipwho.is/",
            "https://ipapi.co/json/"
        )

        /** 半小时刷新一次：城市不会几分钟就变，没必要反复把 IP 送出去。 */
        const val TTL_MILLIS = 30 * 60 * 1000L

        /** 定位是可选功能，超时必须短：每个候选只值一次快速尝试。 */
        const val REQUEST_TIMEOUT_MILLIS = 8_000L
    }
}
