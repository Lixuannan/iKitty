package com.codingcow.ikitty

import okio.Buffer
import okio.Inflater
import okio.InflaterSource
import okio.buffer

/** ZIP 里的一个条目。归档里存的都是小文件（JSONL、记忆、图片），所以整段放在内存里够用。 */
class ZipEntryData(val name: String, val bytes: ByteArray)

/** 归档不是合法 ZIP，或用了不支持的压缩方式。 */
class ZipFormatException(message: String) : Exception(message)

/**
 * 跨平台的 ZIP 读写。
 *
 * **写**只用 STORED（不压缩）：`.ikitty` 里主要是已经压过的 JPEG 和体积很小的 JSON，
 * 再压一遍收益有限，而 STORED 的写入不需要任何压缩实现。格式是标准 ZIP，
 * Android 的 `java.util.zip` 能正常读。
 *
 * **读**同时支持 STORED 与 DEFLATE：Android 导出用的是 `ZipOutputStream` 的默认
 * DEFLATE，所以 iOS 必须能解压，否则读不了已经存在的备份。解压交给 okio 的
 * [InflaterSource]（它的 `Inflater` 是 raw deflate，正好对应 ZIP 的压缩数据），
 * 于是 commonMain 里就有了一份两端共用的实现。
 *
 * 只实现需要的子集：不支持加密、ZIP64、多卷、以及带数据描述符的流式写入。
 * 这些都是"我们自己写、我们自己读"的归档，超出子集就明确报错，而不是猜。
 */
object ZipCodec {

    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50

    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8

    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val EOCD_SIZE = 22

    /**
     * 固定写入 1980-01-01 00:00 这个 MS-DOS 时间戳。
     *
     * 归档内容才是要保真的东西，时间戳不是；写死可以让同样的输入产出**逐字节相同**的
     * 归档（测试可以直接比对），也避免依赖跑归档那台机器的时区。
     * 0 值不是合法的 MS-DOS 日期（"日"从 1 开始），所以不能用 0。
     */
    private const val DOS_TIME = 0
    private const val DOS_DATE = 0x0021

