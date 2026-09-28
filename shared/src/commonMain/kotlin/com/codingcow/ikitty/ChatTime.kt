package com.codingcow.ikitty

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * 聊天流里给人看的时间。
 *
 * 这是**界面契约**，不是提示词契约：Android 与 iOS 必须给出同样的字符串与同样的分组，
 * 否则同一段对话在两端看起来是两个样子。进 system prompt 的时间是另一套
 * （`PromptTime.kt` 的 `formatMoment` / `formatMessageStamp`），两者不要混用。
 *
 * 时区交给 kotlinx-datetime，不自己算 epoch 偏移——夏令时与历史时区规则手算必错。
 */

/** 相邻两条消息间隔达到这个值，就在后一条前面重新显示一次时间，和常见聊天应用一致。 */
const val CHAT_TIME_GAP_MILLIS = 5 * 60 * 1000L

/**
 * 当天只显示时分，昨天带「昨天」，更早带年月日。
 *
 * [nowMillis] 与 [timeZone] 显式传入是为了让测试不依赖跑测试的机器；生产路径见
 * [messageTimeLabel] 与 [shouldShowMessageTime]。
 */
fun formatMessageTime(
    epochMillis: Long,
    nowMillis: Long = currentTimeMillis(),
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    val clock = "${local.hour.pad2()}:${local.minute.pad2()}"
    // 比较本地日期而不是毫秒差：跨夏令时的一天不是 86400000 毫秒，按差值算会错一天。
    val day = local.date.toEpochDays()
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date.toEpochDays()
    return when (day) {
        today -> clock
        today - 1 -> "昨天 $clock"
        else -> "${(local.month.ordinal + 1).pad2()}-${local.day.pad2()} $clock"
    }
}

/**
 * 界面直接调用的一层：当前时刻取系统时钟、时区取系统默认。
 *
 * 单独给出来是因为 Kotlin/Native 导出的 Swift 签名**不认默认参数**，
 * 只暴露全参数版本，Swift 侧没法省掉 `nowMillis` / `timeZone`。
 */
fun messageTimeLabel(epochMillis: Long): String =
    formatMessageTime(epochMillis, currentTimeMillis(), TimeZone.currentSystemDefault())

/**
 * 这一条前面是否显示时间：第一条、换了说话人、或距上一条达到 [CHAT_TIME_GAP_MILLIS]。
 *
 * 接收相邻两条而不是整个列表加下标，两端都只要一次相邻比较就能调用。
 */
fun shouldShowMessageTime(previous: StoredMessage?, current: StoredMessage): Boolean {
    if (previous == null) return true
    if (previous.role != current.role) return true
    return current.createdAt - previous.createdAt >= CHAT_TIME_GAP_MILLIS
}

@OptIn(ExperimentalTime::class)
private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun Int.pad2(): String = if (this < 10) "0$this" else toString()
