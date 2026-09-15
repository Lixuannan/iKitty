package com.example.aicat

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

/** GitHub release 的来源仓库；更新检查与下载都指向这里。 */
internal const val RELEASE_API_URL = "https://api.github.com/repos/Lixuannan/iKitty/releases/latest"

/** 一次可用的软件更新，字段全部来自 release 本身。 */
data class UpdateInfo(
    /** 去掉前缀 `v` 的版本号，例如 `0.2.0`；与当前版本比较用这个值。 */
    val version: String,
    /** release 的原始 tag，例如 `v0.2.0`。 */
    val tagName: String,
    /** release 说明（Markdown 原文）。 */
    val notes: String,
    val publishedAt: Long?,
    val apkName: String,
    val apkUrl: String,
    val apkSizeBytes: Long
)

/** 更新流程的界面状态。 */
sealed interface UpdateStatus {
    /** 还没有检查过。 */
    data object Idle : UpdateStatus

    data object Checking : UpdateStatus

    data class UpToDate(val currentVersion: String) : UpdateStatus

    data class Available(val info: UpdateInfo, val currentVersion: String) : UpdateStatus

    data class Downloading(
        val info: UpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateStatus

    /** 安装包已下载并通过校验，可以交给系统安装器覆盖安装。 */
    data class Ready(val info: UpdateInfo, val file: File) : UpdateStatus

    /** [info] 非空时表示「已经拿到版本信息但这一步失败」，界面可以据此重试。 */
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateStatus
}

/**
 * 解析 GitHub `releases/latest` 的返回体。
 *
 * 返回 `null` 的两种情况——release 里没有 `.apk` 附件，或返回体无法解析——
 * 对用户是同一个动作（去 GitHub 发布页手动下载），所以不再细分。
 */
internal fun parseLatestRelease(body: String): UpdateInfo? {
    val json = try {
        JSONObject(body)
    } catch (_: JSONException) {
        // GitHub 返回的不是 JSON（网关错误页等），按「拿不到可用更新」处理。
        return null
    }
    // latest 接口本来就排除 draft / prerelease，这里再挡一次，避免旧接口或缓存漏过来。
    if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return null

    val tagName = json.stringOrEmpty("tag_name")
    val version = normalizeVersion(tagName)
    if (version.isEmpty()) return null

    val asset = pickApkAsset(json.optJSONArray("assets"), version) ?: return null
    val url = asset.stringOrEmpty("browser_download_url")
    if (url.isEmpty()) return null

    return UpdateInfo(
        version = version,
        tagName = tagName,
        notes = json.stringOrEmpty("body"),
        publishedAt = parseInstant(json.stringOrEmpty("published_at")),
        apkName = asset.stringOrEmpty("name").ifEmpty { "iKitty-$tagName.apk" },
        apkUrl = url,
        apkSizeBytes = asset.optLong("size", 0L).coerceAtLeast(0L)
    )
}

/**
 * 版本号比较，用于判断 release 是否比当前版本新。
 *
 * 只比较数字段（`0.2.0` 对 `0.1.0`）；数字段完全相同后，带预发布后缀的一方更旧，
 * 这样 `0.2.0-beta.1 < 0.2.0`。认不出的段落按 0 处理，绝不因为版本号写法奇怪就报错。
 */
internal fun compareVersions(left: String, right: String): Int {
    val a = ParsedVersion.of(left)
    val b = ParsedVersion.of(right)
    val size = maxOf(a.numbers.size, b.numbers.size)
    for (index in 0 until size) {
        val diff = a.numbers.getOrElse(index) { 0 }.compareTo(b.numbers.getOrElse(index) { 0 })
        if (diff != 0) return diff
    }
    return when {
        a.prerelease == b.prerelease -> 0
        a.prerelease.isEmpty() -> 1
        b.prerelease.isEmpty() -> -1
        else -> a.prerelease.compareTo(b.prerelease)
    }
}

/** 去掉 `v` 前缀和首尾空白，得到用于展示和比较的版本号。 */
internal fun normalizeVersion(raw: String): String =
    raw.trim().removePrefix("v").removePrefix("V").substringBefore(' ').trim()

/** 优先挑与版本号同名的 APK，其次任意 APK；没有就返回 null。 */
private fun pickApkAsset(assets: JSONArray?, version: String): JSONObject? {
    if (assets == null || assets.length() == 0) return null
    val apks = (0 until assets.length())
        .mapNotNull { assets.optJSONObject(it) }
        .filter { it.stringOrEmpty("name").endsWith(".apk", ignoreCase = true) }
    return apks.firstOrNull { it.stringOrEmpty("name").contains(version) } ?: apks.firstOrNull()
}

private fun parseInstant(raw: String): Long? {
    if (raw.isEmpty()) return null
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: DateTimeParseException) {
        // release 的 published_at 不是合法 ISO-8601 时只丢时间，不影响其他字段。
        null
    }
}

private class ParsedVersion(val numbers: List<Int>, val prerelease: String) {
    companion object {
        fun of(raw: String): ParsedVersion {
            val normalized = normalizeVersion(raw)
            val separator = normalized.indexOfFirst { it == '-' || it == '+' }
            val core = if (separator >= 0) normalized.take(separator) else normalized
            val prerelease = if (separator >= 0) normalized.substring(separator + 1) else ""
            val numbers = core.split('.', '_')
                .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            return ParsedVersion(numbers, prerelease)
        }
    }
}
