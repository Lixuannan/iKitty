package com.codingcow.ikitty

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 聊天流里给人看的时间：当天只显示时分，昨天带「昨天」，更早带日期。
 *
 * 只有界面用它，所以留在 Android 侧；进 system prompt 的 `formatMoment` /
 * `formatElapsed` 在 `:shared` 的 `PromptTime.kt`。
 */
internal fun formatMessageTime(
    epochMillis: Long,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val zone = TimeZone.getDefault()
    val day = localDayIndex(epochMillis, zone)
    val pattern = when (day) {
        localDayIndex(nowMillis, zone) -> "HH:mm"
        localDayIndex(nowMillis, zone) - 1 -> "昨天 HH:mm"
        else -> "MM-dd HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
}

private fun localDayIndex(epochMillis: Long, zone: TimeZone): Long =
    Math.floorDiv(epochMillis + zone.getOffset(epochMillis), MILLIS_PER_DAY)

private const val MILLIS_PER_DAY = 86_400_000L
