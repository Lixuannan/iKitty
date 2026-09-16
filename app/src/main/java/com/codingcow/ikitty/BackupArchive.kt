package com.codingcow.ikitty

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份文件后缀。用自定义后缀而不是 `.zip`：一眼能认出这是 iKitty 的备份。 */
const val BACKUP_EXTENSION = ".ikitty"

/**
 * 备份文件的 MIME。
 *
 * `.ikitty` 在系统里没有注册类型，按二进制流处理最稳：声明成 `application/zip`
 * 会让部分文件选择器把文件名改回 `.zip`。
 */
const val BACKUP_MIME = "application/octet-stream"

private const val FORMAT = "ikitty-backup"
private const val FORMAT_VERSION = 1

private const val ENTRY_MANIFEST = "manifest.json"
private const val ENTRY_SETTINGS = "settings.json"
private const val ENTRY_LOG = "chat/chat_log.jsonl"
private const val ENTRY_MEMORY = "chat/cat_memory.json"
private const val ENTRY_IMAGES = "chat/images"
private const val STAGING_IMAGES = "images"
private const val STAGING_DIR = "backup_staging"

private const val COPY_BUFFER = 64 * 1024
private const val MAX_ENTRIES = 20_000
private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
private const val MAX_JSON_BYTES = 1024 * 1024

/** 归档里能恢复的设置。API Key 也在里面：备份的意义就是换机后不用重填。 */
data class BackupSettings(
    val config: ApiConfig,
    val persona: CatPersona,
    val locationEnabled: Boolean
)

/** 备份清单，用来向用户交代"这份备份里有什么"。 */
data class BackupSummary(
    val exportedAt: Long,
    val appVersion: String,
    val messageCount: Int,
    val imageCount: Int,
    val factCount: Int
)

/** 备份文件本身不可用：不是 iKitty 备份、格式太新，或者内容前后对不上。 */
class BackupException(message: String) : Exception(message)

/** 备份/恢复的进行状态，界面只读。 */
sealed interface BackupStatus {
    data object Idle : BackupStatus
    data class Working(val label: String) : BackupStatus
    data class Done(val message: String) : BackupStatus
    data class Failed(val message: String) : BackupStatus
}

/**
 * 应用数据的完整备份：`.ikitty` 文件实际上是一个 ZIP。
 *
 * 为什么是 ZIP 而不是一个大 JSON：聊天记录本身就是 JSONL，可以直接整段搬进搬出，
 * 图片也能按原始 JPEG 存，不必 base64（那会平白多出三分之一体积）。
 * 归档里的条目：
 *
 * - `manifest.json`：格式版本、导出时间、各类条目数量；
 * - `chat/chat_log.jsonl`：与 [ChatLogStore] 落盘格式完全一致的聊天记录；
 * - `chat/cat_memory.json`：与 [CatMemoryStore] 落盘格式一致的记忆；
 * - `chat/images/<名字>`：聊天引用到的原始图片；
 * - `settings.json`：模型配置、角色设定、位置开关。
 *
 * 导入是"整体覆盖"：先解压到暂存目录并全部校验通过，再动本机的数据，
 * 所以拿一个不是备份的文件去导入，什么都不会被改坏。
 */
