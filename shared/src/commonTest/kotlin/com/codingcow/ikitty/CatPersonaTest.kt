package com.codingcow.ikitty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 角色设定会直接影响 system prompt，这里把契约固定下来。 */
class CatPersonaTest {

    @Test
    fun `default prompt carries name, traits and json contract`() {
        val prompt = CatPersona().systemPrompt()
        assertTrue(prompt.contains("你叫「猫猫」"))
        CatPersona.DEFAULT_TRAITS.forEach { assertTrue(prompt.contains(it.prompt), it.prompt) }
        assertTrue(prompt.contains(CatSpeechStyle.DAILY.prompt))
        assertTrue(prompt.contains(CatFlavor.HINT.prompt))
        assertTrue(prompt.contains(""""reply": "想说的话""""))
        assertFalse(prompt.contains("补充设定"))
    }

    @Test
    fun `traits render in declaration order and can be empty`() {
        val persona = CatPersona(traits = setOf(CatTrait.LAZY, CatTrait.WITTY))
        val prompt = persona.systemPrompt()
        assertTrue(prompt.indexOf(CatTrait.WITTY.prompt) < prompt.indexOf(CatTrait.LAZY.prompt))

        assertFalse(CatPersona(traits = emptySet()).systemPrompt().contains("性格："))
    }

    @Test
    fun `notes are appended only when present`() {
        val prompt = CatPersona(notes = "  叫我主人  ").systemPrompt()
        assertTrue(prompt.contains("补充设定：\n叫我主人"))
    }

    @Test
    fun `human flavor drops cat greeting`() {
        val persona = CatPersona(name = "小白", flavor = CatFlavor.HUMAN)
        assertFalse(persona.systemPrompt().contains("喵"))
        assertEquals("你好呀！我是你的小白。今天想和我聊点什么？", persona.welcome())
    }

    @Test
    fun `only non-human flavors suggest cat actions`() {
        CatFlavor.entries.forEach { flavor ->
            val prompt = CatPersona(flavor = flavor).systemPrompt()
            assertTrue(prompt.contains(flavor.prompt), flavor.name)
            assertTrue(prompt.contains(flavor.hint), flavor.name)
            if (flavor == CatFlavor.HUMAN) {
                assertFalse(prompt.contains("建议在回复里"))
            } else {
                assertTrue(prompt.contains("建议在回复里"), flavor.name)
            }
        }
    }

    @Test
    fun `blank name falls back to default`() {
        assertEquals(CatPersona.DEFAULT_NAME, CatPersona(name = "   ").displayName())
        assertTrue(CatPersona(name = "").welcome().contains(CatPersona.DEFAULT_NAME))
    }

    @Test
    fun `traits survive a storage round trip`() {
        val traits = setOf(CatTrait.WITTY, CatTrait.LAZY)
        assertEquals(traits, parseTraits(traits.encodeTraits(), emptySet()))

        // 空字符串是"一个都没选"，null 才是"从没存过"。
        assertEquals(emptySet<CatTrait>(), parseTraits("", CatPersona.DEFAULT_TRAITS))
        assertEquals(CatPersona.DEFAULT_TRAITS, parseTraits(null, CatPersona.DEFAULT_TRAITS))
        // 认不出来的名字直接丢掉，不炸。
        assertEquals(setOf(CatTrait.LAZY), parseTraits("LAZY,BOGUS", emptySet()))
    }

    @Test
    fun `enum lookup rejects unknown names`() {
        assertEquals(CatSpeechStyle.CONCISE, enumByName<CatSpeechStyle>("CONCISE"))
        assertNull(enumByName<CatSpeechStyle>("NOPE"))
        assertNull(enumByName<CatSpeechStyle>(null))
    }
}
