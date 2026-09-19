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
    private val scope: CoroutineScope
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
        }
    }

    fun saveSettings(newConfig: ApiConfig, newPersona: CatPersona, locationEnabled: Boolean) {
        scope.launch {
            settings.save(newConfig)
            settings.save(newPersona)
            settings.saveLocationEnabled(locationEnabled)
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
     * 这里再抄一遍是为了让界面立刻反映备份里的内容。
     */
    suspend fun reloadFromDisk() {
        _config.value = settings.config.first()
        _persona.value = settings.persona.first()
        _locationEnabled.value = settings.locationEnabled.first()
        _memory.value = memoryStore.load()
        _memoryStatus.value = MemoryStatus()
        _contextPlan.value = null
        // 归档里的图片按原名覆盖过，缓存里的旧编码不能再用了。
        invalidateImageCache()

        val loaded = log.tail(LOAD_LIMIT)
        _messages.value = loaded
        nextSeq = (loaded.maxOfOrNull { it.seq } ?: 0L) + 1L
        // 备份是空对话时补一句开场白，否则导入完会是一片空白。
        if (loaded.isEmpty()) ensureWelcome()
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
            ensureWelcome()
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
        scope.launch { log.append(message) }
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
