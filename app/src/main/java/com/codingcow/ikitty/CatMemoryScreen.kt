package com.codingcow.ikitty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 「猫猫的记忆」页。
 *
 * 记忆是模型自己整理的，所以必须可见可改：记错了可以直接删掉或改掉，
 * 否则用户没有任何办法纠正它。
 */
@Composable
fun CatMemoryScreen(
    facts: List<MemoryFact>,
    status: MemoryStatus,
    plan: ContextPlan?,
    messageCount: Int,
    onBack: () -> Unit,
    onExtract: () -> Unit,
    onUpsert: (String?, MemoryCategory, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onClearMemory: () -> Unit,
    onClearMessages: () -> Unit
) {
    // 对话框的状态必须由本页持有，不能用 rememberSaveable 放在 AlertDialog 里面：
    // Dialog 是独立窗口，Activity 的 onSaveInstanceState 不会保存它的视图树，对话框内部的
    // 可保存状态在旋转时一定丢。放在这里才能跨重建保住用户已经输入的关键词和内容。
    var creating by rememberSaveable { mutableStateOf(false) }
    /** 正在修改的条目 key；用 key 而不是整条记录，旋转后按最新的 facts 重新查一遍。 */
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var draftCategory by rememberSaveable { mutableStateOf(MemoryCategory.OWNER) }
    var draftKey by rememberSaveable { mutableStateOf("") }
    var draftValue by rememberSaveable { mutableStateOf("") }

    val editing = facts.firstOrNull { it.key == editingKey }

    fun openCreate() {
        creating = true
        editingKey = null
        draftCategory = MemoryCategory.OWNER
        draftKey = ""
        draftValue = ""
    }

    fun openEdit(fact: MemoryFact) {
        creating = false
        editingKey = fact.key
        draftCategory = fact.category
        draftKey = fact.key
        draftValue = fact.value
    }

    fun closeEditor() {
        creating = false
        editingKey = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "猫猫的记忆",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { openCreate() }) {
                Icon(Icons.Default.Add, contentDescription = "新增记忆")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Note("猫猫会定期把对话里长期有效的事整理成下面这些条目，每条都会随下一次请求一起发给它。没提到的条目不会被自动删掉。")
            }

            item { MemoryStatusCard(status = status, onExtract = onExtract, onClearMemory = onClearMemory) }

            if (facts.isEmpty()) {
                item { Note("还没有记住任何事。聊几句之后会自动整理，也可以点上面的「现在整理」。") }
            }

            MemoryCategory.entries.forEach { category ->
                val group = facts.filter { it.category == category }
                if (group.isEmpty()) return@forEach
                item(key = "header-${category.name}") { GroupHeader(category) }
                items(group, key = { it.key }) { fact ->
                    FactCard(
                        fact = fact,
                        onEdit = { openEdit(fact) },
                        onDelete = { onDelete(fact.key) },
                        onTogglePin = { onTogglePin(fact.key) }
                    )
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
            }

            item { ConversationCard(messageCount = messageCount, onClear = onClearMessages) }

            plan?.let { item { ContextCard(it) } }
        }
    }

    if (creating || editing != null) {
        val initial = editing
        FactDialog(
            title = if (initial == null) "新增记忆" else "修改记忆",
            category = draftCategory,
            keyword = draftKey,
            content = draftValue,
            onCategoryChange = { draftCategory = it },
            onKeywordChange = { draftKey = it },
            onContentChange = { draftValue = it },
            onDismiss = { closeEditor() },
            onConfirm = {
                onUpsert(initial?.key, draftCategory, draftKey.trim(), draftValue.trim())
                closeEditor()
            }
        )
    }
}

@Composable
private fun MemoryStatusCard(status: MemoryStatus, onExtract: () -> Unit, onClearMemory: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.running) {
                    CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        status.running -> "正在整理记忆…"
                        status.lastRunAt > 0L -> "上次整理：${formatMessageTime(status.lastRunAt)}"
                        else -> "还没有整理过记忆"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            status.lastError?.let {
                Text(
                    text = "整理失败：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExtract, enabled = !status.running) { Text("现在整理") }
                TextButton(onClick = onClearMemory, enabled = !status.running) { Text("清空记忆") }
            }
        }
    }
}

@Composable
private fun FactCard(
    fact: MemoryFact,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fact.key,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (fact.pinned) {
                    Text(
                        text = "已固定",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = fact.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onTogglePin) {
                    Text(if (fact.pinned) "取消固定" else "固定")
                }
                TextButton(onClick = onEdit) { Text("修改") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun ConversationCard(messageCount: Int, onClear: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "对话记录",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "已保存 $messageCount 条消息，全部存在本机，不会随聊天变长而变慢。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onClear) { Text("清空聊天记录（记忆保留）") }
        }
    }
}

@Composable
private fun ContextCard(plan: ContextPlan) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "上次请求的上下文",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "带了 ${plan.keptMessages} 条消息，省略 ${plan.droppedMessages} 条；" +
                    "估算 ${plan.estimatedTokens} / ${plan.inputBudget} tokens。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "省略的老消息仍然保存在本机，只是不再发出去。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GroupHeader(category: MemoryCategory) {
    Text(
        text = "${category.label} · ${category.prompt}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 新增 / 修改记忆的对话框。
 *
 * 完全无状态：草稿由 [CatMemoryScreen] 用 rememberSaveable 持有，因为 Dialog 里的
 * 可保存状态活不过 Activity 重建（见调用处的说明）。
 */
@Composable
private fun FactDialog(
    title: String,
    category: MemoryCategory,
    keyword: String,
    content: String,
    onCategoryChange: (MemoryCategory) -> Unit,
    onKeywordChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemoryCategory.entries.forEach { option ->
                        FilterChip(
                            selected = option == category,
                            onClick = { onCategoryChange(option) },
                            label = { Text(option.label) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    label = { Text("关键词") },
                    placeholder = { Text("例如：名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    label = { Text("内容") },
                    placeholder = { Text("例如：小明") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = keyword.isNotBlank() && content.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
