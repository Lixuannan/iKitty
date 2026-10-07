package com.codingcow.ikitty

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val syncStatus by vm.syncStatus.collectAsState()

    // 输入框与待发图片用 rememberSaveable 持有：旋转屏幕会重建 Activity，
    // 只靠 remember 的话用户已经打好的文字和选好的图片会在重建时消失。
    var input by rememberSaveable { mutableStateOf("") }
    /** 已选好、等待发送的图片文件名。 */
    var attachments by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    // 这三个也要跨重建保留：设置页/记忆页的草稿是它们自己的 rememberSaveable，
    // 页面一旦因旋转被关掉，那些草稿会随页面一起离开 composition，等于白存。
    /** 正在全屏查看的聊天记录图片名；`null` 表示没有打开大图。 */
    var previewImage by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMemory by rememberSaveable { mutableStateOf(false) }
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
            syncStatus = syncStatus,
            loadSyncSettings = {
                SyncSettingsSnapshot(
                    serviceUrl = vm.syncServiceUrl(),
                    accountKey = vm.syncAccountKey(),
                    includeApiKey = vm.syncIncludeApiKey()
                )
            },
            onSaveSyncCredentials = { url, key, includeApiKey ->
                vm.saveSyncCredentials(url, key, includeApiKey)
            },
            onPushToCloud = { url, key, includeApiKey ->
                vm.pushToCloud(url, key, includeApiKey)
            },
            onPullFromCloud = { url, key, includeApiKey ->
                vm.pullFromCloud(url, key, includeApiKey)
            },
            onSetSyncIncludeApiKey = { vm.setSyncIncludeApiKey(it) },
            onDeleteCloudData = { vm.deleteCloudData() },
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
            // 开场白不是一条消息：它只在"一条记录都没有"时渲染，不落盘、不上云，
            // 因此不会像以前那样每台新设备都在云端堆一条重复的问候。
            if (messages.isEmpty()) {
                item(key = "greeting") { CatTextBubble(text = persona.welcome()) }
            }
            // key 必须是**稳定身份**（[StoredMessage.msgId]），不能用 [StoredMessage.seq]：
            // 同步把云端那一份（服务端分配的 seq）与本机还没推上去的草稿（本机 seq）合并在一份
            // 列表里，两套 seq 的取值范围会重叠，同一个 seq 出现两次。Compose 的 key 一旦重复
            // 就抛 IllegalArgumentException 直接崩掉进程（滚动或自动滚动到末尾时触发）——这正是
            // "开启云同步后频繁闪退"的原因。msgId 在同步前就已固化，且合并时按它去重。
            itemsIndexed(messages, key = { _, msg -> msg.msgId }) { index, msg ->
                MessageBubble(
                    msg = msg,
                    // 分组规则由 :shared 给，Android 与 iOS 因此显示得一样。
                    showTime = shouldShowMessageTime(messages.getOrNull(index - 1), msg),
                    onImageClick = { name -> previewImage = name }
                )
            }
            if (busy && streamingReply.isNullOrEmpty()) {
                item { ThinkingBubble() }
            }
            if (!streamingReply.isNullOrEmpty()) {
                // 流式内容也是猫猫的回复，同样去掉开头被模型照抄回来的时间前缀；
                // 否则那截前缀会在气泡里显示到这一轮结束、落成消息时才消失。
                item { CatTextBubble(text = stripLeadingMessageStamp(streamingReply.orEmpty())) }
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

    // 大图用全屏 Dialog 盖在聊天页上：它自己处理返回键，不影响上面的页面切换。
    previewImage?.let { name ->
        ImagePreviewDialog(name = name, onDismiss = { previewImage = null })
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

/** 气泡四边统一圆角；用户和猫猫只靠左右位置和配色区分，不再靠缺角。 */
private val BubbleShape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: StoredMessage, showTime: Boolean, onImageClick: (String) -> Unit) {
    val isUser = msg.role == StoredMessage.ROLE_USER

    // 显示与复制都用这一份：猫猫回复开头被模型照抄回来的时间前缀在这里被去掉，
    // 用户自己打的字原样保留。去前缀只发生在展示层，落盘与请求正文都不动。
    val displayText = msg.displayContent()

    // 长按气泡把整条消息复制走。文字本身用 [SelectionContainer] 包起来，
    // 所以"选中一部分再复制"用的是系统自带的文本选择工具条，两者互不冲突：
    // 长按文字是选择，长按气泡空白处是复制整条。
    val context = LocalContext.current
    val copyMessage = {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("iKitty", displayText))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        Unit
    }

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
                modifier = Modifier
                    .widthIn(max = 292.dp)
                    // 没有文字的消息（纯图片）没有可复制的内容，不给这个手势。
                    .then(
                        if (displayText.isNotEmpty()) {
                            Modifier.combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                // 不要涟漪：长按复制是隐藏手势，点一下不该有任何视觉反馈。
                                indication = null,
                                onClick = {},
                                onLongClick = copyMessage
                            )
                        } else {
                            Modifier
                        }
                    )
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
                                        .clip(RoundedCornerShape(12.dp)),
                                    onClick = { onImageClick(name) }
                                )
                            }
                        }
                    }
                    // 图片消息允许不带文字，此时不渲染空气泡文本。
                    if (displayText.isNotEmpty()) {
                        // 选中文字交给系统：长按文字是选择，工具条里的「复制」复制选中的那一段。
                        SelectionContainer {
                            Text(
                                text = displayText,
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
}

/**
 * 猫猫一侧的纯文本气泡。
 *
 * 两处共用：流式回复的临时气泡（内容随增量增长，结束后由真正的消息取代），以及空会话的
 * 开场白（不落盘、不上云，只在这里显示）。
 */
@Composable
private fun CatTextBubble(text: String) {
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
