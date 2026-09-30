package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * 图片目录上的同步读写。
 *
 * 图片文件名就是内容哈希（见 [imageIdFor]），所以这里是最薄的一层：
 * "在不在、读出来、写进去"，没有任何查找或映射表。平台类（Android 的 `ImageStore`、
 * iOS 的 `IosImageStore`）各自把这一层接到自己的目录与调度器上。
 *
 * **不负责**压缩与归一化：那些依赖平台图形栈，是平台层的事。从云端下来的图片已经是
 * 归一化后的 JPEG，直接落盘即可。
 */
class FileSystemImageOps(
    private val fileSystem: FileSystem,
    private val imagesDir: Path,
    private val ioDispatcher: CoroutineDispatcher
) : SyncImageOps {

    override fun exists(imageId: String): Boolean =
        isImageId(imageId) && fileSystem.exists(imagesDir / imageId)

    override suspend fun read(imageId: String): ByteArray? = withContext(ioDispatcher) {
        if (!isImageId(imageId)) return@withContext null
        val target = imagesDir / imageId
        if (!fileSystem.exists(target)) return@withContext null
        try {
            fileSystem.read(target) { readByteArray() }
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun write(imageId: String, bytes: ByteArray): Boolean = withContext(ioDispatcher) {
        // 只写自己算得出来的名字：云端清单是外部输入，不能拿它当路径用。
        if (!isImageId(imageId)) return@withContext false
        if (bytes.isEmpty()) return@withContext false
        try {
            fileSystem.createDirectories(imagesDir)
            fileSystem.write(imagesDir / imageId) { write(bytes) }
            true
        } catch (_: IOException) {
            false
        }
    }
}

/** 一次图片迁移的结果，用来决定要不要回写日志。 */
class ImageMigrationResult(
    /** 日志里有引用、本机却找不到的文件个数。 */
    val missingFiles: Int,
    /** 实际重命名（或内容改名）的文件个数。 */
    val renamedFiles: Int,
    /** 日志里被改写的图片引用个数。 */
    val rewrittenReferences: Int
) {
    val changedSomething: Boolean get() = rewrittenReferences > 0
}

/**
 * 把老图片名（`img_<随机 16 位 hex>.jpg`）换成内容哈希名。
 *
 * 必须**先重命名文件、再改写日志引用**，而且两件事在同一轮里做完：
 * 反过来先改日志的话，中途崩溃会留下一批指向不存在文件的引用，图片就永久丢了。
 * 先重命名再崩溃的后果只是"文件改了名、日志还指旧名"——下一轮迁移会把新名算出来，
 * 依然能接上（[FileSystem] 的 `atomicMove` 覆盖已存在的目标，所以重名不冲突）。
 *
 * 之后日志内容变化但解析不出来的行会被跳过并留给调用方决定——这里只处理能解析的消息。
 */
suspend fun migrateLegacyImageNames(
    fileSystem: FileSystem,
    imagesDir: Path,
    log: ChatLogStore,
    ioDispatcher: CoroutineDispatcher,
    onWarning: (String) -> Unit = {}
): ImageMigrationResult = withContext(ioDispatcher) {
    val messages = log.all()
    if (messages.isEmpty()) return@withContext ImageMigrationResult(0, 0, 0)

    // 先把需要改名的文件整理出来：同一份内容只算一次。
    val renames = LinkedHashMap<String, String>() // 旧名 -> 新名
    val missing = LinkedHashSet<String>()
    messages.forEach { message ->
        message.images.forEach { name ->
            if (isImageId(name) || renames.containsKey(name)) return@forEach
            val source = imagesDir / name
            if (!fileSystem.exists(source)) {
                missing += name
                return@forEach
            }
            try {
                val bytes = fileSystem.read(source) { readByteArray() }
                val target = imageIdFor(bytes)
                if (target != name) renames[name] = target
            } catch (_: IOException) {
                missing += name
            }
        }
    }

    var renamed = 0
    renames.forEach { (old, new) ->
        try {
            fileSystem.atomicMove(imagesDir / old, imagesDir / new)
            renamed++
        } catch (_: IOException) {
            // 改名失败就保持旧引用：把日志指向一个不存在的名字，等于删掉用户的图。
            onWarning("图片 $old 改名失败，已保留原名")
        }
    }

    if (renames.isEmpty()) {
        if (missing.isNotEmpty()) onWarning("有 ${missing.size} 张图片在本机找不到，已跳过")
        return@withContext ImageMigrationResult(missing.size, 0, 0)
    }

    var rewritten = 0
    val migrated = messages.map { message ->
        if (message.images.isEmpty()) return@map message
        val images = message.images.map { name ->
            // 改名失败的那些不在表里，原样保留。
            val target = renames[name] ?: return@map name
            rewritten++
            target
        }
        message.copy(images = images)
    }

    log.replaceAll(migrated)
    if (missing.isNotEmpty()) onWarning("有 ${missing.size} 张图片在本机找不到，已跳过")
    ImageMigrationResult(missing.size, renamed, rewritten)
}
