import Combine
import PhotosUI
import SwiftUI
import Shared

struct ChatView: View {
    @ObservedObject var model: AppModel
    @FocusState private var isInputFocused: Bool
    @State private var photoItem: PhotosPickerItem?
    @State private var isShowingCamera = false
    @State private var isShowingMemory = false
    @State private var isShowingBackup = false

    var body: some View {
        NavigationStack {
            Group {
                if #available(iOS 26.0, *) {
                    // 输入栏用 safeAreaBar 交给系统：它会在消息列表下方建立滚动边缘效果，
                    // 让列表从玻璃条底下穿过，而不是被一条实心色块顶上去。
                    messageList
                        .safeAreaBar(edge: .bottom, spacing: 8) { inputArea }
                } else {
                    VStack(spacing: 0) {
                        messageList
                        inputArea
                            .background(.ultraThinMaterial)
                    }
                }
            }
            .background(AppTheme.background)
            .navigationTitle(model.state?.persona.displayName() ?? "猫猫")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button("记忆…") { isShowingMemory = true }
                        Button("备份与恢复…") { isShowingBackup = true }
                        Button("清空聊天记录", role: .destructive) { model.clearMessages() }
                        Button("现在整理记忆") { model.extractMemoryNow() }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { model.isShowingSettings = true } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .sheet(isPresented: $model.isShowingSettings) { SettingsView(model: model) }
            .sheet(isPresented: $isShowingMemory) { MemoryView(model: model) }
            .sheet(isPresented: $isShowingBackup) { BackupView(model: model) }
            .fullScreenCover(isPresented: $isShowingCamera) {
                CameraPicker(isPresented: $isShowingCamera) { image in
                    Task { await model.attach(image: image) }
                }
                .ignoresSafeArea()
            }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let image = UIImage(data: data) {
                        await model.attach(image: image)
                    }
                    photoItem = nil
                }
            }
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(model.state?.messages ?? [], id: \.seq) { message in
                        MessageBubble(message: message) { name in model.image(for: name) }
                            .id(message.seq)
                    }
                    if let streaming = model.state?.streamingReply, !streaming.isEmpty {
                        // 流式回复单独显示，等结束后才落成一条真正的消息。
                        MessageBubble(text: streaming, isUser: false, isError: false)
                            .id(Self.streamingId)
                    }
                    if isBusy && (model.state?.streamingReply ?? "").isEmpty {
                        ThinkingIndicator()
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .onChange(of: model.state?.messages.count ?? 0) { _, _ in scrollToBottom(proxy) }
            .onChange(of: model.state?.streamingReply ?? "") { _, _ in scrollToBottom(proxy) }
        }
    }

    private var pendingStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.pendingImages, id: \.self) { name in
                    ZStack(alignment: .topTrailing) {
                        if let image = model.image(for: name) {
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 64, height: 64)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                        Button {
                            model.removePendingImage(name)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.white, .black.opacity(0.6))
                        }
                        .offset(x: 4, y: -4)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    /// 输入区：可选图片条 + 图片错误提示 + 输入栏本身。
    ///
    /// iOS 26 上由 `safeAreaBar` 承载，滚动边缘效果交给系统；老系统上它叠在
    /// `.ultraThinMaterial` 材质上，两端的观感一致。
    private var inputArea: some View {
        VStack(spacing: 0) {
            if !model.pendingImages.isEmpty { pendingStrip }
            if let error = model.imageError {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(AppTheme.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
            }
            inputBar
        }
    }

    /// 输入栏：左侧「+」与文本框合成一枚玻璃胶囊，发送键是旁边的独立圆形按钮。
    ///
    /// 发送键不嵌在文本框里，是为了和系统消息类界面保持一致——它是主动作，
    /// 单独成形才能在可用/禁用之间给出清晰的状态差异，也不会随文本框换行而变形。
    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 8) {
            HStack(alignment: .bottom, spacing: 4) {
                Menu {
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Label("从相册选择", systemImage: "photo")
                    }
                    if UIImagePickerController.isSourceTypeAvailable(.camera) {
                        Button {
                            isShowingCamera = true
                        } label: {
                            Label("拍照", systemImage: "camera")
                        }
                    }
                } label: {
                    Image(systemName: "plus.circle")
                        .font(.system(size: 24))
                        .frame(width: 40, height: 40)
                }
                .tint(AppTheme.primary)

                TextField("和猫猫说点什么…", text: $model.draft, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 8)
                    .focused($isInputFocused)
                    .onChange(of: model.draft) { _, _ in model.onDraftChanged() }
            }
            .padding(.leading, 6)
            .padding(.trailing, 10)
            .padding(.vertical, 4)
            .glassCapsule()

            Button {
                model.send()
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 36, height: 36)
            }
            .glassAccentButtonStyle()
            .disabled(!canSend)
        }
        .padding(.horizontal, 12)
    }

    private var isBusy: Bool { model.state?.busy ?? false }

    private var canSend: Bool {
        guard !isBusy else { return false }
        if !model.pendingImages.isEmpty { return true }
        return !model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private static let streamingId = Int64.min

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        withAnimation(.easeOut(duration: 0.15)) {
            if let streaming = model.state?.streamingReply, !streaming.isEmpty {
                proxy.scrollTo(Self.streamingId, anchor: .bottom)
            } else if let last = model.state?.messages.last {
                proxy.scrollTo(last.seq, anchor: .bottom)
            }
        }
    }
}

