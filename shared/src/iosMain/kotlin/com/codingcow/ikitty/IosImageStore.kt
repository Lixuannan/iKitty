package com.codingcow.ikitty

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * iOS 侧的聊天图片存储。
 *
 * 存的是"已经归一化好的 JPEG"：Swift 侧用 CoreGraphics 把相册/相机拿到的图降采样、
 * 按 EXIF 摆正、铺白底再编码，然后整段字节交给这里落盘。像素操作留在平台层，
 * 文件命名、目录、数据 URL 缓存这些跨端一致的部分留在这里。
 *
 * 缓存是必要的：装配上下文时每次都要把历史图片编成数据 URL，一张 200 KB 的图
 * base64 之后是 270 KB 字符串，长对话里反复重算会明显拖慢发送。
 */
class IosImageStore(
    private val fileSystem: FileSystem,
    private val imagesDir: Path,
    private val ioDispatcher: CoroutineDispatcher
) {
    private val cache = LruCache<String, String>(CACHE_LIMIT)

    /** 保存一段 JPEG，返回本机文件名；写失败返回 null（调用方跳过这张图）。 */
    suspend fun save(jpegBytes: ByteArray): String? = withContext(ioDispatcher) {
        if (jpegBytes.isEmpty()) return@withContext null
        val name = newImageFileName()
        val target = imagesDir / name
        try {
            fileSystem.createDirectories(imagesDir)
            fileSystem.write(target) { write(jpegBytes) }
            name
        } catch (_: okio.IOException) {
            // 存储空间不足等：这张图就当作没选。
            null
        }
    }

    /** 一次性把多条图片名编成数据 URL；读不到的（文件被删）直接跳过。 */
    suspend fun dataUrls(names: Collection<String>): Map<String, String> = withContext(ioDispatcher) {
        names.distinct().mapNotNull { name -> dataUrl(name)?.let { name to it } }.toMap()
    }

    /**
     * 丢掉编码缓存。
     *
     * 备份导入后必须调用：归档里的图片按原名覆盖，正常情况下名字是随机的不会撞，
     * 但手工拼出来的备份可以复用旧名字，不清缓存就会继续用旧内容编码。
     */
    fun invalidateCache() {
        cache.clear()
    }

    /**
     * 给 Swift 用的入口：接收 `Data`（在 Kotlin 侧是 `NSData`）。
     *
     * 不定义成 `ByteArray` 参数是因为 Kotlin 的 `ByteArray` 在 Swift 里是
     * `KotlinByteArray`，从 `Data` 转过去要逐元素拷贝；这里用一次 `memcpy`
     * 更直接，也不比逐元素慢。
     */
    @OptIn(ExperimentalForeignApi::class)
    suspend fun saveData(data: NSData): String? = save(data.toByteArray())

    private fun dataUrl(name: String): String? {
        // 命中就当作"最近使用"，这样长对话里反复用到的图不会被一次性的冷图挤出去。
        cache.getAndTouch(name)?.let { return it }
        val path = imagesDir / name
        if (!fileSystem.exists(path)) return null
        val encoded = try {
            jpegDataUrl(fileSystem.read(path) { readByteArray() })
        } catch (_: okio.IOException) {
            return null
        }
        cache.put(name, encoded)
        return encoded
    }
}

/**
 * 数据 URL 的缓存上限。
 *
 * 和 Android 侧一致取 12：装配上下文时**每一条历史消息里的每一张图**都会被问一次，
 * 不设上限的话这个缓存会一路涨到"这次会话出现过的所有图片"，成了内存泄漏。
 * 一张 200 KB 的图 base64 之后约 270 KB，12 张约 3 MB，是一个合理的上限。
 */
private const val CACHE_LIMIT = 12
