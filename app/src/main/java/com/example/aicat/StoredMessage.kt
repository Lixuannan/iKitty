package com.example.aicat

import org.json.JSONException
import org.json.JSONObject

/**
 * 一条落盘的对话消息。
 *
 * [seq] 是会话内单调递增的序号，同时也是消息的稳定 id：排序不依赖墙钟，
 * 因为用户改系统时间或 NTP 校正都会让时间戳倒退。
 *
 * [localError] 标记本地生成的错误提示：它要出现在聊天流里，
 * 但不进请求上下文，也不参与记忆提取。
 */
data class StoredMessage(
    val seq: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val localError: Boolean = false
) {
    /** 转换成发给服务商的格式。元数据只留在这边，不会漏进请求体。 */
    fun toWire(): ChatMessage = ChatMessage(role, content)

    fun toJson(): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("role", role)
        put("content", content)
        put("at", createdAt)
        if (localError) put("error", true)
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 解析 JSONL 里的一行；坏行返回 null，调用方跳过即可。 */
        fun fromJson(line: String): StoredMessage? = try {
            val obj = JSONObject(line)
            val role = obj.optString("role")
            val content = obj.optString("content")
            if (role.isEmpty() || content.isEmpty()) {
                null
            } else {
                StoredMessage(
                    seq = obj.optLong("seq"),
                    role = role,
                    content = content,
                    createdAt = obj.optLong("at"),
                    localError = obj.optBoolean("error", false)
                )
            }
        } catch (_: JSONException) {
            null
        }
    }
}
