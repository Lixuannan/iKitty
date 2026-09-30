package com.codingcow.ikitty

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 进 system prompt 的时间格式化。
 *
 * 期望值是从旧实现的 `SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA)`
 * 直接跑出来的（Asia/Shanghai 时为 UTC+8，UTC，以及一个有夏令时的时区），
 * 所以这组断言锁的是"提示词字节不变"，不只是"看起来对"。
 */
class PromptTimeTest {

    private val shanghai = TimeZone.of("Asia/Shanghai")
    private val utc = TimeZone.of("UTC")
    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun `moment matches the original SimpleDateFormat output`() {
        assertEquals("2023-11-15 06:13 星期三", formatMoment(1_700_000_000_000L, shanghai))
        // 同一天的最后一毫秒仍然属于同一天，不因取整跳到下一天。
        assertEquals("2023-11-15 06:13 星期三", formatMoment(1_699_999_999_999L, shanghai))
        assertEquals("1970-01-01 08:00 星期四", formatMoment(0L, shanghai))
    }

    @Test
    fun `moment follows the requested time zone`() {
        assertEquals("2023-11-14 22:13 星期二", formatMoment(1_700_000_000_000L, utc))
        assertEquals("2023-11-14 17:13 星期二", formatMoment(1_700_000_000_000L, newYork))
    }

    /** 夏令时前后同一个时区必须给出各自的墙上时间。 */
    @Test
    fun `moment respects daylight saving transitions`() {
        // 2020-09-13 是纽约的夏令时（UTC-4）。
        assertEquals("2020-09-13 08:26 星期日", formatMoment(1_600_000_000_000L, newYork))
        // 2024-05-18 仍是夏令时。
        assertEquals("2024-05-17 22:40 星期五", formatMoment(1_716_000_000_000L, newYork))
    }

    @Test
    fun `moment pads every numeric field`() {
        assertEquals("1970-01-01 00:00 星期四", formatMoment(0L, utc))
    }

    @Test
    fun `elapsed is rendered coarsely`() {
        assertEquals("刚刚", formatElapsed(30_000L))
        assertEquals("12 分钟", formatElapsed(12 * 60_000L))
        assertEquals("3 小时", formatElapsed(3 * 3_600_000L))
        assertEquals("2 天", formatElapsed(2 * 86_400_000L))
        // 时钟倒退也不能显示负数
        assertEquals("刚刚", formatElapsed(-5_000L))
    }

    @Test
    fun `elapsed boundaries sit where the comment says`() {
        assertEquals("刚刚", formatElapsed(59_999L))
        assertEquals("1 分钟", formatElapsed(60_000L))
        assertEquals("59 分钟", formatElapsed(3_599_999L))
        assertEquals("1 小时", formatElapsed(3_600_000L))
        assertEquals("23 小时", formatElapsed(86_399_999L))
        assertEquals("1 天", formatElapsed(86_400_000L))
    }

    /**
     * 历史消息的时间前缀。
     *
     * 前缀必须和 `formatMoment` 逐字节同源：它是同一个「这条消息是什么时候说的」答案，
     * 分成两套格式就会让模型看到的时间前后不一致。
     */
    @Test
    fun `message stamp wraps the same moment the model already knows`() {
        assertEquals("[2023-11-15 06:13 星期三]", formatMessageStamp(1_700_000_000_000L, shanghai))
        assertEquals(
            "[${formatMoment(0L, utc)}]",
            formatMessageStamp(0L, utc)
        )
    }

    /** 前缀必须是**绝对**时间：同一条历史每次算出来都一样，提示词缓存前缀才稳定。 */
    @Test
    fun `message stamp is stable for the same instant`() {
        val at = 1_700_000_000_000L
        assertEquals(formatMessageStamp(at, shanghai), formatMessageStamp(at, shanghai))
    }

    /**
     * 展示层要把模型照抄回来的时间前缀剥掉，而请求正文里的那份必须原样保留。
     *
     * 去掉的只有"开头恰好是时间戳"的方括号：用户自己打的 `[图片]` 或普通方括号内容不受影响，
     * 否则界面就在改写用户的输入。
     */
    @Test
    fun `a leading time stamp is stripped for display`() {
        assertEquals("喵～在呢", stripLeadingMessageStamp("[2023-11-15 06:13 星期三] 喵～在呢"))
        // 秒可以省略、前后可以有空白。
        assertEquals("喵～在呢", stripLeadingMessageStamp("[2023-11-15 06:13]喵～在呢"))
        assertEquals("喵～在呢", stripLeadingMessageStamp("  [2023-11-15 06:13 星期三]   喵～在呢"))
        assertEquals("喵～在呢", stripLeadingMessageStamp("[2023-11-15 06:13:07 星期三] 喵～在呢"))
        // 整条回复只有前缀时，结果为空而不是留一对方括号。
        assertEquals("", stripLeadingMessageStamp("[2023-11-15 06:13 星期三]"))
    }

    @Test
    fun `contents that are not a leading time stamp are left alone`() {
        assertEquals("你好", stripLeadingMessageStamp("你好"))
        assertEquals("[图片] 你好", stripLeadingMessageStamp("[图片] 你好"))
        assertEquals("[1] 你好", stripLeadingMessageStamp("[1] 你好"))
        // 前缀后面才出现的时间戳不是前缀，保留。
        assertEquals("你好 [2023-11-15 06:13 星期三]", stripLeadingMessageStamp("你好 [2023-11-15 06:13 星期三]"))
        // 不是 yyyy-MM-dd HH:mm 的形状，不动。
        assertEquals("[2023-11-15] 你好", stripLeadingMessageStamp("[2023-11-15] 你好"))
    }

    /**
     * 只有猫猫的回复去前缀：用户自己打出来的方括号时间是用户的输入，界面不该改写它。
     */
    @Test
    fun `display content strips the stamp from the cat but never from the user`() {
        val stamped = "[2023-11-15 06:13 星期三] 喵～"
        assertEquals(
            "喵～",
            StoredMessage(1, StoredMessage.ROLE_ASSISTANT, stamped, 0L).displayContent()
        )
        assertEquals(
            stamped,
            StoredMessage(2, StoredMessage.ROLE_USER, stamped, 0L).displayContent()
        )
    }
}
