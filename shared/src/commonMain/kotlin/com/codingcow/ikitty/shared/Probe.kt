package com.codingcow.ikitty.shared

/**
 * Phase 0.5 探测占位。存在的唯一目的是让 `:shared` 有可编译的源码，
 * 从而逼 Kotlin/Native 真正走一次「下载工具链 → 生成 klib → 链接 framework」。
 * Phase 1 迁入 Batch 1 后即可删除。
 */
internal const val NATIVE_PROBE: String = "ikitty-native-probe"
