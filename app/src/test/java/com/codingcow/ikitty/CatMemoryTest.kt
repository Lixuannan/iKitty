package com.codingcow.ikitty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 记忆整理返回值的解析容错。
 *
 * 这部分依赖平台 JSON 实现，所以暂时留在 Android 侧；纯合并规则已经迁到
 * `:shared` 的 `CatMemoryRulesTest`。Phase 2 把 JSON 层提到 commonMain 后，
 * 这里会整体搬过去。
 */
class CatMemoryTest {

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
