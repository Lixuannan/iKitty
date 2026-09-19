package com.codingcow.ikitty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一次界面快照。
 *
 * 把 [ChatEngine] 的十几个状态合并成一份不可变值：非 Compose 的界面（SwiftUI）
 * 每次只拿到一份完整状态，不会读到"消息已更新但 busy 还没更新"的中间态。
 */
data class ChatUiState(
    val messages: List<StoredMessage> = emptyList(),
    val streamingReply: String? = null,
    val busy: Boolean = false,
    val mood: CatMood = CatMood.IDLE,
    val animation: CatAnimation = CatAnimation.NONE,
    val memory: CatMemory = CatMemory(),
    val memoryStatus: MemoryStatus = MemoryStatus(),
    val contextPlan: ContextPlan? = null,
    val config: ApiConfig = ApiConfig(),
    val persona: CatPersona = CatPersona(),
    val locationEnabled: Boolean = true
)

/**
 * 给非 Compose 界面用的观察入口。
 *
 * [ChatEngine] 暴露的是一堆 `StateFlow`，而 Swift 侧不能直接收集 Kotlin 的 Flow
 * （要额外引入 SKIE 之类的工具，与"不引不必要的框架"冲突）。这里只做一件事：
 * 把那些流镜像进一份 [ChatUiState]，并通过回调推给界面。
 *
 * 注意：`observe` 返回的闭包必须被持有，否则取消订阅的句柄会被立刻释放。
 */
class ChatEngineObserver(
    private val engine: ChatEngine,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 订阅界面快照；返回的函数用来取消订阅（Swift 侧要持有它）。 */
    fun observe(onChange: (ChatUiState) -> Unit): () -> Unit {
        val mirrors = listOf(
            scope.launch { engine.messages.collect { value -> _state.update { it.copy(messages = value) } } },
            scope.launch { engine.streamingReply.collect { value -> _state.update { it.copy(streamingReply = value) } } },
            scope.launch { engine.busy.collect { value -> _state.update { it.copy(busy = value) } } },
            scope.launch { engine.mood.collect { value -> _state.update { it.copy(mood = value) } } },
            scope.launch { engine.animation.collect { value -> _state.update { it.copy(animation = value) } } },
            scope.launch { engine.memory.collect { value -> _state.update { it.copy(memory = value) } } },
            scope.launch { engine.memoryStatus.collect { value -> _state.update { it.copy(memoryStatus = value) } } },
            scope.launch { engine.contextPlan.collect { value -> _state.update { it.copy(contextPlan = value) } } },
            scope.launch { engine.config.collect { value -> _state.update { it.copy(config = value) } } },
            scope.launch { engine.persona.collect { value -> _state.update { it.copy(persona = value) } } },
            scope.launch { engine.locationEnabled.collect { value -> _state.update { it.copy(locationEnabled = value) } } }
        )
        val downstream = scope.launch { _state.collect { onChange(it) } }
        return {
            downstream.cancel()
            mirrors.forEach { it.cancel() }
        }
    }
}
