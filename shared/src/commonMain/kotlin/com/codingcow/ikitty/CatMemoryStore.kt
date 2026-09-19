package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * 结构化记忆的持久化。
 *
 * 记忆是一小份有界数据（最多 [CatMemoryRules.MAX_FACTS] 条），所以整个文件重写就够了，
 * 不需要数据库。写法是"先写临时文件再改名"：改名是原子的，进程在写一半时被杀
 * 也不会留下半个 JSON。
 *
 * 文件的**内容**由 [encodeCatMemory] / [parseCatMemory] 决定，这里只管落盘。
 */
class CatMemoryStore(
    private val fileSystem: FileSystem,
    private val path: Path,
    private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun load(): CatMemory = withContext(ioDispatcher) {
        if (!fileSystem.exists(path)) return@withContext CatMemory()
        // 文件损坏就当作没有记忆，而不是让聊天起不来。
        parseCatMemory(fileSystem.read(path) { readUtf8() }) ?: CatMemory()
    }

    suspend fun save(memory: CatMemory) = withContext(ioDispatcher) {
        val text = encodeCatMemory(memory).toString()
        val parent = path.parent
        parent?.let { fileSystem.createDirectories(it) }
        val temp = (parent ?: path) / (path.name + ".tmp")
        fileSystem.write(temp) { writeUtf8(text) }
        try {
            fileSystem.atomicMove(temp, path)
        } catch (_: IOException) {
            // 个别文件系统上改名会失败，退回直接写，至少不丢数据。
            fileSystem.write(path) { writeUtf8(text) }
            fileSystem.delete(temp)
        }
    }
}
