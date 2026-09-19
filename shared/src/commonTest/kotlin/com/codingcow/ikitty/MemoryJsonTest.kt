package com.codingcow.ikitty

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 记忆落盘与解析的容错。
 *
 * 记忆文件是跨平台契约：iOS 与 Android 必须能互读同一份 `chat/cat_memory.json`，
 * 所以键名与结构都在这里锁死。
 */
class MemoryJsonTest {

    private fun fact(
        category: MemoryCategory = MemoryCategory.OWNER,
        key: String = "名字",
        value: String = "小明",
        at: Long = 10L,
        seq: Long = 0L,
        pinned: Boolean = false
    ) = MemoryFact(category = category, key = key, value = value, updatedAt = at, sourceSeq = seq, pinned = pinned)

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

    /** 落盘结构：`version` / `lastExtractedSeq` / `lastExtractedAt` / `facts`。 */
    @Test
    fun `the memory file shape is the cross-platform contract`() {
        val encoded = encodeCatMemory(
            CatMemory(
                facts = listOf(fact()),
                lastExtractedSeq = 5L,
                lastExtractedAt = 99L
            )
        )
        assertEquals(
            buildJsonObject {
                put("version", 1)
                put("lastExtractedSeq", 5L)
                put("lastExtractedAt", 99L)
                put("facts", kotlinx.serialization.json.buildJsonArray { add(fact().toJson()) })
            },
            encoded
        )
        assertEquals(
            buildJsonObject {
                put("category", "OWNER")
                put("key", "名字")
                put("value", "小明")
                put("at", 10L)
                put("seq", 0L)
            },
            fact().toJson()
        )
        assertEquals(JsonPrimitive(true), fact(pinned = true, seq = 7L).toJson()["pinned"])
    }

    @Test
    fun `a memory file survives an encode parse round trip`() {
        val memory = CatMemory(
            facts = listOf(fact(), fact(category = MemoryCategory.PREFERENCE, key = "火锅", value = "喜欢")),
            lastExtractedSeq = 12L,
            lastExtractedAt = 34L
        )
        assertEquals(memory, parseCatMemory(encodeCatMemory(memory).toString()))
    }

    @Test
    fun `a corrupt memory file returns null instead of throwing`() {
        assertNull(parseCatMemory("<html>gateway</html>"))
        assertNull(parseCatMemory(""))
    }

    /** `pinned` 只在为真时出现，避免给老解析器塞一个多余的 false。 */
    @Test
    fun `pinned is only written when true`() {
        assertNull(fact().toJson()["pinned"])
        assertNotNull(fact(pinned = true).toJson()["pinned"])
    }
}
