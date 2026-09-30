package com.codingcow.ikitty

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/**
 * iOS 侧的 SHA-256（真机与模拟器共用这一份）。
 *
 * 用 CommonCrypto 而不是 CryptoKit：CommonCrypto 是 C API，Kotlin/Native 自带它的 cinterop
 * （CryptoKit 是 Swift-only，Kotlin 侧没有对应 klib）。用一次性的 `CC_SHA256` 而不是
 * Init/Update/Final 三段式：整段字节本来就在内存里，三段式只会多出要管理生命周期的上下文。
 *
 * 空输入单独处理：`usePinned` 不接受空数组，而"空图片"是可能出现的输入（不该崩）。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    // 空输入时借一个字节的缓冲区：长度传 0，摘要结果与直接对空输入求值一致。
    val source = if (bytes.isEmpty()) ByteArray(1) else bytes

    source.usePinned { sourcePinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(
                sourcePinned.addressOf(0),
                bytes.size.convert(),
                digestPinned.addressOf(0).reinterpret()
            )
        }
    }
    return digest
}
