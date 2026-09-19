package com.codingcow.ikitty

import okio.Path

/**
 * 应用私有目录下的全部固定路径。
 *
 * 这些相对路径是**数据兼容契约**：改动任何一条，等于让老用户的聊天记录、记忆或图片
 * "凭空消失"，也等于让旧备份无法还原。所以它们只在这里定义一次，两端共用。
 *
 * 根目录由各平台给出（Android 是 `filesDir`，iOS 是 Application Support），
 * 但相对结构必须完全一致，这样同一份数据才能跨端互读、备份才能互导。
 */
class AppPaths(val root: Path) {
    val chatLog: Path = root / CHAT_DIR / CHAT_LOG_NAME
    val catMemory: Path = root / CHAT_DIR / CAT_MEMORY_NAME
    val imagesDir: Path = root / CHAT_DIR / IMAGES_NAME

    companion object {
        const val CHAT_DIR = "chat"
        const val CHAT_LOG_NAME = "chat_log.jsonl"
        const val CAT_MEMORY_NAME = "cat_memory.json"
        const val IMAGES_NAME = "images"

        /** 相对根目录的路径字符串；备份归档与跨端互读按这些字符串取文件。 */
        const val CHAT_LOG_PATH = "$CHAT_DIR/$CHAT_LOG_NAME"
        const val CAT_MEMORY_PATH = "$CHAT_DIR/$CAT_MEMORY_NAME"
        const val IMAGES_PATH = "$CHAT_DIR/$IMAGES_NAME"
    }
}
