package com.codingcow.ikitty

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 聊天的编排逻辑：状态、一次发送的完整生命周期、记忆整理、情绪与动作。
 *
 * 这是从 Android 的 `CatChatViewModel` **逐行搬过来**的（移动，不是重写）：
 * 行为、错误措辞、状态迁移都保持一致。搬出来之后 iOS 与 Android 共用同一份编排，
 * 不需要各写一套"什么时候发请求、失败怎么补提示、游标怎么前进"。
 *
 * 平台差异全部由构造参数注入，类本身不引用任何平台 API：
 * - [scope]：由调用方提供（Android 是 `viewModelScope`，iOS 是应用级作用域）；
 * - [imageDataUrls]：把本机图片文件名编成数据 URL（Android 用 `ImageStore`）；
 * - [locationSource]：可选的 IP 定位；为 null 时"此刻"背景里就没有地点这一行；
 * - [invalidateImageCache]：备份导入后要丢弃图片编码缓存。
 */
class ChatEngine(
    private val api: ApiClient,
    private val settings: SettingsRepository,
    private val log: ChatLogStore,
    private val memoryStore: CatMemoryStore,
    private val extractor: MemoryExtractor,
    private val imageDataUrls: suspend (Collection<String>) -> Map<String, String>,
    private val locationSource: LocationSource?,
    private val invalidateImageCache: () -> Unit,
    private val scope: CoroutineScope,
    /**
     * 本地内容发生了变化（消息落盘、设置保存）时回调。
     *
     * 由同步用它触发一次防抖同步。做成**回调而不是让 ChatEngine 认识同步**：
     * 聊天不需要知道"有没有云端"，而同步的实现细节（Worker 地址、账号密钥）更不该
     * 渗进聊天逻辑。默认空实现，所以测试与不用同步的调用方不必关心它。
     */
    private val onContentChanged: () -> Unit = {}
) {
    private val _messages = MutableStateFlow<List<StoredMessage>>(emptyList())
    val messages: StateFlow<List<StoredMessage>> = _messages.asStateFlow()

    private val _memory = MutableStateFlow(CatMemory())
    val memory: StateFlow<CatMemory> = _memory.asStateFlow()

    private val _memoryStatus = MutableStateFlow(MemoryStatus())
    val memoryStatus: StateFlow<MemoryStatus> = _memoryStatus.asStateFlow()

    /** 上一次请求实际装进去的上下文，用来解释"为什么历史被省略了"。 */
    private val _contextPlan = MutableStateFlow<ContextPlan?>(null)
    val contextPlan: StateFlow<ContextPlan?> = _contextPlan.asStateFlow()

    private val _mood = MutableStateFlow(CatMood.IDLE)
    val mood: StateFlow<CatMood> = _mood.asStateFlow()

    private val _animation = MutableStateFlow(CatAnimation.NONE)
    val animation: StateFlow<CatAnimation> = _animation.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 正在流式输出的回复；null 表示当前没有流。 */
    private val _streamingReply = MutableStateFlow<String?>(null)
    val streamingReply: StateFlow<String?> = _streamingReply.asStateFlow()

    private val _config = MutableStateFlow(ApiConfig())
    val config: StateFlow<ApiConfig> = _config.asStateFlow()

    private val _persona = MutableStateFlow(CatPersona())
    val persona: StateFlow<CatPersona> = _persona.asStateFlow()

    private val _locationEnabled = MutableStateFlow(true)
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()

    /** 首次加载是否完成；见公开的 [ready]。 */
    private val _ready = MutableStateFlow(false)

    private var moodResetJob: Job? = null
    private var animationJob: Job? = null
    private var nextSeq = 1L

    /**
     * 开始工作：订阅设置、载入记忆与历史。
     *
     * 由调用方显式调用而不是放在 init 里：这样构造与"开始跑"分开，
     * 调用方可以先把自己接好再启动，也不会在构造期间就漏掉一次发射。
     */
    fun start() {
        scope.launch {
            settings.config.collect { _config.value = it }
        }
        scope.launch {
            _memory.value = memoryStore.load()
        }
        scope.launch {
            settings.locationEnabled.collect { enabled ->
                _locationEnabled.value = enabled
                // 打开开关就趁用户还在打字时先定位一次，刷新内部会自己判断新鲜度。
                if (enabled) locationSource?.refresh(log.timestamp())
            }
        }
        scope.launch {
            // 开场白要用存下来的设定，所以先等第一份 persona 读出来再载入历史，
            // 否则会先显示默认名字再跳变。
            _persona.value = settings.persona.first()
            launch { settings.persona.collect { _persona.value = it } }
            loadHistory()
            _ready.value = true
        }
    }

    /**
     * [start] 的首次加载（设置 + 历史）是否已经完成。
     *
     * 界面不需要等它：它只会让"默认名字/空气泡"多闪一下。它是给测试与"启动即可编程调用"的
     * 调用方用的——在此之前发消息，可能与 [loadHistory] 的赋值交错。
     */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * 保存设置：**先更新内存，再异步落盘**。
     *
     * 内存状态必须在函数返回时就等于新值，不能等存储那条路回来再更新。理由有两条，
     * 都曾经在 iOS 上造成"设置保存不进去"：
     *
     * 1. 界面的其他入口（以及紧接着的下一次 [saveSettings]）读的是 [config] / [persona] /
     *    [locationEnabled]。如果内存要等存储重发才更新，那么"读-改-写"的调用方（设置页依次
     *    保存配置、角色、定位）会拿着**旧快照**把前一次刚写的字段覆盖回去。
     * 2. "存储写入后会重发"是**平台相关的**：Android 的 DataStore 会，iOS 的 `NSUserDefaults`
     *    不会。把保存的正确性建立在这条差异上，等于让同一份逻辑在两个平台上行为不同。
     *
     * 落盘走 [SettingsRepository.save] 的单次原子写入，三块设置一起进存储。
     */
    fun saveSettings(newConfig: ApiConfig, newPersona: CatPersona, locationEnabled: Boolean) {
        _config.value = newConfig
        _persona.value = newPersona
        _locationEnabled.value = locationEnabled
        scope.launch {
            settings.save(newConfig, newPersona, locationEnabled)
            // 保存在协程里，通知也放进来：否则界面刚显示"已保存"、同步却还没看到新设置。
            notifyContentChanged()
        }
    }

    /** 测试连接，返回本次请求的完整结果或错误信息。 */
    suspend fun testConnection(candidate: ApiConfig): Result<TestOutcome> =
        runCatching { api.test(candidate) }

    /** 拉取服务商支持的模型列表。 */
    suspend fun listModels(candidate: ApiConfig): Result<ModelListOutcome> =
        runCatching { api.listModels(candidate) }

    /**
     * 备份导入之后重新读盘。
     *
     * **不重读设置**：调用方（备份导入、设置保存）都已经通过 [saveSettings] 同步更新过内存状态，
     * 而落盘是异步的。这里再读一次存储，很可能读到还没写完的旧值，把刚恢复的设置又冲回去——
     * iOS 上尤其明显（`NSUserDefaults` 的写入不走同一条续体）。设置的部分由 [saveSettings] 负责。
     *
     * 这里只重读备份真正会整体替换的东西：记忆与聊天记录。
     */
    suspend fun reloadFromDisk() {
        _memory.value = memoryStore.load()
        _memoryStatus.value = MemoryStatus()
        _contextPlan.value = null
        // 归档里的图片按原名覆盖过，缓存里的旧编码不能再用了。
        invalidateImageCache()

        val loaded = log.tail(LOAD_LIMIT)
        _messages.value = loaded
        nextSeq = (loaded.maxOfOrNull { it.seq } ?: 0L) + 1L
    }

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
     * [imageNames] 是已复制进本机存储的图片文件名；文字和图片可以同时存在，也可以只有其中一种。
     */
    fun send(text: String, imageNames: List<String> = emptyList()) {
        val trimmed = text.trim()
        val names = imageNames.filter { it.isNotBlank() }
        if ((trimmed.isEmpty() && names.isEmpty()) || _busy.value) return

        val now = log.timestamp()
        // "距离上一条消息多久"要用发送前的那条来算。
        val previousAt = _messages.value.lastOrNull()?.createdAt

        val userMessage = StoredMessage(
            seq = nextSeq++,
            role = StoredMessage.ROLE_USER,
            content = trimmed,
            createdAt = now,
            images = names
        )
        _messages.value = _messages.value + userMessage
        scope.launch { log.append(userMessage) }

        // 定位只在后台刷，关键路径上只读缓存：它永远不该拖慢一条消息的发出。
        val place = if (_locationEnabled.value && locationSource != null) {
            if (!locationSource.isFresh(now)) {
                scope.launch { locationSource.refresh(log.timestamp()) }
            }
            locationSource.cached()
        } else {
            null
        }

        _busy.value = true
        playAnimation(CatAnimation.NONE)
        setMood(CatMood.THINKING)

        scope.launch {
            try {
                val config = _config.value
                // 图片先编码好再装配：装配保持纯函数，重发历史图片不会阻塞主线程。
                val wireImages = imageDataUrls(_messages.value.flatMap { it.images })
                val plan = ContextAssembler.assemble(
                    systemPrompt = _persona.value.systemPrompt(),
                    memoryBlock = CatMemoryRender.block(_memory.value.facts),
                    // 时间和间隔总会带上；只有"在哪个城市"受设置里的开关控制。
                    ambientBlock = AmbientContext.block(
                        now = now,
                        lastMessageAt = previousAt,
                        place = place
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

    /** 清空聊天记录但保留记忆。 */
    fun clearMessages() {
        scope.launch {
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
        }
    }

    /** 用户在记忆页手动点「现在整理」。 */
    fun extractMemoryNow() {
        runMemoryExtraction()
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

    /**
     * 一次成功的同步之后重读日志。
     *
     * 同步已经把云端快照整体写回磁盘（见 `SyncEngine`），这里只负责让界面与本地状态
     * 跟上：消息列表、[nextSeq] 与图片缓存。不重新读盘的话，界面会一直显示旧顺序，
     * 而且 [nextSeq] 会继续在旧的草稿序号上往上加，与云端分配的权威序号错开。
     *
     * 与 [reloadFromDisk] 分开：那个是"备份导入"用的，会跟着重读设置与记忆；
     * 同步不改记忆，也没必要把设置流重新订阅一遍。
     */
    suspend fun syncCompleted() {
        // 从云端下来的图片是新的文件名，缓存里的旧编码不能再沿用。
        invalidateImageCache()
        _messages.value = log.tail(LOAD_LIMIT)
        nextSeq = (_messages.value.maxOfOrNull { it.seq } ?: 0L) + 1L
    }

    private suspend fun loadHistory() {
        val loaded = log.tail(LOAD_LIMIT)
        // 启动是异步的：用户可能在历史读回来之前就抢先发了一条。直接赋值会把那条消息连同
        // 它的回复一起抹掉，所以这里把"内存里已有的"并在盘上历史之后，按 msgId 去重。
        val inMemory = _messages.value
        val merged = if (inMemory.isEmpty()) {
            loaded
        } else {
            (loaded + inMemory)
                .distinctBy { it.msgId }
                .sortedWith(compareBy({ it.seq }, { it.createdAt }))
        }
        _messages.value = merged
        nextSeq = (merged.maxOfOrNull { it.seq } ?: 0L) + 1L
    }

    private fun append(message: StoredMessage) {
        _messages.value = _messages.value + message
        scope.launch {
            log.append(message)
            // 落盘之后才通知同步：发送方看到"已同步"时，消息一定已经在磁盘上了。
            notifyContentChanged()
        }
    }

    /**
     * 告知"本地内容变了"。
     *
     * 收在引擎里而不是让调用方各自去碰回调：这样"什么时候算内容变了"只有一个地方定义，
     * 平台层与测试都不需要知道回调挂在哪个字段上。
     */
    internal fun notifyContentChanged() {
        onContentChanged()
    }

    /** 攒够一批新消息就后台整理一次记忆；整理失败不影响聊天。 */
    private fun maybeExtractMemory() {
        val cursor = _memory.value.lastExtractedSeq
        val pending = _messages.value.count { it.seq > cursor && !it.localError }
        if (pending < MEMORY_BATCH) return
        runMemoryExtraction()
    }

    private fun runMemoryExtraction() {
        if (_memoryStatus.value.running) return
        if (_config.value.baseUrl.isBlank()) {
            _memoryStatus.value = _memoryStatus.value.copy(lastError = "还没有配置模型服务")
            return
        }
        scope.launch {
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

    private fun updateMemory(transform: (CatMemory) -> List<MemoryFact>) {
        scope.launch {
            val current = _memory.value
            val next = current.copy(facts = transform(current))
            memoryStore.save(next)
            _memory.value = next
        }
    }

    private fun setMood(newMood: CatMood, autoReset: Boolean = false) {
        moodResetJob?.cancel()
        _mood.value = newMood
        if (autoReset) {
            moodResetJob = scope.launch {
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
        animationJob = scope.launch {
            delay(newAnimation.durationMillis + ANIMATION_TAIL_MILLIS)
            _animation.value = CatAnimation.NONE
        }
    }

    companion object {
        /** 情绪自动回落的等待时间。 */
        const val MOOD_RESET_MILLIS = 4_000L

        /** 动作结束后多留一点时间，避免界面在动画最后一帧就被切回待机。 */
        const val ANIMATION_TAIL_MILLIS = 200L

        /** 启动时最多载入多少条历史（内存里的会话上限）。 */
        const val LOAD_LIMIT = 400

        /** 攒够多少条新消息就整理一次记忆。 */
        const val MEMORY_BATCH = 6

        /** 一次记忆整理最多读多少条消息。 */
        const val MEMORY_WINDOW = 40
    }
}
