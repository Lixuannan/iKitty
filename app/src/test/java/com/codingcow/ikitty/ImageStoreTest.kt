package com.codingcow.ikitty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片导入里与 Android 无关的纯逻辑：采样倍率与数据 URL 的 MIME。
 *
 * 真正的解码/压缩路径依赖 BitmapFactory，只能在设备上验证。
 */
class ImageStoreTest {

    @Test
    fun `sampling keeps the longest edge within the limit`() {
        // 4000 -> 2000 -> 1000，第一次小于等于 1280 的倍率是 4。
        assertEquals(4, sampleSizeFor(width = 4000, height = 3000, maxDimension = 1280))
        // 已经足够小就不采样。
        assertEquals(1, sampleSizeFor(width = 800, height = 600, maxDimension = 1280))
    }

    @Test
    fun `sampling uses the longer edge`() {
        // 高比宽长，采样必须跟着长边走。
        assertEquals(4, sampleSizeFor(width = 3000, height = 5000, maxDimension = 1280))
    }

    @Test
    fun `sampled dimensions never exceed the limit`() {
        val samples = listOf(1, 2, 3, 4, 8, 16)
        samples.forEach { ratio ->
            val width = 1280 * ratio
            val height = 960 * ratio
            val sample = sampleSizeFor(width, height, 1280)
            assertTrue("$width x $height 采样后仍超过上限", width / sample <= 1280)
            assertTrue(height / sample <= 1280)
        }
    }

    @Test
    fun `mime falls back to jpeg for the names we store`() {
        assertEquals("image/jpeg", mimeFor("img_1234.jpg"))
        assertEquals("image/png", mimeFor("hand_placed.PNG"))
        assertEquals("image/webp", mimeFor("a.webp"))
        assertEquals("image/jpeg", mimeFor("noextension"))
    }

    @Test
    fun `exif orientation maps to the expected rotation and mirroring`() {
        // 1 正常、2 左右镜像、3 旋转 180、4 上下镜像（= 旋转 180 再镜像）。
        assertEquals(ExifTransform(0, false), exifTransformFor(1))
        assertEquals(ExifTransform(0, true), exifTransformFor(2))
        assertEquals(ExifTransform(180, false), exifTransformFor(3))
        assertEquals(ExifTransform(180, true), exifTransformFor(4))
        // 5/6/7/8：竖拍照片最常见的是 6，转 90 度。
        assertEquals(ExifTransform(90, true), exifTransformFor(5))
        assertEquals(ExifTransform(90, false), exifTransformFor(6))
        assertEquals(ExifTransform(270, true), exifTransformFor(7))
        assertEquals(ExifTransform(270, false), exifTransformFor(8))
    }

    @Test
    fun `unknown exif orientation leaves the pixels untouched`() {
        // 0 是 UNDEFINED，其余取值同样按「不认识就不转」处理。
        assertEquals(ExifTransform(0, false), exifTransformFor(0))
        assertEquals(ExifTransform(0, false), exifTransformFor(99))
    }
}
