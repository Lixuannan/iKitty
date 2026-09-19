package com.codingcow.ikitty

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 记忆的磁盘表示。
 *
 * 与回复解析共用 [JsonSupport] 的显式 builder 风格：写出哪些键、条件写哪些字段
 * 都由代码直接决定，不经过注解处理器，格式才可逐字节核对。
 *
 * 落盘键名是跨平台契约：iOS 与 Android 必须能互读同一份 `chat/cat_memory.json`。
 */

fun MemoryFact.toJson(): JsonObject = buildJsonObject {
    put("category", category.name)
    put("key", key)
    put("value", value)
    put("at", updatedAt)
    put("seq", sourceSeq)
    if (pinned) put("pinned", true)
}

/** 解析一条记忆；key 或 value 为空视为脏数据，返回 null。 */
fun parseMemoryFact(obj: JsonObject): MemoryFact? {
    val key = obj.stringOrEmpty("key")
    val value = obj.stringOrEmpty("value")
    if (key.isEmpty() || value.isEmpty()) return null
    return MemoryFact(
        category = MemoryCategory.fromName(obj.optString("category")),
        key = key,
        value = value,
        updatedAt = obj.longOrZero("at"),
        sourceSeq = obj.longOrZero("seq"),
        pinned = obj.booleanOr("pinned", false)
    )
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
    val obj = parseJsonObjectOrNull(json) ?: return null
    val facts = obj.arrayOrNull("facts").orEmptyList().mapNotNull { element ->
        (element as? JsonObject)?.let { parseMemoryFact(it)?.copy(updatedAt = now) }
    }
    val forget = obj.arrayOrNull("forget").stringItems().toSet()
    return MemoryUpdate(facts, forget)
}

/** 记忆文件的正文；键名即落盘契约。 */
fun encodeCatMemory(memory: CatMemory): JsonObject = buildJsonObject {
    put("version", MEMORY_FILE_VERSION)
    put("lastExtractedSeq", memory.lastExtractedSeq)
    put("lastExtractedAt", memory.lastExtractedAt)
    put("facts", buildJsonArray { memory.facts.forEach { add(it.toJson()) } })
}

/** 解析记忆文件的正文；文件损坏（不是 JSON）返回 null，由调用方决定回退策略。 */
fun parseCatMemory(text: String): CatMemory? {
    val obj = parseJsonObjectOrNull(text) ?: return null
    val facts = obj.arrayOrNull("facts").orEmptyList().mapNotNull { element ->
        (element as? JsonObject)?.let { parseMemoryFact(it) }
    }
    return CatMemory(
        facts = facts,
        lastExtractedSeq = obj.longOrZero("lastExtractedSeq"),
        lastExtractedAt = obj.longOrZero("lastExtractedAt")
    )
}

/** 记忆文件格式版本；改动落盘结构时必须一起改。 */
const val MEMORY_FILE_VERSION = 1
