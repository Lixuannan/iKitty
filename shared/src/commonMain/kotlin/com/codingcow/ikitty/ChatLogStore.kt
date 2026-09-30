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

    /**
     * 一次追加多条。
     *
     * 与逐条 [append] 的区别只在"打开一次文件"：同步可能一次推上来几十条消息，
     * 逐条开合 sink 是没必要的系统调用。
     */
    suspend fun appendAll(messages: List<StoredMessage>) = withContext(ioDispatcher) {
        if (messages.isEmpty()) return@withContext
        path.parent?.let { fileSystem.createDirectories(it) }
        val sink = fileSystem.appendingSink(path).buffer()
        try {
            messages.forEach { sink.writeUtf8(it.toJson().toString()); sink.writeUtf8("\n") }
        } finally {
            sink.close()
        }
    }

    /** 读整个文件。只在同步的"整体替换"与迁移时需要，正常读取用 [tail] / [readAfter]。 */
    suspend fun all(): List<StoredMessage> = withContext(ioDispatcher) {
        readAllLines().mapNotNull { StoredMessage.fromJson(it) }
    }

    /**
     * 用 [messages] 整体替换日志文件（同步拉取与 id 迁移用）。
     *
     * 先写同目录的临时文件再原子重命名：中途崩溃时，要么还是旧文件，要么已经是完整的新文件，
     * 不会留下半份日志。临时文件与目标同目录，才能保证重命名是原子的（跨设备重命名不是）。
     *
     * 调用方必须自己保证 [messages] 的落盘顺序，这里不排序。
     */
    suspend fun replaceAll(messages: List<StoredMessage>) = withContext(ioDispatcher) {
        path.parent?.let { fileSystem.createDirectories(it) }
        val temp = path.parent?.let { it / "$CHAT_LOG_TEMP_SUFFIX" } ?: path
        val sink = fileSystem.sink(temp).buffer()
        try {
            messages.forEach { sink.writeUtf8(it.toJson().toString()); sink.writeUtf8("\n") }
            sink.flush()
        } finally {
            sink.close()
        }
        fileSystem.atomicMove(temp, path)
    }

    suspend fun clear() = withContext(ioDispatcher) {
        if (fileSystem.exists(path)) fileSystem.delete(path)
    }

    /**
     * 给老记录补上 [StoredMessage.msgId]。
     *
     * 为什么必须一次性回写而不是"每次读出来再补"：`fromJson` 对缺 `id` 的行只能生成随机 id，
     * 那么同一条老记录每读一次就换一个身份，同步时会被当成新消息反复上传，云端越积越多。
     * 所以迁移必须在**第一次同步之前**把 id 固化到磁盘上。
     *
     * 返回是否发生了改写；没有老记录时不碰文件，避免无谓的 IO。
     */
    suspend fun migrateMissingIds(): Boolean = withContext(ioDispatcher) {
        if (!fileSystem.exists(path)) return@withContext false

        val lines = readAllLines()
        if (lines.none { line -> parseJsonObjectOrNull(line)?.stringOrEmpty("id").isNullOrEmpty() }) {
            return@withContext false
        }

        // 走 fromJson 统一补 id，顺序保持不变（这一遍就是"读一遍、补一遍、写回去"）。
        replaceAll(lines.mapNotNull { StoredMessage.fromJson(it) })
        true
    }

    /** 整份读成按行切分的文本；空行丢掉。 */
    private fun readAllLines(): List<String> {
        if (!fileSystem.exists(path)) return emptyList()
        val source = fileSystem.source(path).buffer()
        return try {
            buildList {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isNotBlank()) add(line)
                }
            }
        } finally {
            source.close()
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

        /** [replaceAll] 的临时文件名；与目标同目录才能保证重命名是原子的。 */
        const val CHAT_LOG_TEMP_SUFFIX = "chat_log.jsonl.tmp"
    }
}
