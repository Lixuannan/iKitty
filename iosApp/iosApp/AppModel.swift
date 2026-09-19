import Foundation
import Shared

/// SwiftUI 与 `:shared` 之间的唯一桥梁。
///
/// `:shared` 暴露的是一堆 StateFlow，Swift 不能直接收集；`ChatEngineObserver`
/// 把它们合成一份 `ChatUiState` 并用回调推过来。这里只负责把回调转成 `@Published`。
@MainActor
final class AppModel: ObservableObject {

    /// 观察者会立刻发第一份快照，所以在它到达之前先当作"加载中"。
    @Published private(set) var state: ChatUiState?
    @Published var draft: String = ""
    @Published var isShowingSettings = false
    @Published var settingsError: String?

    let environment = IosAppEnvironment()

    private var unsubscribe: (() -> Void)?

    func start() {
        guard unsubscribe == nil else { return }
        environment.engine.start()
        unsubscribe = environment.observer.observe { [weak self] snapshot in
            // 环境的作用域跑在 Dispatchers.Main 上，回调一定在主线程。
            self?.state = snapshot
        }
    }

    func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        draft = ""
        environment.engine.send(text: text, imageNames: [])
    }

    func onDraftChanged() {
        environment.engine.onInputChanged(text: draft)
    }

    func clearMessages() {
        environment.engine.clearMessages()
    }

    func extractMemoryNow() {
        environment.engine.extractMemoryNow()
    }

    /// 保存连接设置。
    ///
    /// 只改地址 / Key / 模型这三个用户真正会动的字段，其余（采样参数等）沿用现有值，
    /// 免得 Swift 侧去构造一个带十几个参数的 `ApiConfig`。
    func saveConnection(baseUrl: String, apiKey: String, model: String) {
        let trimmed = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            settingsError = "请先填写 Base URL"
            return
        }
        guard let url = URL(string: trimmed), url.scheme != nil else {
            settingsError = "Base URL 看起来不是一个合法地址"
            return
        }
        settingsError = nil
        environment.updateConnection(baseUrl: trimmed, apiKey: apiKey, model: model)
    }

    deinit {
        unsubscribe?()
        environment.dispose()
    }
}
