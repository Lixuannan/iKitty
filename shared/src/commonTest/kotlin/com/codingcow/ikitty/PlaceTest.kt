package com.codingcow.ikitty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 地点模型本身与平台无关：显示名回退顺序、空值判定。 */
class PlaceTest {

    @Test
    fun `display falls back city then region then country`() {
        assertEquals("杭州", Place(city = "杭州", region = "浙江省", country = "中国").display)
        assertEquals("浙江省", Place(region = "浙江省", country = "中国").display)
        assertEquals("中国", Place(country = "中国").display)
        assertTrue(Place().isEmpty)
        assertFalse(Place(city = "杭州").isEmpty)
    }
}
