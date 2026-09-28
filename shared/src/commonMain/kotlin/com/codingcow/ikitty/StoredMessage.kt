package com.codingcow.ikitty

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一条落盘的对话消息。
 *
 * [seq] 是会话内单调递增的序号，同时也是消息的稳定 id：排序不依赖墙钟，
 * 因为用户改系统时间或 NTP 校正都会让时间戳倒退。
 *
 * [localError] 标记本地生成的错误提示：它要出现在聊天流里，
 * 但不进请求上下文，也不参与记忆提取。
 *
 * [images] 是本机图片文件名（见 `ImageStore`），不是 URI：借用相册的 `content://`
 * URI 重启后会失效，所以图片先复制进应用私有目录，这里只记录文件名。
 *
 * [createdAt] 是 UTC 毫秒墙钟，界面上显示给人看，也作为时间前缀进请求正文（见 [contentForPrompt]）。
 * 排序与去重一律用 [seq]，墙钟可以被用户改表或 NTP 校正弄倒退。
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
     * 发给服务商时正文的内容：时间前缀 + 原文。
     *
     * 每条消息都带上自己是何时说的，模型才算得出「这轮距离上一轮多久」「昨天那句话是什么时候」，
     * 而不是只靠末尾那一行「现在」去猜——实测里长对话的失效模式正是模型沿用几轮前自己说过的时间。
     *
     * 前缀是绝对的、写完就不再变，所以进请求的历史字节逐轮稳定，提示词缓存前缀照样命中。
     * `createdAt <= 0`（老记录没有 `at` 字段）时不加前缀，免得把「1970 年」塞给模型。
     *
     * 只有图片没有文字的消息也会拿到前缀：正文变成纯时间，模型仍然知道这张图是什么时候发的。
     */
    fun contentForPrompt(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
        if (createdAt <= 0L) return content
        val stamp = formatMessageStamp(createdAt, timeZone)
        return if (content.isEmpty()) stamp else "$stamp $content"
    }

    /**
     * 转换成发给服务商的格式。元数据只留在这边，不会漏进请求体。
     *
     * [imageUrl] 把本机文件名解析成数据 URL；解析不到（文件已被删）的图片直接跳过，
     * 于是删掉图片不会让历史消息无法发送。
     *
     * [timeZone] 只是为了测试可注入；生产路径用系统时区，两端因此一致。
     */
    fun toWire(
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        imageUrl: (String) -> String? = { null }
    ): ChatMessage =
        ChatMessage(role, contentForPrompt(timeZone), images.mapNotNull(imageUrl))

    /**
     * - 有图片才写 `images`；
     * - 只有 [localError] 为真才写 `error`。
     *
     * 这两条条件写入是落盘契约的一部分：多写一个 `false` 会让老版本的解析结果不变，
     * 但会让归档文件的字节发生变化，所以保持原样。
     */
    fun toJson(): JsonObject = buildJsonObject {
        put("seq", seq)
        put("role", role)
        put("content", content)
        put("at", createdAt)
        if (localError) put("error", true)
        if (images.isNotEmpty()) {
            put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
        }
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 解析 JSONL 里的一行；坏行返回 null，调用方跳过即可。 */
        fun fromJson(line: String): StoredMessage? {
            val obj = parseJsonObjectOrNull(line) ?: return null
            val role = obj.optString("role")
            val content = obj.optString("content")
            val images = obj.arrayOrNull("images").stringItems()
            // 纯文字消息要求 content 非空；图片消息允许只说图片不带文字。
            if (role.isEmpty() || (content.isEmpty() && images.isEmpty())) return null
            return StoredMessage(
                seq = obj.longOrZero("seq"),
                role = role,
                content = content,
                createdAt = obj.longOrZero("at"),
                localError = obj.booleanOr("error", false),
                images = images
            )
        }
    }
}
