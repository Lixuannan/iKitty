package com.codingcow.ikitty

import android.content.Context
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Android 侧的私有目录根。
 *
 * `filesDir` 是应用私有且不会被系统清理的目录，聊天记录、记忆与图片都放在它下面。
 * 相对结构由 [AppPaths] 统一定义，这里只负责把根目录交出去。
 */
fun androidAppPaths(context: Context): AppPaths =
    AppPaths(context.applicationContext.filesDir.toOkioPath())

/** 真实文件系统；`FileSystem.SYSTEM` 在 Android 上就是 `java.io.File` 那一套。 */
fun androidFileSystem(): FileSystem = FileSystem.SYSTEM

/**
 * 用应用私有目录直接建出两个存储。
 *
 * Android 的 IO 调度器只有这里知道，所以由这组工厂显式传入 `Dispatchers.IO`，
 * 而不是让共享代码用一个默认值兜底。
 */
fun androidChatLogStore(
    context: Context,
    now: () -> Long = System::currentTimeMillis
): ChatLogStore = ChatLogStore(
    fileSystem = FileSystem.SYSTEM,
    path = androidAppPaths(context).chatLog,
    ioDispatcher = Dispatchers.IO,
    now = now
)

fun androidCatMemoryStore(context: Context): CatMemoryStore = CatMemoryStore(
    fileSystem = FileSystem.SYSTEM,
    path = androidAppPaths(context).catMemory,
    ioDispatcher = Dispatchers.IO
)
