package com.codingcow.ikitty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 追加式记录的读写契约：长对话全靠它不丢东西。 */
class ChatLogStoreTest {

    private val fileSystem = FakeFileSystem()

    private val root = "/data".toPath()
    private val paths = AppPaths(root)

    private fun newStore() = ChatLogStore(
        fileSystem = fileSystem,
        path = paths.chatLog,
        ioDispatcher = Dispatchers.Unconfined,
        now = { 1_000L }
    )

    @AfterTest
    fun tearDown() {
        fileSystem.checkNoOpenFiles()
    }

    private fun message(seq: Long, role: String, content: String) =
        StoredMessage(seq = seq, role = role, content = content, createdAt = seq)

    @Test
    fun `append then tail round trips a message`() = runTest {
        val log = newStore()
        log.append(message(1, StoredMessage.ROLE_ASSISTANT, "喵～你好呀"))
        log.append(message(2, StoredMessage.ROLE_USER, "今天有点累"))

        val loaded = log.tail(10)
        assertEquals(listOf(1L, 2L), loaded.map { it.seq })
        assertEquals("今天有点累", loaded.last().content)
        assertEquals(2L, loaded.last().createdAt)
    }

    @Test
    fun `tail returns only the newest entries`() = runTest {
        val log = newStore()
        (1..50).forEach { log.append(message(it.toLong(), StoredMessage.ROLE_USER, "第 $it 条")) }

        assertEquals((46L..50L).toList(), log.tail(5).map { it.seq })
        assertTrue(log.tail(0).isEmpty())
    }

    @Test
    fun `reading across block boundaries does not corrupt chinese text`() = runTest {
        val log = newStore()
        // 一个汉字 3 字节：几条消息就跨过多个 8192 字节的读块，块边界落在完整消息中间。
        val filler = "汉".repeat(1_200)
        (1..12).forEach { log.append(message(it.toLong(), StoredMessage.ROLE_USER, "$it-$filler")) }

        val loaded = log.tail(100)
        assertEquals((1L..12L).toList(), loaded.map { it.seq })
        loaded.forEach { msg ->
            assertFalse(msg.content.contains('\uFFFD'), "内容被截断：${msg.content.take(20)}")
            assertTrue(msg.content.endsWith(filler))
        }
    }

    @Test
    fun `readAfter returns only what is past the cursor`() = runTest {
        val log = newStore()
        (1..10).forEach { log.append(message(it.toLong(), StoredMessage.ROLE_USER, "m$it")) }

        assertEquals(listOf(9L, 10L), log.readAfter(8, 10).map { it.seq })
        assertEquals(listOf(1L, 2L), log.readAfter(0, 2).map { it.seq })
    }

    @Test
    fun `a corrupt line does not take the rest of the log down`() = runTest {
        val log = newStore()
        log.append(message(1, StoredMessage.ROLE_USER, "第一条"))
        fileSystem.appendingSink(paths.chatLog).buffer().let { sink ->
            try {
                sink.writeUtf8("{这一行不是 JSON\n")
            } finally {
                sink.close()
            }
        }
        log.append(message(2, StoredMessage.ROLE_ASSISTANT, "第二条"))

        assertEquals(listOf(1L, 2L), log.tail(10).map { it.seq })
    }

    @Test
    fun `clear empties the log`() = runTest {
        val log = newStore()
        log.append(message(1, StoredMessage.ROLE_USER, "hi"))
        log.clear()
        assertTrue(log.tail(10).isEmpty())
    }

    /** 路径契约：聊天记录必须落在 `chat/chat_log.jsonl`。 */
    @Test
    fun `the log lives at the canonical path`() = runTest {
        newStore().append(message(1, StoredMessage.ROLE_USER, "hi"))
        assertTrue(fileSystem.exists(root / "chat" / "chat_log.jsonl"))
    }
}

/** 记忆文件的读写契约。 */
class CatMemoryStoreTest {

    private val fileSystem = FakeFileSystem()
    private val paths = AppPaths("/data".toPath())

    @AfterTest
    fun tearDown() {
        fileSystem.checkNoOpenFiles()
    }

    private fun newStore() = CatMemoryStore(fileSystem, paths.catMemory, Dispatchers.Unconfined)

    @Test
    fun `memory survives a save and load round trip`() = runTest {
        val store = newStore()
        val memory = CatMemory(
            facts = listOf(
                MemoryFact(MemoryCategory.OWNER, "名字", "小明", 5L, 3L, pinned = true),
                MemoryFact(MemoryCategory.PREFERENCE, "火锅", "喜欢", 6L, 4L)
            ),
            lastExtractedSeq = 12L,
            lastExtractedAt = 99L
        )
        store.save(memory)
        assertEquals(memory, store.load())
    }

    @Test
    fun `an unreadable file reads as empty memory instead of crashing`() = runTest {
        fileSystem.createDirectories(paths.catMemory.parent!!)
        fileSystem.write(paths.catMemory) { writeUtf8("{ 半个 JSON") }
        assertEquals(CatMemory(), newStore().load())
    }

    @Test
    fun `an absent file reads as empty memory`() = runTest {
        assertEquals(CatMemory(), newStore().load())
    }

    /** 路径契约：记忆必须落在 `chat/cat_memory.json`。 */
    @Test
    fun `the memory file lives at the canonical path`() = runTest {
        newStore().save(CatMemory())
        assertTrue(fileSystem.exists("/data".toPath() / "chat" / "cat_memory.json"))
    }
}