    fun write(entries: List<ZipEntryData>): ByteArray {
        val out = Buffer()
        val central = Buffer()
        var offset = 0

        entries.forEach { entry ->
            val name = entry.name.encodeToByteArray()
            val crc = crc32(entry.bytes)
            val size = entry.bytes.size

            out.writeIntLe(LOCAL_HEADER_SIGNATURE)
            out.writeShortLe(20) // version needed
            out.writeShortLe(0) // flags
            out.writeShortLe(METHOD_STORED)
            out.writeShortLe(DOS_TIME)
            out.writeShortLe(DOS_DATE)
            out.writeIntLe(crc)
            out.writeIntLe(size) // compressed == uncompressed for STORED
            out.writeIntLe(size)
            out.writeShortLe(name.size)
            out.writeShortLe(0) // extra length
            out.write(name)
            out.write(entry.bytes)

            central.writeIntLe(CENTRAL_HEADER_SIGNATURE)
            central.writeShortLe(20) // version made by
            central.writeShortLe(20) // version needed
            central.writeShortLe(0)
            central.writeShortLe(METHOD_STORED)
            central.writeShortLe(DOS_TIME)
            central.writeShortLe(DOS_DATE)
            central.writeIntLe(crc)
            central.writeIntLe(size)
            central.writeIntLe(size)
            central.writeShortLe(name.size)
            central.writeShortLe(0) // extra
            central.writeShortLe(0) // comment
            central.writeShortLe(0) // disk number start
            central.writeShortLe(0) // internal attributes
            central.writeIntLe(0) // external attributes
            central.writeIntLe(offset)
            central.write(name)

            offset += LOCAL_HEADER_SIZE + name.size + size
        }

        val centralBytes = central.readByteArray()
        val centralOffset = offset

        out.write(centralBytes)
        out.writeIntLe(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        out.writeShortLe(0) // this disk
        out.writeShortLe(0) // disk with central directory
        out.writeShortLe(entries.size)
        out.writeShortLe(entries.size)
        out.writeIntLe(centralBytes.size)
        out.writeIntLe(centralOffset)
        out.writeShortLe(0) // comment length

        return out.readByteArray()
    }

    fun read(archive: ByteArray): List<ZipEntryData> {
        val eocd = findEndOfCentralDirectory(archive)
            ?: throw ZipFormatException("不是合法的 ZIP：找不到中央目录结尾记录")

        val entryCount = archive.readShortLe(eocd + 10)
        val centralOffset = archive.readIntLe(eocd + 16)
        if (centralOffset < 0 || centralOffset >= archive.size) {
            throw ZipFormatException("中央目录偏移越界：$centralOffset")
        }

        val entries = mutableListOf<ZipEntryData>()
        var cursor = centralOffset
        repeat(entryCount) {
            if (archive.readIntLe(cursor) != CENTRAL_HEADER_SIGNATURE) {
                throw ZipFormatException("中央目录第 ${entries.size} 条记录签名不对")
            }
            val method = archive.readShortLe(cursor + 10)
            val crc = archive.readIntLe(cursor + 16)
            val compressedSize = archive.readIntLe(cursor + 20)
            val nameLength = archive.readShortLe(cursor + 28)
            val extraLength = archive.readShortLe(cursor + 30)
            val commentLength = archive.readShortLe(cursor + 32)
            val localOffset = archive.readIntLe(cursor + 42)
            val name = archive.decodeToString(cursor + CENTRAL_HEADER_SIZE, nameLength)

            entries += ZipEntryData(
                name = name,
                bytes = readEntryData(archive, localOffset, method, compressedSize, crc, name)
            )

            cursor += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
        }
        return entries
    }

    private fun readEntryData(
        archive: ByteArray,
        localOffset: Int,
        method: Int,
        compressedSize: Int,
        crc: Int,
        name: String
    ): ByteArray {
        if (archive.readIntLe(localOffset) != LOCAL_HEADER_SIGNATURE) {
            throw ZipFormatException("$name 的本地文件头签名不对")
        }
        val localNameLength = archive.readShortLe(localOffset + 26)
        val localExtraLength = archive.readShortLe(localOffset + 28)
        val dataStart = localOffset + LOCAL_HEADER_SIZE + localNameLength + localExtraLength
        if (dataStart + compressedSize > archive.size) {
            throw ZipFormatException("$name 的数据越界")
        }
        val raw = archive.copyOfRange(dataStart, dataStart + compressedSize)

        val bytes = when (method) {
            METHOD_STORED -> raw
            METHOD_DEFLATED -> inflateRaw(raw, name)
            else -> throw ZipFormatException("$name 用了不支持的压缩方式：$method")
        }

        if (crc32(bytes) != crc) {
            throw ZipFormatException("$name 的校验和不匹配，归档可能已损坏")
        }
        return bytes
    }

    /**
     * ZIP 的压缩数据是 **raw deflate**（没有 zlib / gzip 头），所以要 `nowrap = true`。
     *
     * 注意 okio 的 `Inflater()` 无参构造是**带 zlib 头**的那一种；用它去解 ZIP 会得到
     * `incorrect header check`。这个区别只有拿 `java.util.zip` 真的压一份出来读才会暴露，
     * 自己写的归档自己是发现不了的（我们写 STORED，根本不走这里）。
     *
     * 参数只能按位置传：在 JVM 上 `Inflater` 是 `java.util.zip.Inflater` 的类型别名，
     * 具名参数对 Java 构造函数不可用。
     */
    private fun inflateRaw(raw: ByteArray, name: String): ByteArray {
        val source = InflaterSource(Buffer().write(raw), Inflater(true))
        val buffered = source.buffer()
        return try {
            buffered.readByteArray()
        } catch (e: Exception) {
            throw ZipFormatException("$name 解压失败：${e.message}")
        } finally {
            buffered.close()
        }
    }

    /**
     * 从后往前找中央目录结尾记录。
     *
     * 它不在固定位置：后面还可能跟最多 64 KB 的归档注释。
     */
    private fun findEndOfCentralDirectory(archive: ByteArray): Int? {
        if (archive.size < EOCD_SIZE) return null
        val earliest = maxOf(0, archive.size - EOCD_SIZE - MAX_COMMENT_BYTES)
        for (offset in archive.size - EOCD_SIZE downTo earliest) {
            if (archive.readIntLe(offset) == END_OF_CENTRAL_DIRECTORY_SIGNATURE) return offset
        }
        return null
    }

    private const val MAX_COMMENT_BYTES = 0xFFFF
}

// ---- 小端读写 ----
// ZIP 全部字段都是小端；Kotlin 没有现成的字节数组小端读写，所以自己写，
// 并且都在越界前检查，避免坏归档把异常抛成 ArrayIndexOutOfBounds。

private fun Buffer.writeShortLe(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value ushr 8) and 0xFF)
}

private fun Buffer.writeIntLe(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value ushr 8) and 0xFF)
    writeByte((value ushr 16) and 0xFF)
    writeByte((value ushr 24) and 0xFF)
}

private fun ByteArray.readShortLe(offset: Int): Int {
    if (offset < 0 || offset + 1 >= size) throw ZipFormatException("读取 16 位字段越界：$offset")
    return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.readIntLe(offset: Int): Int {
    if (offset < 0 || offset + 3 >= size) throw ZipFormatException("读取 32 位字段越界：$offset")
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
}

private fun ByteArray.decodeToString(offset: Int, length: Int): String {
    if (offset < 0 || length < 0 || offset + length > size) {
        throw ZipFormatException("读取文件名字段越界：$offset+$length")
    }
    return copyOfRange(offset, offset + length).decodeToString()
}

private val CRC_TABLE: IntArray = IntArray(256) { index ->
    var value = index
    repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0xEDB88320.toInt() else value ushr 1 }
    value
}

/** 标准 CRC-32（反射，多项式 0xEDB88320），与 `java.util.zip.CRC32` 一致。 */
internal fun crc32(bytes: ByteArray): Int {
    var crc = 0xFFFFFFFF.toInt()
    bytes.forEach { byte ->
        crc = (crc ushr 8) xor CRC_TABLE[(crc xor byte.toInt()) and 0xFF]
    }
    return crc.inv()
}
