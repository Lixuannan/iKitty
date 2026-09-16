package com.codingcow.ikitty

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 一条消息最多附带几张图片。 */
private const val MAX_ATTACHMENTS = 9

@Composable
fun CatChatScreen(vm: CatChatViewModel = viewModel()) {
    val messages by vm.messages.collectAsState()
    val mood by vm.mood.collectAsState()
    val busy by vm.busy.collectAsState()
    val streamingReply by vm.streamingReply.collectAsState()
    val config by vm.config.collectAsState()
    val persona by vm.persona.collectAsState()
    val memory by vm.memory.collectAsState()
    val memoryStatus by vm.memoryStatus.collectAsState()
    val contextPlan by vm.contextPlan.collectAsState()
    val locationEnabled by vm.locationEnabled.collectAsState()
    val updateStatus by vm.updateStatus.collectAsState()
    val backupStatus by vm.backupStatus.collectAsState()

    var input by remember { mutableStateOf("") }
    /** 已选好、等待发送的图片文件名。 */
    var attachments by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 相册选择：优先用系统照片选择器，老设备自动回退到系统文件选择器。
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                attachments = (attachments + vm.importImages(uris)).take(MAX_ATTACHMENTS)
            }
        }
    }

    // 系统相机：直接写进 FileProvider 提供的目标地址，返回后再收编进本机存储。
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        scope.launch {
            vm.finishCamera(success)?.let { name ->
                attachments = (attachments + name).take(MAX_ATTACHMENTS)
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            initial = config,
            initialPersona = persona,
            initialLocationEnabled = locationEnabled,
            appVersion = vm.appVersion,
            updateStatus = updateStatus,
            backupStatus = backupStatus,
            onSave = { newConfig, newPersona, enableLocation ->
                vm.saveSettings(newConfig, newPersona, enableLocation)
                showSettings = false
            },
            onTest = { vm.testConnection(it) },
            onListModels = { vm.listModels(it) },
            onCheckUpdate = { vm.checkForUpdate() },
            onDownloadUpdate = { vm.downloadUpdate() },
            onExportBackup = { vm.exportBackup(it) },
            onImportBackup = { vm.importBackup(it) },
            onBack = { showSettings = false }
        )
        return
    }

    if (showMemory) {
        CatMemoryScreen(
            facts = memory.facts,
            status = memoryStatus,
            plan = contextPlan,
            messageCount = messages.size,
            onBack = { showMemory = false },
            onExtract = { vm.extractMemoryNow() },
            onUpsert = { originalKey, category, key, value ->
                vm.upsertFact(originalKey, category, key, value)
            },
            onDelete = { vm.deleteFact(it) },
            onTogglePin = { vm.toggleFactPin(it) },
            onClearMemory = { vm.clearMemory() },
            onClearMessages = { vm.clearMessages() }
        )
        return
    }

    // 自动滚动到最新一条。流式输出期间用 scrollToItem，避免每个增量都重启一次动画。
    LaunchedEffect(messages.size, busy, streamingReply) {
        val itemCount = messages.size + if (busy || streamingReply != null) 1 else 0
        if (itemCount > 0) {
            if (streamingReply != null) {
                listState.scrollToItem(itemCount - 1)
            } else {
                listState.animateScrollToItem(itemCount - 1)
            }
        }
    }

    fun submit() {
        val text = input.trim()
        if ((text.isEmpty() && attachments.isEmpty()) || busy) return
        val toSend = attachments
        input = ""
        attachments = emptyList()
        vm.send(text, toSend)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
    ) {
        Header(
            title = persona.displayName(),
            mood = mood,
            modelLabel = "${config.provider().name} · ${config.model}",
            memoryCount = memory.facts.size,
            onOpenMemory = { showMemory = true },
            onOpenSettings = { showSettings = true }
        )

        // 猫咪画布暂时不显示，只保留聊天。CatView 仍在 CatView.kt 中，
        // 恢复时在这里插入一个 CatView(mood, animation) 的舞台即可。
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.seq }) { index, msg ->
                MessageBubble(msg = msg, showTime = shouldShowTime(messages, index))
            }
            if (busy && streamingReply.isNullOrEmpty()) {
                item { ThinkingBubble() }
            }
            if (!streamingReply.isNullOrEmpty()) {
                item { StreamingBubble(text = streamingReply.orEmpty()) }
            }
        }

        InputBar(
            value = input,
            attachments = attachments,
            onValueChange = {
                input = it
                vm.onInputChanged(it)
            },
            onSend = { submit() },
            onPickImages = {
                pickImages.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onTakePhoto = {
                vm.newCameraTarget()?.let { uri -> takePhoto.launch(uri) }
            },
            onRemoveAttachment = { index ->
                attachments = attachments.filterIndexed { position, _ -> position != index }
            },
            sendEnabled = (input.isNotBlank() || attachments.isNotEmpty()) && !busy
        )
    }
}

