package com.codingcow.ikitty

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全屏查看聊天记录里的一张原图。
 *
 * 与列表里 512px 的缩略图不同，这里按 [PREVIEW_PIXELS] 重新采样解码，
 * 所以还能看清原图细节；支持双指缩放与拖动，点右上角或按返回键关闭。
 */
@Composable
fun ImagePreviewDialog(name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val file = remember(name) { ImageStore(context).file(name) }
    val state by produceState<PreviewState>(initialValue = PreviewState.Loading, file) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(file, PREVIEW_PIXELS)
                ?.let { PreviewState.Ready(it.asImageBitmap()) }
                ?: PreviewState.Missing
        }
    }

    var scale by remember(name) { mutableFloatStateOf(1f) }
    var offset by remember(name) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = next
        offset = clampPan(offset + panChange, next, viewport)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            when (val current = state) {
                PreviewState.Loading -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )

                PreviewState.Missing -> Text(
                    text = "这张图片已经找不到了",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )

                is PreviewState.Ready -> Image(
                    bitmap = current.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewport = it }
                        .transformable(transformState)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭大图",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 大图的加载状态：区分「还在解码」与「文件已经不在了」，避免占位转圈停不下来。 */
private sealed interface PreviewState {
    data object Loading : PreviewState
    data object Missing : PreviewState
    data class Ready(val bitmap: ImageBitmap) : PreviewState
}

/** 大图解码的最长边。比缩略图大，又不会把 1280px 的原图放大出内存峰值。 */
private const val PREVIEW_PIXELS = 2048
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

/**
 * 限制放大后的拖动范围，避免把图片拖出屏幕之后找不回来。
 *
 * 以视口的一半为基准：放大 [scale] 倍时，最多只能越过边缘这么多。
 */
internal fun clampPan(offset: Offset, scale: Float, viewport: IntSize): Offset {
    if (scale <= MIN_SCALE) return Offset.Zero
    val limitX = viewport.width * (scale - 1f) / 2f
    val limitY = viewport.height * (scale - 1f) / 2f
    return Offset(
        x = offset.x.coerceIn(-limitX, limitX),
        y = offset.y.coerceIn(-limitY, limitY)
    )
}
