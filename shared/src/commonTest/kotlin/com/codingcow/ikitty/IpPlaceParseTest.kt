package com.codingcow.ikitty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** IP 定位返回的解析容错：认不出的返回体必须返回 null，而不是缓存一个假地点。 */
class IpPlaceParseTest {

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
}