@Composable
private fun Header(
    title: String,
    mood: CatMood,
    modelLabel: String,
    memoryCount: Int,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = mood.label(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onOpenMemory,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = if (memoryCount > 0) "猫猫的记忆（$memoryCount）" else "猫猫的记忆",
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/** 每 5 分钟或换了说话人就重新显示一次时间，和常见聊天应用一致。 */
private fun shouldShowTime(messages: List<StoredMessage>, index: Int): Boolean {
    if (index == 0) return true
    val previous = messages[index - 1]
    val current = messages[index]
    if (previous.role != current.role) return true
    return current.createdAt - previous.createdAt >= TIME_GAP_MILLIS
}

private const val TIME_GAP_MILLIS = 5 * 60 * 1000L

/** 气泡四边统一圆角；用户和猫猫只靠左右位置和配色区分，不再靠缺角。 */
private val BubbleShape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(msg: StoredMessage, showTime: Boolean) {
    val isUser = msg.role == StoredMessage.ROLE_USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (showTime) {
            Text(
                text = formatMessageTime(msg.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                ) {
                    CatAvatar(modifier = Modifier.fillMaxSize().padding(3.dp))
                }
                Spacer(Modifier.width(8.dp))
            }

            Surface(
                shape = BubbleShape,
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                shadowElevation = if (isUser) 0.dp else 1.dp,
                border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.widthIn(max = 292.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (msg.images.isNotEmpty()) {
                        FlowRow(
                            maxItemsInEachRow = 2,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            msg.images.forEach { name ->
                                ChatImage(
                                    name = name,
                                    modifier = Modifier
                                        .size(126.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
                        }
                    }
                    // 图片消息允许不带文字，此时不渲染空气泡文本。
                    if (msg.content.isNotEmpty()) {
                        Text(
                            text = msg.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = when {
                                isUser -> MaterialTheme.colorScheme.onPrimary
                                msg.localError -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 流式回复的临时气泡：内容随增量增长，结束后由真正的消息取代。 */
@Composable
private fun StreamingBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        ) {
            CatAvatar(modifier = Modifier.fillMaxSize().padding(3.dp))
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = BubbleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 292.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        ) {
            CatAvatar(modifier = Modifier.fillMaxSize().padding(3.dp))
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = BubbleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    var active by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        delay(index * 160L)
                        while (true) {
                            active = true
                            delay(420L)
                            active = false
                            delay(420L)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(if (active) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    attachments: List<String>,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImages: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    sendEnabled: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (attachments.isNotEmpty()) {
                AttachmentStrip(names = attachments, onRemove = onRemoveAttachment)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                AddImageButton(onPickImages = onPickImages, onTakePhoto = onTakePhoto)
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("和猫猫说点什么…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = sendEnabled,
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/** 已选图片的缩略图条，每张右上角带一个删除按钮。 */
@Composable
private fun AttachmentStrip(names: List<String>, onRemove: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        names.forEachIndexed { index, name ->
            Box(modifier = Modifier.size(64.dp)) {
                ChatImage(
                    name = name,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                )
                IconButton(
                    onClick = { onRemove(index) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000))
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除图片",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/** 「+」按钮：相册与拍照两个入口。 */
@Composable
private fun AddImageButton(onPickImages: () -> Unit, onTakePhoto: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加图片",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("从相册选择") },
                onClick = {
                    open = false
                    onPickImages()
                }
            )
            DropdownMenuItem(
                text = { Text("拍照") },
                onClick = {
                    open = false
                    onTakePhoto()
                }
            )
        }
    }
}

internal fun CatMood.label(): String = when (this) {
    CatMood.IDLE -> "陪你待着"
    CatMood.LISTENING -> "在听你说…"
    CatMood.THINKING -> "思考中…"
    CatMood.HAPPY -> "喵喵很开心"
    CatMood.SAD -> "呜……好像出问题了"
    CatMood.EXCITED -> "好兴奋！"
    CatMood.SLEEPY -> "有点困了…"
}
