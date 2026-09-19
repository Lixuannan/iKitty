package com.codingcow.ikitty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设置的读写语义。
 *
 * 存储介质两端不同（DataStore / NSUserDefaults），但"缺字段怎么回退""旧版本怎么迁移"
 * 必须一致，这组断言锁的就是这部分。
 */
class SettingsRepositoryTest {

    private class FakeStore(
        initial: Map<String, SettingValue> = emptyMap()
    ) : KeyValueStore {
        private val state = MutableStateFlow(initial)
        override val values: Flow<Map<String, SettingValue>> = state

        override suspend fun put(entries: Map<String, SettingValue>) {
            state.value = state.value + entries
        }

        fun raw(): Map<String, SettingValue> = state.value
    }

    @Test
    fun `an empty store yields the built-in defaults`() = runTest {
        val repository = SettingsRepository(FakeStore())
        assertEquals(ApiConfig(), repository.config.first())
        assertEquals(CatPersona(), repository.persona.first())
        assertTrue(repository.locationEnabled.first())
    }

    /** 旧版本只存过 Base URL，没有 providerId：必须按地址反推出来。 */
    @Test
    fun `a missing provider id is inferred from the base url`() = runTest {
        val store = FakeStore(
            mapOf(SettingsKeys.BASE_URL to SettingValue.Str("https://api.deepseek.com/v1"))
        )
        assertEquals("deepseek", SettingsRepository(store).config.first().providerId)
    }

    @Test
    fun `an unknown base url falls back to the custom provider`() = runTest {
        val store = FakeStore(
            mapOf(SettingsKeys.BASE_URL to SettingValue.Str("https://example.com/v1"))
        )
        assertEquals(CUSTOM_PROVIDER_ID, SettingsRepository(store).config.first().providerId)
    }

    @Test
    fun `config survives a save and reload`() = runTest {
        val store = FakeStore()
        val repository = SettingsRepository(store)
        val config = ApiConfig(
            providerId = "openai",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "  sk-abc  ",
            model = " gpt-5.5 ",
            temperature = 0.3f,
            topP = 0.5f,
            maxTokens = 2048,
            thinking = ThinkingMode.ON,
            reasoningEffort = ReasoningEffort.HIGH
        )
        repository.save(config)

        val loaded = repository.config.first()
        // 保存时裁剪空白，和旧实现一致。
        assertEquals("sk-abc", loaded.apiKey)
        assertEquals("gpt-5.5", loaded.model)
        assertEquals(0.3f, loaded.temperature)
        assertEquals(0.5f, loaded.topP)
        assertEquals(2048, loaded.maxTokens)
        assertEquals(ThinkingMode.ON, loaded.thinking)
        assertEquals(ReasoningEffort.HIGH, loaded.reasoningEffort)
        assertEquals("openai", loaded.providerId)
    }

    @Test
    fun `persona survives a save and reload`() = runTest {
        val store = FakeStore()
        val repository = SettingsRepository(store)
        val persona = CatPersona(
            name = " 小白 ",
            traits = setOf(CatTrait.WITTY, CatTrait.LAZY),
            speechStyle = CatSpeechStyle.CONCISE,
            flavor = CatFlavor.CAT,
            notes = " 叫我主人 "
        )
        repository.save(persona)

        val loaded = repository.persona.first()
        assertEquals("小白", loaded.name)
        assertEquals(setOf(CatTrait.WITTY, CatTrait.LAZY), loaded.traits)
        assertEquals(CatSpeechStyle.CONCISE, loaded.speechStyle)
        assertEquals(CatFlavor.CAT, loaded.flavor)
        assertEquals("叫我主人", loaded.notes)
    }

    @Test
    fun `an unrecognised enum name falls back instead of throwing`() = runTest {
        val store = FakeStore(
            mapOf(
                SettingsKeys.THINKING to SettingValue.Str("SOMETHING_ELSE"),
                SettingsKeys.CAT_FLAVOR to SettingValue.Str("NOPE")
            )
        )
        val repository = SettingsRepository(store)
        assertEquals(ThinkingMode.AUTO, repository.config.first().thinking)
        assertEquals(CatPersona().flavor, repository.persona.first().flavor)
    }

    @Test
    fun `location can be turned off and stays off`() = runTest {
        val store = FakeStore()
        val repository = SettingsRepository(store)
        repository.saveLocationEnabled(false)
        assertTrue(!repository.locationEnabled.first())
    }

    /** 键名是跨端契约：换存储实现时这些字符串不能变。 */
    @Test
    fun `settings are written under the canonical key names`() = runTest {
        val store = FakeStore()
        val repository = SettingsRepository(store)
        repository.save(ApiConfig())
        repository.save(CatPersona())
        repository.saveLocationEnabled(true)

        val written = store.raw().keys
        listOf(
            SettingsKeys.BASE_URL, SettingsKeys.API_KEY, SettingsKeys.MODEL,
            SettingsKeys.TEMPERATURE, SettingsKeys.TOP_P, SettingsKeys.MAX_TOKENS,
            SettingsKeys.THINKING, SettingsKeys.REASONING_EFFORT, SettingsKeys.PROVIDER_ID,
            SettingsKeys.CAT_NAME, SettingsKeys.CAT_TRAITS, SettingsKeys.CAT_SPEECH_STYLE,
            SettingsKeys.CAT_FLAVOR, SettingsKeys.CAT_NOTES, SettingsKeys.LOCATION_ENABLED
        ).forEach { assertTrue(it in written, "缺少设置键 $it") }
    }
}
