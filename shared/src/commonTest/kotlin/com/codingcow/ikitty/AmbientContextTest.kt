package com.codingcow.ikitty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 注入 system prompt 的「此刻」背景块：知道什么写什么，不知道的整行不出现。 */
class AmbientContextTest {

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

    /** 时间和位置都是易变信息，必须写在提示词最后一块。 */
    @Test
    fun `the block tells the model not to parrot it`() {
        val block = AmbientContext.block(now = 1_700_000_000_000L, lastMessageAt = null, place = null)
        assertTrue(block.contains("不要复述"))
    }
}
