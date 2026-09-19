package com.codingcow.ikitty

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 聊天图片的本机存储。
 *
 * 选中的图片一律**先复制进应用私有目录**再记录文件名，原因是相册返回的 `content://`
 * URI 只在本次进程内可读：不复制的话，重启后聊天记录里的图片就会变成空白。
 *
 * 所有图片统一降采样并转成 JPEG：既把请求体大小限制在可控范围，也让发送时只需要一种 MIME，
 * 不必关心来源是相册、相机还是其他应用。
 */
class ImageStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR)
    private val tempDir = File(appContext.cacheDir, TEMP_DIR)

    /** 已编码数据 URL 的内存缓存：文件不会被改写，所以可以放心按文件名缓存。 */
    private val dataUrlCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_LIMIT
    }

    fun file(name: String): File = File(dir, name)

    /** 相机需要的目标位置：先写缓存目录，拍照成功后再收编。 */
    fun newCameraTarget(): CameraTarget {
        tempDir.mkdirs()
        val file = File(tempDir, "cam_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        return CameraTarget(file, uri)
    }

    suspend fun importAll(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        // 逐个兜住异常：个别 URI 读不了（权限被收回、文件已被删）不该让整批导入失败。
        uris.mapNotNull { uri ->
            runCatching { importBlocking { resolver.openInputStream(uri) } }.getOrNull()
        }
    }

    /** 拍照成功就收编成正式图片；取消或失败直接删掉临时文件。 */
    suspend fun commitCamera(target: File): String? = withContext(Dispatchers.IO) {
        val name = runCatching { importBlocking { target.inputStream() } }.getOrNull()
        target.delete()
        name
    }

    /** 一次性编码多条；用于装配上下文时把历史图片还原成请求需要的形态。 */
    suspend fun dataUrls(names: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        names.distinct().mapNotNull { name -> dataUrlBlocking(name)?.let { name to it } }.toMap()
    }

    /**
     * 丢掉编码缓存。
     *
     * 备份导入后必须调用：归档里的图片按原名覆盖，正常情况下文件名是 UUID 不会撞，
     * 但手工拼出来的备份可以复用旧名字，不清缓存就会继续用旧内容编码。
     */
    fun invalidateCache() {
        synchronized(dataUrlCache) { dataUrlCache.clear() }
    }

    private fun dataUrlBlocking(name: String): String? {
        synchronized(dataUrlCache) { dataUrlCache[name] }?.let { return it }
        val file = file(name)
        if (!file.isFile) return null
        val encoded = "data:${mimeFor(name)};base64," +
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        synchronized(dataUrlCache) { dataUrlCache[name] = encoded }
        return encoded
    }

    /**
     * 读取图片并统一成 JPEG。任何一步失败都返回 null，由调用方跳过这张图，
     * 而不是让整个发送失败。
     */
    private fun importBlocking(open: () -> InputStream?): String? {
        // 只把"打不开流"当成失败：inJustDecodeBounds 模式下 decodeStream 本来就返回 null，
        // 它只是用来把尺寸填进 bounds，不能据此判断读取失败。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = open() ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // BitmapFactory 不会按 EXIF 自动摆正像素：竖拍照片的像素是横的，方向只写在标签里。
        // 不在这里转正，重新编码后标签就丢了，缩略图和发给模型的原图都会躺倒。
        val orientation = open()?.use { readOrientation(it) } ?: ExifInterface.ORIENTATION_NORMAL

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        val decoded = open()?.use { BitmapFactory.decodeStream(it, null, decodeOptions) } ?: return null

        val scaled = scaleDown(decoded, MAX_DIMENSION)
        val upright = applyExifOrientation(scaled, orientation)
        val flat = flattenAlpha(upright)
        dir.mkdirs()
        val name = "img_${UUID.randomUUID()}.jpg"
        return try {
            val written = file(name).outputStream().use { out ->
                flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (written) name else {
                file(name).delete()
                null
            }
        } finally {
            if (flat !== upright) flat.recycle()
            if (upright !== scaled) upright.recycle()
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    companion object {
        /** 相对 `filesDir` 的图片目录；备份归档按这条路径取文件。 */
        internal const val DIR = "chat/images"

        private const val TEMP_DIR = "chat_camera"
        private const val MAX_DIMENSION = 1280
        private const val JPEG_QUALITY = 85
        private const val CACHE_LIMIT = 12
    }

    data class CameraTarget(val file: File, val uri: Uri)
}

/** 数据 URL 的 MIME；本机只存 JPEG，其余按名字兜底以兼容手工放入的文件。 */
internal fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "image/jpeg"
}

/** 让解码后的最长边不超过 [maxDimension] 的采样倍率。 */
internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    while (max(width / sample, height / sample) > maxDimension) sample *= 2
    return sample
}

private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxDimension) return bitmap
    val ratio = maxDimension.toFloat() / longest
    val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    return if (scaled === bitmap) bitmap else scaled
}

/** JPEG 没有透明通道，直接压缩会把透明区变成黑色，先铺一层白底。 */
private fun flattenAlpha(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    Canvas(flattened).apply {
        drawColor(Color.WHITE)
        drawBitmap(bitmap, 0f, 0f, null)
    }
    return flattened
}

/** 按 [maxPixels] 采样解码，供界面显示缩略图，避免整图进内存。 */
internal fun decodeSampledBitmap(file: File, maxPixels: Int): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

/**
 * EXIF 方向标签要做的几何变换。
 *
 * 单独抽出来是为了能在纯 JVM 测试里验证 1–8 八个取值的映射；
 * 真正调用 [Bitmap.createBitmap] 的那一步只能在设备上验证。
 */
internal data class ExifTransform(val degrees: Int, val mirrored: Boolean)

/** 把 EXIF 方向取值翻译成「先顺时针旋转 [ExifTransform.degrees] 度，再左右镜像」。 */
internal fun exifTransformFor(orientation: Int): ExifTransform = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(90, false)
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(180, false)
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(270, false)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0, true)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true)
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
    // 含 ORIENTATION_NORMAL / ORIENTATION_UNDEFINED 与认不出的取值：原样返回。
    else -> ExifTransform(0, false)
}

/** 读取流里的 EXIF 方向；读不出来（非图片、截断、不支持的格式）一律当作「不用转」。 */
private fun readOrientation(stream: InputStream): Int = runCatching {
    ExifInterface(stream).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

/** 按 [orientation] 把像素摆正；不需要变换时直接返回原图，避免多复制一份。 */
private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val transform = exifTransformFor(orientation)
    if (transform.degrees == 0 && !transform.mirrored) return bitmap
    val matrix = Matrix().apply {
        if (transform.degrees != 0) postRotate(transform.degrees.toFloat())
        if (transform.mirrored) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
