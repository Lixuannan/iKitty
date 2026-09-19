package com.codingcow.ikitty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `.ikitty` 归档的读写契约。
 *
 * 这套测试覆盖的是"备份能不能原样还回来"：丢一条消息、少一张图片，
 * 或者让一个坏文件把本机数据覆盖掉，都是用户无法接受的失败。
 *
 * 归档格式已经搬到 commonMain，所以这些用例现在在 jvm 与 android 两个 target 上都跑，
 * iOS 用的也是同一份实现。
 */
class BackupArchiveTest {

    @AfterTest
    fun tearDown() {
        // 每个夹具自己的 FakeFileSystem 在 Fixture 里检查，这里什么都不用做。
    }

    @Test
    fun `export then import restores messages memory images and settings`() = runTest {
        val source = Fixture()
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

        val target = Fixture()
        // 本机已有的旧数据，导入后应该被整体替换掉。
        target.writeMemory(fact("旧事", "应当消失"))
        target.writeImage("img_old.jpg")

        val contents = target.archive.stage(bytes)
        assertEquals(2, contents.summary.messageCount)
        assertEquals(1, contents.summary.imageCount)
        assertEquals(1, contents.summary.factCount)
        assertEquals(Fixture.NOW, contents.summary.exportedAt)
        assertEquals("咪咪", contents.settings.persona.name)
        assertFalse(contents.settings.locationEnabled)
        assertEquals("sk-secret", contents.settings.config.apiKey)

        // 校验通过之前本机数据不能被碰过。
        assertTrue(target.readMemoryText()!!.contains("旧事"))

        target.archive.commit(contents)

        val restored = target.readLog()
        assertEquals(listOf(1L, 2L), restored.map { it.seq })
        assertEquals("喵～你好呀", restored.first().content)
        assertEquals(listOf("img_a.jpg"), restored.last().images)

        val restoredMemory = parseCatMemory(target.readMemoryText()!!)!!
        assertEquals(listOf("猫的名字"), restoredMemory.facts.map { it.key })
        assertEquals(2L, restoredMemory.lastExtractedSeq)

        assertTrue(target.fileExists(target.paths.imagesDir / "img_a.jpg"))
        assertFalse(target.fileExists(target.paths.imagesDir / "img_old.jpg"))
    }

    @Test
    fun `stage rejects a file that is not a backup`() = runTest {
        val fixture = Fixture()
        fixture.writeLog(message(1, StoredMessage.ROLE_USER, "本机消息"))

        val error = runCatching { fixture.archive.stage("这根本不是 zip".encodeToByteArray()) }.exceptionOrNull()

        assertTrue(error is BackupException)
        // 失败之后本机数据必须原封不动。
        assertEquals(listOf("本机消息"), fixture.readLog().map { it.content })
    }

    @Test
    fun `stage rejects a zip without a manifest`() = runTest {
        val fixture = Fixture()
        val zip = buildZip("random.txt" to "hello".encodeToByteArray())
        val error = runCatching { fixture.archive.stage(zip) }.exceptionOrNull()

        assertTrue(error is BackupException)
    }

    @Test
    fun `stage rejects a backup made by a newer format version`() = runTest {
        val fixture = Fixture()
        val zip = buildZip(
            ENTRY_MANIFEST_NAME to manifestJson(version = 99).toString().encodeToByteArray(),
            ENTRY_SETTINGS_NAME to "{}".encodeToByteArray()
        )
        val error = runCatching { fixture.archive.stage(zip) }.exceptionOrNull()

        assertTrue(error is BackupException)
        assertTrue(error!!.message.orEmpty().contains("更新版本"))
    }

    @Test
    fun `stage ignores image entries that try to escape the archive directory`() = runTest {
        val fixture = Fixture()
        val zip = buildZip(
            ENTRY_MANIFEST_NAME to manifestJson().toString().encodeToByteArray(),
            ENTRY_SETTINGS_NAME to "{}".encodeToByteArray(),
            "chat/images/../evil.jpg" to byteArrayOf(1, 2, 3)
        )

        val contents = fixture.archive.stage(zip)
        fixture.archive.commit(contents)

        assertEquals(0, contents.summary.imageCount)
        assertFalse(fixture.fileExists(fixture.paths.imagesDir / "evil.jpg"))
        assertFalse(fixture.fileExists(fixture.paths.imagesDir.parent!! / "evil.jpg"))
    }

    @Test
    fun `importing a backup without a chat log clears the local history`() = runTest {
        val fixture = Fixture()
        fixture.writeLog(message(1, StoredMessage.ROLE_USER, "旧消息"))
        fixture.writeImage("img_old.jpg")

        val zip = buildZip(
            ENTRY_MANIFEST_NAME to manifestJson().toString().encodeToByteArray(),
            ENTRY_SETTINGS_NAME to "{}".encodeToByteArray()
        )
        val contents = fixture.archive.stage(zip)
        fixture.archive.commit(contents)

        assertEquals(0, contents.summary.messageCount)
        assertEquals(emptyList<StoredMessage>(), fixture.readLog())
        assertFalse(fixture.fileExists(fixture.paths.chatLog))
        assertFalse(fixture.fileExists(fixture.paths.imagesDir / "img_old.jpg"))
    }

