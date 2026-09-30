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
    val locationEnabled: Boolean = true,
    /** 同步状态。没接同步时保持 [SyncUiStatus.disabled]。 */
    val sync: SyncUiStatus = SyncUiStatus.disabled
)

/**
 * 同步状态在非 Compose 界面里的样子。
 *
 * 把 [SyncStatus] 这个密封接口摊平成"一句话 + 几个标志"：Swift 不能对 Kotlin 的密封类型
 * 做穷举匹配，而界面真正需要的也只是"显示哪句话、按钮要不要灰"。
 * 与 `IosAppEnvironment` 里 `ModelCapabilities` 的处理方式一致。
 */
class SyncUiStatus(
    /** 0 = 未配置服务地址，1 = 空闲，2 = 进行中，3 = 完成，4 = 失败，5 = 需要账号密钥。 */
    val kind: Int,
    val message: String
) {
    val isWorking: Boolean get() = kind == 2

    /** 需要用户去填服务地址或账号密钥。 */
    val needsSetup: Boolean get() = kind == 0 || kind == 5

    val isFailed: Boolean get() = kind == 4

    companion object {
        val disabled = SyncUiStatus(0, "还没有配置同步服务")

        fun of(status: SyncStatus): SyncUiStatus = when (status) {
            is SyncStatus.Disabled -> SyncUiStatus(0, "还没有配置同步服务")
            is SyncStatus.Idle -> SyncUiStatus(1, "")
            is SyncStatus.Working -> SyncUiStatus(2, status.label)
            is SyncStatus.Done -> SyncUiStatus(3, status.message)
            is SyncStatus.Failed -> SyncUiStatus(4, status.message)
            is SyncStatus.NeedsAccountKey -> SyncUiStatus(5, status.message)
        }
    }
}

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
    private val scope: CoroutineScope,
    /** 同步状态；没有同步时传 null，快照里保持"未配置"。 */
    private val syncStatus: StateFlow<SyncStatus>? = null
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
        ) + syncMirrors()
        val downstream = scope.launch { _state.collect { onChange(it) } }
        return {
            downstream.cancel()
            mirrors.forEach { it.cancel() }
        }
    }

    private fun syncMirrors(): List<kotlinx.coroutines.Job> {
        val status = syncStatus ?: return emptyList()
        return listOf(
            scope.launch {
                status.collect { value -> _state.update { it.copy(sync = SyncUiStatus.of(value)) } }
            }
        )
    }
}
