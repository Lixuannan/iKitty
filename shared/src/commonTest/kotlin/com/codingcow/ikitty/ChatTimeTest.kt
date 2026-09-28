package com.codingcow.ikitty

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 聊天流里给人看的时间：两端共用的显示契约。
 *
 * 期望值取自旧 Android 实现（`SimpleDateFormat("HH:mm")` 系列）在 Asia/Shanghai 下的输出，
 * 所以这组断言锁的是"换到 commonMain 之后两端看到的字符串没变"。
 */
class ChatTimeTest {

    private val shanghai = TimeZone.of("Asia/Shanghai")

    /** 2023-11-15 06:13 星期三（UTC+8）。 */
    private val now = 1_700_000_000_000L

    private fun label(at: Long) = formatMessageTime(at, now, shanghai)

    @Test
    fun `today shows only the clock`() {
        assertEquals("06:13", label(now))
        // 同一天更早的时刻也是时分，不带日期。
        assertEquals("01:13", label(now - 5 * 3_600_000L))
    }

    @Test
    fun `yesterday is labelled 昨天`() {
        assertEquals("昨天 06:13", label(now - 86_400_000L))
    }

    @Test
    fun `anything older carries the date`() {
        assertEquals("11-13 06:13", label(now - 2 * 86_400_000L))
    }

    /** 月和日都要补零，否则会和 Android 旧实现的 `MM-dd` 对不上。 */
    @Test
    fun `month and day are zero padded`() {
        assertEquals("01-05 10:00", formatMessageTime(1_672_884_000_000L, now, shanghai))
    }

    /**
     * 「哪一天」必须按本地日历算，不能按固定 86400000 毫秒的差值算：
     * 夏令时切换那天只有 23 或 25 小时，按毫秒差会把「昨天」判错。
     */
    @Test
    fun `the day boundary follows the local calendar`() {
        val newYork = TimeZone.of("America/New_York")
        // 2020-09-13 08:26 纽约（夏令时切换当天）。
        val afternoon = 1_600_000_000_000L
        val nextMorning = afternoon + 20 * 3_600_000L
        // 相隔只有 20 小时，但已经跨到第二天，必须显示日期而不是时分。
        assertEquals("昨天 08:26", formatMessageTime(afternoon, nextMorning, newYork))
    }

    private fun message(seq: Long, role: String, at: Long) = StoredMessage(seq, role, "x", at)

    @Test
    fun `the first message always shows its time`() {
        assertTrue(shouldShowMessageTime(null, message(1, StoredMessage.ROLE_USER, now)))
    }

    @Test
    fun `a speaker change shows the time again`() {
        val previous = message(1, StoredMessage.ROLE_USER, now)
        val current = message(2, StoredMessage.ROLE_ASSISTANT, now + 1_000L)
        assertTrue(shouldShowMessageTime(previous, current))
    }

    @Test
    fun `a quick same-speaker reply does not repeat the time`() {
        val previous = message(1, StoredMessage.ROLE_ASSISTANT, now)
        val current = message(2, StoredMessage.ROLE_ASSISTANT, now + 4 * 60_000L)
        assertFalse(shouldShowMessageTime(previous, current))
    }

    @Test
    fun `a five minute gap shows the time again`() {
        val previous = message(1, StoredMessage.ROLE_ASSISTANT, now)
        val current = message(2, StoredMessage.ROLE_ASSISTANT, now + CHAT_TIME_GAP_MILLIS)
        assertTrue(shouldShowMessageTime(previous, current))
    }
}
