package com.codingcow.ikitty

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 结构化记忆的持久化。
 *
 * 记忆是一小份有界数据（最多 [CatMemoryRules.MAX_FACTS] 条），所以整个文件重写就够了，
 * 不需要数据库。写法是"先写临时文件再改名"：改名是原子的，进程在写一半时被杀
 * 也不会留下半个 JSON。
 */
class CatMemoryStore(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_PATH))

    suspend fun load(): CatMemory = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext CatMemory()
        // 文件损坏就当作没有记忆，而不是让聊天起不来。
        parseCatMemory(file.readText(StandardCharsets.UTF_8)) ?: CatMemory()
    }

    suspend fun save(memory: CatMemory) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("version", VERSION)
            put("lastExtractedSeq", memory.lastExtractedSeq)
            put("lastExtractedAt", memory.lastExtractedAt)
            put("facts", JSONArray().apply { memory.facts.forEach { put(it.toJson()) } })
        }
        val text = payload.toString()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text, StandardCharsets.UTF_8)
        if (!temp.renameTo(file)) {
            // 个别文件系统上改名会失败，退回直接写，至少不丢数据。
            file.writeText(text, StandardCharsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        /** 相对 `filesDir` 的落盘路径；备份归档按这条路径取文件。 */
        internal const val FILE_PATH = "chat/cat_memory.json"

        private const val VERSION = 1
    }
}

/** 解析记忆文件的正文；文件损坏（不是 JSON）返回 null，由调用方决定回退策略。 */
internal fun parseCatMemory(text: String): CatMemory? = try {
    val obj = JSONObject(text)
    val facts = buildList {
        val array = obj.optJSONArray("facts") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            parseMemoryFact(item)?.let { add(it) }
        }
    }
    CatMemory(
        facts = facts,
        lastExtractedSeq = obj.optLong("lastExtractedSeq"),
        lastExtractedAt = obj.optLong("lastExtractedAt")
    )
} catch (_: JSONException) {
    null
}
