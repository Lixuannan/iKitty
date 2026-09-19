package com.codingcow.ikitty

/**
 * 解析 IP 定位接口的返回。
 *
 * 两家的字段名不一样（`regionName` / `region`、`country` / `country_name`），
 * 这里一次兼容；失败状态、出错标记和不认识的返回体都返回 null。
 *
 * 纯解析逻辑，两端共用；真正的网络请求各自实现（Android 走 OkHttp，iOS 走 Ktor）。
 */
fun parseIpPlace(body: String, now: Long): Place? {
    val json = parseJsonObjectOrNull(body) ?: return null
    if (json.optString("status").equals("fail", ignoreCase = true)) return null
    if (json.booleanOr("error", false)) return null
    return Place(
        city = json.stringOrEmpty("city"),
        region = json.stringOrEmpty("regionName").ifEmpty { json.stringOrEmpty("region") },
        country = json.stringOrEmpty("country").ifEmpty { json.stringOrEmpty("country_name") },
        fetchedAt = now
    )
}
