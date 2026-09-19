package com.codingcow.ikitty

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 跨平台 JSON 读写。
 *
 * 刻意保持**显式 builder 风格**，不用 `@Serializable` 数据类：写出哪些键、条件写哪些字段
 * 都由代码直接决定，既不需要编译器插件，也让线上/落盘格式完全由这一层说了算。
 *
 * 读取侧的语义对齐原来的 `org.json`，因为换掉 JSON 库绝不能改变容错行为：
 * - 键不存在 / 值是 JSON null → 空串或 [fallback]，绝不抛异常；
 * - 数字/布尔按原文取值，取不出就回退；
 * - 整体不是合法 JSON → 返回 null，由调用方决定回退策略。
 *
 * 刻意不提供 `getXxx` 这类会抛异常的读取：现有代码全部是"容错解析"，
 * 提供一个会抛的入口只会诱使新代码破坏这个约定。
 */

/** 把一段文本解析成 JSON 对象；不是合法 JSON、或根不是对象时返回 null。 */
fun parseJsonObjectOrNull(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** 非空、非 JSON null 的原始值；对象/数组与 null 都返回 null。 */
private fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element as? JsonPrimitive
}

/** `org.json` 的 `optString(key)`：缺失或 JSON null 都是空串，**不裁剪空白**。 */
fun JsonObject.optString(key: String): String = primitiveOrNull(key)?.content.orEmpty()

/** 同 [optString]，但去掉首尾空白；线上字段统一用这个。 */
fun JsonObject.stringOrEmpty(key: String): String = optString(key).trim()

/** `org.json` 的 `optLong(key)`：数字、数字字符串都接受，取不出就是 0。 */
fun JsonObject.longOrZero(key: String): Long {
    val primitive = primitiveOrNull(key) ?: return 0L
    return primitive.longOrNull ?: primitive.content.toDoubleOrNull()?.toLong() ?: 0L
}

/** `org.json` 的 `optInt(key)`。 */
fun JsonObject.intOrZero(key: String): Int = longOrZero(key).toInt()

/** `org.json` 的 `optBoolean(key, fallback)`；大小写不敏感的 "true"/"false" 也认。 */
fun JsonObject.booleanOr(key: String, fallback: Boolean): Boolean {
    val primitive = primitiveOrNull(key) ?: return fallback
    primitive.booleanOrNull?.let { return it }
    return when (primitive.content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}

/** `org.json` 的 `optJSONObject(key)`；键不存在或不是对象都返回 null。 */
fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

/** `org.json` 的 `optJSONArray(key)`；键不存在或不是数组都返回 null。 */
fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

/** 把可能缺失的数组当成空列表，省掉调用方的 `?: emptyList()`。 */
fun JsonArray?.orEmptyList(): List<JsonElement> = this ?: emptyList()

/**
 * 把 Double 写成"整数不带小数点"的形式。
 *
 * `org.json` 的 `numberToString` 会把 `1.0` 写成 `1`，而 kotlinx 默认写成 `1.0`。
 * 请求体是线上契约，所以这里显式对齐旧实现的写法。
 */
fun jsonNumber(value: Double): JsonPrimitive =
    if (value.isFinite() && value == value.toLong().toDouble()) {
        JsonPrimitive(value.toLong())
    } else {
        JsonPrimitive(value)
    }

/** 数组元素里的非空字符串：JSON null、非字符串、空白项一律丢掉。 */
fun JsonArray?.stringItems(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (element in this@stringItems) {
            if (element is JsonNull) continue
            val name = (element as? JsonPrimitive)?.content?.trim().orEmpty()
            if (name.isNotEmpty()) add(name)
        }
    }
}
