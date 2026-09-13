package com.example.aicat

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 结构化记忆的分类。
 *
 * 分类是固定枚举而不是自由文本：提示词、界面分组、合并规则都由这一份定义派生，
 * 模型返回不认识的值时统一落到 [SITUATION]。
 */
enum class MemoryCategory(val label: String, val prompt: String) {
    OWNER("主人", "主人的身份、称呼、基本情况"),
    PREFERENCE("喜好", "主人喜欢或讨厌的东西"),
    RELATIONSHIP("关系", "主人和猫猫之间的称呼与相处方式"),
    EXPERIENCE("经历", "一起经历过的重要事情"),
    SITUATION("近况", "主人最近持续中的状态，例如在准备考试、刚换了工作");

    companion object {
        /** 同时接受枚举名和中文标签，模型两种都可能返回。 */
        fun fromName(raw: String?): MemoryCategory {
            val key = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
                ?: entries.firstOrNull { it.label == key }
                ?: SITUATION
        }
    }
}

/**
 * 一条结构化记忆。
 *
 * [key] 是同一件事的稳定标识，也是合并时的去重依据：模型说"主人的猫叫咪咪"，
 * 下一次说"主人养的猫叫咪咪"，只要 key 都是「猫的名字」就会覆盖而不是堆积。
 */
data class MemoryFact(
    val category: MemoryCategory,
    val key: String,
    val value: String,
    val updatedAt: Long,
    val sourceSeq: Long = 0L,
    val pinned: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("category", category.name)
        put("key", key)
        put("value", value)
        put("at", updatedAt)
        put("seq", sourceSeq)
        if (pinned) put("pinned", true)
    }

    companion object {
        /** 解析一条记忆；key 或 value 为空视为脏数据，返回 null。 */
        fun fromJson(obj: JSONObject): MemoryFact? {
            val key = obj.optString("key").trim()
            val value = obj.optString("value").trim()
            if (key.isEmpty() || value.isEmpty()) return null
            return MemoryFact(
                category = MemoryCategory.fromName(obj.optString("category")),
                key = key,
                value = value,
                updatedAt = obj.optLong("at"),
                sourceSeq = obj.optLong("seq"),
                pinned = obj.optBoolean("pinned", false)
            )
        }
    }
}

/** 全部记忆：事实列表 + "已经整理到哪一条消息"。 */
data class CatMemory(
    val facts: List<MemoryFact> = emptyList(),
    val lastExtractedSeq: Long = 0L,
    val lastExtractedAt: Long = 0L
)

/** 记忆整理的状态，界面只读。 */
data class MemoryStatus(
    val running: Boolean = false,
    val lastRunAt: Long = 0L,
    val lastError: String? = null
)

/** 模型返回的一次记忆更新：要写入的事实 + 要忘掉的 key。 */
data class MemoryUpdate(
    val facts: List<MemoryFact>,
    val forget: Set<String>
)

/**
 * 结构化记忆的合并规则。
 *
 * 最关键的一条是**只增不减**：模型没有提到的旧事实一律保留，删除只能靠显式的
 * forget 列表或用户手动操作。这样即使某次整理只返回了半截 JSON，
 * 最坏结果也只是"这次没记住新的"，而不是把已经记住的事丢掉。
 */
object CatMemoryRules {

    /** 记忆条数上限，超出后按"最久没更新"淘汰未固定的条目。 */
    const val MAX_FACTS = 60
    const val MAX_KEY_CHARS = 12
    const val MAX_VALUE_CHARS = 60

    fun merge(
        existing: List<MemoryFact>,
        incoming: List<MemoryFact>,
        forget: Set<String>,
        now: Long
    ): List<MemoryFact> {
        val forgotten = forget.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val result = existing.filter { it.key !in forgotten || it.pinned }.toMutableList()

        incoming.forEach { raw ->
            val fact = raw.sanitized(now) ?: return@forEach
            val index = result.indexOfFirst { it.key == fact.key }
            if (index < 0) {
                result += fact
            } else {
                val old = result[index]
                // 值没变就原样保留，避免每次整理都把 updatedAt 刷新一遍：
                // 否则一个老事实会永远排在最"新"的位置，逃过淘汰。
                result[index] = if (old.category == fact.category && old.value == fact.value) {
                    old
                } else {
                    // 时间戳由这次合并决定，不采信传入的值：模型不会给准确的时间。
                    fact.copy(pinned = old.pinned, updatedAt = now)
                }
            }
        }
        return evict(result)
    }

