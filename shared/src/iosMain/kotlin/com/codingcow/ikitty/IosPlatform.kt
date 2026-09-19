package com.codingcow.ikitty

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * iOS 侧的私有目录根。
 *
 * 选 `Library/Application Support/iKitty` 而不是 Documents：
 * - Application Support 是放应用自己数据的地方，不会出现在"文件"App 里被用户误删；
 * - 它仍然参与 iCloud/iTunes 备份，用户换机不会丢聊天记录。
 *
 * 目录**不会自动创建**，第一次写入前必须显式建出来。
 * 相对结构交给 [AppPaths]，与 Android 完全一致，这样两端数据与备份可以互读。
 */
// createDirectoryAtPath 的 error 参数是 C 指针，属于 Kotlin/Native 的 cinterop 实验性 API。
@OptIn(ExperimentalForeignApi::class)
fun iosAppPaths(): AppPaths {
    val manager = NSFileManager.defaultManager
    val base = manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
        .firstOrNull() as? NSURL
        ?: error("取不到 Application Support 目录")
    val basePath = base.path ?: error("取不到 Application Support 路径")
    val rootPath = "$basePath/iKitty"
    // 已经存在时 createDirectory 会失败，所以先判断再建。
    if (!manager.fileExistsAtPath(rootPath)) {
        manager.createDirectoryAtPath(
            path = rootPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }
    return AppPaths(rootPath.toPath())
}

/** 真实文件系统；okio 在 Apple 目标上用 POSIX 实现。 */
fun iosFileSystem(): FileSystem = FileSystem.SYSTEM

/**
 * iOS 上没有 `Dispatchers.IO`。
 *
 * 聊天记录、记忆、图片都是小文件，`Default` 足够；单独抽成函数是为了让
 * "这一端用什么调度器"只在一处决定，而不是散落在各个构造点。
 */
fun iosIoDispatcher(): CoroutineDispatcher = Dispatchers.Default
