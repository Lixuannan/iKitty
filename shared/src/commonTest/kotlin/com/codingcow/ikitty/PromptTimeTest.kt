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
}
