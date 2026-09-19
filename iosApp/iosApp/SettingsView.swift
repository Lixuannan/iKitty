import SwiftUI
import Shared

/// 连接设置。
///
/// 只覆盖"要让聊天跑起来"必需的三个字段：Base URL、API Key、模型名。
/// 采样参数、角色设定、定位开关等后续再加。
struct SettingsView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var baseUrl = ""
    @State private var apiKey = ""
    @State private var modelName = ""

    var body: some View {
        NavigationStack {
            Form {
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

                Section {
                    Button("保存") {
                        model.saveConnection(baseUrl: baseUrl, apiKey: apiKey, model: modelName)
                        if model.settingsError == nil {
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
            }
            .onAppear(perform: loadCurrentValues)
        }
    }

    private func loadCurrentValues() {
        guard let config = model.state?.config else { return }
        baseUrl = config.baseUrl
        apiKey = config.apiKey
        modelName = config.model
    }
}
