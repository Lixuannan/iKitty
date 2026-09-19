package com.codingcow.ikitty

import kotlin.concurrent.Volatile

/**
 * 一个极小的 LRU 缓存。
 *
 * 刻意用"不可变 map + 整体替换"而不是加锁：条目很少（数据 URL 缓存上限 12 条），
 * 每次写入复制一份的代价可以忽略，而读侧永远看到的是一个完整快照，不需要同步。
 *
 * 不用 `Mutex` 是因为缓存的失效入口（`invalidateCache`）是非 suspend 的：
 * 它从 `ChatEngine` 的普通回调里被调用，没法等一个 suspend 锁。用不可变快照就没有这个问题。
 *
 * 并发的两次写入最多丢掉一次缓存写入——对缓存来说这是可接受的语义，
 * 丢掉的只是"下次要重新编码一遍"。
 */
class LruCache<K, V>(private val limit: Int) {
    init {
        require(limit > 0) { "上限必须为正数，实际是 $limit" }
    }

    @Volatile
    private var entries: Map<K, V> = emptyMap()

    val size: Int get() = entries.size

    /** 只读查找，不影响淘汰顺序。 */
    operator fun get(key: K): V? = entries[key]

    /** 命中就把它挪到"最近使用"的位置，这样热点条目不会被冷条目挤掉。 */
    fun getAndTouch(key: K): V? {
        val value = entries[key] ?: return null
        put(key, value)
        return value
    }

    fun put(key: K, value: V) {
        val next = LinkedHashMap(entries)
        // 先删再放：Kotlin 的 LinkedHashMap 保留插入顺序，这样它就成了最近使用的那一个。
        next.remove(key)
        next[key] = value
        while (next.size > limit) {
            next.remove(next.keys.first())
        }
        entries = next
    }

    fun clear() {
        entries = emptyMap()
    }
}
