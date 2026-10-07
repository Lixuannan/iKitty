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

    /// 保存设置页的整份草稿。
    ///
    /// 一次提交全部设置，而不是分成"配置 / 角色 / 定位"三次写入：三次写入的每一次都是
    /// "读当前值、改一个字段、写回"，后一次会拿旧快照把前一次刚改的字段冲掉
    /// （表现为"改了 API Key 和名字，一保存就变回去"）。落盘由共享层的
    /// `ChatEngine.saveSettings` 同步更新内存后异步完成，这里不需要 `await`。
    func saveSettings(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int32,
        thinking: ThinkingMode,
        reasoningEffort: ReasoningEffort,
        catName: String,
        catNotes: String,
        traits: [CatTrait],
        speechStyle: CatSpeechStyle,
        flavor: CatFlavor,
        locationEnabled: Bool
    ) {
        environment.saveSettings(
            draft: IosAppEnvironment.SettingsDraft(
                baseUrl: baseUrl,
                apiKey: apiKey,
                model: model,
                temperature: temperature,
                topP: topP,
                maxTokens: maxTokens,
                thinking: thinking,
                reasoningEffort: reasoningEffort,
                catName: catName,
                catNotes: catNotes,
                traits: traits,
                speechStyle: speechStyle,
                flavor: flavor,
                locationEnabled: locationEnabled
            )
        )
    }

    // MARK: - 同步

    /// 当前同步凭据（地址、密钥、开关）。
    ///
    /// **不 await**：凭据的读取走 `NSUserDefaults` 的直接路径，不再经过 Kotlin 的 suspend 桥。
    func syncCredentials() -> SyncCredentialWriter.Applied {
        environment.currentSyncCredentials()
    }

    /// 上传到云端：先把设置页的凭据写进本机，再跑一轮上传并**等它结束**。
    ///
    /// 返回要给用户看的一句话（nil 表示没什么可说的；失败时就是那条失败原因）。
    ///
    /// 落盘在前、上传在后，而且必须是**同一条路径**：分成两个按钮之后，用户完全可能改完地址
    /// 直接点上传——那时拿到的还是上一次保存的旧地址与旧密钥，请求会发到别的云空间去。
    ///
    /// 上一版的问题不在"等"，而在**等的路径**：Swift 要 `await` 三个 Kotlin suspend 写入，
    /// 中间还夹着一次由门面自己发起的同步，任何一段接不上就挂在主 actor 上。现在只有一次
    /// `await`，等的是这一轮 upload 自己的终态，而且带超时兜底（见
    /// `IosAppEnvironment.awaitSync`）。
    func pushToCloud(serviceUrl url: String, accountKey: String, includeApiKey: Bool) async -> String? {
        if let failure = environment.applySyncCredentials(
            serviceUrl: url,
            accountKey: accountKey,
            includeApiKey: includeApiKey
        ) {
            return failure
        }
        // Kotlin 的 suspend 导出到 Swift 是 `async throws`：`pushAndWait` 自己不会抛
        // （失败已经折成返回文案），但签名要求这里兜住，否则编译不过。真抛了就等于
        // "没等到结果"，按超时那一类处理。
        do {
            return try await environment.pushAndWait()
        } catch {
            return "同步没有完成：\(error.localizedDescription)"
        }
    }

    /// 从云端下载：先落盘凭据，再拉一整份云端快照回本机并等它结束。
    ///
    /// 拉取会**整体替换**本地聊天记录（云端权威），本机还没上传的消息会保留在本地。
    func pullFromCloud(serviceUrl url: String, accountKey: String, includeApiKey: Bool) async -> String? {
        if let failure = environment.applySyncCredentials(
            serviceUrl: url,
            accountKey: accountKey,
            includeApiKey: includeApiKey
        ) {
            return failure
        }
        do {
            return try await environment.pullAndWait()
        } catch {
            return "同步没有完成：\(error.localizedDescription)"
        }
    }

    /// 只把同步凭据写进本机，**不**发起同步。返回错误文案（nil 表示写入成功）。
    ///
    /// 用在「保存」与「清空云端数据」：那边删完再上传会把刚清掉的数据传回去，
    /// 而「保存」也不该把关闭设置页这个动作挂在一次网络往返上。
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

    /// 一条消息最多附带几张图片。
    ///
    /// 与 Android `CatChatScreen.MAX_ATTACHMENTS` 以及 README「单次最多 9 张」保持一致。
    /// 对话页用它限制相册的单次选择张数，`attach(images:)` 再用它截断——上限放在写入待发列表
    /// 的那一处兜底，相册之外的相机入口才不会漏掉。
    static let maxAttachments = 9

    /// 归一化并保存一张图片（相机入口）。
    func attach(image: UIImage) async {
        await attach(images: [image])
    }

    /// 归一化并保存一批刚选中的图片，按顺序追加到待发送列表。
    ///
    /// 逐张兜底：一张失败（iCloud 上还没下载完、格式不认识、空间不足）只跳过它，
    /// 同批的其它图片照常加入，最后把失败和超限的张数一次性告诉用户——静默丢图比多一句提示更糟。
    /// 超过上限的张数按 Android 的 `.take(MAX_ATTACHMENTS)` 同样截断，但这里会明说。
    func attach(images: [UIImage]) async {
        var failed = 0
        var dropped = 0
        var lastError: String?
        for image in images {
            guard pendingImages.count < Self.maxAttachments else {
                dropped += 1
                continue
            }
            guard let data = await ImageNormalizer.normalizedJpegData(from: image) else {
                failed += 1
                continue
            }
            do {
                if let name = try await environment.saveImage(data: data) {
                    pendingImages.append(name)
                } else {
                    failed += 1
                }
            } catch {
                // Kotlin 的 suspend 函数在 Swift 里是 async throws；写文件失败会走这里。
                failed += 1
                lastError = error.localizedDescription
            }
        }
        imageError = Self.attachMessage(failed: failed, dropped: dropped, reason: lastError)
    }

    private static func attachMessage(failed: Int, dropped: Int, reason: String?) -> String? {
        var parts: [String] = []
        if failed == 1, let reason {
            // 只有一张失败时带上底层原因：那条信息通常正好是可用的（例如磁盘写不进去）。
            parts.append("有 1 张图片没能加入：\(reason)")
        } else if failed > 0 {
            // 多张失败时原因可能各不相同，只报张数，避免挑出其中一条以偏概全。
            parts.append("有 \(failed) 张图片没能加入，可能是格式不支持或存储空间不足")
        }
        if dropped > 0 {
            parts.append("有 \(dropped) 张因为超过 \(maxAttachments) 张上限没有加入")
        }
        return parts.isEmpty ? nil : parts.joined(separator: "；") + "。"
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
