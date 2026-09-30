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

    // 同步（与设置分开存放，见 IosAppEnvironment.syncDefaults）
    @State private var syncServiceUrl = ""
    @State private var syncAccountKey = ""
    @State private var syncIncludeApiKey = false
    @State private var confirmingCloudDelete = false

    /// 与服务端 `MIN_KEY_LENGTH` 一致；不一致会让用户拿到一个看不懂的 401。
    private let minAccountKeyLength = 5

    // 异步反馈
    @State private var isBusy = false
    @State private var notice: String?
    @State private var noticeIsError = false
    @State private var fetchedModels: [String] = []

    /// 初始值是否已经从 `model.state` 填过。见 `loadCurrentValues`。
    @State private var hasLoaded = false

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
                syncSection
                if let notice {
                    Section {
                        Text(notice).foregroundStyle(noticeIsError ? AppTheme.error : AppTheme.onSurface)
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
            // 正在等同步时不允许再点一次：连点两次会发出两轮同步、两轮等待。
            .disabled(isBusy)
            .onAppear(perform: loadCurrentValues)
            .onChange(of: model.state != nil) { _, _ in loadCurrentValues() }
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
                        .foregroundStyle(modelName == item ? AppTheme.onSurfaceVariant : AppTheme.primary)
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
                            Image(systemName: "checkmark").foregroundStyle(AppTheme.primary)
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

    // MARK: - 同步

    private var syncSection: some View {
        Section {
            TextField("同步服务地址", text: $syncServiceUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            SecureField("账号密钥", text: $syncAccountKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            // 警告要可执行：只提示"太短"而不给一条一步变强的路，用户只会去编一个更长的弱串。
            Button("生成随机密钥") { syncAccountKey = Self.randomAccountKey() }

            // 短密钥只有在被离线枚举时才会出问题，而那正是用户最难自己意识到的事：
            // 所以这里明说后果，而不是只写"建议更长"。
            if !syncAccountKey.isEmpty && syncAccountKey.count < minAccountKeyLength {
                Text("密钥太短：云端数据的账号 id 就是它的哈希，短串可以被离线枚举出来——"
                    + "别人能读到你的聊天记录和同步上去的 API Key。")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.error)
            }

            Toggle("把 API Key 一并同步到云端", isOn: $syncIncludeApiKey)

            // 正在等同步时要说清楚在等什么：这时按钮是禁用的，没有这行字用户只会觉得点不动。
            // 有反馈文案时优先显示它——失败原因比"正在同步"更需要被看到。
            if isBusy && notice == nil {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("正在保存并同步…").foregroundStyle(AppTheme.onSurfaceVariant)
                }
            } else if let status = model.state?.sync, !status.message.isEmpty {
                Text(status.message)
                    .font(.footnote)
                    .foregroundStyle(status.isFailed ? AppTheme.error : AppTheme.onSurfaceVariant)
            }

            // 点这里会**等**一次同步跑完再收工：用户点完就知道成功还是失败，不需要事后猜。
            // 等待期间上面的忙碌提示会顶住，超时有兜底，不会永远转下去。
            Button("保存并同步") {
                saveSyncCredentials()
            }
            // `state.sync` 本身不是可选的（只有 `state` 是），所以不能再套一层 `?.`。
            .disabled(isBusy || model.state?.sync.isWorking == true)

            Button("清空云端数据", role: .destructive) { confirmingCloudDelete = true }
                .disabled(isBusy || (syncAccountKey.isEmpty && syncServiceUrl.isEmpty))
        } header: {
            Text("云端同步")
        } footer: {
            Text("填写你自建的 Cloudflare Worker 地址与账号密钥即可在多台设备间同步聊天记录。"
                + "地址与密钥随「保存」一起写入本机，下次打开会回填；"
                + "「保存并同步」会立刻上传一次并等它结束，结果就在这一行显示。"
                + "同步失败不会影响本机数据。"
                + "云端以最后写入为准，本机记录不会被同步删除。"
                + "打开上面的开关后，API Key 会以明文存放在你的 D1 数据库里。")
        }
        .confirmationDialog(
            "清空云端数据？",
            isPresented: $confirmingCloudDelete,
            titleVisibility: .visible
        ) {
            Button("清空云端", role: .destructive) {
                // 先落盘再清：否则用的是上一次保存的凭据，可能清到别的账号上。
                // 这里刻意**不**顺带同步一次——删完就同步会把云端刚清掉的数据又传回去。
                // 落盘是同步完成的，所以这里不需要 Task，也不会等任何网络。
                if let failure = model.saveSyncCredentials(
                    serviceUrl: syncServiceUrl,
                    accountKey: syncAccountKey,
                    includeApiKey: syncIncludeApiKey
                ) {
                    notice = failure
                    noticeIsError = true
                    return
                }
                model.deleteCloudData()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("云端的历史记录会被删除且无法恢复（没有账号找回）。本机记录不受影响。")
        }
    }

    /// 保存同步凭据，等一次同步跑完，然后把结果写进 `notice`。
    ///
    /// 失败（例如密钥太短）也要显示出来：写入校验不过时本地什么都没改，用户看到的仍是
    /// 上一次的配置，这一点必须让人知道，而不是静默关掉页面。
    private func saveSyncCredentials() {
        isBusy = true
        notice = nil
        Task {
            let failure = await model.syncCredentialsAndSync(
                serviceUrl: syncServiceUrl,
                accountKey: syncAccountKey,
                includeApiKey: syncIncludeApiKey
            )
            isBusy = false
            if let failure {
                notice = failure
                // 同步失败只是"这次没成"，本机数据完好——用错误色提示，但不阻止继续操作。
                noticeIsError = model.state?.sync.isFailed == true
            }
        }
    }

    /// 32 字节 → base64url。不做任何"让它好记"的加工：这一步存在的唯一理由就是不可枚举。
    private static func randomAccountKey() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        for index in bytes.indices { bytes[index] = UInt8.random(in: 0...255) }
        let base64 = Data(bytes).base64EncodedString()
        let urlSafe = base64
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "ikitty-" + urlSafe
    }

    // MARK: - 行为

    private func loadCurrentValues() {
        // 只在第一次拿到状态时填一遍：`state` 会随着聊天不断变化，每次都重填会把用户的
        // 编辑冲掉。而它也可能在 sheet 刚出现时还没准备好（观察者的第一份快照还没到），
        // 那时不能就这么算了——否则界面是空的，一保存就把已有配置清成了空值。
        guard !hasLoaded, let state = model.state else { return }
        hasLoaded = true

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

        // 同步凭据不在状态快照里（它们不是聊天状态），直接读一次本机存储。
        // 读是同步的，所以没有"读回来之前用户就能编辑"的窗口：输入框能编辑时值已经填好了。
        //
        // 整套设置页共用「保存」这一个提交点：地址、密钥、开关都是草稿，点保存才落盘，
        // 点取消就丢掉。单独给开关开一条"立即生效"的路径会让取消不再是取消。
        let credentials = model.syncCredentials()
        syncServiceUrl = credentials.serviceUrl
        syncAccountKey = credentials.accountKey
        syncIncludeApiKey = credentials.includeApiKey
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
        model.saveSettings(
            baseUrl: trimmed,
            apiKey: apiKey,
            model: modelName,
            temperature: Float(temperature),
            topP: Float(topP),
            maxTokens: Int32(maxTokens),
            thinking: thinking,
            reasoningEffort: reasoningEffort,
            catName: catName,
            catNotes: catNotes,
            traits: Array(traits),
            speechStyle: speechStyle,
            flavor: flavor,
            locationEnabled: locationEnabled
        )

        // 同步凭据也必须在这里保存。
        //
        // 这是"URL 和密钥存不进去"的第二个根因：同步区原来只有一个写入点（「立即同步」），
        // 点「保存」只是关闭了页面，@State 里的地址与密钥随视图一起丢掉，
        // 下次打开 `loadCurrentValues` 读到空、再把空值赋回输入框。
        //
        // 保存时要**等一次同步**：这样关闭页面之前就能把失败原因显示出来，否则用户带着一个
        // 没生效的配置离开，还以为已经同步过了。校验不过（例如密钥太短）同样不能关页面。
        isBusy = true
        Task {
            let failure = await model.syncCredentialsAndSync(
                serviceUrl: syncServiceUrl,
                accountKey: syncAccountKey,
                includeApiKey: syncIncludeApiKey
            )
            isBusy = false
            if let failure {
                notice = failure
                noticeIsError = model.state?.sync.isFailed == true
                return
            }
            dismiss()
        }
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
                Text(formatted).foregroundStyle(AppTheme.onSurfaceVariant).monospacedDigit()
            }
            Slider(
                value: $value,
                in: Double(range.min)...Double(range.max),
                step: Double(range.step)
            )
            Text(hint).font(.caption).foregroundStyle(AppTheme.onSurfaceVariant)
        }
    }

    private var formatted: String {
        String(format: "%.\(range.decimals)f", value)
    }
}
