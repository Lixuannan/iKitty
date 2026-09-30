package com.codingcow.ikitty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 图片的共享约定。
 *
 * 数据 URL 是请求体的一部分，所以拼法必须逐字节固定；文件名由内容哈希决定，
 * 所以形状与"内容相同则名字相同"都必须稳定——云端按它去重。
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
    fun `an image id is a 32 hex content hash with the expected shape`() {
        val id = imageIdFor(byteArrayOf(0, 1, 2, 3))
        assertTrue(id.startsWith("img_"), id)
        assertTrue(id.endsWith(".jpg"), id)
        assertEquals(40, id.length, id)
        assertTrue(id.substring(4, 36).all { it in "0123456789abcdef" }, id)
        assertTrue(isImageId(id))
    }

    @Test
    fun `the same bytes always produce the same id`() {
        // 内容寻址的前提：同一张图在两台设备上必须得到同一个名字，否则去重就失效了。
        val bytes = "同一张图".encodeToByteArray()
        assertEquals(imageIdFor(bytes), imageIdFor(bytes.copyOf()))
    }

    @Test
    fun `different bytes produce different ids`() {
        assertNotEquals(imageIdFor(byteArrayOf(1)), imageIdFor(byteArrayOf(2)))
    }

    @Test
    fun `anything that is not a content hash is rejected`() {
        // 这些名字可能来自旧版本、手工放进来的文件，或者云端清单（外部输入），
        // 一律不能当作本地路径或上传标识使用。
        assertFalse(isImageId("img_abc.jpg"))
        assertFalse(isImageId("img_" + "g".repeat(32) + ".jpg"))
        assertFalse(isImageId("../../etc/passwd"))
        assertFalse(isImageId("img_" + "a".repeat(33) + ".jpg"))
        assertFalse(isImageId(""))
    }
}
