package com.example.aicat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    }

    private val api = ApiClient()
    private val store = SettingsStore(app)
    private val log = ChatLogStore(app)
    private val memoryStore = CatMemoryStore(app)
    private val extractor = MemoryExtractor(api)
    private val locationSource: LocationSource = IpLocationSource()

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

    private val _config = MutableStateFlow(ApiConfig())
    val config = _config.asStateFlow()

    private val _persona = MutableStateFlow(CatPersona())
    val persona = _persona.asStateFlow()

    private val _locationEnabled = MutableStateFlow(true)
    val locationEnabled = _locationEnabled.asStateFlow()

    private var moodResetJob: Job? = null
    private var animationJob: Job? = null
    private var nextSeq = 1L

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

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return

        val now = log.timestamp()
        // "距离上一条消息多久"要用发送前的那条来算。
        val previousAt = _messages.value.lastOrNull()?.createdAt

        val userMessage = StoredMessage(
            seq = nextSeq++,
            role = StoredMessage.ROLE_USER,
            content = trimmed,
            createdAt = now
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
                    budget = ContextAssembler.budgetFor(config.spec(), config.maxTokens)
                )
                _contextPlan.value = plan

                val raw = api.chat(config, plan.messages)
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
            } catch (e: Exception) {
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
                _busy.value = false
            }
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
