package com.codingcow.ikitty

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ZIP 编解码的自身契约。
 *
 * 与 `java.util.zip` 的互操作在 `jvmTest/ZipInteropTest` 里验证——那才是"Android 导出的
 * 备份能不能在 iOS 读"的真正证据。
 */
class ZipTest {

    @Test
    fun `crc32 matches the standard check value`() {
        // "123456789" 的 CRC-32 是公认的校验值。
        assertEquals(0xCBF43926.toInt(), crc32("123456789".encodeToByteArray()))
        assertEquals(0, crc32(ByteArray(0)))
    }

    @Test
    fun `a single entry round trips`() {
        val entries = listOf(ZipEntryData("chat/chat_log.jsonl", "你好\n世界\n".encodeToByteArray()))
        val archive = ZipCodec.write(entries)

        val read = ZipCodec.read(archive)
        assertEquals(1, read.size)
        assertEquals("chat/chat_log.jsonl", read.single().name)
        assertContentEquals(entries.single().bytes, read.single().bytes)
    }

    @Test
    fun `multiple entries keep their order and names`() {
        val entries = listOf(
            ZipEntryData("manifest.json", """{"version":1}""".encodeToByteArray()),
            ZipEntryData("chat/cat_memory.json", """{"version":1,"facts":[]}""".encodeToByteArray()),
            ZipEntryData("chat/images/a.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)),
            ZipEntryData("chat/images/b.jpg", ByteArray(5000) { it.toByte() })
        )
        val read = ZipCodec.read(ZipCodec.write(entries))

        assertEquals(entries.map { it.name }, read.map { it.name })
        entries.zip(read).forEach { (expected, actual) ->
            assertContentEquals(expected.bytes, actual.bytes, actual.name)
        }
    }

    /** 空文件也要能往返：备份里可能有空的图片或空日志。 */
    @Test
    fun `empty entries round trip`() {
        val read = ZipCodec.read(ZipCodec.write(listOf(ZipEntryData("empty", ByteArray(0)))))
        assertEquals(1, read.size)
        assertEquals(0, read.single().bytes.size)
    }

    @Test
    fun `an archive with no entries is still a valid zip`() {
        val archive = ZipCodec.write(emptyList())
        assertTrue(ZipCodec.read(archive).isEmpty())
    }

    /** 同样的输入必须产出同样的字节：归档格式是可比对、可复现的。 */
    @Test
    fun `writing is deterministic`() {
        val entries = listOf(
            ZipEntryData("a.txt", "abc".encodeToByteArray()),
            ZipEntryData("b.txt", "defg".encodeToByteArray())
        )
        assertContentEquals(ZipCodec.write(entries), ZipCodec.write(entries))
    }

    /** 多字节文件名（UTF-8）要能原样往返。 */
    @Test
    fun `non-ascii file names round trip`() {
        val read = ZipCodec.read(ZipCodec.write(listOf(ZipEntryData("图片/小猫咪.jpg", byteArrayOf(1, 2, 3)))))
        assertEquals("图片/小猫咪.jpg", read.single().name)
    }

    @Test
    fun `a non-zip input is rejected instead of half-parsed`() {
        assertFailsWith<ZipFormatException> { ZipCodec.read("<html>not a zip</html>".encodeToByteArray()) }
        assertFailsWith<ZipFormatException> { ZipCodec.read(ByteArray(0)) }
    }

    /** 内容被改过必须被发现：靠条目自带的 CRC，而不是"读出来了就算对"。 */
    @Test
    fun `a corrupted entry is detected by its crc`() {
        val archive = ZipCodec.write(listOf(ZipEntryData("a.txt", "hello".encodeToByteArray())))
        // 翻掉数据区里的一个字节（头部 30 + 名字 5 之后）。
        val corrupted = archive.copyOf()
        corrupted[30 + 5 + 1] = (corrupted[30 + 5 + 1].toInt() xor 0xFF).toByte()
        assertFailsWith<ZipFormatException> { ZipCodec.read(corrupted) }
    }

    /** 归档末尾可以有注释，中央目录结尾记录的位置因此不固定。 */
    @Test
    fun `a trailing comment does not hide the directory`() {
        val archive = ZipCodec.write(listOf(ZipEntryData("a.txt", "hi".encodeToByteArray())))
        // 手工把注释长度与注释内容贴到末尾，并修正 EOCD 里的注释长度字段。
        val withComment = archive + "这是一段归档注释".encodeToByteArray()
        val eocd = withComment.size - 22 - "这是一段归档注释".encodeToByteArray().size
        val commentLength = "这是一段归档注释".encodeToByteArray().size
        withComment[eocd + 20] = (commentLength and 0xFF).toByte()
        withComment[eocd + 21] = ((commentLength ushr 8) and 0xFF).toByte()

        val read = ZipCodec.read(withComment)
        assertEquals("a.txt", read.single().name)
        assertContentEquals("hi".encodeToByteArray(), read.single().bytes)
    }
}
