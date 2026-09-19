import Foundation
import Shared
import UIKit

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

    /// 已选好、还没发出去的图片（本机文件名）。
    @Published private(set) var pendingImages: [String] = []
    @Published var imageError: String?

    /// 备份/恢复的结果文案，展示在备份页里。
    @Published var backupMessage: String?

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
        guard !text.isEmpty || !pendingImages.isEmpty else { return }
        let names = pendingImages
        draft = ""
        pendingImages = []
        environment.engine.send(text: text, imageNames: names)
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

    // MARK: - 记忆

    func upsertFact(originalKey: String?, category: MemoryCategory, key: String, value: String) {
        environment.engine.upsertFact(
            originalKey: originalKey,
            category: category,
            key: key,
            value: value
        )
    }

    func deleteFact(key: String) {
        environment.engine.deleteFact(key: key)
    }

    func toggleFactPin(key: String) {
        environment.engine.toggleFactPin(key: key)
    }

    func clearMemory() {
        environment.engine.clearMemory()
    }

    func setLocationEnabled(_ enabled: Bool) {
        environment.updateLocationEnabled(enabled: enabled)
    }

    func savePersona(
        name: String,
        notes: String,
        traits: [CatTrait],
        speechStyle: CatSpeechStyle,
        flavor: CatFlavor
    ) {
        environment.updatePersona(
            name: name,
            notes: notes,
            traits: traits,
            speechStyle: speechStyle,
            flavor: flavor
        )
    }

    func saveConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int32,
        thinking: ThinkingMode,
        reasoningEffort: ReasoningEffort
    ) {
        environment.updateConfig(
            baseUrl: baseUrl,
            apiKey: apiKey,
            model: model,
            temperature: temperature,
            topP: topP,
            maxTokens: maxTokens,
            thinking: thinking,
            reasoningEffort: reasoningEffort
        )
    }

    // MARK: - 图片

    /// 归一化并保存一张刚选中的图片。
    func attach(image: UIImage) async {
        guard let data = ImageNormalizer.normalizedJpegData(from: image) else {
            imageError = "这张图片没法处理，换一张试试。"
            return
        }
        do {
            guard let name = try await environment.saveImage(data: data) else {
                imageError = "图片没能存下来，可能是存储空间不足。"
                return
            }
            imageError = nil
            pendingImages.append(name)
        } catch {
            // Kotlin 的 suspend 函数在 Swift 里是 async throws；写文件失败会走这里。
            imageError = "图片没能存下来：\(error.localizedDescription)"
        }
    }

    func removePendingImage(_ name: String) {
        pendingImages.removeAll { $0 == name }
    }

    /// 某张本机图片的文件路径，用来显示缩略图。
    func imagePath(_ name: String) -> String {
        environment.imageFilePath(name: name)
    }

    func image(for name: String) -> UIImage? {
        UIImage(contentsOfFile: environment.imageFilePath(name: name))
    }

    deinit {
        unsubscribe?()
        environment.dispose()
    }
}