class BackupArchive(
    private val chatLog: File,
    private val memory: File,
    private val imagesDir: File,
    private val stagingDir: File,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context, appVersion: String) : this(
        chatLog = File(context.applicationContext.filesDir, ChatLogStore.FILE_PATH),
        memory = File(context.applicationContext.filesDir, CatMemoryStore.FILE_PATH),
        imagesDir = File(context.applicationContext.filesDir, ImageStore.DIR),
        stagingDir = File(context.applicationContext.cacheDir, STAGING_DIR),
        appVersion = appVersion
    )

    /**
     * 把本机数据写进 [target]。
     *
     * [settings] 由调用方给出：它存在 DataStore 里，不在这个类的文件视野内。
     */
    suspend fun export(target: OutputStream, settings: BackupSettings): BackupSummary =
        withContext(Dispatchers.IO) {
            val scan = scanLog(chatLog)
            val facts = readFacts()
            val imageFiles = scan.images.map { File(imagesDir, it) }.filter { it.isFile }
            val summary = BackupSummary(
                exportedAt = now(),
                appVersion = appVersion,
                messageCount = scan.messageCount,
                imageCount = imageFiles.size,
                factCount = facts
            )

            ZipOutputStream(BufferedOutputStream(target)).use { zip ->
                zip.writeEntry(ENTRY_MANIFEST, manifestJson(summary).toString().toByteArray(UTF_8))
                zip.writeEntry(ENTRY_SETTINGS, settingsToJson(settings).toString().toByteArray(UTF_8))
                if (chatLog.isFile) zip.copyEntry(ENTRY_LOG, chatLog)
                if (memory.isFile) zip.copyEntry(ENTRY_MEMORY, memory)
                imageFiles.forEach { file -> zip.copyEntry("$ENTRY_IMAGES/${file.name}", file) }
            }
            summary
        }

    /**
     * 解包并校验 [source]，结果落在暂存目录里等待 [commit]。
     *
     * 校验失败会先清掉暂存目录再抛出 [BackupException]，本机数据自始至终没有被碰过。
     */
    suspend fun stage(source: InputStream): BackupContents = withContext(Dispatchers.IO) {
        stagingDir.deleteRecursively()
        val images = File(stagingDir, STAGING_IMAGES)
        images.mkdirs()

        try {
            var manifest: JSONObject? = null
            var settings: JSONObject? = null
            var totalBytes = 0L
            var entries = 0

            ZipInputStream(BufferedInputStream(source)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw BackupException("备份文件里的条目异常多，可能已损坏")
                    if (entry.isDirectory) continue

                    val remaining = MAX_TOTAL_BYTES - totalBytes
                    if (remaining <= 0) throw BackupException("备份文件解压后超过大小上限")

                    when (val name = entry.name.removePrefix("./")) {
                        ENTRY_MANIFEST -> manifest = zip.readJson(name, MAX_JSON_BYTES)
                        ENTRY_SETTINGS -> settings = zip.readJson(name, MAX_JSON_BYTES)
                        ENTRY_LOG -> totalBytes += zip.copyCapped(File(stagingDir, ENTRY_LOG), remaining, name)
                        ENTRY_MEMORY -> totalBytes += zip.copyCapped(File(stagingDir, ENTRY_MEMORY), remaining, name)
                        else -> if (name.startsWith("$ENTRY_IMAGES/")) {
                            val imageName = safeImageName(name) ?: continue
                            val limit = minOf(remaining, MAX_IMAGE_BYTES)
                            totalBytes += zip.copyCapped(File(images, imageName), limit, imageName)
                        }
                    }
                }
            }

            val summary = validate(manifest, settings, images)
            BackupContents(summary, settingsFromJson(settings!!), stagingDir)
        } catch (e: Throwable) {
            stagingDir.deleteRecursively()
            throw e
        }
    }

    /**
     * 把 [contents] 覆盖到本机数据上，返回没能写回的图片张数。
     *
     * 调用方负责同时恢复 DataStore 里的设置。单张图片写失败（例如存储空间不足）不会让整次导入失败：
     * 聊天记录已经换好，缺一张图只是那一处不显示，但调用方要把这个数字告诉用户。
     */
    suspend fun commit(contents: BackupContents): Int = withContext(Dispatchers.IO) {
        replace(File(contents.stagingRoot, ENTRY_LOG), chatLog)
        replace(File(contents.stagingRoot, ENTRY_MEMORY), memory)

        // 图片按名字还原：整体覆盖意味着本机原有的图片也该一起换掉，否则会留下孤儿文件。
        imagesDir.mkdirs()
        imagesDir.listFiles()?.forEach { it.delete() }
        var failed = 0
        File(contents.stagingRoot, STAGING_IMAGES).listFiles()?.forEach { source ->
            val copied = runCatching { source.copyTo(File(imagesDir, source.name), overwrite = true) }
            if (copied.isFailure) failed++
        }

        contents.stagingRoot.deleteRecursively()
        failed
    }

    /** 只读清单，用来在校验后、落盘前把"备份里有什么"告诉用户。 */
    private fun validate(manifest: JSONObject?, settings: JSONObject?, images: File): BackupSummary {
        val header = manifest ?: throw BackupException("这不是 iKitty 的备份文件：缺少清单")
        if (header.optString("format") != FORMAT) throw BackupException("这不是 iKitty 的备份文件")
        when (val version = header.optInt("version")) {
            0 -> throw BackupException("备份文件缺少格式版本，无法确认能否读取")
            in 1..FORMAT_VERSION -> Unit
            else -> throw BackupException("备份文件来自更新版本的 iKitty（格式 v$version），请先更新应用")
        }
        if (settings == null) throw BackupException("备份文件里没有设置")

        val log = File(stagingDir, ENTRY_LOG)
        val scan = if (log.isFile) scanLog(log) else LogScan(0, 0, emptyList())
        // 有内容却一条都解析不出来，说明记录被改坏或根本不是这个格式，不能拿来覆盖本机。
        if (scan.nonBlankLines > 0 && scan.messageCount == 0) {
            throw BackupException("备份文件里的聊天记录无法解析")
        }

        val memoryFile = File(stagingDir, ENTRY_MEMORY)
        val facts = if (memoryFile.isFile) {
            val parsed = parseCatMemory(memoryFile.readText(UTF_8))
                ?: throw BackupException("备份文件里的记忆无法解析")
            parsed.facts.size
        } else {
            0
        }

        return BackupSummary(
            exportedAt = header.optLong("exportedAt"),
            appVersion = header.optString("appVersion"),
            messageCount = scan.messageCount,
            imageCount = images.listFiles()?.size ?: 0,
            factCount = facts
        )
    }

    /** 用临时文件 + 改名替换目标文件；归档里没有这一项就删除本机文件（整体覆盖）。 */
    private fun replace(staged: File, target: File) {
        if (!staged.isFile) {
            target.delete()
            return
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".import")
        temp.delete()
        staged.copyTo(temp, overwrite = true)
        if (!temp.renameTo(target)) {
            // 个别文件系统上改名会失败，退回直接写：此时整包已校验完，不该留下半个文件。
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    /** 统计 [file] 里能解析的消息条数与引用到的图片名。 */
    private fun scanLog(file: File): LogScan {
        if (!file.isFile) return LogScan(0, 0, emptyList())
        var messages = 0
        var nonBlank = 0
        val images = LinkedHashSet<String>()
        file.bufferedReader(UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                nonBlank++
                val message = StoredMessage.fromJson(line) ?: return@forEach
                messages++
                images += message.images
            }
        }
        return LogScan(messages, nonBlank, images.toList())
    }

    /** 本机记忆里的事实条数；文件缺失或损坏都算 0，导出一份空记忆也是合法的。 */
    private fun readFacts(): Int =
        memory.takeIf { it.isFile }?.let { parseCatMemory(it.readText(UTF_8))?.facts?.size } ?: 0

    private fun manifestJson(summary: BackupSummary): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", FORMAT_VERSION)
        put("app", "iKitty")
        put("appVersion", summary.appVersion)
        put("exportedAt", summary.exportedAt)
        put("messageCount", summary.messageCount)
        put("imageCount", summary.imageCount)
        put("factCount", summary.factCount)
    }

    private data class LogScan(val messageCount: Int, val nonBlankLines: Int, val images: List<String>)
}

