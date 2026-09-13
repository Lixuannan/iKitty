package com.example.aicat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结构化记忆的合并规则和解析容错。这里是"记忆不会被弄丢"的契约所在。 */
class CatMemoryTest {

    private fun fact(
        key: String,
        value: String,
        category: MemoryCategory = MemoryCategory.OWNER,
        at: Long = 1L,
        pinned: Boolean = false
    ) = MemoryFact(category = category, key = key, value = value, updatedAt = at, pinned = pinned)

    @Test
    fun `merge adds new facts and keeps the ones the model did not mention`() {
        val existing = listOf(fact("名字", "小明"))
        val merged = CatMemoryRules.merge(existing, listOf(fact("城市", "杭州")), emptySet(), now = 10L)
        assertEquals(listOf("名字", "城市"), merged.map { it.key })
    }

    @Test
    fun `same key overwrites instead of piling up`() {
        val existing = listOf(fact("名字", "小明", at = 1L))
        val merged = CatMemoryRules.merge(existing, listOf(fact("名字", "阿明")), emptySet(), now = 10L)
        assertEquals(1, merged.size)
        assertEquals("阿明", merged.single().value)
        assertEquals(10L, merged.single().updatedAt)
    }

    @Test
    fun `an unchanged fact keeps its old timestamp so eviction stays fair`() {
        val existing = listOf(fact("名字", "小明", at = 1L))
        val merged = CatMemoryRules.merge(existing, listOf(fact("名字", "小明")), emptySet(), now = 10L)
        assertEquals(1L, merged.single().updatedAt)
    }

    @Test
    fun `forget removes a fact but never a pinned one`() {
        val existing = listOf(fact("旧地址", "上海"), fact("名字", "小明", pinned = true))
        val merged = CatMemoryRules.merge(existing, emptyList(), setOf("旧地址", "名字"), now = 10L)
        assertEquals(listOf("名字"), merged.map { it.key })
    }

    @Test
    fun `over the cap the least recently updated unpinned fact is dropped`() {
        val existing = (1..CatMemoryRules.MAX_FACTS).map { fact("k$it", "v$it", at = it.toLong()) }
        val merged = CatMemoryRules.merge(existing, listOf(fact("新", "值")), emptySet(), now = 999L)
        assertEquals(CatMemoryRules.MAX_FACTS, merged.size)
        assertFalse(merged.any { it.key == "k1" })
        assertTrue(merged.any { it.key == "新" })
    }

    @Test
    fun `upsert renames a key without leaving the old one behind`() {
        val next = CatMemoryRules.upsert(
            facts = listOf(fact("名字", "小明")),
            originalKey = "名字",
            draft = fact("称呼", "主人"),
            now = 5L
        )
        assertEquals(listOf("称呼"), next.map { it.key })
    }

    @Test
    fun `upsert clamps runaway values instead of rejecting them`() {
        val next = CatMemoryRules.upsert(emptyList(), null, fact("k".repeat(40), "v".repeat(400)), now = 5L)
        assertEquals(CatMemoryRules.MAX_KEY_CHARS, next.single().key.length)
        assertEquals(CatMemoryRules.MAX_VALUE_CHARS, next.single().value.length)
    }

    @Test
    fun `render groups by category and is empty without facts`() {
        assertEquals("", CatMemoryRender.block(emptyList()))
        val block = CatMemoryRender.block(
            listOf(
                fact("名字", "小明", MemoryCategory.OWNER),
                fact("火锅", "喜欢", MemoryCategory.PREFERENCE)
            )
        )
        assertTrue(block.contains("【你记得的事】"))
        assertTrue(block.indexOf("主人：") < block.indexOf("喜好："))
        assertTrue(block.contains("- 名字：小明"))
    }

    @Test
    fun `parse accepts plain json, fenced json and chinese category labels`() {
        val raw = """{"facts":[{"category":"喜好","key":"火锅","value":"喜欢"}],"forget":["旧地址"]}"""
        val update = parseMemoryUpdate(raw, now = 7L)!!
        assertEquals("火锅", update.facts.single().key)
        assertEquals(MemoryCategory.PREFERENCE, update.facts.single().category)
        assertEquals(7L, update.facts.single().updatedAt)
        assertEquals(setOf("旧地址"), update.forget)

        assertEquals(1, parseMemoryUpdate("```json\n$raw\n```", 7L)!!.facts.size)
    }

    @Test
    fun `parse returns null on garbage so the old memory survives`() {
        assertNull(parseMemoryUpdate("喵？我不知道该记什么", 1L))
        assertNull(parseMemoryUpdate("", 1L))
    }

    @Test
    fun `an unknown category falls back instead of dropping the fact`() {
        val update = parseMemoryUpdate("""{"facts":[{"category":"??","key":"k","value":"v"}]}""", 1L)!!
        assertEquals(MemoryCategory.SITUATION, update.facts.single().category)
    }
}