private struct MessageBubble: View {
    let text: String
    let images: [String]
    let isUser: Bool
    let isError: Bool
    let imageFor: (String) -> UIImage?

    init(message: StoredMessage, imageFor: @escaping (String) -> UIImage?) {
        self.text = message.content
        self.images = message.images
        self.isUser = message.role == "user"
        self.isError = message.localError
        self.imageFor = imageFor
    }

    init(text: String, isUser: Bool, isError: Bool) {
        self.text = text
        self.images = []
        self.isUser = isUser
        self.isError = isError
        self.imageFor = { _ in nil }
    }

    var body: some View {
        HStack(alignment: .top) {
            if isUser { Spacer(minLength: 40) }
            VStack(alignment: isUser ? .trailing : .leading, spacing: 6) {
                // 文件被删掉的图片直接不显示，而不是画一个空白框。
                ForEach(images, id: \.self) { name in
                    if let image = imageFor(name) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 220, maxHeight: 220)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                }
                if !text.isEmpty {
                    Text(text)
                        .textSelection(.enabled)
                        .foregroundStyle(textColor)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(AppTheme.bubbleColor(isUser: isUser), in: RoundedRectangle(cornerRadius: 20))
                        // 猫猫气泡用极淡的投影分出边界，不做重描边和重投影：
                        // 层次感留给镀铬层的玻璃材质，避免两套深度语言打架。
                        .shadow(color: .black.opacity(isUser ? 0 : 0.04), radius: 2, y: 1)
                }
            }
            if !isUser { Spacer(minLength: 40) }
        }
    }

    /// Android 端错误消息是白底 + 错误色文字（`CatChatScreen.kt`），而不是整块红气泡。
    private var textColor: Color {
        if isError { return AppTheme.error }
        return isUser ? AppTheme.onPrimary : AppTheme.onSurface
    }
}

private extension AppTheme {
    /// 用户气泡用品牌主色；猫猫气泡用暖白卡片色。
    static func bubbleColor(isUser: Bool) -> Color {
        isUser ? primary : surface
    }
}

/// 思考中的省略号，用一个点的明暗呼吸表示，颜色取自 Android 的 `ThinkingBubble`。
private struct ThinkingIndicator: View {
    @State private var phase = 0
    private let timer = Timer.publish(every: 0.4, on: .main, in: .common).autoconnect()

    var body: some View {
        Text(String(repeating: "·", count: phase + 1))
            .foregroundStyle(AppTheme.primary.opacity(phase.isMultiple(of: 2) ? 1 : 0.35))
            .onReceive(timer) { _ in phase = (phase + 1) % 3 }
    }
}
