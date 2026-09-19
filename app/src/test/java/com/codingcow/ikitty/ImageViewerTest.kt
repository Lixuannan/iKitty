package com.codingcow.ikitty

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 大图查看里与 Android 无关的纯逻辑：拖动范围的钳制。
 *
 * 真正的解码、缩放与手势要在设备上验证。
 */
class ImageViewerTest {

    @Test
    fun `panning is ignored while the image is not zoomed`() {
        assertEquals(
            Offset.Zero,
            clampPan(Offset(120f, -80f), scale = 1f, viewport = IntSize(1000, 500))
        )
    }

    @Test
    fun `panning is clamped to the overflow of the zoomed image`() {
        val viewport = IntSize(1000, 500)
        // scale = 2 时横向可移 500、纵向可移 250。
        assertEquals(Offset(500f, -250f), clampPan(Offset(900f, -900f), scale = 2f, viewport = viewport))
        // 没超界就原样保留。
        assertEquals(Offset(-120f, 80f), clampPan(Offset(-120f, 80f), scale = 2f, viewport = viewport))
    }

    @Test
    fun `clamping grows with the zoom level`() {
        val viewport = IntSize(1000, 500)
        assertEquals(Offset(1000f, 500f), clampPan(Offset(9999f, 9999f), scale = 3f, viewport = viewport))
    }
}
