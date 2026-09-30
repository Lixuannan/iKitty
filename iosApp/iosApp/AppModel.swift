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

    // MARK: - 同步

    /// 应用回到前台时同步一次。
    ///
    /// 没有系统级后台调度（那需要 `BGTaskScheduler` 与 Info.plist 配置），
    /// 前台化这一次已经覆盖了"换设备后看到新消息"这个主要场景。
    func onForeground() {
        environment.onForeground()
    }

    /// 当前同步凭据（地址、密钥、开关）。
    ///
    /// **不 await**：凭据的读取走 `NSUserDefaults` 的直接路径，不再经过 Kotlin 的 suspend 桥。
    func syncCredentials() -> SyncCredentialWriter.Applied {
        environment.currentSyncCredentials()
    }

    /// 保存同步凭据，然后**等一次同步跑完**。
    ///
    /// 返回要给用户看的一句话（nil 表示没什么可说的；失败时就是那条失败原因）。
    ///
    /// 为什么是两步、又为什么要等：落盘是同步的（`NSUserDefaults` 的写入，返回即生效），
    /// 而同步是一轮真实网络往返。等它的收益是"保存"这个动作有确定的结果——点完就能看到
    /// 同步成功还是失败，而不是事后去猜。代价是这段时间界面不响应，所以调用方必须先把
    /// 忙碌状态显示出来（设置页会显示"正在同步…"并禁用按钮）。
    ///
    /// 上一版的问题不在"等"，而在**等的路径**：Swift 要 `await` 三个 Kotlin suspend 写入，
    /// 中间还夹着一次由门面自己发起的同步，任何一段接不上就挂在主 actor 上。现在只有一次
    /// `await`，等的是同步自己的终态，而且带超时兜底（见 `IosAppEnvironment.syncNowAndWait`）。
    func syncCredentialsAndSync(
        serviceUrl url: String,
        accountKey: String,
        includeApiKey: Bool
    ) async -> String? {
        if let failure = environment.applySyncCredentials(
            serviceUrl: url,
            accountKey: accountKey,
            includeApiKey: includeApiKey
        ) {
            return failure
        }
        // Kotlin 的 suspend 导出到 Swift 是 `async throws`：`syncNowAndWait` 自己不会抛
        // （失败已经折成返回文案），但签名要求这里兜住，否则编译不过。真抛了就等于
        // "没等到结果"，按超时那一类处理。
        do {
            return try await environment.syncNowAndWait()
        } catch {
            return "同步没有完成：\(error.localizedDescription)。设置已保存，稍后会自动重试"
        }
    }

    /// 只把同步凭据写进本机，**不**发起同步。返回错误文案（nil 表示写入成功）。
    ///
    /// 用在不该顺带上传的地方，例如「清空云端数据」：那边删完再同步会把刚清掉的数据传回去。
    func saveSyncCredentials(serviceUrl url: String, accountKey: String, includeApiKey: Bool) -> String? {
        environment.applySyncCredentials(
            serviceUrl: url,
            accountKey: accountKey,
            includeApiKey: includeApiKey
        )
    }

    func clearSyncAccountKey() {
        environment.clearSyncAccountKey()
    }

    /// 清空云端。不可撤销，调用方必须先让用户确认。
    func deleteCloudData() {
        environment.deleteCloudData()
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

    /// 缩略图缓存。
    ///
    /// 必须有：消息气泡在每次 SwiftUI 重绘时都会问一次图片，直接 `UIImage(contentsOfFile:)`
    /// 会把磁盘读取与图片解码放进主线程，长对话一滚动就卡。`NSCache` 会在内存吃紧时自己回收，
    /// 所以不需要自己算大小上限。
    private let thumbnails: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 40
        return cache
    }()

    func image(for name: String) -> UIImage? {
        if let cached = thumbnails.object(forKey: name as NSString) { return cached }
        guard let image = UIImage(contentsOfFile: environment.imageFilePath(name: name)) else {
            return nil
        }
        thumbnails.setObject(image, forKey: name as NSString)
        return image
    }

    /// 导入备份之后必须调用：归档里的图片是按名字覆盖的，缓存里的旧图不能再用了。
    func invalidateThumbnails() {
        thumbnails.removeAllObjects()
    }

    /// 导入备份并让界面立刻反映归档内容。
    func importBackup(data: Data) async throws -> String {
        let message = try await environment.importBackup(data: data)
        invalidateThumbnails()
        return message
    }

    deinit {
        unsubscribe?()
        environment.dispose()
    }
}
