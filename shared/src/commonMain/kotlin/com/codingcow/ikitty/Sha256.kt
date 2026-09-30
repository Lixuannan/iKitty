package com.codingcow.ikitty

/**
 * SHA-256 摘要。
 *
 * 没有放进"构造参数注入"的模式里，因为它是**纯函数**而不是平台资源：
 * 注入它只会让每个调用点都要多传一个参数，而实现本身没有任何可配置之处。
 * 代价是新增目标（例如将来加 macOS）必须提供 `actual`——这正好是想要的：
 * 缺实现的编译错误，比运行期才发现哈希不对要好。
 */
internal expect fun sha256(bytes: ByteArray): ByteArray

/** 把 [bytes] 编成小写十六进制。协议与文件名都用这个形态。 */
internal fun hexLower(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        append(HEX_LOWER[value ushr 4])
        append(HEX_LOWER[value and 0x0F])
    }
}
