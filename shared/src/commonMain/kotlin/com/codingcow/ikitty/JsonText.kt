package com.codingcow.ikitty

/**
 * 从模型输出里把 JSON 抠出来的纯文本处理。
 *
 * 与具体 JSON 库无关，所以两条链路（回复解析、记忆整理）共用同一份实现，
 * 避免"某一边能容错、另一边不能"的漂移。
 */

/** 去掉 ```json ... ``` 之类的代码块围栏。 */
fun stripCodeFence(raw: String): String {
    var body = raw.trim()
    if (!body.startsWith("```")) return body

    val firstNewline = body.indexOf('\n')
    body = if (firstNewline >= 0) body.substring(firstNewline + 1) else body.removePrefix("```")
    val closingFence = body.lastIndexOf("```")
    if (closingFence >= 0) body = body.substring(0, closingFence)
    return body.trim()
}

/** 取第一个 `{` 到最后一个 `}` 之间的内容；找不到成对括号时返回 null。 */
fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return text.substring(start, end + 1)
}
