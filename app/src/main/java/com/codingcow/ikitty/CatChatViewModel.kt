package com.codingcow.ikitty

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class CatChatViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val MOOD_RESET_MILLIS = 4000L

        /** 动画播完之后多留一点缓冲再清回 NONE。 */
        const val ANIMATION_TAIL_MILLIS = 200L

        /** 聊天页一次从文件里载入多少条历史。 */
        const val LOAD_LIMIT = 400

        /** 攒够多少条新消息就整理一次记忆。 */
        const val MEMORY_BATCH = 6

        /** 一次记忆整理最多读多少条消息。 */
        const val MEMORY_WINDOW = 40

        /** 更新包下载到缓存目录的这个子目录；安装器通过 FileProvider 只看到它。 */
        const val UPDATE_DIR = "updates"

        /** 下载进度最多按总量的 1/100 上报一次，避免每 64KB 就重写一次界面状态。 */
        const val UPDATE_PROGRESS_STEPS = 100L
    }

    private val api = androidApiClient()
    private val store = SettingsStore(app)
    private val log = androidChatLogStore(app)
    private val memoryStore = androidCatMemoryStore(app)
    private val images = ImageStore(app)
    private val extractor = MemoryExtractor(api)
    private val locationSource: LocationSource = IpLocationSource()
    private val updateClient = UpdateClient()

    /** 当前安装包的版本名，用来和 release 的 tag 比较。 */
    val appVersion: String = runCatching {
        getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0)
            .versionName
    }.getOrNull().orEmpty().ifBlank { "0.0.0" }

    private val archive = BackupArchive(app, appVersion)

    private val updateDir: File
        get() = File(getApplication<Application>().cacheDir, UPDATE_DIR)

    private val _messages = MutableStateFlow<List<StoredMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _memory = MutableStateFlow(CatMemory())
    val memory = _memory.asStateFlow()

    private val _memoryStatus = MutableStateFlow(MemoryStatus())
    val memoryStatus = _memoryStatus.asStateFlow()

    /** 上一次请求实际装进去的上下文，用来解释"为什么历史被省略了"。 */
    private val _contextPlan = MutableStateFlow<ContextPlan?>(null)
    val contextPlan = _contextPlan.asStateFlow()

    /** 情绪：表情由 CatView 决定。 */
    private val _mood = MutableStateFlow(CatMood.IDLE)
    val mood = _mood.asStateFlow()

    /** 一次性动作，播放完会自己回到 NONE。 */
    private val _animation = MutableStateFlow(CatAnimation.NONE)
    val animation = _animation.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /**
     * 正在流式到达的回复文本；`null` 表示当前没有流式回复。
     *
     * 单独用一个状态而不是逐段改写 `messages`：流式增量可能一秒几十次，
     * 每次都复制一遍消息列表代价太高；等回复结束再落成一条真正的消息。
     */
    private val _streamingReply = MutableStateFlow<String?>(null)
    val streamingReply = _streamingReply.asStateFlow()

    private val _config = MutableStateFlow(ApiConfig())
    val config = _config.asStateFlow()

    private val _persona = MutableStateFlow(CatPersona())
    val persona = _persona.asStateFlow()

    private val _locationEnabled = MutableStateFlow(true)
    val locationEnabled = _locationEnabled.asStateFlow()

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus = _updateStatus.asStateFlow()

    private val _backupStatus = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val backupStatus = _backupStatus.asStateFlow()

    private var moodResetJob: Job? = null
    private var animationJob: Job? = null
    private var nextSeq = 1L

    /** 相机拍照的目标文件；拍照返回后由 [finishCamera] 收编或删除。 */
    private var pendingCameraFile: File? = null

    init {
        viewModelScope.launch {
            store.config.collect { _config.value = it }
        }
        viewModelScope.launch {
            _memory.value = memoryStore.load()
        }
        viewModelScope.launch {
            store.locationEnabled.collect { enabled ->
                _locationEnabled.value = enabled
                // 打开开关就趁用户还在打字时先定位一次，刷新内部会自己判断新鲜度。
                if (enabled) locationSource.refresh(log.timestamp())
            }
        }
        viewModelScope.launch {
            // 开场白要用存下来的设定，所以先等第一份 persona 读出来再载入历史，
            // 否则会先显示默认名字再跳变。
            _persona.value = store.persona.first()
            launch { store.persona.collect { _persona.value = it } }
            loadHistory()
        }
    }

    fun saveSettings(newConfig: ApiConfig, newPersona: CatPersona, locationEnabled: Boolean) {
        viewModelScope.launch {
            store.save(newConfig)
            store.save(newPersona)
            store.saveLocationEnabled(locationEnabled)
        }
    }

    /** 测试连接，返回本次请求的完整结果或错误信息。 */
    suspend fun testConnection(candidate: ApiConfig): Result<TestOutcome> =
        runCatching { api.test(candidate) }

    /** 拉取服务商支持的模型列表。 */
    suspend fun listModels(candidate: ApiConfig): Result<ModelListOutcome> =
        runCatching { api.listModels(candidate) }

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
                val settings = BackupSettings(_config.value, _persona.value, _locationEnabled.value)
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
        if (_busy.value) {
            _backupStatus.value = BackupStatus.Failed("正在等回复，等这条消息结束后再导入")
            return
        }
        _backupStatus.value = BackupStatus.Working("正在读取备份…")

        viewModelScope.launch {
            _backupStatus.value = try {
                val contents = openInput(uri).use { archive.stage(it) }
                val failedImages = archive.commit(contents)
                store.save(contents.settings.config)
                store.save(contents.settings.persona)
                store.saveLocationEnabled(contents.settings.locationEnabled)
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
     * 走 store 而不是直接写内存：DataStore 与文件才是唯一事实来源，
     * 这里再抄一遍是为了让界面立刻反映备份里的内容。
     */
    private suspend fun reloadFromDisk() {
        _config.value = store.config.first()
        _persona.value = store.persona.first()
        _locationEnabled.value = store.locationEnabled.first()
        _memory.value = memoryStore.load()
        _memoryStatus.value = MemoryStatus()
        _contextPlan.value = null
        // 归档里的图片按原名覆盖过，缓存里的旧编码不能再用了。
        images.invalidateCache()

        val loaded = log.tail(LOAD_LIMIT)
        _messages.value = loaded
        nextSeq = (loaded.maxOfOrNull { it.seq } ?: 0L) + 1L
        // 备份是空对话时补一句开场白，否则导入完会是一片空白。
        if (loaded.isEmpty()) ensureWelcome()
    }

    // ---- 聊天 ----

    /**
     * 输入框内容变化：有内容就是 LISTENING，清空就回到 IDLE。
     * 正在等回复或刚回复完时不会覆盖当前情绪。
     */
    fun onInputChanged(text: String) {
        if (_busy.value) return
        if (text.isBlank()) {
            if (_mood.value == CatMood.LISTENING) setMood(CatMood.IDLE)
        } else if (_mood.value != CatMood.LISTENING) {
            setMood(CatMood.LISTENING)
        }
    }

    /**
     * 发送一条消息。
     *
     * [attachments] 是已复制进本机存储的图片文件名；文字和图片可以同时存在，也可以只有其中一种。
     */
    fun send(text: String, attachments: List<String> = emptyList()) {
        val trimmed = text.trim()
        val imageNames = attachments.filter { it.isNotBlank() }
        if ((trimmed.isEmpty() && imageNames.isEmpty()) || _busy.value) return

        val now = log.timestamp()
        // "距离上一条消息多久"要用发送前的那条来算。
        val previousAt = _messages.value.lastOrNull()?.createdAt

        val userMessage = StoredMessage(
            seq = nextSeq++,
            role = StoredMessage.ROLE_USER,
            content = trimmed,
            createdAt = now,
            images = imageNames
        )
        _messages.value = _messages.value + userMessage
        viewModelScope.launch { log.append(userMessage) }

        // 定位只在后台刷，关键路径上只读缓存：它永远不该拖慢一条消息的发出。
        if (_locationEnabled.value && !locationSource.isFresh(now)) {
            viewModelScope.launch { locationSource.refresh(log.timestamp()) }
        }

        _busy.value = true
        playAnimation(CatAnimation.NONE)
        setMood(CatMood.THINKING)

        viewModelScope.launch {
            try {
                val config = _config.value
                // 图片先编码好再装配：装配保持纯函数，重发历史图片不会阻塞主线程。
                val wireImages = images.dataUrls(_messages.value.flatMap { it.images })
                val plan = ContextAssembler.assemble(
                    systemPrompt = _persona.value.systemPrompt(),
                    memoryBlock = CatMemoryRender.block(_memory.value.facts),
                    // 时间和间隔总会带上；只有"在哪个城市"受设置里的开关控制。
                    ambientBlock = AmbientContext.block(
                        now = now,
                        lastMessageAt = previousAt,
                        place = if (_locationEnabled.value) locationSource.cached() else null
                    ),
                    history = _messages.value,
                    budget = ContextAssembler.budgetFor(config.spec(), config.maxTokens),
                    imageUrl = { name -> wireImages[name] }
                )
                _contextPlan.value = plan

                _streamingReply.value = ""
                val raw = api.chatStream(config, plan.messages) { delta ->
                    val current = _streamingReply.value
                    if (current != null) _streamingReply.value = current + delta
                }
                _streamingReply.value = null

                val reply = parseCatReply(raw.text)
                append(
                    StoredMessage(
                        seq = nextSeq++,
                        role = StoredMessage.ROLE_ASSISTANT,
                        content = reply.text,
                        createdAt = log.timestamp()
                    )
                )

                val mood = reply.mood ?: CatMood.HAPPY
                setMood(mood, autoReset = true)
                // 模型没给动作时，按情绪挑一个自然的默认动作
                playAnimation(reply.animation ?: CatAnimation.defaultFor(mood))

                maybeExtractMemory()
            } catch (e: CancellationException) {
                _streamingReply.value = null
                throw e
            } catch (e: Exception) {
                // 已经流出来的半截回复先留下，再补一条错误提示，避免用户看到的内容凭空消失。
                val partial = _streamingReply.value.orEmpty()
                _streamingReply.value = null
                if (partial.isNotBlank()) {
                    append(
                        StoredMessage(
                            seq = nextSeq++,
                            role = StoredMessage.ROLE_ASSISTANT,
                            content = partial,
                            createdAt = log.timestamp()
                        )
                    )
                }
                append(
                    StoredMessage(
                        seq = nextSeq++,
                        role = StoredMessage.ROLE_ASSISTANT,
                        content = "呜……连接 API 的时候出问题了：${e.message ?: "未知错误"}",
                        createdAt = log.timestamp(),
                        localError = true
                    )
                )
                setMood(CatMood.SAD, autoReset = true)
                // 出错时甩甩头，情绪和动作是分开的两件事
                playAnimation(CatAnimation.SHAKE)
            } finally {
                _streamingReply.value = null
                _busy.value = false
            }
        }
    }

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

    // ---- 聊天记录 ----

    /** 清空聊天记录但保留记忆。 */
    fun clearMessages() {
        viewModelScope.launch {
            log.clear()
            _messages.value = emptyList()
            _contextPlan.value = null
            nextSeq = 1L
            // 提取游标必须一起归零：seq 从 1 重新开始，旧游标会让新消息被当成"早就整理过"。
            val current = _memory.value
            if (current.lastExtractedSeq != 0L) {
                val next = current.copy(lastExtractedSeq = 0L)
                memoryStore.save(next)
                _memory.value = next
            }
            ensureWelcome()
        }
    }

    private suspend fun loadHistory() {
        val loaded = log.tail(LOAD_LIMIT)
        _messages.value = loaded
        nextSeq = (loaded.maxOfOrNull { it.seq } ?: 0L) + 1L
        if (loaded.isEmpty()) ensureWelcome()
    }

    /** 空会话补一条开场白；换名字之后要清空记录才会看到新的开场白。 */
    private fun ensureWelcome() {
        if (_messages.value.isNotEmpty()) return
        append(
            StoredMessage(
                seq = nextSeq++,
                role = StoredMessage.ROLE_ASSISTANT,
                content = _persona.value.welcome(),
                createdAt = log.timestamp()
            )
        )
    }

    private fun append(message: StoredMessage) {
        _messages.value = _messages.value + message
        viewModelScope.launch { log.append(message) }
    }

    // ---- 结构化记忆 ----

    /** 攒够一批新消息就后台整理一次记忆；整理失败不影响聊天。 */
    private fun maybeExtractMemory() {
        val cursor = _memory.value.lastExtractedSeq
        val pending = _messages.value.count { it.seq > cursor && !it.localError }
        if (pending < MEMORY_BATCH) return
        runMemoryExtraction()
    }

    /** 用户在记忆页手动点「现在整理」。 */
    fun extractMemoryNow() {
        runMemoryExtraction()
    }

    private fun runMemoryExtraction() {
        if (_memoryStatus.value.running) return
        if (_config.value.baseUrl.isBlank()) {
            _memoryStatus.value = _memoryStatus.value.copy(lastError = "还没有配置模型服务")
            return
        }
        viewModelScope.launch {
            _memoryStatus.value = _memoryStatus.value.copy(running = true, lastError = null)
            try {
                val now = log.timestamp()
                val recent = log.readAfter(_memory.value.lastExtractedSeq, MEMORY_WINDOW)
                if (recent.isEmpty()) {
                    _memoryStatus.value = _memoryStatus.value.copy(running = false, lastRunAt = now)
                    return@launch
                }
                val update = extractor.extract(_config.value, _memory.value, recent, now)
                val next = CatMemory(
                    facts = CatMemoryRules.merge(_memory.value.facts, update.facts, update.forget, now),
                    lastExtractedSeq = recent.last().seq,
                    lastExtractedAt = now
                )
                memoryStore.save(next)
                _memory.value = next
                _memoryStatus.value = _memoryStatus.value.copy(running = false, lastRunAt = now)
            } catch (e: Exception) {
                // 游标不前进，下次整理会把同一批消息再读一遍，所以失败是可以自愈的。
                _memoryStatus.value = _memoryStatus.value.copy(
                    running = false,
                    lastError = e.message ?: "未知错误"
                )
            }
        }
    }

    fun upsertFact(originalKey: String?, category: MemoryCategory, key: String, value: String) {
        val now = log.timestamp()
        val draft = MemoryFact(category = category, key = key, value = value, updatedAt = now)
        updateMemory { CatMemoryRules.upsert(it.facts, originalKey, draft, now) }
    }

    fun deleteFact(key: String) {
        updateMemory { CatMemoryRules.remove(it.facts, key) }
    }

    fun toggleFactPin(key: String) {
        updateMemory { CatMemoryRules.togglePin(it.facts, key) }
    }

    /** 清空记忆但保留提取游标：用户要求忘掉的事不该被下一次整理重新学回来。 */
    fun clearMemory() {
        updateMemory { emptyList() }
    }

    private fun updateMemory(transform: (CatMemory) -> List<MemoryFact>) {
        viewModelScope.launch {
            val current = _memory.value
            val next = current.copy(facts = transform(current))
            memoryStore.save(next)
            _memory.value = next
        }
    }

    // ---- 情绪与动作 ----

    private fun setMood(newMood: CatMood, autoReset: Boolean = false) {
        moodResetJob?.cancel()
        _mood.value = newMood
        if (autoReset) {
            moodResetJob = viewModelScope.launch {
                delay(MOOD_RESET_MILLIS)
                if (!_busy.value) _mood.value = CatMood.IDLE
            }
        }
    }

    /** 播放一次性动作，到时间自动清回 NONE，这样同一个动作可以重复触发。 */
    private fun playAnimation(newAnimation: CatAnimation) {
        animationJob?.cancel()
        if (newAnimation == CatAnimation.NONE) {
            _animation.value = CatAnimation.NONE
            return
        }
        _animation.value = newAnimation
        animationJob = viewModelScope.launch {
            delay(newAnimation.durationMillis + ANIMATION_TAIL_MILLIS)
            _animation.value = CatAnimation.NONE
        }
    }
}
