import SwiftUI
import UniformTypeIdentifiers
import Shared

/// 备份与恢复。
///
/// `.ikitty` 就是一个 ZIP，但系统没有注册这个类型，所以导入时按"任意数据"选文件，
/// 再由共享代码校验是不是 iKitty 的备份——校验不通过什么都不会被改。
struct BackupView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var exportedFile: URL?
    @State private var isImporting = false
    @State private var isWorking = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if isWorking {
                        HStack {
                            ProgressView()
                            Text("正在处理…")
                        }
                    }
                    Button("导出备份") { export() }
                        .disabled(isWorking)
                    if let exportedFile {
                        ShareLink(item: exportedFile) {
                            Label("分享备份文件", systemImage: "square.and.arrow.up")
                        }
                    }
                } header: {
                    Text("导出")
                } footer: {
                    Text("备份包含聊天记录、记忆、图片和设置（含 API Key）。"
                        + "导出后系统会给出分享选项，可以存到「文件」或发给别人。")
                }

                Section {
                    Button("导入备份") { isImporting = true }
                        .disabled(isWorking || (model.state?.busy ?? false))
                } header: {
                    Text("恢复")
                } footer: {
                    Text("导入会整体覆盖本机数据。文件会先完整校验，不是 iKitty 的备份就什么都不会变。"
                        + "正在等回复时不能导入，先让这条消息结束。")
                }

                if let message = model.backupMessage {
                    Section {
                        Text(message)
                            .foregroundStyle(message.contains("失败") ? .red : .primary)
                    }
                }
            }
            .navigationTitle("备份与恢复")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("完成") { dismiss() }
                }
            }
            .fileImporter(
                isPresented: $isImporting,
                allowedContentTypes: [.data],
                allowsMultipleSelection: false
            ) { result in
                handleImport(result)
            }
        }
    }

    private func export() {
        isWorking = true
        model.backupMessage = nil
        Task {
            do {
                let path = try await model.environment.exportBackupToFile()
                exportedFile = URL(fileURLWithPath: path)
            } catch {
                model.backupMessage = "导出失败：\(error.localizedDescription)"
            }
            isWorking = false
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            model.backupMessage = "导入失败：\(error.localizedDescription)"
        case .success(let urls):
            guard let url = urls.first else { return }
            isWorking = true
            model.backupMessage = nil
            Task {
                // fileImporter 给的是安全作用域外的 URL，读之前必须显式取权限。
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    let data = try Data(contentsOf: url)
                    model.backupMessage = try await model.environment.importBackup(data: data)
                } catch {
                    model.backupMessage = "导入失败：\(error.localizedDescription)"
                }
                isWorking = false
            }
        }
    }
}
