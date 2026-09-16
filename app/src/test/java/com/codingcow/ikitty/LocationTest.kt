package com.codingcow.ikitty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IP 定位的解析容错，以及注入提示词的「此刻」背景块。 */
class LocationTest {

    @Test
    fun `parses the chinese ip-api response`() {
        val body = """{"status":"success","country":"中国","regionName":"浙江省","city":"杭州"}"""
        val place = parseIpPlace(body, now = 5L)!!
        assertEquals("杭州", place.city)
        assertEquals("浙江省", place.region)
        assertEquals("中国", place.country)
        assertEquals("杭州", place.display)
        assertEquals(5L, place.fetchedAt)
    }

    @Test
    fun `parses the ipapi-co fallback field names`() {
        val place = parseIpPlace(
            """{"city":"Hangzhou","region":"Zhejiang","country_name":"China"}""",
            now = 5L
        )!!
        assertEquals("Hangzhou", place.display)
        assertEquals("China", place.country)
    }

    @Test
    fun `parses the ipwho-is fallback shape`() {
        val body = """
            {"ip":"40.160.137.80","success":true,"country":"United States",
             "region":"Oregon","city":"Portland"}
        """.trimIndent()
        val place = parseIpPlace(body, now = 5L)!!
        assertEquals("Portland", place.display)
        assertEquals("United States", place.country)
    }

    @Test
    fun `a json null city does not become the string null`() {
        val place = parseIpPlace("""{"city":null,"region":"浙江省"}""", now = 1L)!!
        assertEquals("", place.city)
        assertEquals("浙江省", place.display)
    }

    @Test
    fun `failure responses and garbage are rejected instead of cached`() {
        assertNull(parseIpPlace("""{"status":"fail","message":"private range"}""", 1L))
        assertNull(parseIpPlace("""{"error":true,"reason":"RateLimited"}""", 1L))
        assertNull(parseIpPlace("<html>gateway</html>", 1L))
        assertNull(parseIpPlace("", 1L))
    }

    @Test
    fun `display falls back city then region then country`() {
        assertEquals("杭州", Place(city = "杭州", region = "浙江省", country = "中国").display)
        assertEquals("浙江省", Place(region = "浙江省", country = "中国").display)
        assertEquals("中国", Place(country = "中国").display)
        assertTrue(Place().isEmpty)
        assertFalse(Place(city = "杭州").isEmpty)
    }

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