    @Test
    fun `corrupted chat log is rejected instead of overwriting local data`() = runTest {
        val fixture = Fixture()
        fixture.writeLog(message(1, StoredMessage.ROLE_USER, "本机消息"))

        val zip = buildZip(
            ENTRY_MANIFEST_NAME to manifestJson().toString().encodeToByteArray(),
            ENTRY_SETTINGS_NAME to "{}".encodeToByteArray(),
            "chat/chat_log.jsonl" to "{不是 JSON}\n".encodeToByteArray()
        )
        val error = runCatching { fixture.archive.stage(zip) }.exceptionOrNull()

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
        val restored = settingsFromJson(buildJsonObject { })

        assertEquals(ApiConfig(), restored.config)
        assertEquals(CatPersona(), restored.persona)
        assertTrue(restored.locationEnabled)
    }

    /** `maxTokens` 为 0 表示"不限制"，是一个有意义的值，不能被当成"没存过"。 */
    @Test
    fun `an explicit zero max tokens survives the round trip`() {
        val settings = BackupSettings(ApiConfig(maxTokens = 0), CatPersona(), locationEnabled = true)
        assertEquals(0, settingsFromJson(settingsToJson(settings)).config.maxTokens)
    }

    @Test
    fun `parsing a memory file returns null for garbage`() {
        assertNull(parseCatMemory("不是 JSON"))
        assertEquals(emptyList<MemoryFact>(), parseCatMemory("{}")!!.facts)
    }

    @Test
    fun `default backup file name carries the extension`() {
        val name = defaultBackupFileName(Fixture.NOW)

        assertTrue(name.startsWith("iKitty-"), name)
        assertTrue(name.endsWith(BACKUP_EXTENSION), name)
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
        updatedAt = Fixture.NOW
    )

    private fun manifestJson(version: Int = 1): JsonObject = buildJsonObject {
        put("format", "ikitty-backup")
        put("version", version)
        put("exportedAt", Fixture.NOW)
        put("appVersion", "0.2.1")
        put("messageCount", 0)
        put("imageCount", 0)
        put("factCount", 0)
    }

    /** 用生产代码的编码器造夹具归档：STORE/DEFLATE 的互操作已经由 ZipInteropTest 覆盖。 */
    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ZipCodec.write(entries.map { ZipEntryData(it.first, it.second) })

    /** 一整套本机目录，让每个用例自己决定往里面放什么。 */
    private class Fixture {
        val fileSystem = FakeFileSystem()
        val paths = AppPaths("/data".toPath())
        val archive = BackupArchive(
            fileSystem = fileSystem,
            paths = paths,
            appVersion = "0.2.1",
            ioDispatcher = Dispatchers.Unconfined,
            now = { NOW }
        )

        fun fileExists(path: okio.Path): Boolean = fileSystem.exists(path)

        fun writeLog(vararg messages: StoredMessage) {
            fileSystem.createDirectories(paths.chatLog.parent!!)
            fileSystem.write(paths.chatLog) {
                messages.forEach {
                    writeUtf8(it.toJson().toString())
                    writeUtf8("\n")
                }
            }
        }

        fun readLog(): List<StoredMessage> {
            if (!fileSystem.exists(paths.chatLog)) return emptyList()
            return fileSystem.read(paths.chatLog) { readUtf8() }
                .lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { StoredMessage.fromJson(it) }
                .toList()
        }

        fun writeMemory(vararg facts: MemoryFact, lastExtractedSeq: Long = 0L) {
            fileSystem.createDirectories(paths.catMemory.parent!!)
            fileSystem.write(paths.catMemory) {
                writeUtf8(
                    encodeCatMemory(
                        CatMemory(facts = facts.toList(), lastExtractedSeq = lastExtractedSeq)
                    ).toString()
                )
            }
        }

        fun readMemoryText(): String? =
            if (fileSystem.exists(paths.catMemory)) fileSystem.read(paths.catMemory) { readUtf8() } else null

        fun writeImage(name: String) {
            fileSystem.createDirectories(paths.imagesDir)
            fileSystem.write(paths.imagesDir / name) { write(byteArrayOf(1, 2, 3)) }
        }

        suspend fun export(settings: BackupSettings): ByteArray = archive.export(settings).bytes

        companion object {
            const val NOW = 1_700_000_000_000L
        }
    }

    private companion object {
        const val ENTRY_MANIFEST_NAME = "manifest.json"
        const val ENTRY_SETTINGS_NAME = "settings.json"
    }
}
