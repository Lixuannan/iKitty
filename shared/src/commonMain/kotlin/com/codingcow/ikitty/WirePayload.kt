package com.codingcow.ikitty

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 与请求体形状有关的全部逻辑。
 *
 * 这是**线上契约**所在：字段出现与否、数值精度、多模态结构都必须与 Android 旧实现
 * 以及 iOS 完全一致，所以它必须是同一份 commonMain 代码，不能两边各写一份。
 */

/** 服务端或网络层错误；[message] 已经是可以直接展示给用户的中文描述。 */
class ApiException(message: String) : Exception(message)

/** 待发送的请求体，以及本次实际写入的参数名（供"测试连接"展示）。 */
class BuiltRequest(val payload: JsonObject, val sentParams: List<String>)

/**
 * 按模型能力拼请求体：只有 [ModelSpec] 声明支持的参数才会出现。
 *
 * 图片走标准的 OpenAI 兼容多模态结构（`content` 数组里的 `image_url` + data URL），
 * 不针对任何一家服务商做特殊处理；纯文本消息的 `content` 仍然是普通字符串，
 * 与加入图片功能之前完全一致。
 *
 * 写入顺序刻意与旧实现一致：`org.json` 在 Android 上按插入顺序输出，
 * 保持同样的顺序才能让请求体逐字节不变。
 */
fun buildChatPayload(
    config: ApiConfig,
    spec: ModelSpec,
    messages: List<ChatMessage>,
    stream: Boolean
): BuiltRequest {
    val model = config.model.trim()
    if (model.isEmpty()) throw ApiException("请先填写模型名称")

    val resolved = config.resolvedFor(spec)
    val sent = mutableListOf<String>()

    val payload = buildJsonObject {
        put("model", model)

        resolved.temperature?.let { value ->
            val number = spec.temperature?.jsonNumber(value) ?: value.toDouble()
            put("temperature", jsonNumber(number))
            sent += "temperature"
        }
        resolved.topP?.let { value ->
            val number = spec.topP?.jsonNumber(value) ?: value.toDouble()
            put("top_p", jsonNumber(number))
            sent += "top_p"
        }
        resolved.maxTokens?.let {
            put("max_tokens", it)
            sent += "max_tokens"
        }

        when (spec.reasoning) {
            is ReasoningSpec.Toggle -> when (resolved.thinking) {
                ThinkingMode.AUTO -> Unit
                ThinkingMode.ON -> {
                    put("thinking", buildJsonObject { put("type", "enabled") })
                    sent += "thinking"
                }
                ThinkingMode.OFF -> {
                    put("thinking", buildJsonObject { put("type", "disabled") })
                    sent += "thinking"
                }
            }
            is ReasoningSpec.Effort -> resolved.reasoningEffort.wireValue?.let {
                put("reasoning_effort", it)
                sent += "reasoning_effort"
            }
            ReasoningSpec.AlwaysOn, ReasoningSpec.Unsupported -> Unit
        }

        if (stream) put("stream", true)

        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(
                    buildJsonObject {
                        put("role", message.role)
                        put("content", chatContent(message))
                    }
                )
            }
        })
    }

    return BuiltRequest(payload, sent)
}

/**
 * 一条消息在 `content` 字段里的取值。
 *
 * 没有图片时是纯字符串；有图片时是 `[{type:"text"},{type:"image_url"}]`。
 * 只有图片、没有文字时不写空 text 段——部分服务商会拒绝空的文本块。
 */
fun chatContent(message: ChatMessage): JsonElement {
    if (message.images.isEmpty()) return JsonPrimitive(message.content)
    return buildJsonArray {
        if (message.content.isNotBlank()) {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", message.content)
                }
            )
        }
        message.images.forEach { url ->
            add(
                buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", url) })
                }
            )
        }
    }
}
