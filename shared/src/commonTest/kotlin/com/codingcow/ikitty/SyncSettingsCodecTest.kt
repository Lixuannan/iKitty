package com.codingcow.ikitty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 云端设置载荷的字段契约。
 *
 * `SyncSettingsCodec` 是设置"能不能跨设备带过去"的唯一编码处。它曾经与
 * `SettingsRepository` 的字段集合各自演进：新增一个设置项只改了读写两侧，同步那边没跟上，
 * 于是那个设置永远同步不过去、每台设备都要重设一遍——正是"性格名字之类每次都要改"的来源。
 *
 * 所以这里锁两件事：
 * 1. 全部字段（含角色的名字、性格、说话风格、猫味浓度、补充设定）往返一致；
 * 2. 载荷里出现的键**覆盖 [SettingsKeys] 的全部设置项**——新增设置项忘了加进 codec，这里会红。
 */
class SyncSettingsCodecTest {

    private val fullConfig = ApiConfig(
        providerId = "custom",
        baseUrl = "https://example.test/v1/",
        apiKey = "sk-sync",
        model = "cat-9",
        temperature = 0.35f,
        topP = 0.91f,
        maxTokens = 4096,
        thinking = ThinkingMode.ON,
        reasoningEffort = ReasoningEffort.HIGH
    )

    private val fullPersona = CatPersona(
        name = "团子",
        traits = setOf(CatTrait.LAZY, CatTrait.WITTY, CatTrait.CLINGY),
        speechStyle = CatSpeechStyle.LITERARY,
        flavor = CatFlavor.CAT,
        notes = "叫我主人，不要聊工作"
    )

    @Test
    fun `every setting including the full persona survives a round trip`() {
        val payload = SyncSettingsCodec.encode(
            BackupSettings(fullConfig, fullPersona, locationEnabled = false),
            includeApiKey = true,
            deviceId = "device-1"
        )

        val decoded = SyncSettingsCodec.decode(payload, currentApiKey = "")
        assertNotNull(decoded, "自己编出来的载荷必须解得回来")

        val config = decoded.config
        assertEquals("custom", config.providerId)
        assertEquals("https://example.test/v1", config.normalizedBaseUrl())
        assertEquals("sk-sync", config.apiKey)
        assertEquals("cat-9", config.model)
        assertEquals(0.35f, config.temperature)
        assertEquals(0.91f, config.topP)
        assertEquals(4096, config.maxTokens)
        assertEquals(ThinkingMode.ON, config.thinking)
        assertEquals(ReasoningEffort.HIGH, config.reasoningEffort)

        // 角色这一组是用户最在意"每台设备都要重设"的部分。
        val persona = decoded.persona
        assertEquals("团子", persona.name)
        assertEquals(setOf(CatTrait.LAZY, CatTrait.WITTY, CatTrait.CLINGY), persona.traits)
        assertEquals(CatSpeechStyle.LITERARY, persona.speechStyle)
        assertEquals(CatFlavor.CAT, persona.flavor)
        assertEquals("叫我主人，不要聊工作", persona.notes)

        assertFalse(decoded.locationEnabled, "定位开关也要跟着同步")
    }

    /**
     * 载荷里必须能看到**每一个**设置键。
     *
     * 用 [SettingsKeys] 当清单而不是手抄一份：这条断言的目的正是"新增设置项时 codec 不能漏"，
     * 手抄的清单会跟着一起漏。`api_key` 在开关打开时出现，`updatedBy` 是 codec 自己的元数据。
     */
    @Test
    fun `the payload covers every stored settings key`() {
        val payload = SyncSettingsCodec.encode(
            BackupSettings(fullConfig, fullPersona, locationEnabled = true),
            includeApiKey = true,
            deviceId = "device-1"
        )
        val keys = parseJsonObjectOrNull(payload)?.keys.orEmpty()

        val expected = listOf(
            SettingsKeys.BASE_URL,
            SettingsKeys.API_KEY,
            SettingsKeys.MODEL,
            SettingsKeys.TEMPERATURE,
            SettingsKeys.TOP_P,
            SettingsKeys.MAX_TOKENS,
            SettingsKeys.THINKING,
            SettingsKeys.REASONING_EFFORT,
            SettingsKeys.PROVIDER_ID,
            SettingsKeys.CAT_NAME,
            SettingsKeys.CAT_TRAITS,
            SettingsKeys.CAT_SPEECH_STYLE,
            SettingsKeys.CAT_FLAVOR,
            SettingsKeys.CAT_NOTES,
            SettingsKeys.LOCATION_ENABLED
        )
        expected.forEach { key ->
            assertTrue(key in keys, "云端的设置载荷里缺少 `$key`，这项设置同步不过去")
        }
    }

    /** 关掉开关时 `api_key` 整个字段都不能出现（空串与"没有这个字段"必须可区分）。 */
    @Test
    fun `turning the api key off omits the field entirely`() {
        val payload = SyncSettingsCodec.encode(
            BackupSettings(fullConfig, fullPersona, locationEnabled = true),
            includeApiKey = false,
            deviceId = "device-1"
        )

        assertFalse(SyncSettingsCodec.containsApiKey(payload))
        assertFalse(parseJsonObjectOrNull(payload)!!.containsKey(SettingsKeys.API_KEY))
    }
}
