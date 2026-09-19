import SwiftUI
import Shared

struct ChatView: View {
    @ObservedObject var model: AppModel
    @FocusState private var isInputFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                messageList
                Divider()
                inputBar
            }
            .navigationTitle(model.state?.persona.displayName() ?? "猫猫")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button("清空聊天记录", role: .destructive) {
                            model.clearMessages()
                        }
                        Button("现在整理记忆") {
                            model.extractMemoryNow()
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        model.isShowingSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .sheet(isPresented: $model.isShowingSettings) {
                SettingsView(model: model)
            }
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(messages, id: \.seq) { message in
                        MessageBubble(message: message)
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
            .onChange(of: model.state?.messages.count ?? 0) { _, _ in
                scrollToBottom(proxy)
            }
            .onChange(of: model.state?.streamingReply ?? "") { _, _ in
                scrollToBottom(proxy)
            }
        }
    }

    private var inputBar: some View {
        HStack(spacing: 10) {
            TextField("说点什么…", text: $model.draft, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
                .focused($isInputFocused)
                .onChange(of: model.draft) { _, _ in
                    model.onDraftChanged()
                }

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

    private var messages: [StoredMessage] {
        model.state?.messages ?? []
    }

    private var isBusy: Bool {
        model.state?.busy ?? false
    }

    private var canSend: Bool {
        !isBusy && !model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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
    let isUser: Bool
    let isError: Bool

    init(message: StoredMessage) {
        self.text = message.content
        self.isUser = message.role == "user"
        self.isError = message.localError
    }

    init(text: String, isUser: Bool, isError: Bool) {
        self.text = text
        self.isUser = isUser
        self.isError = isError
    }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 40) }
            Text(text)
                .textSelection(.enabled)
                .foregroundStyle(foreground)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(background, in: RoundedRectangle(cornerRadius: 16))
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
            .onReceive(timer) { _ in
                phase = (phase + 1) % 3
            }
    }
}
