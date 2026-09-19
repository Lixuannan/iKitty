package com.codingcow.ikitty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设目录的契约。
 *
 * 这些断言保护的是"设置页默认模型"这件事本身：每个预设的默认模型都必须能解析到
 * 自己的内置能力条目，而不是悄悄掉进通用兜底。
 */
class ModelCatalogTest {

    private val expectedProviders = listOf(
        "zhipu", "zai", "deepseek", "openai", "anthropic", "google", "xai", "dashscope",
        "moonshot", "minimax", "doubao", "hunyuan", "ernie", "mistral", "siliconflow",
        "openrouter", "ollama", CUSTOM_PROVIDER_ID
    )

    @Test
    fun `每个预设的默认模型都有属于自己的内置能力表`() {
        ModelCatalog.providers
            .filter { it.models.isNotEmpty() }
            .forEach { provider ->
                val spec = ModelCatalog.resolve(provider.id, provider.defaultModel)
                assertEquals(
                    "${provider.id} 的默认模型掉进了通用兜底",
                    provider.id,
                    spec.providerId
                )
                assertTrue("${provider.id} 窗口非正", spec.contextWindow > 0)
                assertTrue(
                    "${provider.id} 仍在使用通用兜底说明",
                    spec.note?.contains("没有该模型的内置参数表") != true
                )
            }
    }

    @Test
    fun `预设清单覆盖表里的全部厂商`() {
        val ids = ModelCatalog.providers.map { it.id }
        expectedProviders.forEach { assertTrue("缺少预设 $it", it in ids) }
    }

    @Test
    fun `新增预设都有可用的 Base URL 并能反查回自己`() {
        ModelCatalog.providers
            .filter { it.id != CUSTOM_PROVIDER_ID }
            .forEach { provider ->
                assertTrue("${provider.id} 没有 Base URL", provider.baseUrl.isNotBlank())
                assertEquals(provider.id, ModelCatalog.providerIdForBaseUrl(provider.baseUrl))
            }
    }

    @Test
    fun `glm-5_3 改用 reasoning_effort 且窗口为 1M`() {
        val spec = ModelCatalog.resolve("zhipu", "glm-5.3")
        assertEquals(1_000_000, spec.contextWindow)
        assertTrue(spec.reasoning is ReasoningSpec.Effort)
        assertNotNull(spec.temperature)
    }

    @Test
    fun `名称启发式把 glm-5 系列也认成 reasoning_effort`() {
        val spec = ModelCatalog.resolve("zhipu", "glm-5.9-preview")
        assertTrue(spec.reasoning is ReasoningSpec.Effort)
    }

    @Test
    fun `gpt-5_5 是推理模型，只发 reasoning_effort`() {
        val spec = ModelCatalog.resolve("openai", "gpt-5.5")
        assertEquals(null, spec.temperature)
        assertEquals(null, spec.topP)
        assertTrue(spec.reasoning is ReasoningSpec.Effort)
    }

    @Test
    fun `Meta 的 Llama 经聚合平台与本地运行时接入`() {
        assertEquals(
            "meta-llama/llama-4-maverick",
            ModelCatalog.provider("openrouter").defaultModel
        )
        assertTrue(ModelCatalog.provider("ollama").models.contains("llama4:maverick"))
    }

    @Test
    fun `应用默认使用 DeepSeek 的 deepseek-flash`() {
        val defaults = ApiConfig()
        assertEquals("deepseek", defaults.providerId)
        assertEquals("deepseek-flash", defaults.model)
        assertEquals(ModelCatalog.provider("deepseek").baseUrl, defaults.baseUrl)
        // 预设清单第一条就是默认模型，切回该服务商时不会跳回另一个模型。
        assertEquals("deepseek-flash", ModelCatalog.provider("deepseek").defaultModel)
    }

    @Test
    fun `deepseek-flash 可以调整思考深度`() {
        val spec = ModelCatalog.resolve("deepseek", "deepseek-flash")
        assertTrue(spec.reasoning is ReasoningSpec.Effort)
        assertTrue(spec.supportedParamNames.contains("reasoning_effort"))
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            (spec.reasoning as ReasoningSpec.Effort).supported
        )

        val config = ApiConfig()
        // 默认档位是「关闭」，请求里不出现 reasoning_effort。
        assertEquals(ReasoningEffort.OFF, config.resolvedFor(spec).reasoningEffort)
        // 三档都照发。
        ReasoningEffort.entries
            .filter { it != ReasoningEffort.OFF }
            .forEach { level ->
                assertEquals(
                    level,
                    config.copy(reasoningEffort = level).resolvedFor(spec).reasoningEffort
                )
            }
    }

    @Test
    fun `旧模型没有内置条目时仍然能解析出可用参数`() {
        val spec = ModelCatalog.resolve("openai", "gpt-4o-mini")
        assertNotNull(spec.temperature)
        assertNotNull(spec.maxTokens)
        assertTrue(spec.note!!.contains("没有该模型的内置参数表"))
    }
}
