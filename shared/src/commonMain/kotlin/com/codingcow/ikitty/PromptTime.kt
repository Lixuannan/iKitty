package com.codingcow.ikitty

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 会进入 system prompt 的时间格式化。
 *
 * 这两个函数的输出是**提示词契约的一部分**：两端必须给出完全一样的字符串，
 * 否则同一段对话在 Android 与 iOS 上会得到不同的模型行为。
 * 界面用的时间显示（`formatMessageTime`）不属于这一层，各平台自己实现。
 *
 * 时区交给 kotlinx-datetime，不自己算 epoch 偏移——夏令时与历史时区规则手算必错。
 */

/**
 * 给模型看的完整时间，带年月日和星期。
 *
 * [timeZone] 默认取系统时区；显式传入是为了让测试不依赖跑测试的机器的时区。
 * 输出对齐原来的 `SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA)`。
 */
fun formatMoment(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    return buildString {
        append(local.year.toString().padStart(4, '0'))
        append('-')
        append((local.month.ordinal + 1).toString().padStart(2, '0'))
        append('-')
        append(local.day.toString().padStart(2, '0'))
        append(' ')
        append(local.hour.toString().padStart(2, '0'))
        append(':')
        append(local.minute.toString().padStart(2, '0'))
        append(' ')
        append(chineseWeekday(local.dayOfWeek))
    }
}

/** 「刚刚」「12 分钟」「3 小时」「2 天」——给模型看的粗略间隔。 */
fun formatElapsed(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    return when {
        safe < 60_000L -> "刚刚"
        safe < 3_600_000L -> "${safe / 60_000L} 分钟"
        safe < 86_400_000L -> "${safe / 3_600_000L} 小时"
        else -> "${safe / 86_400_000L} 天"
    }
}

/** 与 `Locale.CHINA` 的 `EEEE` 输出一致，包括「星期日」而不是「星期天」。 */
private fun chineseWeekday(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "星期一"
    DayOfWeek.TUESDAY -> "星期二"
    DayOfWeek.WEDNESDAY -> "星期三"
    DayOfWeek.THURSDAY -> "星期四"
    DayOfWeek.FRIDAY -> "星期五"
    DayOfWeek.SATURDAY -> "星期六"
    DayOfWeek.SUNDAY -> "星期日"
}
