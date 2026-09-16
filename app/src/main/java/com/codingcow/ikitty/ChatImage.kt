package com.codingcow.ikitty

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 显示一条消息或一张待发送图片的本机缩略图。
 *
 * 图片来自应用私有目录，按 [maxPixels] 采样解码，不会把整张原图读进内存。
 * 解码完成前保持一个浅色占位块，避免列表在滚动时跳动。
 */
@Composable
fun ChatImage(
    name: String,
    modifier: Modifier = Modifier,
    maxPixels: Int = THUMBNAIL_PIXELS
) {
    val context = LocalContext.current
    val file = remember(name) { ImageStore(context).file(name) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file, maxPixels) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(file, maxPixels)?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** 缩略图解码的最长边；列表和待发送条都用它。 */
private const val THUMBNAIL_PIXELS = 512
