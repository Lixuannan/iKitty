package com.example.aicat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** 覆盖安装前对下载到的 APK 的检查结果。 */
enum class ApkCompatibility {
    /** 包名和签名都对得上，可以覆盖安装。 */
    COMPATIBLE,

    /** 包名不是 iKitty。 */
    PACKAGE_MISMATCH,

    /** 签名对不上，系统会拒绝覆盖安装。 */
    SIGNATURE_MISMATCH,

    /** 读不出包信息（文件损坏 / 缓存被清理）。 */
    UNREADABLE
}

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * 这里刻意不做任何「先卸载再安装」的动作：覆盖安装只替换代码，应用私有目录里的
 * 聊天记录（`filesDir/chat/`）、图片和 DataStore 设置都会原样保留。签名不一致时
 * 系统会直接拒绝安装，数据同样不受影响——用 [check] 提前发现这种情况，是为了避免
 * 用户被「装不上」误导去卸载重装，那才会真的清空聊天记录。
 */
object ApkInstaller {

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 是否已允许本应用安装未知来源的应用（Android 8.0+ 按应用授权）。 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 系统的「安装未知应用」授权页。 */
    fun unknownSourcesSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(context))

    /** 打开系统安装器覆盖安装 [apk]。 */
    fun install(context: Context, apk: File) {
        // FileProvider 只把这一个文件临时授权给安装器，不暴露应用其他目录。
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 校验 [apk] 的包名与签名是否和当前安装的应用一致。 */
    fun check(context: Context, apk: File): ApkCompatibility {
        if (!apk.isFile) return ApkCompatibility.UNREADABLE
        val manager = context.packageManager
        val flags = signatureFlags()
        val archive = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return ApkCompatibility.UNREADABLE
        if (archive.packageName != context.packageName) return ApkCompatibility.PACKAGE_MISMATCH

        val installed = try {
            manager.getPackageInfo(context.packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            // 包名取自 context.packageName，系统里必然有它；走到这里说明 PackageManager 状态异常。
            return ApkCompatibility.UNREADABLE
        }

        val archiveDigests = signatureDigests(archive)
        val installedDigests = signatureDigests(installed)
        // 两边都读不出签名时不能当作「一致」，否则等于跳过了这道校验。
        if (archiveDigests.isEmpty() || installedDigests.isEmpty()) return ApkCompatibility.UNREADABLE
        return if (archiveDigests == installedDigests) {
            ApkCompatibility.COMPATIBLE
        } else {
            ApkCompatibility.SIGNATURE_MISMATCH
        }
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun signatureDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().mapTo(mutableSetOf()) { digest(it.toByteArray()) }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")
}
