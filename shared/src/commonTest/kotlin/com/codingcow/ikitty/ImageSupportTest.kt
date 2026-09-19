package com.codingcow.ikitty

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 图片的共享约定。
 *
 * 数据 URL 是请求体的一部分，所以拼法必须逐字节固定；文件名只要求随机且形状正确。
 */
class ImageSupportTest {

    @Test
    fun `a data url uses the standard base64 alphabet and no line breaks`() {
        // 标准字母表带 '=' 填充，且不能有换行——换行会让部分服务商解析失败。
        assertEquals("data:image/jpeg;base64,AAEC", jpegDataUrl(byteArrayOf(0, 1, 2)))
        assertEquals("data:image/jpeg;base64,aGVsbG8=", jpegDataUrl("hello".encodeToByteArray()))
        assertTrue(!jpegDataUrl(ByteArray(200) { it.toByte() }).contains('\n'))
    }

    @Test
    fun `an empty image still produces a valid data url prefix`() {
        assertEquals("data:image/jpeg;base64,", jpegDataUrl(ByteArray(0)))
    }

    @Test
    fun `file names are jpeg, hex and unique`() {
        val random = Random(1234)
        val names = (1..50).map { newImageFileName(random) }
        names.forEach { name ->
            assertTrue(name.startsWith("img_"), name)
            assertTrue(name.endsWith(".jpg"), name)
            assertEquals(4 + 16 + 4, name.length, name)
            assertTrue(name.substring(4, 20).all { it in "0123456789abcdef" }, name)
        }
        // 撞名会让不同图片互相覆盖，所以必须基本不可能重复。
        assertEquals(names.size, names.toSet().size)
    }
}
