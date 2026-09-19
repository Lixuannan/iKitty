import SwiftUI
import Shared

/// 设置。
///
/// 参数控件不是写死的：当前模型支持哪些参数、各自的上下限，都由共享的模型能力表决定
/// （`ModelCatalog`），所以换一个模型界面会跟着变，和 Android 侧一致。
struct SettingsView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    // 连接
    @State private var baseUrl = ""
    @State private var apiKey = ""
    @State private var modelName = ""

    // 采样参数与思考开关
    @State private var temperature: Double = 0.8
    @State private var topP: Double = 1.0
    @State private var maxTokens: Double = 0
    @State private var thinking: ThinkingMode = .auto_
    @State private var reasoningEffort: ReasoningEffort = .off

    // 角色
    @State private var catName = ""
    @State private var catNotes = ""
    @State private var traits: Set<CatTrait> = []
    @State private var speechStyle: CatSpeechStyle = .daily
    @State private var flavor: CatFlavor = .hint

    // 隐私
    @State private var locationEnabled = true

    // 异步反馈
    @State private var isBusy = false
    @State private var notice: String?
    @State private var noticeIsError = false
    @State private var fetchedModels: [String] = []

    private let allTraits: [CatTrait] = [
        .gentle, .playful, .aloof, .clingy, .witty, .calm, .curious, .lazy
    ]
    private let allSpeechStyles: [CatSpeechStyle] = [.daily, .concise, .sweet, .literary, .energetic]
    private let allFlavors: [CatFlavor] = [.human, .hint, .cat]

    private var capabilities: IosAppEnvironment.ModelCapabilities {
        model.environment.capabilitiesFor(baseUrl: baseUrl, model: modelName)
    }

    var body: some View {
        NavigationStack {
            Form {
                connectionSection
                samplingSection
                reasoningSection
                personaSection
                traitsSection
                privacySection
                if let notice {
                    Section {
                        Text(notice).foregroundStyle(noticeIsError ? .red : .primary)
                    }
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") { save() }
                }
            }
            .onAppear(perform: loadCurrentValues)
        }
    }

    // MARK: - 连接

    private var connectionSection: some View {
        Section {
            TextField("Base URL", text: $baseUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            TextField("API Key", text: $apiKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("模型", text: $modelName)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button("测试连接") { testConnection() }
                .disabled(isBusy)
            Button("获取模型列表") { fetchModels() }
                .disabled(isBusy)

            if !fetchedModels.isEmpty {
                ForEach(fetchedModels, id: \.self) { item in
                    Button(item) { modelName = item }
                        .foregroundStyle(modelName == item ? .secondary : .primary)
                }
            }
        } header: {
            Text("模型服务")
        } footer: {
            Text("任何提供 /chat/completions 的 OpenAI 兼容服务都可以填。"
                + "「测试连接」会用当前参数真的发一条最短的请求。")
        }
    }

    // MARK: - 参数

    @ViewBuilder
    private var samplingSection: some View {
        let caps = capabilities
        if caps.temperature != nil || caps.topP != nil || caps.maxTokens != nil {
            Section {
                if let range = caps.temperature {
                    SliderRow(
                        title: "temperature",
                        value: $temperature,
                        range: range,
                        hint: "越高越随机"
                    )
                }
                if let range = caps.topP {
                    SliderRow(title: "top_p", value: $topP, range: range, hint: "采样范围")
                }
                if let range = caps.maxTokens {
                    SliderRow(
                        title: "max_tokens",
                        value: $maxTokens,
                        range: range,
                        hint: "0 表示不限制"
                    )
                }
            } header: {
                Text("采样参数")
            } footer: {
                Text("只显示当前模型接受的参数；模型不支持的会被自动跳过。")
            }
        }
    }

    @ViewBuilder
    private var reasoningSection: some View {
        let caps = capabilities
        if caps.hasThinkingToggle || !caps.reasoningLevels.isEmpty {
            Section {
                if caps.hasThinkingToggle {
                    Picker("思考", selection: $thinking) {
                        Text(ThinkingMode.auto_.label).tag(ThinkingMode.auto_)
                        Text(ThinkingMode.on.label).tag(ThinkingMode.on)
                        Text(ThinkingMode.off.label).tag(ThinkingMode.off)
                    }
                }
                if !caps.reasoningLevels.isEmpty {
                    Picker("思考深度", selection: $reasoningEffort) {
                        ForEach(caps.reasoningLevels, id: \.self) { level in
                            Text(level.label).tag(level)
                        }
                        // 「关闭」表示请求里根本不出现这个字段。
                        Text(ReasoningEffort.off.label).tag(ReasoningEffort.off)
                    }
                }
            } header: {
                Text("思考")
            }
        }
    }

    private var personaSection: some View {
        Section {
            TextField("名字", text: $catName)
            TextField("补充设定（称呼、背景、禁忌…）", text: $catNotes, axis: .vertical)
                .lineLimit(2...6)

            Picker("说话风格", selection: $speechStyle) {
                ForEach(allSpeechStyles, id: \.self) { style in
                    Text(style.label).tag(style)
                }
            }
            Picker("猫味浓度", selection: $flavor) {
                ForEach(allFlavors, id: \.self) { item in
                    Text(item.label).tag(item)
                }
            }
        } header: {
            Text("猫猫")
        } footer: {
            Text("名字、补充设定、风格与浓度都会进 system prompt。"
                + "名字要清空聊天记录后，新开场白才会用上。")
        }
    }

    @ViewBuilder
    private var traitsSection: some View {
        Section {
            ForEach(allTraits, id: \.self) { trait in
                Button {
                    toggle(trait)
                } label: {
                    HStack {
                        Text(trait.label)
                        Spacer()
                        if traits.contains(trait) {
                            Image(systemName: "checkmark").foregroundStyle(.tint)
                        }
                    }
                }
            }
        } header: {
            Text("性格（最多 \(CatPersona.companion.MAX_TRAITS) 个）")
        }
    }

    private var privacySection: some View {
        Section {
            Toggle("允许按 IP 推测城市", isOn: $locationEnabled)
        } header: {
            Text("隐私")
        } footer: {
            Text("打开后每半小时会把你的出口 IP 发给第三方接口换取城市名。"
                + "精度只到城市，开着 VPN 时拿到的是 VPN 的位置。关掉之后不再发任何定位请求。")
        }
    }

    // MARK: - 行为

    private func loadCurrentValues() {
        guard let state = model.state else { return }
        baseUrl = state.config.baseUrl
        apiKey = state.config.apiKey
        modelName = state.config.model
        temperature = Double(state.config.temperature)
        topP = Double(state.config.topP)
        maxTokens = Double(state.config.maxTokens)
        thinking = state.config.thinking
        reasoningEffort = state.config.reasoningEffort

        catName = state.persona.name
        catNotes = state.persona.notes
        traits = Set(state.persona.traits)
        speechStyle = state.persona.speechStyle
        flavor = state.persona.flavor

        locationEnabled = state.locationEnabled
    }

    private func toggle(_ trait: CatTrait) {
        if traits.contains(trait) {
            traits.remove(trait)
        } else if traits.count < Int(CatPersona.companion.MAX_TRAITS) {
            traits.insert(trait)
        }
    }

    private func testConnection() {
        isBusy = true
        notice = nil
        Task {
            // Kotlin 的 suspend 在 Swift 里是 async throws；失败已经在 Kotlin 侧折成
            // result.ok / message，所以这里只需要兜住真正的异常。
            do {
                let result = try await model.environment.testConnection(
                    baseUrl: baseUrl,
                    apiKey: apiKey,
                    model: modelName
                )
                notice = result.message
                noticeIsError = !result.ok
            } catch {
                notice = "测试失败：\(error.localizedDescription)"
                noticeIsError = true
            }
            isBusy = false
        }
    }

    private func fetchModels() {
        isBusy = true
        notice = nil
        Task {
            do {
                let result = try await model.environment.fetchModels(baseUrl: baseUrl, apiKey: apiKey)
                fetchedModels = result.models
                notice = result.message
                noticeIsError = !result.ok
            } catch {
                notice = "获取失败：\(error.localizedDescription)"
                noticeIsError = true
            }
            isBusy = false
        }
    }

    private func save() {
        let trimmed = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            notice = "请先填写 Base URL"
            noticeIsError = true
            return
        }
        guard let url = URL(string: trimmed), url.scheme != nil else {
            notice = "Base URL 看起来不是一个合法地址"
            noticeIsError = true
            return
        }
        model.saveConfig(
            baseUrl: trimmed,
            apiKey: apiKey,
            model: modelName,
            temperature: Float(temperature),
            topP: Float(topP),
            maxTokens: Int32(maxTokens),
            thinking: thinking,
            reasoningEffort: reasoningEffort
        )
        model.savePersona(
            name: catName,
            notes: catNotes,
            traits: Array(traits),
            speechStyle: speechStyle,
            flavor: flavor
        )
        model.setLocationEnabled(locationEnabled)
        dismiss()
    }
}

/// 一条带说明的数值滑杆：取值范围来自模型能力表，步长也用它给的。
private struct SliderRow: View {
    let title: String
    @Binding var value: Double
    let range: IosAppEnvironment.NumberRange
    let hint: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                Spacer()
                Text(formatted).foregroundStyle(.secondary).monospacedDigit()
            }
            Slider(
                value: $value,
                in: Double(range.min)...Double(range.max),
                step: Double(range.step)
            )
            Text(hint).font(.caption).foregroundStyle(.secondary)
        }
    }

    private var formatted: String {
        String(format: "%.\(range.decimals)f", value)
    }
}
