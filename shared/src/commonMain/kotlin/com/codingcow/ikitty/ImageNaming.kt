package com.codingcow.ikitty

import kotlin.math.max

/**
 * 图片处理的平台无关部分：文件名到 MIME、解码采样倍率、EXIF 方向的几何含义。
 *
 * 真正读写像素的那一半依赖平台图形栈（Android 是 BitmapFactory，iOS 是 CoreGraphics），
 * 不在这里；但"该转多少度""该采样到几分之一"这类判断必须两端一致，
 * 否则同一张照片在两边会得到不同尺寸或不同朝向的结果。
 */

/** 数据 URL 的 MIME；本机只存 JPEG，其余按名字兜底以兼容手工放入的文件。 */
fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "image/jpeg"
}

/** 让解码后的最长边不超过 [maxDimension] 的采样倍率。 */
fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    while (max(width / sample, height / sample) > maxDimension) sample *= 2
    return sample
}

/**
 * EXIF 方向标签要做的几何变换。
 *
 * 单独抽出来是为了能在纯测试里验证 1–8 八个取值的映射；
 * 真正调用图形 API 的那一步只能在设备上验证。
 */
data class ExifTransform(val degrees: Int, val mirrored: Boolean)

// 标准 EXIF Orientation 取值，与各平台库的常量数值一致。
private const val ORIENTATION_FLIP_HORIZONTAL = 2
private const val ORIENTATION_ROTATE_180 = 3
private const val ORIENTATION_FLIP_VERTICAL = 4
private const val ORIENTATION_TRANSPOSE = 5
private const val ORIENTATION_ROTATE_90 = 6
private const val ORIENTATION_TRANSVERSE = 7
private const val ORIENTATION_ROTATE_270 = 8

/** 把 EXIF 方向取值翻译成「先顺时针旋转 [ExifTransform.degrees] 度，再左右镜像」。 */
fun exifTransformFor(orientation: Int): ExifTransform = when (orientation) {
    ORIENTATION_ROTATE_90 -> ExifTransform(90, false)
    ORIENTATION_ROTATE_180 -> ExifTransform(180, false)
    ORIENTATION_ROTATE_270 -> ExifTransform(270, false)
    ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0, true)
    ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true)
    ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
    ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
    // 含 ORIENTATION_NORMAL / ORIENTATION_UNDEFINED 与认不出的取值：原样返回。
    else -> ExifTransform(0, false)
}
