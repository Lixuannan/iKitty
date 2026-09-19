import Combine
import PhotosUI
import SwiftUI
import Shared

struct ChatView: View {
    @ObservedObject var model: AppModel
    @FocusState private var isInputFocused: Bool
    @State private var photoItem: PhotosPickerItem?
    @State private var isShowingCamera = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                messageList
                if !model.pendingImages.isEmpty { pendingStrip }
                if let error = model.imageError {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                }
                Divider()
                inputBar
            }
            .navigationTitle(model.state?.persona.displayName() ?? "猫猫")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
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

    private var inputBar: some View {
        HStack(spacing: 10) {
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
                    .font(.system(size: 26))
            }

            TextField("说点什么…", text: $model.draft, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
                .focused($isInputFocused)
                .onChange(of: model.draft) { _, _ in model.onDraftChanged() }

            Button {
                model.send()
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 30))
            }
            .disabled(!canSend)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
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
                        .foregroundStyle(foreground)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(background, in: RoundedRectangle(cornerRadius: 16))
                }
            }
            if !isUser { Spacer(minLength: 40) }
        }
    }

    private var foreground: Color {
        if isError { return .white }
        return isUser ? .white : .primary
    }

    private var background: Color {
        if isError { return .orange }
        return isUser ? .accentColor : Color(.secondarySystemBackground)
    }
}

private struct ThinkingIndicator: View {
    @State private var phase = 0
    private let timer = Timer.publish(every: 0.4, on: .main, in: .common).autoconnect()

    var body: some View {
        Text(String(repeating: "·", count: phase + 1))
            .foregroundStyle(.secondary)
            .onReceive(timer) { _ in phase = (phase + 1) % 3 }
    }
}
