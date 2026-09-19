package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.time.Instant

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

private const val MAX_ENTRIES = 20_000
private const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
private const val MAX_JSON_BYTES = 1024 * 1024

/**
 * 解压后的总大小上限。
 *
 * 归档是整份读进内存的（图片本身就是已压缩的 JPEG，再套一层流式解压收益很小），
 * 所以上限必须比"磁盘上能放多少"保守得多：它的作用是挡住畸形归档，而不是描述真实用量。
 * 256 MB 已经远超正常备份——一张图约 200 KB，那相当于上千张图。
 */
private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

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

/** 一次导出的结果：归档字节 + 用来向用户交代的清单。 */
class BackupExport(val bytes: ByteArray, val summary: BackupSummary)

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
 * 导入是"整体覆盖"：先把归档**全部**解出来并校验通过，再动本机的数据，
 * 所以拿一个不是备份的文件去导入，什么都不会被改坏。
 *
 * 两端共用这一份实现，所以备份可以互导——这也是为什么归档格式必须集中在这里，
 * 而不是各平台各写一份。
 */
class BackupArchive(
    private val fileSystem: FileSystem,
    private val paths: AppPaths,
    private val appVersion: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long = { 0L }
) {
    /**
     * 把本机数据打包成一份归档。
     *
     * [settings] 由调用方给出：它存在各平台自己的键值存储里，不在这个类的文件视野内。
     */
    suspend fun export(settings: BackupSettings): BackupExport = withContext(ioDispatcher) {
        val scan = scanLog(paths.chatLog)
        val facts = readFacts()
        val imageNames = scan.images.filter { fileSystem.exists(paths.imagesDir / it) }
        val summary = BackupSummary(
            exportedAt = now(),
            appVersion = appVersion,
            messageCount = scan.messageCount,
            imageCount = imageNames.size,
            factCount = facts
        )

        val entries = buildList {
            add(ZipEntryData(ENTRY_MANIFEST, manifestJson(summary).toString().encodeToByteArray()))
            add(ZipEntryData(ENTRY_SETTINGS, settingsToJson(settings).toString().encodeToByteArray()))
            readIfPresent(paths.chatLog)?.let { add(ZipEntryData(ENTRY_LOG, it)) }
            readIfPresent(paths.catMemory)?.let { add(ZipEntryData(ENTRY_MEMORY, it)) }
            imageNames.forEach { name ->
                readIfPresent(paths.imagesDir / name)?.let { add(ZipEntryData("$ENTRY_IMAGES/$name", it)) }
            }
        }
        ZipCodec.write(entries).let { BackupExport(it, summary) }
    }

    /**
     * 解包并校验 [archive]。
     *
     * 校验不通过就抛 [BackupException]，本机数据自始至终没有被碰过。
     * 解出来的内容留在内存里等 [commit]，所以 `stage` 与 `commit` 之间不要再改本机数据。
     */
    suspend fun stage(archive: ByteArray): BackupContents = withContext(ioDispatcher) {
        val entries = try {
            ZipCodec.read(archive)
        } catch (e: ZipFormatException) {
            throw BackupException("这不是 iKitty 的备份文件：${e.message}")
        }
        if (entries.size > MAX_ENTRIES) throw BackupException("备份文件里的条目异常多，可能已损坏")

        var manifest: JsonObject? = null
        var settings: JsonObject? = null
        var log: ByteArray? = null
        var memory: ByteArray? = null
        val images = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L

        entries.forEach { entry ->
            val name = entry.name.removePrefix("./")
            totalBytes += entry.bytes.size
            if (totalBytes > MAX_TOTAL_BYTES) throw BackupException("备份文件解压后超过大小上限")

            when (name) {
                ENTRY_MANIFEST -> manifest = entry.readJson(name)
                ENTRY_SETTINGS -> settings = entry.readJson(name)
                ENTRY_LOG -> log = entry.bytes
                ENTRY_MEMORY -> memory = entry.bytes
                else -> if (name.startsWith("$ENTRY_IMAGES/")) {
                    // 带路径分隔符或 `..` 的条目可能想把文件写到图片目录之外，直接丢掉。
                    val imageName = safeImageName(name) ?: return@forEach
                    if (entry.bytes.size > MAX_IMAGE_BYTES) {
                        throw BackupException("备份文件里的图片 $imageName 异常大，可能已损坏")
                    }
                    images[imageName] = entry.bytes
                }
            }
        }

        val summary = validate(manifest, settings, log, memory, images.size)
        BackupContents(
            summary = summary,
            settings = settingsFromJson(settings!!),
            log = log,
            memory = memory,
            images = images
        )
    }

    /**
     * 把 [contents] 覆盖到本机数据上，返回没能写回的图片张数。
     *
     * 调用方负责同时恢复设置。单张图片写失败（例如存储空间不足）不会让整次导入失败：
     * 聊天记录已经换好，缺一张图只是那一处不显示，但调用方要把这个数字告诉用户。
     */
    suspend fun commit(contents: BackupContents): Int = withContext(ioDispatcher) {
        writeOrDelete(paths.chatLog, contents.log)
        writeOrDelete(paths.catMemory, contents.memory)

        // 整体覆盖：本机原有的图片一起换掉，否则会留下孤儿文件。
        if (fileSystem.exists(paths.imagesDir)) fileSystem.deleteRecursively(paths.imagesDir)
        var failed = 0
        contents.images.forEach { (name, bytes) ->
            try {
                fileSystem.createDirectories(paths.imagesDir)
                fileSystem.write(paths.imagesDir / name) { write(bytes) }
            } catch (_: IOException) {
                failed++
            }
        }
        failed
    }

    /** 只读清单：在校验通过之后、落盘之前把"备份里有什么"告诉用户。 */
    private fun validate(
        manifest: JsonObject?,
        settings: JsonObject?,
        log: ByteArray?,
        memory: ByteArray?,
        imageCount: Int
    ): BackupSummary {
        val header = manifest ?: throw BackupException("这不是 iKitty 的备份文件：缺少清单")
        if (header.optString("format") != FORMAT) throw BackupException("这不是 iKitty 的备份文件")
        when (val version = header.intOrZero("version")) {
            0 -> throw BackupException("备份文件缺少格式版本，无法确认能否读取")
            in 1..FORMAT_VERSION -> Unit
            else -> throw BackupException("备份文件来自更新版本的 iKitty（格式 v$version），请先更新应用")
        }
        if (settings == null) throw BackupException("备份文件里没有设置")

        val scan = if (log != null) scanLogBytes(log) else LogScan(0, 0, emptyList())
        // 有内容却一条都解析不出来，说明记录被改坏或根本不是这个格式，不能拿来覆盖本机。
        if (scan.nonBlankLines > 0 && scan.messageCount == 0) {
            throw BackupException("备份文件里的聊天记录无法解析")
        }

        val facts = if (memory != null) {
            parseCatMemory(memory.decodeToString())?.facts?.size
                ?: throw BackupException("备份文件里的记忆无法解析")
        } else {
            0
        }

        return BackupSummary(
            exportedAt = header.longOrZero("exportedAt"),
            appVersion = header.stringOrEmpty("appVersion"),
            messageCount = scan.messageCount,
            imageCount = imageCount,
            factCount = facts
        )
    }

    /** 归档里没有这一项就删掉本机文件（整体覆盖）。 */
    private fun writeOrDelete(target: Path, bytes: ByteArray?) {
        if (bytes == null) {
            if (fileSystem.exists(target)) fileSystem.delete(target)
            return
        }
        target.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(target) { write(bytes) }
    }

    private fun readIfPresent(path: Path): ByteArray? = try {
        if (fileSystem.exists(path)) fileSystem.read(path) { readByteArray() } else null
    } catch (_: IOException) {
        null
    }

    /** 统计聊天记录里能解析的消息条数与引用到的图片名。 */
    private fun scanLog(path: Path): LogScan =
        readIfPresent(path)?.let { scanLogBytes(it) } ?: LogScan(0, 0, emptyList())

    private fun scanLogBytes(bytes: ByteArray): LogScan {
        var messages = 0
        var nonBlank = 0
        val images = LinkedHashSet<String>()
        bytes.decodeToString().lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            nonBlank++
            val message = StoredMessage.fromJson(line) ?: return@forEach
            messages++
            images += message.images
        }
        return LogScan(messages, nonBlank, images.toList())
    }

    /** 本机记忆里的事实条数；文件缺失或损坏都算 0，导出一份空记忆也是合法的。 */
    private fun readFacts(): Int =
        readIfPresent(paths.catMemory)?.let { parseCatMemory(it.decodeToString())?.facts?.size } ?: 0

    private fun manifestJson(summary: BackupSummary): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("version", FORMAT_VERSION)
        put("app", "iKitty")
        put("appVersion", summary.appVersion)
        put("exportedAt", summary.exportedAt)
        put("messageCount", summary.messageCount)
        put("imageCount", summary.imageCount)
        put("factCount", summary.factCount)
    }

    private fun ZipEntryData.readJson(name: String): JsonObject {
        if (bytes.size > MAX_JSON_BYTES) throw BackupException("备份文件里的 $name 异常大，可能已损坏")
        return parseJsonObjectOrNull(bytes.decodeToString())
            ?: throw BackupException("备份文件里的 $name 不是合法 JSON")
    }

    private data class LogScan(val messageCount: Int, val nonBlankLines: Int, val images: List<String>)
}

