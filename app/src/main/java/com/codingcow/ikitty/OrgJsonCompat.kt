package com.codingcow.ikitty

import org.json.JSONObject

/**
 * Android 侧仅存的 `org.json` 用法所需的容错读取。
 *
 * 业务格式（聊天记录、记忆文件、请求体）已经全部迁到 `:shared` 的 [JsonSupport]，
 * 这里只剩两处尚未迁移的 Android-only 代码：`ApiClient` 的响应解析（Phase 5 迁）
 * 和 APK 更新的 release 解析。等它们各自迁完，本文件即删除。
 */

/** 字段值为 null 时 `optString` 会返回字符串 "null"，这里统一收敛成空串。 */
internal fun JSONObject.stringOrEmpty(key: String): String =
    if (isNull(key)) "" else optString(key).trim()
