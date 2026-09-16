package com.codingcow.ikitty

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * 追加式聊天记录：一条消息一行 JSON（JSONL）。
 *
 * 为什么不是 DataStore 或 Room：
 * - 追加一条消息就是一次 append，写入量和历史长度无关；DataStore 每次都要重写整个文件。
 * - 崩溃最多损坏最后一行，前面的记录仍然可读。
 * - 不引入 Room / KSP，构建保持现在的样子。
 *
 * 代价是查询能力弱，所以这里只提供聊天真正需要的两种读法：
 * 读末尾 N 条（界面），读某个序号之后的记录（记忆提取）。
 */
class ChatLogStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context, now: () -> Long = System::currentTimeMillis) :
        this(File(context.applicationContext.filesDir, FILE_PATH), now)

    /** 当前时间。由外部注入，测试里可以换成固定时钟。 */
    fun timestamp(): Long = now()

    suspend fun append(message: StoredMessage) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.appendText(message.toJson().toString() + "\n", StandardCharsets.UTF_8)
    }

    /** 读最后 [limit] 条。文件中间出现坏行只影响那一行，其余记录照常返回。 */
    suspend fun tail(limit: Int): List<StoredMessage> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        readTailLines(limit).mapNotNull { StoredMessage.fromJson(it) }
    }

    /** 读 [seq] 之后的记录，最多 [limit] 条。记忆提取用它消费"还没整理过"的消息。 */
    suspend fun readAfter(seq: Long, limit: Int): List<StoredMessage> = withContext(Dispatchers.IO) {
        if (limit <= 0 || !file.exists()) return@withContext emptyList()
        val result = mutableListOf<StoredMessage>()
        file.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val message = StoredMessage.fromJson(line) ?: continue
                if (message.seq <= seq) continue
                result += message
                if (result.size >= limit) break
            }
        }
        result
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        if (file.exists()) file.delete()
    }

    /**
     * 从文件末尾往前按块读，凑够 [limit] + 1 个换行就停。
     *
     * 这样"读最近 N 条"的耗时只和这 N 条的大小有关，
     * 不会因为文件里有几万条历史而变慢。
     */
    private fun readTailLines(limit: Int): List<String> {
        if (!file.exists()) return emptyList()
        val length = file.length()
        if (length == 0L) return emptyList()

        val blocks = ArrayDeque<ByteArray>()
        var newlines = 0
        var end = length
        RandomAccessFile(file, "r").use { raf ->
            while (end > 0 && newlines <= limit) {
                val size = minOf(CHUNK_BYTES.toLong(), end).toInt()
                val start = end - size
                val buffer = ByteArray(size)
                raf.seek(start)
                raf.readFully(buffer)
                blocks.addFirst(buffer)
                for (byte in buffer) if (byte == NEWLINE) newlines++
                end = start
            }
        }

        val text = buildString {
            // 必须先把字节拼起来再解码：一个 UTF-8 汉字可能横跨两个块，
            // 逐块解码会在边界上产生一串替换字符。
            val bytes = ByteArray(blocks.sumOf { it.size })
            var offset = 0
            blocks.forEach { block ->
                System.arraycopy(block, 0, bytes, offset, block.size)
                offset += block.size
            }
            append(String(bytes, StandardCharsets.UTF_8))
        }
        var lines = text.split('\n').filter { it.isNotBlank() }
        // 没读到文件开头，说明第一行是从中间截断的，丢掉。
        if (end > 0 && lines.isNotEmpty()) lines = lines.drop(1)
        return lines.takeLast(limit)
    }

    companion object {
        /** 相对 `filesDir` 的落盘路径；备份归档按这条路径取文件。 */
        internal const val FILE_PATH = "chat/chat_log.jsonl"

        private const val CHUNK_BYTES = 8192
        private val NEWLINE = '\n'.code.toByte()
    }
}