/**
 * 已解包、已校验的备份。
 *
 * 内容留在内存里等 [BackupArchive.commit]：归档里的每一项都已经过大小上限与格式校验，
 * 所以"先校验再落盘"这条保证仍然成立，只是暂存介质从磁盘换成了内存。
 */
class BackupContents internal constructor(
    val summary: BackupSummary,
    val settings: BackupSettings,
    internal val log: ByteArray?,
    internal val memory: ByteArray?,
    internal val images: Map<String, ByteArray>
)

/** 备份文件的默认名字，例如 `iKitty-20260916-1430.ikitty`。 */
fun defaultBackupFileName(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    val stamp = buildString {
        append(local.year.toString().padStart(4, '0'))
        append((local.month.ordinal + 1).toString().padStart(2, '0'))
        append(local.day.toString().padStart(2, '0'))
        append('-')
        append(local.hour.toString().padStart(2, '0'))
        append(local.minute.toString().padStart(2, '0'))
    }
    return "iKitty-$stamp$BACKUP_EXTENSION"
}

// ---- 设置序列化 ----

/** 设置整体写成一个 JSON 对象：字段名与设置键保持一致，便于人工核对。 */
fun settingsToJson(settings: BackupSettings): JsonObject = buildJsonObject {
    val config = settings.config
    put("providerId", config.providerId)
    put("baseUrl", config.baseUrl)
    put("apiKey", config.apiKey)
    put("model", config.model)
    put("temperature", config.temperature)
    put("topP", config.topP)
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
fun settingsFromJson(json: JsonObject): BackupSettings {
    val configDefaults = ApiConfig()
    val number = json["temperature"].let { if (it == null) null else it.toString().toDoubleOrNull() }
    val topPNumber = json["topP"].let { if (it == null) null else it.toString().toDoubleOrNull() }
    val config = ApiConfig(
        providerId = json.stringOrEmpty("providerId").ifBlank { configDefaults.providerId },
        baseUrl = json.stringOrEmpty("baseUrl").ifBlank { configDefaults.baseUrl },
        // 允许为空：本地模型本来就不需要 Key。
        apiKey = json.optString("apiKey"),
        model = json.stringOrEmpty("model").ifBlank { configDefaults.model },
        temperature = number?.toFloat() ?: configDefaults.temperature,
        topP = topPNumber?.toFloat() ?: configDefaults.topP,
        // 用"键在不在"区分"没存过"和"存了 0"：0 表示不限制，是一个有意义的值。
        maxTokens = json["maxTokens"]?.toString()?.toDoubleOrNull()?.toInt()
            ?: configDefaults.maxTokens,
        thinking = enumByName<ThinkingMode>(json.optString("thinking")) ?: configDefaults.thinking,
        reasoningEffort = ReasoningEffort.fromName(json.optString("reasoningEffort"))
    )

    val personaDefaults = CatPersona()
    val persona = CatPersona(
        name = json.stringOrEmpty("catName").ifBlank { personaDefaults.name },
        traits = parseTraits(
            json.optString("catTraits").takeIf { it.isNotBlank() },
            personaDefaults.traits
        ),
        speechStyle = enumByName<CatSpeechStyle>(json.optString("catSpeechStyle"))
            ?: personaDefaults.speechStyle,
        flavor = enumByName<CatFlavor>(json.optString("catFlavor")) ?: personaDefaults.flavor,
        notes = json.optString("catNotes")
    )

    return BackupSettings(config, persona, json.booleanOr("locationEnabled", true))
}

/**
 * 归档里图片条目的名字。
 *
 * 只接受 `chat/images/` 下的裸文件名：带路径分隔符或 `..` 的条目可能想把文件写到归档目录之外。
 */
internal fun safeImageName(entryName: String): String? {
    val raw = entryName.removePrefix("$ENTRY_IMAGES/")
    if (raw.isBlank() || raw.length > 120) return null
    if (raw.startsWith(".")) return null
    if (raw.contains('/') || raw.contains('\\') || raw.contains("..")) return null
    return raw
}
