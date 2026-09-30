package com.codingcow.ikitty

/**
 * Content addressing for chat images.
 *
 * An image's file name **is** its identity everywhere: on disk, in the message's `images`
 * list, in the D1 `images` table, and as the R2 object key. It is derived from the bytes,
 * so two identical images picked on two devices are stored and transferred exactly once.
 *
 * The hash itself is platform-specific ([sha256]) because Kotlin has no common crypto;
 * every decision built on top of it lives here.
 */

/** Name of a content-addressed image, e.g. `img_3f2a…c1.jpg`. */
fun imageIdForDigest(digest: ByteArray): String =
    "img_" + hexLower(digest.copyOf(IMAGE_ID_DIGEST_BYTES)) + IMAGE_EXTENSION

/** Name of a content-addressed image computed from its bytes. */
fun imageIdFor(bytes: ByteArray): String = imageIdForDigest(sha256(bytes))

/**
 * Whether [name] is a content-addressed image id.
 *
 * The same shape the Worker accepts: anything else (old random names, hand-copied files,
 * path traversal attempts) must not be uploaded or requested.
 */
fun isImageId(name: String): Boolean =
    name.length == IMAGE_ID_LENGTH &&
        name.startsWith("img_") &&
        name.endsWith(IMAGE_EXTENSION) &&
        name.substring(4, name.length - IMAGE_EXTENSION.length).all { it in HEX_LOWER }

/** 32 hex characters = 128 bits of the digest; collisions are not a practical concern here. */
internal const val IMAGE_ID_DIGEST_BYTES = 16

internal const val IMAGE_EXTENSION = ".jpg"

/** `img_` + 32 hex + `.jpg`. */
internal const val IMAGE_ID_LENGTH = 40

/** 内容寻址与"这台设备是谁"都需要摘要，所以摘要本身是共享契约，实现按平台给（[sha256]）。 */
internal const val HEX_LOWER = "0123456789abcdef"
