package com.codingcow.ikitty

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 从 GitHub release 获取更新信息并下载 APK。
 *
 * 只做网络与文件搬运：版本比较、界面状态、校验与安装分别由 [UpdateModels]、
 * ViewModel 和 [ApkInstaller] 负责。
 */
class UpdateClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 读取仓库最新的 release。
     *
     * 返回 `null` 表示「有 release 但没有可下载的 APK」（或返回体无法解析）；
     * 网络与 HTTP 失败抛 [ApiException]，message 可直接展示。
     */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASE_API_URL)
            .addHeader("Accept", "application/vnd.github+json")
            .get()
            .build()

        execute(request).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpFailure(response.code)
            parseLatestRelease(body)
        }
    }

    /**
     * 下载 [info] 的 APK 到 [destination]，返回下载完成的文件。
     *
     * 先写 `*.part` 再改名：中途失败或被杀不会留下一个看起来完整的坏安装包。
     * [onProgress] 会拿到累计字节数与总字节数；总长度未知时为 0。
     */
    suspend fun download(
        info: UpdateInfo,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + PART_SUFFIX)
        // 上一轮失败的残留不能让本次续着写，先删掉。
        temp.delete()

        val request = Request.Builder().url(info.apkUrl).get().build()
        try {
            execute(request).use { response ->
                if (!response.isSuccessful) throw httpFailure(response.code)
                val body = response.body ?: throw ApiException("下载更新失败：返回内容为空")
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSizeBytes

                var downloaded = 0L
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        output.flush()
                    }
                }

                // release 声明了大小却对不上，说明连接被截断；宁可报错也不要让系统去装坏包。
                if (info.apkSizeBytes > 0 && downloaded != info.apkSizeBytes) {
                    throw ApiException("下载不完整（$downloaded/${info.apkSizeBytes} 字节），请重试")
                }
            }

            // 改名是原子的；个别文件系统上失败就退回复制，至少不丢刚下好的包。
            destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            destination
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** OkHttp 的失败（DNS、超时、连接被拒等）统一转成可展示的提示。 */
    private fun execute(request: Request) = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        throw ApiException("网络请求失败：${e.message ?: e.javaClass.simpleName}")
    }

    private fun httpFailure(code: Int): ApiException = ApiException(
        when (code) {
            403, 429 -> "GitHub 接口访问受限（HTTP $code），稍后再试"
            404 -> "GitHub 上没有找到这个仓库的 release"
            in 500..599 -> "GitHub 暂时不可用（HTTP $code），稍后再试"
            else -> "获取更新失败：HTTP $code"
        }
    )

    private companion object {
        const val PART_SUFFIX = ".part"
        const val BUFFER_BYTES = 64 * 1024
    }
}
