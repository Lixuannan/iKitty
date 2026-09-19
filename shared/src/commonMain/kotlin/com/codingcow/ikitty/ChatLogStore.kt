package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer

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
 *
 * 平台差异只体现在构造参数上：真实文件系统由各平台给出，[ioDispatcher] 也由调用方
 * 显式指定（Android 是 `Dispatchers.IO`），不做成默认值，避免"忘了传就悄悄退化成
 * 主线程调度"这种不报错的错误。
 */
class ChatLogStore(
    private val fileSystem: FileSystem,
    private val path: Path,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long
) {
    /** 当前时间。由外部注入，测试里可以换成固定时钟。 */
    fun timestamp(): Long = now()

    suspend fun append(message: StoredMessage) = withContext(ioDispatcher) {
        path.parent?.let { fileSystem.createDirectories(it) }
        // 不用 `use {}`：okio 的 Closeable 在 Kotlin/Native 上不是 AutoCloseable，
        // 而 stdlib 的 use 只对 AutoCloseable 可用。
        val sink = fileSystem.appendingSink(path).buffer()
        try {
            sink.writeUtf8(message.toJson().toString())
            sink.writeUtf8("\n")
        } finally {
            sink.close()
        }
    }

    /** 读最后 [limit] 条。文件中间出现坏行只影响那一行，其余记录照常返回。 */
    suspend fun tail(limit: Int): List<StoredMessage> = withContext(ioDispatcher) {
        if (limit <= 0) return@withContext emptyList()
        readTailLines(limit).mapNotNull { StoredMessage.fromJson(it) }
    }

    /** 读 [seq] 之后的记录，最多 [limit] 条。记忆提取用它消费"还没整理过"的消息。 */
    suspend fun readAfter(seq: Long, limit: Int): List<StoredMessage> = withContext(ioDispatcher) {
        if (limit <= 0 || !fileSystem.exists(path)) return@withContext emptyList()
        val result = mutableListOf<StoredMessage>()
        val source = fileSystem.source(path).buffer()
        try {
            while (result.size < limit) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val message = StoredMessage.fromJson(line) ?: continue
                if (message.seq <= seq) continue
                result += message
            }
        } finally {
            source.close()
        }
        result
    }

    suspend fun clear() = withContext(ioDispatcher) {
        if (fileSystem.exists(path)) fileSystem.delete(path)
    }

    /**
     * 从文件末尾往前按块读，凑够 [limit] + 1 个换行就停。
     *
     * 这样"读最近 N 条"的耗时只和这 N 条的大小有关，
     * 不会因为文件里有几万条历史而变慢。
     */
    private fun readTailLines(limit: Int): List<String> {
        if (!fileSystem.exists(path)) return emptyList()
        val length = fileSystem.metadata(path).size ?: return emptyList()
        if (length == 0L) return emptyList()

        val blocks = ArrayDeque<ByteArray>()
        var newlines = 0
        var end = length
        val handle = fileSystem.openReadOnly(path)
        try {
            while (end > 0 && newlines <= limit) {
                val size = minOf(CHUNK_BYTES.toLong(), end).toInt()
                val start = end - size
                val buffer = ByteArray(size)
                var filled = 0
                // 单次 read 可能读不满，必须循环到填满这一块或到达文件末尾。
                while (filled < size) {
                    val read = handle.read(start + filled, buffer, filled, size - filled)
                    if (read <= 0) break
                    filled += read
                }
                blocks.addFirst(buffer)
                for (byte in buffer) if (byte == NEWLINE) newlines++
                end = start
            }
        } finally {
            handle.close()
        }

        // 必须先把字节拼起来再解码：一个 UTF-8 汉字可能横跨两个块，
        // 逐块解码会在边界上产生一串替换字符。
        val bytes = ByteArray(blocks.sumOf { it.size })
        var offset = 0
        for (block in blocks) {
            block.copyInto(bytes, offset)
            offset += block.size
        }
        val text = bytes.decodeToString()

        var lines = text.split('\n').filter { it.isNotBlank() }
        // 没读到文件开头，说明第一行是从中间截断的，丢掉。
        if (end > 0 && lines.isNotEmpty()) lines = lines.drop(1)
        return lines.takeLast(limit)
    }

    private companion object {
        const val CHUNK_BYTES = 8192
        val NEWLINE = '\n'.code.toByte()
    }
}
