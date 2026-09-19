package com.codingcow.ikitty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 注入提示词的「此刻」背景块。IP 返回的解析已经随 `Place` 迁到 `:shared` 的测试里。 */
class LocationTest {

    @Test
    fun `ambient block carries time, gap and place`() {
        val now = 1_700_000_000_000L
        val block = AmbientContext.block(
            now = now,
            lastMessageAt = now - 3 * 3_600_000L,
            place = Place(city = "杭州", fetchedAt = now)
        )
        assertTrue(block.contains("【此刻】"))
        assertTrue(block.contains("距离上一条消息：3 小时"))
        assertTrue(block.contains("主人大致在：杭州"))
        // 必须标明这只是推测，否则模型会把它当成精确位置来报
        assertTrue(block.contains("可能不准"))
    }

    @Test
    fun `ambient block omits what it does not know`() {
        val block = AmbientContext.block(now = 1_700_000_000_000L, lastMessageAt = null, place = null)
        assertTrue(block.contains("【此刻】"))
        assertFalse(block.contains("距离上一条消息"))
        assertFalse(block.contains("主人大致在"))
    }

    @Test
    fun `a place with a blank city is treated as unknown`() {
        val block = AmbientContext.block(
            now = 1_700_000_000_000L,
            lastMessageAt = null,
            place = Place(fetchedAt = 1L)
        )
        assertFalse(block.contains("主人大致在"))
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
}
