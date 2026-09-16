package com.codingcow.ikitty

/**
 * 结构化记忆的整理器。
 *
 * 整理请求复用用户配置的模型和参数（不额外造一套配置），但换成一份专门的提示词：
 * 只让它从最近的对话里抽取长期事实并输出一小段 JSON，而不是像猫猫那样说话。
 *
 * 整理失败一律抛出，由调用方保留旧记忆。宁可这次没记住，也不要用半截结果覆盖。
 */
class MemoryExtractor(private val api: ApiClient) {

    suspend fun extract(
        config: ApiConfig,
        memory: CatMemory,
        recent: List<StoredMessage>,
        now: Long
    ): MemoryUpdate {
        val completion = api.chat(
            config,
            listOf(
                ChatMessage("system", SYSTEM_PROMPT),
                ChatMessage("user", userPrompt(memory, recent, now))
            )
        )
        return parseMemoryUpdate(completion.text, now)
            ?: throw ApiException("记忆整理没有返回可用的 JSON")
    }

    private fun userPrompt(
        memory: CatMemory,
        recent: List<StoredMessage>,
        now: Long
    ): String = buildString {
        appendLine("【当前时间】")
        appendLine(formatMoment(now))
        appendLine()
        appendLine("【已有记忆】")
        if (memory.facts.isEmpty()) {
            appendLine("（还没有任何记忆）")
        } else {
            memory.facts.forEach { appendLine("- [${it.category.label}] ${it.key}：${it.value}") }
        }
        appendLine()
        appendLine("【最近的对话】")
        recent.forEach { message ->
            val speaker = if (message.role == StoredMessage.ROLE_USER) "主人" else "猫猫"
            appendLine("$speaker：${message.content.replace('\n', ' ')}")
        }
        appendLine()
        append("""只输出需要新增或修改的条目；没有变化就返回 {"facts":[],"forget":[]}。""")
    }

    private companion object {
        val SYSTEM_PROMPT = """
            你是一个记忆整理助手，负责维护一份关于「主人」的结构化长期记忆。
            你会看到已有记忆和最近的对话，请输出需要新增或修改的条目。

            规则：
            - 只记长期有效的事实：身份、称呼、喜好、习惯、关系、重要经历、约定、持续中的状态。
            - 不记一次性的寒暄、当下的情绪、猫猫自己说过的话，也不记你推测出来的内容。
            - key 是同一件事的稳定标识，2 到 6 个汉字；同一件事必须复用已有记忆里的 key。
            - value 是一句简短中文，不超过 40 字，只写结论不写经过。
            - 已有记忆里没提到、但依然成立的条目不要重复输出。
            - 只有确认已经不再成立时，才把对应的 key 放进 forget。

            只输出 JSON，不要解释，不要代码块：
            {"facts":[{"category":"主人|喜好|关系|经历|近况","key":"…","value":"…"}],"forget":["要删除的key"]}
        """.trimIndent()
    }
}
