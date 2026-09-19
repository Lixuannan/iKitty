package com.codingcow.ikitty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 缓存淘汰的契约：上限必须真的生效，否则"缓存"会变成内存泄漏。 */
class LruCacheTest {

    @Test
    fun `values can be stored and read back`() {
        val cache = LruCache<String, String>(limit = 3)
        cache.put("a", "1")
        assertEquals("1", cache["a"])
        assertEquals(1, cache.size)
    }

    @Test
    fun `a missing key is null`() {
        assertNull(LruCache<String, String>(limit = 3)["nope"])
    }

    /** 这是这个类存在的理由：上限之外的条目必须被丢掉。 */
    @Test
    fun `the limit is enforced`() {
        val cache = LruCache<Int, String>(limit = 2)
        cache.put(1, "a")
        cache.put(2, "b")
        cache.put(3, "c")

        assertEquals(2, cache.size)
        assertNull(cache[1], "最久没用的那一条应当被淘汰")
        assertEquals("b", cache[2])
        assertEquals("c", cache[3])
    }

    @Test
    fun `reading through getAndTouch keeps an entry alive`() {
        val cache = LruCache<Int, String>(limit = 2)
        cache.put(1, "a")
        cache.put(2, "b")
        // 碰一下 1，它就不再是最久没用的那个。
        cache.getAndTouch(1)
        cache.put(3, "c")

        assertEquals("a", cache[1])
        assertNull(cache[2])
        assertEquals("c", cache[3])
    }

    /** 单纯 `get` 不改变淘汰顺序：缓存命中不该让冷数据变成热的。 */
    @Test
    fun `a plain get does not change the eviction order`() {
        val cache = LruCache<Int, String>(limit = 2)
        cache.put(1, "a")
        cache.put(2, "b")
        assertEquals("a", cache[1])
        cache.put(3, "c")

        assertNull(cache[1])
        assertEquals("b", cache[2])
    }

    @Test
    fun `overwriting an existing key does not grow the cache`() {
        val cache = LruCache<String, String>(limit = 2)
        cache.put("a", "1")
        cache.put("a", "2")

        assertEquals(1, cache.size)
        assertEquals("2", cache["a"])
    }

    @Test
    fun `clear empties the cache`() {
        val cache = LruCache<String, String>(limit = 2)
        cache.put("a", "1")
        cache.clear()

        assertEquals(0, cache.size)
        assertNull(cache["a"])
    }

    @Test
    fun `a non-positive limit is rejected`() {
        assertFailsWith<IllegalArgumentException> { LruCache<String, String>(limit = 0) }
        assertFailsWith<IllegalArgumentException> { LruCache<String, String>(limit = -1) }
    }

    /** 一个很大的上限不该有限制之外的行为——缓存不该悄悄丢掉还装得下的东西。 */
    @Test
    fun `entries stay until the limit is actually reached`() {
        val cache = LruCache<Int, Int>(limit = 100)
        repeat(100) { cache.put(it, it) }
        assertEquals(100, cache.size)
        repeat(100) { assertEquals(it, cache[it]) }
    }
}
