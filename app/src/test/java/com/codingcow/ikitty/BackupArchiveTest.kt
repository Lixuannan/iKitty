package com.codingcow.ikitty

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `.ikitty` 归档的读写契约。
 *
 * 这套测试覆盖的是"备份能不能原样还回来"：丢一条消息、少一张图片，
 * 或者让一个坏文件把本机数据覆盖掉，都是用户无法接受的失败。
 */
class BackupArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `export then import restores messages memory images and settings`() = runBlocking {
        val source = Fixture(folder.newFolder("source"))
        source.writeLog(
            message(1, StoredMessage.ROLE_ASSISTANT, "喵～你好呀"),
            message(2, StoredMessage.ROLE_USER, "看这个", images = listOf("img_a.jpg"))
        )
        source.writeMemory(fact("猫的名字", "咪咪"), lastExtractedSeq = 2)
        source.writeImage("img_a.jpg")

        val settings = BackupSettings(
            config = ApiConfig(apiKey = "sk-secret", model = "gpt-5.5"),
            persona = CatPersona(name = "咪咪", traits = setOf(CatTrait.CALM), notes = "叫我主人"),
            locationEnabled = false
        )
        val bytes = source.export(settings)

        val target = Fixture(folder.newFolder("target"))
        // 本机已有的旧数据，导入后应该被整体替换掉。
        target.writeMemory(fact("旧事", "应当消失"))
        target.writeImage("img_old.jpg")

        val contents = target.stage(bytes)
        assertEquals(2, contents.summary.messageCount)
        assertEquals(1, contents.summary.imageCount)
        assertEquals(1, contents.summary.factCount)
        assertEquals(NOW, contents.summary.exportedAt)
        assertEquals("咪咪", contents.settings.persona.name)
        assertFalse(contents.settings.locationEnabled)
        assertEquals("sk-secret", contents.settings.config.apiKey)
        target.commit(contents)

        val restored = target.readLog()
        assertEquals(listOf(1L, 2L), restored.map { it.seq })
        assertEquals("喵～你好呀", restored.first().content)
        assertEquals(listOf("img_a.jpg"), restored.last().images)

        val restoredFacts = parseCatMemory(target.memory.readText())!!.facts
        assertEquals(listOf("猫的名字"), restoredFacts.map { it.key })
        assertEquals(2L, parseCatMemory(target.memory.readText())!!.lastExtractedSeq)

