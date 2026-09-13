package com.example.aicat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 聊天流里给人看的时间：当天只显示时分，昨天带「昨天」，更早带日期。 */
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

/** 给模型看的完整时间，带年月日和星期。 */
internal fun formatMoment(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(Date(epochMillis))

/** 「刚刚」「12 分钟」「3 小时」「2 天」——给模型看的粗略间隔。 */
internal fun formatElapsed(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    return when {
        safe < 60_000L -> "刚刚"
        safe < 3_600_000L -> "${safe / 60_000L} 分钟"
        safe < 86_400_000L -> "${safe / 3_600_000L} 小时"
        else -> "${safe / 86_400_000L} 天"
    }
}

private fun localDayIndex(epochMillis: Long, zone: TimeZone): Long =
    Math.floorDiv(epochMillis + zone.getOffset(epochMillis), MILLIS_PER_DAY)

private const val MILLIS_PER_DAY = 86_400_000L
