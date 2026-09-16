package com.codingcow.ikitty

import org.json.JSONException
import org.json.JSONObject

/**
 * AI 回复解析结果。
 *
 * [mood] / [animation] 为 null 表示模型没有提供（此时由调用方自行决定默认值）。
 */
data class CatReply(
    val text: String,
    val mood: CatMood?,
    val animation: CatAnimation?
)

private val EMOTION_TABLE: Map<String, CatMood> = mapOf(
    "neutral" to CatMood.IDLE,
    "idle" to CatMood.IDLE,
    "calm" to CatMood.IDLE,
    "listening" to CatMood.LISTENING,
    "thinking" to CatMood.THINKING,
    "curious" to CatMood.THINKING,
    "happy" to CatMood.HAPPY,
    "joy" to CatMood.HAPPY,
    "glad" to CatMood.HAPPY,
    "sad" to CatMood.SAD,
    "down" to CatMood.SAD,
    "excited" to CatMood.EXCITED,
    "sleepy" to CatMood.SLEEPY,
    "tired" to CatMood.SLEEPY
)

private val ANIMATION_TABLE: Map<String, CatAnimation> = mapOf(
    "none" to CatAnimation.NONE,
    "blink" to CatAnimation.BLINK,
    "look_around" to CatAnimation.LOOK_AROUND,
    "lookaround" to CatAnimation.LOOK_AROUND,
    "look-around" to CatAnimation.LOOK_AROUND,
    "tail_wag" to CatAnimation.TAIL_WAG,
    "tailwag" to CatAnimation.TAIL_WAG,
    "tail-wag" to CatAnimation.TAIL_WAG,
    "bounce" to CatAnimation.BOUNCE,
    "jump" to CatAnimation.BOUNCE,
    "shake" to CatAnimation.SHAKE,
    "yawn" to CatAnimation.YAWN
)

/**
 * 解析模型返回的原始文本。
 *
 * 依次兼容：普通 JSON、``` 代码块包裹的 JSON、以及纯文本。
 * 任何解析问题都退回纯文本，绝不抛异常。
 */
fun parseCatReply(raw: String): CatReply {
    val plain = stripCodeFence(raw)
    val json = extractJsonObject(plain) ?: return CatReply(plain, null, null)
    return try {
        val obj = JSONObject(json)
        val text = obj.optString("reply").trim()
        if (text.isEmpty()) {
            CatReply(plain, null, null)
        } else {
            CatReply(text, moodOf(obj.optString("emotion")), animationOf(obj.optString("animation")))
        }
    } catch (_: JSONException) {
        CatReply(plain, null, null)
    }
}

/** 去掉 ```json ... ``` 之类的代码块围栏。 */
internal fun stripCodeFence(raw: String): String {
    var body = raw.trim()
    if (!body.startsWith("```")) return body

    val firstNewline = body.indexOf('\n')
    body = if (firstNewline >= 0) body.substring(firstNewline + 1) else body.removePrefix("```")
    val closingFence = body.lastIndexOf("```")
    if (closingFence >= 0) body = body.substring(0, closingFence)
    return body.trim()
}

internal fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return text.substring(start, end + 1)
}

/** 空字符串表示模型没给这个字段。 */
private fun moodOf(value: String): CatMood? {
    val key = value.trim().lowercase()
    if (key.isEmpty()) return null
    return EMOTION_TABLE[key] ?: CatMood.HAPPY
}

/** 空字符串表示模型没给这个字段；给了但不认识就用 NONE。 */
private fun animationOf(value: String): CatAnimation? {
    val key = value.trim().lowercase()
    if (key.isEmpty()) return null
    return ANIMATION_TABLE[key] ?: CatAnimation.NONE
}
