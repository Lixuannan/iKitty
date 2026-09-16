package com.codingcow.ikitty

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 追加式记录的读写契约：长对话全靠它不丢东西。 */
class ChatLogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newStore(file: File = folder.newFile("chat_log.jsonl")) =
        ChatLogStore(file) { 1_000L }

    private fun message(seq: Long, role: String, content: String) =
        StoredMessage(seq = seq, role = role, content = content, createdAt = seq)

    @Test
    fun `append then tail round trips a message`() = runBlocking {
        val log = newStore()
        log.append(message(1, StoredMessage.ROLE_ASSISTANT, "喵～你好呀"))
        log.append(message(2, StoredMessage.ROLE_USER, "今天有点累"))

        val loaded = log.tail(10)
        assertEquals(listOf(1L, 2L), loaded.map { it.seq })
        assertEquals("今天有点累", loaded.last().content)
        assertEquals(2L, loaded.last().createdAt)
    }

    @Test
    fun `tail returns only the newest entries`() = runBlocking {
        val log = newStore()
        (1..50).forEach { log.append(message(it.toLong(), StoredMessage.ROLE_USER, "第 $it 条")) }

        assertEquals((46L..50L).toList(), log.tail(5).map { it.seq })
        assertTrue(log.tail(0).isEmpty())
    }

    @Test
    fun `reading across block boundaries does not corrupt chinese text`() = runBlocking {
        val log = newStore()
        // 一个汉字 3 字节：几条消息就跨过多个 8192 字节的读块，块边界落在完整消息中间。
        val filler = "汉".repeat(1_200)
        (1..12).forEach { log.append(message(it.toLong(), StoredMessage.ROLE_USER, "$it-$filler")) }

        val loaded = log.tail(100)
        assertEquals((1L..12L).toList(), loaded.map { it.seq })
        loaded.forEach { msg ->
            assertFalse("内容被截断：${msg.content.take(20)}", msg.content.contains('\uFFFD'))
            assertTrue(msg.content.endsWith(filler))
        }
    }

    @Test
    fun `readAfter returns only what is past the cursor`() = runBlocking {
        val log = newStore()
        (1..10).forEach { log.append(message(it.toLong(), StoredMessage.ROLE_USER, "m$it")) }

        assertEquals(listOf(9L, 10L), log.readAfter(8, 10).map { it.seq })
        assertEquals(listOf(1L, 2L), log.readAfter(0, 2).map { it.seq })
    }

    @Test
    fun `a corrupt line does not take the rest of the log down`() = runBlocking {
        val file = folder.newFile("chat_log.jsonl")
        val log = ChatLogStore(file) { 1_000L }
        log.append(message(1, StoredMessage.ROLE_USER, "第一条"))
        file.appendText("{这一行不是 JSON\n")
        log.append(message(2, StoredMessage.ROLE_ASSISTANT, "第二条"))

        assertEquals(listOf(1L, 2L), log.tail(10).map { it.seq })
    }

    @Test
    fun `clear empties the log`() = runBlocking {
        val log = newStore()
        log.append(message(1, StoredMessage.ROLE_USER, "hi"))
        log.clear()
        assertTrue(log.tail(10).isEmpty())
    }
}

/** 记忆文件的读写契约。 */
class CatMemoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `memory survives a save and load round trip`() = runBlocking {
        val store = CatMemoryStore(folder.newFile("cat_memory.json"))
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
    fun `an unreadable file reads as empty memory instead of crashing`() = runBlocking {
        val file = folder.newFile("cat_memory.json")
        file.writeText("{ 半个 JSON")
        assertEquals(CatMemory(), CatMemoryStore(file).load())
    }
}