    /** 用户在记忆页手动新增或修改一条；[originalKey] 非空表示这次是编辑。 */
    fun upsert(
        facts: List<MemoryFact>,
        originalKey: String?,
        draft: MemoryFact,
        now: Long
    ): List<MemoryFact> {
        val base = originalKey?.let { key -> facts.filter { it.key != key } } ?: facts
        val fact = draft.sanitized(now) ?: return facts
        val existing = base.firstOrNull { it.key == fact.key }
        val merged = if (existing == null) {
            fact
        } else {
            fact.copy(pinned = existing.pinned)
        }
        return evict(base.filter { it.key != merged.key } + merged)
    }

    fun remove(facts: List<MemoryFact>, key: String): List<MemoryFact> =
        facts.filter { it.key != key }

    fun togglePin(facts: List<MemoryFact>, key: String): List<MemoryFact> =
        facts.map { if (it.key == key) it.copy(pinned = !it.pinned) else it }

    private fun MemoryFact.sanitized(now: Long): MemoryFact? {
        val cleanKey = key.trim().take(MAX_KEY_CHARS)
        val cleanValue = value.replace('\n', ' ').trim().take(MAX_VALUE_CHARS)
        if (cleanKey.isEmpty() || cleanValue.isEmpty()) return null
        return copy(
            key = cleanKey,
            value = cleanValue,
            updatedAt = if (updatedAt > 0L) updatedAt else now
        )
    }

    /** 超出上限时优先淘汰"最久没更新且没有被固定"的条目。 */
    private fun evict(facts: List<MemoryFact>): List<MemoryFact> {
        if (facts.size <= MAX_FACTS) return facts
        val doomed = facts.filter { !it.pinned }
            .sortedBy { it.updatedAt }
            .take(facts.size - MAX_FACTS)
            .map { it.key }
            .toSet()
        // 全部都被用户固定时宁可超出上限，也不静默丢掉用户明确要保留的东西。
        if (doomed.isEmpty()) return facts
        return facts.filter { it.key !in doomed }
    }
}

/** 把结构化记忆渲染成注入 system prompt 的一段文字。 */
object CatMemoryRender {

    fun block(facts: List<MemoryFact>): String {
        if (facts.isEmpty()) return ""
        return buildString {
            appendLine("【你记得的事】")
            MemoryCategory.entries.forEach { category ->
                val group = facts.filter { it.category == category }
                if (group.isEmpty()) return@forEach
                appendLine("${category.label}：")
                group.forEach { appendLine("- ${it.key}：${it.value}") }
            }
            appendLine()
            append("这些是长期记忆。自然地用起来，不要逐条复述，也不要说「根据我的记忆」。")
        }
    }
}

/**
 * 解析记忆整理请求的返回。
 *
 * 依次兼容纯 JSON、``` 包裹的 JSON、以及前后带废话的 JSON。
 * 任何解析失败都返回 null，由调用方保留旧记忆——绝不用半截结果覆盖。
 */
fun parseMemoryUpdate(raw: String, now: Long): MemoryUpdate? {
    val plain = stripCodeFence(raw)
    val json = extractJsonObject(plain) ?: return null
    return try {
        val obj = JSONObject(json)
        val facts = buildList {
            val array = obj.optJSONArray("facts") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val fact = MemoryFact.fromJson(item) ?: continue
                add(fact.copy(updatedAt = now))
            }
        }
        val forget = buildSet {
            val array = obj.optJSONArray("forget") ?: JSONArray()
            for (index in 0 until array.length()) {
                val key = array.optString(index).trim()
                if (key.isNotEmpty()) add(key)
            }
        }
        MemoryUpdate(facts, forget)
    } catch (_: JSONException) {
        null
    }
}