/** 已解包、已校验的备份，落在暂存目录里等待 [BackupArchive.commit]。 */
class BackupContents internal constructor(
    val summary: BackupSummary,
    val settings: BackupSettings,
    internal val stagingRoot: File
)

/** 备份文件的默认名字，例如 `iKitty-20260916-1430.ikitty`。 */
fun defaultBackupFileName(epochMillis: Long): String =
    "iKitty-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(epochMillis)) + BACKUP_EXTENSION

// ---- 设置与记忆的序列化 ----

/** 设置整体写成一个 JSON 对象：字段名与 DataStore 的键保持一致，便于人工核对。 */
internal fun settingsToJson(settings: BackupSettings): JSONObject = JSONObject().apply {
    val config = settings.config
    put("providerId", config.providerId)
    put("baseUrl", config.baseUrl)
    put("apiKey", config.apiKey)
    put("model", config.model)
    put("temperature", config.temperature.toDouble())
    put("topP", config.topP.toDouble())
    put("maxTokens", config.maxTokens)
    put("thinking", config.thinking.name)
    put("reasoningEffort", config.reasoningEffort.name)

    val persona = settings.persona
    put("catName", persona.name)
    put("catTraits", persona.traits.encodeTraits())
    put("catSpeechStyle", persona.speechStyle.name)
    put("catFlavor", persona.flavor.name)
    put("catNotes", persona.notes)

    put("locationEnabled", settings.locationEnabled)
}

