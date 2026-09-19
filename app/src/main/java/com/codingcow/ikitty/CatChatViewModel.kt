package com.codingcow.ikitty

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Android 侧的状态持有者。
 *
 * 聊天的状态与编排已经整体搬到 `:shared` 的 [ChatEngine]（见 `docs/KMP_IOS_MIGRATION_PLAN.md`
 * 的 Phase 7a），这里保留的只有不可移植的两块：
 * - 应用内 APK 更新（GitHub release + 安装器）；
 * - `.ikitty` 备份的导入导出（走 Android 的 `content://` 与 `Uri`）。
 *
 * 其余成员都是对 [ChatEngine] 的转发，界面的用法保持不变。
 */
class CatChatViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** 更新包下载到缓存目录的这个子目录；安装器通过 FileProvider 只看到它。 */
        const val UPDATE_DIR = "updates"

        /** 下载进度最多按总量的 1/100 上报一次，避免每 64KB 就重写一次界面状态。 */
        const val UPDATE_PROGRESS_STEPS = 100L
    }

    private val api = okHttpApiClient()
    private val images = ImageStore(app)
    private val locationSource: LocationSource = okHttpLocationSource()
    private val updateClient = UpdateClient()

    /**
     * 聊天的编排逻辑全在 `:shared` 的 [ChatEngine] 里。
     *
     * 这里只注入 Android 侧独有的能力：图片编解码、IP 定位、以及 `viewModelScope`
     * 这个生命周期作用域。APK 更新与备份是不可移植功能，仍留在本类。
     */
    private val engine = ChatEngine(
        api = api,
        settings = androidSettingsRepository(app),
        log = androidChatLogStore(app),
        memoryStore = androidCatMemoryStore(app),
        extractor = MemoryExtractor(api),
        imageDataUrls = { names -> images.dataUrls(names) },
        locationSource = locationSource,
        invalidateImageCache = { images.invalidateCache() },
        scope = viewModelScope
    )

    /** 当前安装包的版本名，用来和 release 的 tag 比较。 */
    val appVersion: String = runCatching {
        getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0)
            .versionName
    }.getOrNull().orEmpty().ifBlank { "0.0.0" }

    private val archive = BackupArchive(app, appVersion)

    private val updateDir: File
        get() = File(getApplication<Application>().cacheDir, UPDATE_DIR)

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus = _updateStatus.asStateFlow()

    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val backupStatus = _backupStatus.asStateFlow()

    // ---- 聊天状态：全部来自共享的 ChatEngine ----

    val messages = engine.messages
    val memory = engine.memory
    val memoryStatus = engine.memoryStatus

    /** 上一次请求实际装进去的上下文，用来解释"为什么历史被省略了"。 */
    val contextPlan = engine.contextPlan

    /** 情绪：表情由 CatView 决定。 */
    val mood = engine.mood

    /** 一次性动作，播放完会自己回到 NONE。 */
    val animation = engine.animation

    val busy = engine.busy

    /**
     * 正在流式到达的回复文本；`null` 表示当前没有流式回复。
     *
     * 由 ChatEngine 维护，界面的用法不变。
     */
    val streamingReply = engine.streamingReply

    val config = engine.config
    val persona = engine.persona
    val locationEnabled = engine.locationEnabled

    /** 相机拍照的目标文件；拍照返回后由 [finishCamera] 收编或删除。 */
    private var pendingCameraFile: File? = null

    init {
        engine.start()
    }

    fun saveSettings(newConfig: ApiConfig, newPersona: CatPersona, locationEnabled: Boolean) =
        engine.saveSettings(newConfig, newPersona, locationEnabled)

    /** 测试连接，返回本次请求的完整结果或错误信息。 */
    suspend fun testConnection(candidate: ApiConfig): Result<TestOutcome> =
        engine.testConnection(candidate)

    /** 拉取服务商支持的模型列表。 */
    suspend fun listModels(candidate: ApiConfig): Result<ModelListOutcome> =
        engine.listModels(candidate)

    // ---- 软件更新 ----

    /** 检查 GitHub release 上有没有比当前版本更新的 APK。 */
    fun checkForUpdate() {
        val current = _updateStatus.value
        // 正在检查或下载时不重复发起，否则两个请求会互相覆盖状态。
        if (current is UpdateStatus.Checking || current is UpdateStatus.Downloading) return
        // 同步落状态再开协程：连点两次时第二次能立刻看到「检查中」，不会又起一个请求。
        _updateStatus.value = UpdateStatus.Checking

        viewModelScope.launch {
            _updateStatus.value = try {
                val info = updateClient.fetchLatest()
                when {
                    info == null ->
                        UpdateStatus.Failed("最新 release 里没有找到可下载的 APK，请到 GitHub 发布页手动下载")
                    compareVersions(info.version, appVersion) <= 0 -> UpdateStatus.UpToDate(appVersion)
                    else -> UpdateStatus.Available(info, appVersion)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateStatus.Failed(e.message ?: "检查更新失败")
            }
        }
    }

    /**
     * 下载可用的新版本并校验签名。
     *
     * 下载目标只落在缓存目录的 `updates/` 下，从不触碰 `filesDir` 里的聊天记录与记忆；
     * 随后的覆盖安装也只是替换代码，应用私有数据留在原处。
     */
    fun downloadUpdate() {
        val current = _updateStatus.value
        if (current is UpdateStatus.Downloading) return
        val info = when (current) {
            is UpdateStatus.Available -> current.info
            // 下载失败后允许原样重试，不用重新检查一次。
            is UpdateStatus.Failed -> current.info
            else -> null
        } ?: return

        // 同样先同步落状态，连点两次不会同时下载同一份包。
        _updateStatus.value = UpdateStatus.Downloading(info, 0L, info.apkSizeBytes)

        viewModelScope.launch {
            try {
                val target = File(updateDir, "iKitty-${info.version}.apk")
                clearStaleUpdates(keep = target)
                var lastReported = -1L
                val file = updateClient.download(info, target) { downloaded, total ->
                    val step = if (total > 0) total / UPDATE_PROGRESS_STEPS else 0L
                    val first = lastReported < 0
                    if (first || step <= 0 || downloaded - lastReported >= step || downloaded >= total) {
                        lastReported = downloaded
                        _updateStatus.value = UpdateStatus.Downloading(info, downloaded, total)
                    }
                }
                _updateStatus.value = verifyDownload(info, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _updateStatus.value = UpdateStatus.Failed(e.message ?: "下载更新失败", info)
            }
        }
    }

    /**
     * 下载完成后立刻校验包名与签名。
     *
     * 校验不过就删掉安装包：把装不上的包留在缓存里，只会诱使用户去卸载重装，
     * 那才会真的清空聊天记录。
     */
    private fun verifyDownload(info: UpdateInfo, file: File): UpdateStatus {
        val context = getApplication<Application>()
        return when (ApkInstaller.check(context, file)) {
            ApkCompatibility.COMPATIBLE -> UpdateStatus.Ready(info, file)

            ApkCompatibility.PACKAGE_MISMATCH -> {
                file.delete()
                UpdateStatus.Failed("下载的安装包不是 iKitty，已删除", info)
            }

            ApkCompatibility.SIGNATURE_MISMATCH -> {
                file.delete()
                UpdateStatus.Failed(
                    "安装包签名与当前应用不一致，系统会拒绝覆盖安装。请改用官方 release 的包；" +
                        "直接卸载重装会清空聊天记录。",
                    info
                )
            }

            ApkCompatibility.UNREADABLE -> {
                file.delete()
                UpdateStatus.Failed("下载的安装包无法校验，可能已损坏，请重试", info)
            }
        }
    }

    /** 清掉缓存目录里其他版本的安装包，只保留本次下载的目标文件。 */
    private fun clearStaleUpdates(keep: File) {
        updateDir.listFiles()?.forEach { file ->
            // 删不掉就留着：它在缓存目录里，系统迟早会回收，不影响本次下载。
            if (file != keep) file.delete()
        }
    }

    // ---- 备份与恢复 ----

    /**
     * 导出全部应用数据到 [uri]。
     *
     * 用的是已经保存下来的设置：设置页里没点「保存」的改动不会被带走，
     * 否则备份内容会取决于用户退出前的最后几次输入，说不清楚。
     */
    fun exportBackup(uri: Uri) {
        if (_backupStatus.value is BackupStatus.Working) return
        _backupStatus.value = BackupStatus.Working("正在导出…")

        viewModelScope.launch {
            _backupStatus.value = try {
                val settings = BackupSettings(config.value, persona.value, locationEnabled.value)
                val output = openOutput(uri)
                val summary = output.use { archive.export(it, settings) }
                BackupStatus.Done(
                    "已导出 ${summary.messageCount} 条消息、${summary.imageCount} 张图片、" +
                        "${summary.factCount} 条记忆和设置。"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupStatus.Failed(e.message ?: "导出失败")
            }
        }
    }

    /**
     * 用 [uri] 指向的备份整体覆盖本机数据。
     *
     * 解包和校验全部通过之后才会动本机文件，所以选错文件不会破坏现有数据。
     */
    fun importBackup(uri: Uri) {
        if (_backupStatus.value is BackupStatus.Working) return
        if (busy.value) {
            _backupStatus.value = BackupStatus.Failed("正在等回复，等这条消息结束后再导入")
            return
        }
        _backupStatus.value = BackupStatus.Working("正在读取备份…")

        viewModelScope.launch {
            _backupStatus.value = try {
                val contents = openInput(uri).use { archive.stage(it) }
                val failedImages = archive.commit(contents)
                // 设置也要跟着归档一起落地；写完之后再整体重读，界面立刻反映备份内容。
                saveSettings(
                    contents.settings.config,
                    contents.settings.persona,
                    contents.settings.locationEnabled
                )
                reloadFromDisk()

                val summary = contents.summary
                val base = "已导入 ${summary.messageCount} 条消息、" +
                    "${summary.imageCount} 张图片、${summary.factCount} 条记忆和设置。"
                val warning = if (failedImages > 0) "有 $failedImages 张图片写入失败，可能存储空间不足。" else ""
                BackupStatus.Done(base + warning)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupStatus.Failed(e.message ?: "导入失败")
            }
        }
    }

    private fun openOutput(uri: Uri): OutputStream {
        val resolver = getApplication<Application>().contentResolver
        return resolver.openOutputStream(uri) ?: throw IOException("无法写入所选位置")
    }

    private fun openInput(uri: Uri): InputStream {
        val resolver = getApplication<Application>().contentResolver
        return resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
    }

    /**
     * 导入之后重新读一遍所有持久化状态。
     *
     * 走 store 与文件这条路径而不是直接改内存：DataStore 与文件才是唯一事实来源，
     * 重新读一遍是为了让界面立刻反映备份里的内容。
     */
    private suspend fun reloadFromDisk() {
        engine.reloadFromDisk()
    }

    // ---- 聊天（全部委托给共享的 ChatEngine） ----

    /**
     * 输入框内容变化：有内容就是 LISTENING，清空就回到 IDLE。
     * 正在等回复或刚回复完时不会覆盖当前情绪。
     */
    fun onInputChanged(text: String) = engine.onInputChanged(text)

    /**
     * 发送一条消息。
     *
     * [attachments] 是已复制进本机存储的图片文件名；文字和图片可以同时存在，也可以只有其中一种。
     */
    fun send(text: String, attachments: List<String> = emptyList()) = engine.send(text, attachments)

    /** 清空聊天记录但保留记忆。 */
    fun clearMessages() = engine.clearMessages()

    /** 用户在记忆页手动点「现在整理」。 */
    fun extractMemoryNow() = engine.extractMemoryNow()

    fun upsertFact(originalKey: String?, category: MemoryCategory, key: String, value: String) =
        engine.upsertFact(originalKey, category, key, value)

    fun deleteFact(key: String) = engine.deleteFact(key)

    fun toggleFactPin(key: String) = engine.toggleFactPin(key)

    /** 清空记忆但保留提取游标：用户要求忘掉的事不该被下一次整理重新学回来。 */
    fun clearMemory() = engine.clearMemory()

    // ---- 图片 ----

    /** 把相册返回的图片复制进本机存储，返回文件名；单张失败只跳过它。 */
    suspend fun importImages(uris: List<Uri>): List<String> = images.importAll(uris)

    /** 生成相机可写的目标地址；返回 null 表示拿不到（调用方不应发起拍照）。 */
    fun newCameraTarget(): Uri? {
        val target = images.newCameraTarget()
        pendingCameraFile = target.file
        return target.uri
    }

    /** 拍照返回：成功就收编成正式图片，取消或失败则丢弃临时文件。 */
    suspend fun finishCamera(success: Boolean): String? {
        val file = pendingCameraFile ?: return null
        pendingCameraFile = null
        return if (success) images.commitCamera(file) else {
            file.delete()
            null
        }
    }
}