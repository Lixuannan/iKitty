package com.codingcow.ikitty

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 与 `java.util.zip` 的双向互操作。
 *
 * 这是"跨端互导"这个承诺的真正证据，也是 iOS 能不能读**已经存在的**
 * Android 备份的关键：Android 用 `ZipOutputStream` 的默认 DEFLATE 写，
 * 所以这里的核心用例是用 `java.util.zip` 压出 DEFLATE 归档，再用共享实现读。
 */
class ZipInteropTest {

    private val entries = listOf(
        ZipEntryData("manifest.json", """{"version":1,"app":"iKitty"}""".encodeToByteArray()),
        ZipEntryData("chat/chat_log.jsonl", "你好\n喵～\n".encodeToByteArray()),
        ZipEntryData("chat/images/a.jpg", ByteArray(4096) { (it * 7).toByte() })
    )

    /** Android 侧的写：默认 DEFLATE。 */
    private fun writeWithJavaZip(
        entries: List<ZipEntryData>,
        comment: String? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            if (comment != null) zip.setComment(comment)
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Android 侧的读。 */
    private fun readWithJavaZip(archive: ByteArray): List<ZipEntryData> {
        val result = mutableListOf<ZipEntryData>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result += ZipEntryData(entry.name, zip.readBytes())
            }
        }
        return result
    }

    /** Android 导出的 DEFLATE 归档必须能被共享实现读出来——这是互通的前提。 */
    @Test
    fun `a deflated archive written by java util zip is readable`() {
        val archive = writeWithJavaZip(entries)

        val read = ZipCodec.read(archive)

        assertEquals(entries.map { it.name }, read.map { it.name })
        entries.zip(read).forEach { (expected, actual) ->
            assertContentEquals(expected.bytes, actual.bytes, actual.name)
        }
    }

    /** 我们用 STORED 写出来的归档，Android 的 ZipInputStream 必须能读。 */
    @Test
    fun `an archive written here is readable by java util zip`() {
        val archive = ZipCodec.write(entries)

        val read = readWithJavaZip(archive)

        assertEquals(entries.map { it.name }, read.map { it.name })
        entries.zip(read).forEach { (expected, actual) ->
            assertContentEquals(expected.bytes, actual.bytes, actual.name)
        }
    }

    /** 大一点、且高度可压缩的内容：确保 DEFLATE 不是"碰巧因为太小而没走压缩分支"。 */
    @Test
    fun `a large highly compressible entry survives deflate`() {
        val big = "重复的内容".repeat(20_000).encodeToByteArray()
        val archive = writeWithJavaZip(listOf(ZipEntryData("big.txt", big)))

        val read = ZipCodec.read(archive)

        assertContentEquals(big, read.single().bytes)
        // 确认它确实被压过：否则这个用例验证不到解压路径。
        assertTrue(archive.size < big.size / 4, "归档 ${archive.size} 字节，看起来没有被压缩")
    }

    /** 带归档注释时中央目录不再贴着文件末尾。 */
    @Test
    fun `a comment on a deflated archive is tolerated`() {
        val archive = writeWithJavaZip(entries, comment = "iKitty backup")
        val read = ZipCodec.read(archive)
        assertEquals(entries.map { it.name }, read.map { it.name })
    }

    /** 混合两种压缩方式也要能读：不同工具/不同时间写出的归档可能混着来。 */
    @Test
    fun `stored and deflated entries can be mixed`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            val stored = ZipEntry("stored.txt").apply {
                val bytes = "不压缩".encodeToByteArray()
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = java.util.zip.CRC32().apply { update(bytes) }.value
            }
            zip.putNextEntry(stored)
            zip.write("不压缩".encodeToByteArray())
            zip.closeEntry()

            zip.setMethod(ZipOutputStream.DEFLATED)
            zip.putNextEntry(ZipEntry("deflated.txt"))
            zip.write("要压缩".repeat(500).encodeToByteArray())
            zip.closeEntry()
        }

        val read = ZipCodec.read(out.toByteArray())
        assertEquals(listOf("stored.txt", "deflated.txt"), read.map { it.name })
        assertEquals("不压缩", read[0].bytes.decodeToString())
    }
}