/** 缺字段一律回退到默认值，这样旧备份也能导入。 */
internal fun settingsFromJson(json: JSONObject): BackupSettings {
    val configDefaults = ApiConfig()
    val config = ApiConfig(
        providerId = json.optString("providerId").ifBlank { configDefaults.providerId },
        baseUrl = json.optString("baseUrl").ifBlank { configDefaults.baseUrl },
        // 允许为空：本地模型本来就不需要 Key。
        apiKey = json.optString("apiKey"),
        model = json.optString("model").ifBlank { configDefaults.model },
        temperature = json.optDouble("temperature", configDefaults.temperature.toDouble()).toFloat(),
        topP = json.optDouble("topP", configDefaults.topP.toDouble()).toFloat(),
        maxTokens = json.optInt("maxTokens", configDefaults.maxTokens),
        thinking = enumByName<ThinkingMode>(json.optString("thinking")) ?: configDefaults.thinking,
        reasoningEffort = ReasoningEffort.fromName(json.optString("reasoningEffort"))
    )

    val personaDefaults = CatPersona()
    val persona = CatPersona(
        name = json.optString("catName").ifBlank { personaDefaults.name },
        traits = parseTraits(
            json.optString("catTraits").takeIf { it.isNotBlank() },
            personaDefaults.traits
        ),
        speechStyle = enumByName<CatSpeechStyle>(json.optString("catSpeechStyle"))
            ?: personaDefaults.speechStyle,
        flavor = enumByName<CatFlavor>(json.optString("catFlavor")) ?: personaDefaults.flavor,
        notes = json.optString("catNotes")
    )

    return BackupSettings(config, persona, json.optBoolean("locationEnabled", true))
}

// ---- ZIP 与流的工具函数 ----

private val UTF_8 = StandardCharsets.UTF_8

private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

private fun ZipOutputStream.copyEntry(name: String, source: File) {
    putNextEntry(ZipEntry(name))
    source.inputStream().use { it.copyTo(this) }
    closeEntry()
}

/** 读一个 JSON 条目；超出上限直接失败，避免一个畸形条目把内存吃光。 */
private fun ZipInputStream.readJson(name: String, limit: Int): JSONObject {
    val text = String(readCapped(limit, name), UTF_8)
    return try {
        JSONObject(text)
    } catch (_: JSONException) {
        throw BackupException("备份文件里的 $name 不是合法 JSON")
    }
}

private fun InputStream.readCapped(limit: Int, label: String): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(COPY_BUFFER)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw BackupException("备份文件里的 $label 异常大，可能已损坏")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun InputStream.copyCapped(target: File, limit: Long, label: String): Long {
    target.parentFile?.mkdirs()
    var total = 0L
    target.outputStream().use { out ->
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw BackupException("备份文件里的 $label 超过大小上限，可能已损坏")
            out.write(buffer, 0, read)
        }
    }
    return total
}

/**
 * 归档里图片条目的名字。
 *
 * 只接受 `chat/images/` 下的裸文件名：带路径分隔符或 `..` 的条目可能想把文件写到归档目录之外。
 */
private fun safeImageName(entryName: String): String? {
    val raw = entryName.removePrefix("$ENTRY_IMAGES/")
    if (raw.isBlank() || raw.length > 120) return null
    if (raw.startsWith(".")) return null
    if (raw.contains('/') || raw.contains('\\') || raw.contains("..")) return null
    return raw
}
