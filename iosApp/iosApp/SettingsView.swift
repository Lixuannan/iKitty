import SwiftUI
import Shared

/// 设置。
///
/// 覆盖三块：连接（地址 / Key / 模型）、角色设定（名字 / 补充说明）、隐私（IP 定位开关）。
/// 采样参数与性格多选还没做——`ChatEngine` 已经能保存它们，只是界面还没暴露。
struct SettingsView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var baseUrl = ""
    @State private var apiKey = ""
    @State private var modelName = ""
    @State private var catName = ""
    @State private var catNotes = ""
    @State private var locationEnabled = true

    var body: some View {
        NavigationStack {
            Form {
                connectionSection
                personaSection
                privacySection
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
        } header: {
            Text("模型服务")
        } footer: {
            if let error = model.settingsError {
                Text(error).foregroundStyle(.red)
            } else {
                Text("任何提供 /chat/completions 的 OpenAI 兼容服务都可以填。")
            }
        }
    }

    private var personaSection: some View {
        Section {
            TextField("名字", text: $catName)
            TextField("补充设定（称呼、背景、禁忌…）", text: $catNotes, axis: .vertical)
                .lineLimit(2...6)
        } header: {
            Text("猫猫")
        } footer: {
            Text("名字和补充设定会写进 system prompt。清空聊天记录后新开场白才会用上新名字。")
        }
    }

    private var privacySection: some View {
        Section {
            Toggle("允许按 IP 推测城市", isOn: $locationEnabled)
        } header: {
            Text("隐私")
        } footer: {
            Text("打开后每半小时会把你的出口 IP 发给第三方接口换取城市名，用来给猫猫一点背景信息。"
                + "精度只到城市，开着 VPN 时拿到的是 VPN 的位置。关掉之后不再发任何定位请求。")
        }
    }

    private func loadCurrentValues() {
        guard let state = model.state else { return }
        baseUrl = state.config.baseUrl
        apiKey = state.config.apiKey
        modelName = state.config.model
        catName = state.persona.name
        catNotes = state.persona.notes
        locationEnabled = state.locationEnabled
    }

    private func save() {
        let trimmed = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            model.settingsError = "请先填写 Base URL"
            return
        }
        guard let url = URL(string: trimmed), url.scheme != nil else {
            model.settingsError = "Base URL 看起来不是一个合法地址"
            return
        }
        model.settingsError = nil
        model.saveConnection(baseUrl: trimmed, apiKey: apiKey, model: modelName)
        model.savePersona(name: catName, notes: catNotes)
        model.setLocationEnabled(locationEnabled)
        dismiss()
    }
}