        assertTrue(File(target.imagesDir, "img_a.jpg").isFile)
        assertFalse(File(target.imagesDir, "img_old.jpg").exists())
        // 暂存目录用完就清掉，不在缓存里留一份聊天记录。
        assertFalse(target.stagingDir.exists())
    }

    @Test
    fun `stage rejects a file that is not a backup`() = runBlocking {
        val fixture = Fixture(folder.newFolder("garbage"))
        val error = runCatching {
            fixture.stage("这根本不是 zip".toByteArray())
        }.exceptionOrNull()

        assertTrue(error is BackupException)
        assertFalse(fixture.stagingDir.exists())
    }

    @Test
    fun `stage rejects a zip without a manifest`() = runBlocking {
        val fixture = Fixture(folder.newFolder("no-manifest"))
        val zip = buildZip("random.txt" to "hello".toByteArray())
        val error = runCatching { fixture.stage(zip) }.exceptionOrNull()

        assertTrue(error is BackupException)
    }

    @Test
    fun `stage rejects a backup made by a newer format version`() = runBlocking {
        val fixture = Fixture(folder.newFolder("future"))
        val zip = buildZip(
            ENTRY_MANIFEST_NAME to manifestJson(version = 99).toString().toByteArray(),
            ENTRY_SETTINGS_NAME to "{}".toByteArray()
        )
        val error = runCatching { fixture.stage(zip) }.exceptionOrNull()

        assertTrue(error is BackupException)
        assertTrue(error!!.message.orEmpty().contains("更新版本"))
    }

    @Test
    fun `stage ignores image entries that try to escape the archive directory`() = runBlocking {
        val fixture = Fixture(folder.newFolder("slip"))
        val zip = buildZip(
            ENTRY_MANIFEST_NAME to manifestJson().toString().toByteArray(),
            ENTRY_SETTINGS_NAME to "{}".toByteArray(),
            "chat/images/../evil.jpg" to byteArrayOf(1, 2, 3)
        )

        val contents = fixture.stage(zip)
        fixture.commit(contents)

        assertEquals(0, contents.summary.imageCount)
        assertFalse(File(fixture.stagingDir, "evil.jpg").exists())
        assertFalse(File(fixture.imagesDir, "evil.jpg").exists())
    }

    @Test
    fun `importing a backup without a chat log clears the local history`() = runBlocking {
        val fixture = Fixture(folder.newFolder("empty"))
        fixture.writeLog(message(1, StoredMessage.ROLE_USER, "旧消息"))
        fixture.writeImage("img_old.jpg")

        val zip = buildZip(
            ENTRY_MANIFEST_NAME to manifestJson().toString().toByteArray(),
            ENTRY_SETTINGS_NAME to "{}".toByteArray()
        )
        val contents = fixture.stage(zip)
        fixture.commit(contents)

        assertEquals(0, contents.summary.messageCount)
        assertEquals(emptyList<StoredMessage>(), fixture.readLog())
        assertFalse(fixture.chatLog.exists())
        assertFalse(File(fixture.imagesDir, "img_old.jpg").exists())
    }

    @Test
    fun `corrupted chat log is rejected instead of overwriting local data`() = runBlocking {
        val fixture = Fixture(folder.newFolder("corrupt"))
        fixture.writeLog(message(1, StoredMessage.ROLE_USER, "本机消息"))

        val zip = buildZip(
            ENTRY_MANIFEST_NAME to manifestJson().toString().toByteArray(),
            ENTRY_SETTINGS_NAME to "{}".toByteArray(),
            "chat/chat_log.jsonl" to "{不是 JSON}\n".toByteArray()
        )
        val error = runCatching { fixture.stage(zip) }.exceptionOrNull()

        assertTrue(error is BackupException)
        assertEquals(listOf("本机消息"), fixture.readLog().map { it.content })
    }

    @Test
    fun `settings json round trips every field the settings screen owns`() {
        val settings = BackupSettings(
            config = ApiConfig(
                providerId = CUSTOM_PROVIDER_ID,
                baseUrl = "http://localhost:11434/v1",
                apiKey = "sk-123",
                model = "qwen3",
                temperature = 0.35f,
                topP = 0.9f,
                maxTokens = 2048,
                thinking = ThinkingMode.ON,
                reasoningEffort = ReasoningEffort.HIGH
            ),
            persona = CatPersona(
                name = "团子",
                traits = setOf(CatTrait.LAZY, CatTrait.WITTY),
                speechStyle = CatSpeechStyle.LITERARY,
                flavor = CatFlavor.CAT,
                notes = "不要聊工作"
            ),
            locationEnabled = true
        )

        val restored = settingsFromJson(settingsToJson(settings))

        assertEquals(settings.config, restored.config)
        assertEquals(settings.persona, restored.persona)
        assertTrue(restored.locationEnabled)
    }

    @Test
    fun `settings json tolerates missing fields`() {
        val restored = settingsFromJson(JSONObject())

        assertEquals(ApiConfig(), restored.config)
        assertEquals(CatPersona(), restored.persona)
        assertTrue(restored.locationEnabled)
    }

    @Test
    fun `parsing a memory file returns null for garbage`() {
        assertNull(parseCatMemory("不是 JSON"))
        assertEquals(emptyList<MemoryFact>(), parseCatMemory("{}")!!.facts)
    }

    @Test
    fun `default backup file name carries the extension`() {
        val name = defaultBackupFileName(NOW)

        assertTrue(name.startsWith("iKitty-"))
        assertTrue(name.endsWith(BACKUP_EXTENSION))
    }

    // ---- 测试辅助 ----

    private fun message(
        seq: Long,
        role: String,
        content: String,
        images: List<String> = emptyList()
    ) = StoredMessage(seq = seq, role = role, content = content, createdAt = seq, images = images)

    private fun fact(key: String, value: String) = MemoryFact(
        category = MemoryCategory.OWNER,
        key = key,
        value = value,
        updatedAt = NOW
    )

    private fun manifestJson(version: Int = 1): JSONObject = JSONObject().apply {
        put("format", "ikitty-backup")
        put("version", version)
        put("exportedAt", NOW)
        put("appVersion", "0.2.1")
        put("messageCount", 0)
        put("imageCount", 0)
        put("factCount", 0)
    }

    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** 一整套本机目录 + 指向它的归档，让每个用例自己决定往里面放什么。 */
    private class Fixture(val root: File) {
        val chatLog = File(root, "files/chat/chat_log.jsonl")
        val memory = File(root, "files/chat/cat_memory.json")
        val imagesDir = File(root, "files/chat/images")
        val stagingDir = File(root, "cache/backup_staging")
        val archive = BackupArchive(chatLog, memory, imagesDir, stagingDir, "0.2.1") { NOW }

        fun writeLog(vararg messages: StoredMessage) {
            chatLog.parentFile?.mkdirs()
            chatLog.writeText(messages.joinToString("") { it.toJson().toString() + "\n" })
        }

        fun readLog(): List<StoredMessage> =
            if (chatLog.isFile) chatLog.readLines().filter { it.isNotBlank() }.mapNotNull { StoredMessage.fromJson(it) }
            else emptyList()

        fun writeMemory(vararg facts: MemoryFact, lastExtractedSeq: Long = 0L) {
            memory.parentFile?.mkdirs()
            memory.writeText(
                JSONObject().apply {
                    put("version", 1)
                    put("lastExtractedSeq", lastExtractedSeq)
                    put("facts", JSONArray().apply { facts.forEach { put(it.toJson()) } })
                }.toString()
            )
        }

        fun writeImage(name: String) {
            imagesDir.mkdirs()
            File(imagesDir, name).writeBytes(byteArrayOf(1, 2, 3))
        }

        fun export(settings: BackupSettings): ByteArray {
            val bytes = ByteArrayOutputStream()
            runBlocking { archive.export(bytes, settings) }
            return bytes.toByteArray()
        }

        suspend fun stage(bytes: ByteArray): BackupContents =
            ByteArrayInputStream(bytes).use { archive.stage(it) }

        suspend fun commit(contents: BackupContents) = archive.commit(contents)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val ENTRY_MANIFEST_NAME = "manifest.json"
        const val ENTRY_SETTINGS_NAME = "settings.json"
    }
}
