package com.codingcow.ikitty

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 记忆的磁盘表示。
 *
 * 刻意用显式 builder 风格而不是 `@Serializable`：写出哪些键、条件写哪些字段，
 * 都由代码直接决定，不经过注解处理器，格式才可逐字节核对。
 *
 * 这一层暂时留在 androidMain —— Phase 2 会把它替换成 commonMain 的 JSON 层，
 * 让 iOS 复用同一份落盘逻辑。落盘键名在迁移期间**不得改动**。
 */

fun MemoryFact.toJson(): JSONObject = JSONObject().apply {
    put("category", category.name)
    put("key", key)
    put("value", value)
    put("at", updatedAt)
    put("seq", sourceSeq)
    if (pinned) put("pinned", true)
}

/** 解析一条记忆；key 或 value 为空视为脏数据，返回 null。 */
fun parseMemoryFact(obj: JSONObject): MemoryFact? {
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
                val fact = parseMemoryFact(item) ?: continue
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
