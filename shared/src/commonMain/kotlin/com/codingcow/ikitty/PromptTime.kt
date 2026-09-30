package com.codingcow.ikitty

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 会进入 system prompt 的时间格式化。
 *
 * 这里每个函数的输出都是**提示词契约的一部分**：两端必须给出完全一样的字符串，
 * 否则同一段对话在 Android 与 iOS 上会得到不同的模型行为。
 * 界面用的时间显示在 `ChatTime.kt`，也是两端共用的一份，但属于另一套格式。
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

/**
 * 一条历史消息在请求里的时间前缀，例如 `[2023-11-15 06:13 星期三]`。
 *
 * 用**绝对**时间而不是「3 小时前」：绝对时间在消息落盘那一刻就固定了，同一条历史每轮请求
 * 都是同样的字节，服务商的提示词缓存前缀照样命中；相对时间每轮都变，会把整个前缀作废。
 *
 * 只给模型看，界面用的是 `ChatTime.kt` 里的 `formatMessageTime`，两者不要混用。
 */
fun formatMessageStamp(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): String = "[${formatMoment(epochMillis, timeZone)}]"

/**
 * 正文**开头**那条时间前缀的形状：`[yyyy-MM-dd HH:mm 星期X]`。
 *
 * 年份-月-日-时:分这一段是必须的，所以不可能是时间戳的方括号（`[图片]`、`[1]`）不会被误伤。
 * 秒与星期几允许省略、冒号前后允许空白：模型照抄这条前缀时经常少抄一点或改格式，
 * 而少一个「星期三」不该就让整条前缀继续留在气泡里。
 */
private val LEADING_MESSAGE_STAMP = Regex(
    """^\s*\[\s*\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?(?:\s+[^\]\r\n]*?)?\s*\]\s*"""
)

/**
 * 界面显示用的正文：去掉开头被模型照抄回来的时间前缀。
 *
 * 这条前缀只该出现在发给模型的请求里（见 [formatMessageStamp]）——它是提示词契约的一部分，
 * 用来让模型知道每句话是什么时候说的。但有些模型会把历史消息的格式一并抄回来，
 * 于是回复开头多出一段 `[2023-11-15 06:13 星期三]`，在气泡里非常难看。
 *
 * 只在**展示**这一层去掉它：落盘内容与请求正文都不动。落在盘上的必须是模型的原始输出，
 * 否则下一轮历史里模型说过的话会凭空少一截，提示词缓存的前缀也会跟着变。
 */
fun stripLeadingMessageStamp(text: String): String =
    LEADING_MESSAGE_STAMP.replaceFirst(text, "")

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
