package com.codingcow.ikitty

import kotlin.random.Random

/**
 * 图片的共享约定：归一化参数、本机文件名、数据 URL 的拼法。
 *
 * **像素操作不在这里**。真正解码、降采样、按 EXIF 摆正、铺白底、压成 JPEG 的那一步
 * 依赖各平台图形栈（Android 是 BitmapFactory，iOS 是 CoreGraphics），
 * 它属于平台层，和"从哪里选图"一样是平台的事。
 *
 * 但下面这几样必须两端一致，否则同一张图在两边会得到不同的请求体：
 * - 最长边与 JPEG 质量（决定发给模型的图有多大）；
 * - 数据 URL 的拼法（是线上契约的一部分）。
 */

/** 归一化后的最长边。 */
const val IMAGE_MAX_DIMENSION = 1280

/** 归一化后的 JPEG 质量（1–100）。 */
const val IMAGE_JPEG_QUALITY = 85

/**
 * 本机图片文件名。
 *
 * 名字只是本机主键，不参与跨端契约，但必须是随机的：不同图片撞名会互相覆盖，
 * 而备份里可能的同名文件也更难分辨。
 */
fun newImageFileName(random: Random = Random.Default): String =
    "img_" + (0 until 16).map { HEX[random.nextInt(HEX.length)] }.joinToString("") + ".jpg"

/**
 * 把已经编码好的 JPEG 字节拼成数据 URL。
 *
 * 逐字节固定在这里，是因为它是请求体的一部分：差一个字符服务商就可能不认。
 * 不带换行——数据 URL 里出现换行会让部分服务商解析失败。
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun jpegDataUrl(bytes: ByteArray): String =
    "data:image/jpeg;base64," + kotlin.io.encoding.Base64.encode(bytes)

private const val HEX = "0123456789abcdef"
