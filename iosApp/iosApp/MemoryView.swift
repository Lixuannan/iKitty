import SwiftUI
import Shared

/// 正在编辑的一条记忆；`originalKey` 为空表示新增。
struct MemoryDraft: Identifiable {
    let id = UUID()
    var originalKey: String?
    var category: MemoryCategory
    var key: String
    var value: String
}

/// 记忆页。
///
/// 结构化长期记忆是这套提示词的核心之一：它会进 system prompt，所以用户必须能看见、
/// 能改、能删。这里只读写 `ChatEngine` 已经暴露的状态与方法，规则本身（合并、上限、
/// 固定项不被淘汰）都在共享代码里。
struct MemoryView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var draft: MemoryDraft?
    @State private var isConfirmingClear = false

    private let categories: [MemoryCategory] = [
        .owner, .preference, .relationship, .experience, .situation
    ]

    var body: some View {
        NavigationStack {
            List {
                statusSection
                ForEach(categories, id: \.self) { category in
                    factsSection(for: category)
                }
            }
            .navigationTitle("记忆")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("完成") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        draft = MemoryDraft(originalKey: nil, category: .owner, key: "", value: "")
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(item: $draft) { editing in
                MemoryEditor(draft: editing, categories: categories) { key, value, category in
                    model.upsertFact(
                        originalKey: editing.originalKey,
                        category: category,
                        key: key,
                        value: value
                    )
                }
            }
            .confirmationDialog(
                "清空所有记忆？",
                isPresented: $isConfirmingClear,
                titleVisibility: .visible
            ) {
                Button("清空", role: .destructive) { model.clearMemory() }
            }
        }
    }

    private var statusSection: some View {
        Section {
            if let status = model.state?.memoryStatus {
                if status.running {
                    HStack {
                        ProgressView()
                        Text("正在整理…")
                    }
                } else if let error = status.lastError {
                    Text("上次整理失败：\(error)").foregroundStyle(.red)
                } else if status.lastRunAt > 0 {
                    Text("上次整理：\(Self.format(status.lastRunAt))")
                        .foregroundStyle(.secondary)
                } else {
                    Text("还没有整理过").foregroundStyle(.secondary)
                }
            }
            Button("现在整理") { model.extractMemoryNow() }
                .disabled(model.state?.memoryStatus.running ?? false)
            Button("清空记忆", role: .destructive) { isConfirmingClear = true }
        } header: {
            Text("整理状态")
        } footer: {
            Text("记忆会进 system prompt。每攒够几条新消息会自动整理一次，也可以手动触发。")
        }
    }

    @ViewBuilder
    private func factsSection(for category: MemoryCategory) -> some View {
        let facts = facts(for: category)
        if !facts.isEmpty {
            Section(category.label) {
                ForEach(facts, id: \.key) { fact in
                    Button {
                        draft = MemoryDraft(
                            originalKey: fact.key,
                            category: fact.category,
                            key: fact.key,
                            value: fact.value
                        )
                    } label: {
                        HStack {
                            if fact.pinned {
                                Image(systemName: "pin.fill")
                                    .font(.caption)
                                    .foregroundStyle(.orange)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(fact.key).font(.subheadline).foregroundStyle(.secondary)
                                Text(fact.value).foregroundStyle(.primary)
                            }
                        }
                    }
                    .swipeActions(edge: .trailing) {
                        Button("删除", role: .destructive) { model.deleteFact(key: fact.key) }
                    }
                    .swipeActions(edge: .leading) {
                        Button(fact.pinned ? "取消固定" : "固定") {
                            model.toggleFactPin(key: fact.key)
                        }
                        .tint(.orange)
                    }
                }
            }
        }
    }

    /// 按分类分组，类别内"最近更新"在前。
    private func facts(for category: MemoryCategory) -> [MemoryFact] {
        (model.state?.memory.facts ?? [])
            .filter { $0.category == category }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    private static func format(_ epochMillis: Int64) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: Double(epochMillis) / 1000))
    }
}

private struct MemoryEditor: View {
    let draft: MemoryDraft
    let categories: [MemoryCategory]
    let onSave: (String, String, MemoryCategory) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var key: String
    @State private var value: String
    @State private var category: MemoryCategory

    init(
        draft: MemoryDraft,
        categories: [MemoryCategory],
        onSave: @escaping (String, String, MemoryCategory) -> Void
    ) {
        self.draft = draft
        self.categories = categories
        self.onSave = onSave
        _key = State(initialValue: draft.key)
        _value = State(initialValue: draft.value)
        _category = State(initialValue: draft.category)
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker("分类", selection: $category) {
                    ForEach(categories, id: \.self) { item in
                        Text(item.label).tag(item)
                    }
                }
                TextField("是什么（2–6 个汉字）", text: $key)
                TextField("记成什么（不超过 40 字）", text: $value, axis: .vertical)
            }
            .navigationTitle(draft.originalKey == nil ? "新增记忆" : "修改记忆")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        onSave(key, value, category)
                        dismiss()
                    }
                    .disabled(!canSave)
                }
            }
        }
    }

    private var canSave: Bool {
        !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
