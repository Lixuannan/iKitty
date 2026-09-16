package com.codingcow.ikitty

import org.json.JSONArray
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
 *
 * [images] 是本机图片文件名（见 [ImageStore]），不是 URI：借用相册的 `content://`
 * URI 重启后会失效，所以图片先复制进应用私有目录，这里只记录文件名。
 */
data class StoredMessage(
    val seq: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val localError: Boolean = false,
    val images: List<String> = emptyList()
) {
    /**
     * 转换成发给服务商的格式。元数据只留在这边，不会漏进请求体。
     *
     * [imageUrl] 把本机文件名解析成数据 URL；解析不到（文件已被删）的图片直接跳过，
     * 于是删掉图片不会让历史消息无法发送。
     */
    fun toWire(imageUrl: (String) -> String? = { null }): ChatMessage =
        ChatMessage(role, content, images.mapNotNull(imageUrl))

    fun toJson(): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("role", role)
        put("content", content)
        put("at", createdAt)
        if (localError) put("error", true)
        if (images.isNotEmpty()) {
            put("images", JSONArray().apply { images.forEach { put(it) } })
        }
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 解析 JSONL 里的一行；坏行返回 null，调用方跳过即可。 */
        fun fromJson(line: String): StoredMessage? = try {
            val obj = JSONObject(line)
            val role = obj.optString("role")
            val content = obj.optString("content")
            val images = obj.optJSONArray("images").toStringList()
            // 纯文字消息要求 content 非空；图片消息允许只说图片不带文字。
            if (role.isEmpty() || (content.isEmpty() && images.isEmpty())) {
                null
            } else {
                StoredMessage(
                    seq = obj.optLong("seq"),
                    role = role,
                    content = content,
                    createdAt = obj.optLong("at"),
                    localError = obj.optBoolean("error", false),
                    images = images
                )
            }
        } catch (_: JSONException) {
            null
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (index in 0 until length()) {
                    val name = optString(index).trim()
                    if (name.isNotEmpty()) add(name)
                }
            }
        }
    }
}
