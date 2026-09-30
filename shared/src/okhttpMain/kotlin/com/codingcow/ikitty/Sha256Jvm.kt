package com.codingcow.ikitty

import java.security.MessageDigest

/**
 * JVM 与 Android 共用的 SHA-256。
 *
 * 放在 `okhttpMain` 而不是各写一份：androidMain 与 jvmMain 依赖同一份 JVM 实现，
 * 而 `okhttpMain` 是这两个目标的公共父 source set（见 `shared/build.gradle.kts`），
 * 所以一处 `actual` 同时满足两个目标，测试也不必为了算哈希去开模拟器。
 *
 * `MessageDigest` 不是线程安全的，所以每次调用都新建一个——同步与图片迁移都是低频操作，
 * 为了复用实例去加一个 ThreadLocal 得不偿失。
 */
internal actual fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
