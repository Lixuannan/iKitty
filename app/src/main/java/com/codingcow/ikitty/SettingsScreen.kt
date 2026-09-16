package com.codingcow.ikitty

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private const val AUTO_FETCH_DEBOUNCE_MILLIS = 700L

@Composable
fun SettingsScreen(
    initial: ApiConfig,
    initialPersona: CatPersona,
    initialLocationEnabled: Boolean,
    appVersion: String,
    updateStatus: UpdateStatus,
    backupStatus: BackupStatus,
    onSave: (ApiConfig, CatPersona, Boolean) -> Unit,
    onTest: suspend (ApiConfig) -> Result<TestOutcome>,
    onListModels: suspend (ApiConfig) -> Result<ModelListOutcome>,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onExportBackup: (Uri) -> Unit,
    onImportBackup: (Uri) -> Unit,
    onBack: () -> Unit
) {
    var providerId by remember(initial) { mutableStateOf(initial.providerId) }
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var apiKey by remember(initial) { mutableStateOf(initial.apiKey) }
    var model by remember(initial) { mutableStateOf(initial.model) }

    var catName by remember(initialPersona) { mutableStateOf(initialPersona.name) }
    var catTraits by remember(initialPersona) { mutableStateOf(initialPersona.traits) }
    var catSpeechStyle by remember(initialPersona) { mutableStateOf(initialPersona.speechStyle) }
    var catFlavor by remember(initialPersona) { mutableStateOf(initialPersona.flavor) }
    var catNotes by remember(initialPersona) { mutableStateOf(initialPersona.notes) }

    // 打开设置时先把已存的值收敛到当前模型的范围，避免滑杆显示越界值。
    val initialSpec = remember(initial) { ModelCatalog.resolve(initial.providerId, initial.model) }
    var temperature by remember(initial) {
        mutableStateOf(initialSpec.temperature?.snap(initial.temperature) ?: initial.temperature)
    }
    var topP by remember(initial) {
        mutableStateOf(initialSpec.topP?.snap(initial.topP) ?: initial.topP)
    }
    var maxTokens by remember(initial) {
        mutableStateOf(
            initialSpec.maxTokens?.let { param ->
                if (initial.maxTokens > param.max) param.max.toInt() else initial.maxTokens
            } ?: initial.maxTokens
        )
    }
    var thinking by remember(initial) { mutableStateOf(initial.thinking) }
    var reasoning by remember(initial) { mutableStateOf(initial.reasoningEffort) }
    var locationEnabled by remember(initialLocationEnabled) { mutableStateOf(initialLocationEnabled) }
    var showKey by remember { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var testOutcome by remember { mutableStateOf<TestOutcome?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }

    var remoteModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf<String?>(null) }
    var modelStatusIsError by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val provider = ModelCatalog.provider(providerId)
    val spec = remember(providerId, model) { ModelCatalog.resolve(providerId, model) }
    val modelOptions = remember(remoteModels, provider) { (provider.models + remoteModels).distinct() }

    fun current(): ApiConfig = ApiConfig(
        providerId = providerId,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        thinking = thinking,
        reasoningEffort = reasoning
    )

    fun currentPersona(): CatPersona = CatPersona(
        name = catName.trim(),
        traits = catTraits,
        speechStyle = catSpeechStyle,
        flavor = catFlavor,
        notes = catNotes.trim()
    )

    /** 切换服务商或点"恢复默认"时，把采样参数拉回该模型的默认值。 */
    fun applyDefaults(target: ModelSpec) {
        target.temperature?.let { temperature = it.default }
        target.topP?.let { topP = it.default }
        maxTokens = target.maxTokens?.default?.toInt() ?: 0
        thinking = ThinkingMode.AUTO
        reasoning = ReasoningEffort.OFF
    }

    /** 换模型只做收敛：不支持的参数隐藏并复位，超范围的值拉回区间内。 */
    fun applyModel(value: String) {
        model = value
        val next = ModelCatalog.resolve(providerId, value)
        temperature = next.temperature?.snap(temperature) ?: temperature
        topP = next.topP?.snap(topP) ?: topP
        maxTokens = next.maxTokens?.let { if (maxTokens > it.max) it.max.toInt() else maxTokens } ?: maxTokens
        if (next.reasoning !is ReasoningSpec.Toggle) thinking = ThinkingMode.AUTO
        if (next.reasoning !is ReasoningSpec.Effort) reasoning = ReasoningEffort.OFF
        testOutcome = null
        testError = null
    }

    suspend fun loadModels(manual: Boolean) {
        if (baseUrl.isBlank()) return
        loadingModels = true
        val result = onListModels(current())
        loadingModels = false
        result.fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is ModelListOutcome.Available -> {
                        remoteModels = outcome.models
                        modelStatusIsError = false
                        modelStatus = if (outcome.models.isEmpty()) {
                            "服务商返回了空列表，先使用内置预设"
                        } else {
                            "已获取 ${outcome.models.size} 个模型"
                        }
                    }
                    ModelListOutcome.NotSupported -> {
                        remoteModels = emptyList()
                        modelStatusIsError = false
                        modelStatus = "该服务商不提供模型列表接口，使用内置预设"
                    }
                }
            },
            onFailure = { error ->
                modelStatusIsError = manual
                modelStatus = if (manual) {
                    "获取模型失败：${error.message ?: "未知错误"}"
                } else {
                    "未能自动获取模型列表，先用内置预设"
                }
            }
        )
    }

    // 填好地址和 Key 之后自动拉取一次最新模型列表。
    LaunchedEffect(providerId, baseUrl, apiKey) {
        val target = ModelCatalog.provider(providerId)
        if (target.modelsPath == null) {
            modelStatusIsError = false
            modelStatus = "该服务商不提供模型列表接口，使用内置预设"
            return@LaunchedEffect
        }
        val canFetch = baseUrl.isNotBlank() && (apiKey.isNotBlank() || !target.authRequired)
        if (!canFetch) {
            modelStatus = null
            return@LaunchedEffect
        }
        delay(AUTO_FETCH_DEBOUNCE_MILLIS)
        loadModels(manual = false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
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
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        SectionTitle("猫猫设定")

        OutlinedTextField(
            value = catName,
            onValueChange = { catName = it },
            label = { Text("名字") },
            placeholder = { Text(CatPersona.DEFAULT_NAME) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Hint("名字会用在标题和开场白上，留空则用「${CatPersona.DEFAULT_NAME}」。")

        Spacer(Modifier.height(12.dp))

        FieldLabel("性格（最多 ${CatPersona.MAX_TRAITS} 个）")
        MultiChipRow(
            options = CatTrait.entries,
            selected = catTraits,
            label = { it.label },
            onToggle = { trait ->
                catTraits = when {
                    trait in catTraits -> catTraits - trait
                    catTraits.size < CatPersona.MAX_TRAITS -> catTraits + trait
                    else -> catTraits
                }
            }
        )
        Hint(
            if (catTraits.size >= CatPersona.MAX_TRAITS) {
                "已选满，想换一个请先取消一个。"
            } else {
                "性格会写进 system prompt，选太多容易互相矛盾。"
            }
        )

        Spacer(Modifier.height(12.dp))

        FieldLabel("说话风格")
        ChipRow(
            options = CatSpeechStyle.entries,
            selected = catSpeechStyle,
            label = { it.label },
            onSelect = { catSpeechStyle = it }
        )

        Spacer(Modifier.height(12.dp))

        FieldLabel("猫味")
        ChipRow(
            options = CatFlavor.entries,
            selected = catFlavor,
            label = { it.label },
            onSelect = { catFlavor = it }
        )
        Hint(catFlavor.prompt)

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = catNotes,
            onValueChange = { catNotes = it },
            label = { Text("补充设定") },
            placeholder = { Text("例如：叫我主人；不要聊工作；喜欢用尾巴拍人") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Hint("会附加在 system prompt 末尾，可以写称呼、背景、禁忌。")

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("模型服务")

        DropdownField(
            label = "服务商",
            value = provider.name,
            options = ModelCatalog.providers.map { it.name },
            onSelect = { name ->
                val option = ModelCatalog.providers.firstOrNull { it.name == name } ?: return@DropdownField
                providerId = option.id
                if (option.baseUrl.isNotBlank()) baseUrl = option.baseUrl
                val nextModel = option.defaultModel.ifBlank { model }
                model = nextModel
                applyDefaults(ModelCatalog.resolve(option.id, nextModel))
                remoteModels = emptyList()
                modelStatus = null
                testOutcome = null
                testError = null
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        DropdownField(
            label = "模型",
            value = model,
            options = modelOptions,
            onSelect = { applyModel(it) },
            editable = true,
            onValueChange = { applyModel(it) },
            placeholder = { Text(provider.defaultModel.ifBlank { "模型名称" }) },
            modifier = Modifier.fillMaxWidth()
        )

        if (spec.label.isNotBlank() && spec.label != model) {
            Hint("识别为：${spec.label}")
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loadingModels) {
                CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = modelStatus ?: "填好地址和 Key 后会自动获取最新模型列表",
                style = MaterialTheme.typography.bodySmall,
                color = if (modelStatusIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { scope.launch { loadModels(manual = true) } },
                enabled = !loadingModels && baseUrl.isNotBlank() && provider.modelsPath != null
            ) {
                Text("刷新")
            }
        }

        Spacer(Modifier.height(8.dp))
        CapabilityCard(spec = spec)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("连接")

        OutlinedTextField(
            value = baseUrl,
            onValueChange = {
                baseUrl = it
                // 地址改回某个预设时自动切回该服务商，否则按自定义处理。
                providerId = ModelCatalog.providerIdForBaseUrl(it) ?: CUSTOM_PROVIDER_ID
            },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        provider.note?.let { Hint(it) }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            placeholder = { Text(provider.keyHint) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "隐藏" else "显示")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                testing = true
                testOutcome = null
                testError = null
                scope.launch {
                    val result = onTest(current())
                    testing = false
                    result.fold(
                        onSuccess = { testOutcome = it },
                        onFailure = { testError = it.message ?: "未知错误" }
                    )
                }
            },
            enabled = !testing && baseUrl.isNotBlank() && model.isNotBlank()
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (testing) "测试中…" else "测试连接")
        }

        testOutcome?.let { TestResultCard(it) }
        testError?.let { TestErrorCard(it) }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("生成参数")

        if (spec.temperature == null && spec.topP == null && spec.maxTokens == null) {
            Hint("该模型不接受采样参数，请求只发送模型名和 messages。")
        }

        spec.temperature?.let { param ->
            ParamSlider(
                label = "Temperature",
                param = param,
                value = temperature,
                valueText = param.format(temperature),
                onValueChange = { temperature = it }
            )
            Hint("越低越稳定克制，越高越活泼发散。")
        }

        spec.topP?.let { param ->
            ParamSlider(
                label = "Top P",
                param = param,
                value = topP,
                valueText = param.format(topP),
                onValueChange = { topP = it }
            )
            Hint("控制候选词范围，通常保持默认即可。")
        }

        spec.maxTokens?.let { param ->
            ParamSlider(
                label = "最大回复长度",
                param = param,
                value = maxTokens.toFloat(),
                valueText = if (maxTokens <= 0) "不限制" else "${param.snap(maxTokens.toFloat()).toInt()} tokens",
                onValueChange = { maxTokens = it.toInt() }
            )
            Hint("上限 ${param.max.toInt()} tokens，0 表示交给服务商决定。")
        }

        if (spec.unsupportedSamplingParams.isNotEmpty()) {
            Hint("该模型不支持 ${spec.unsupportedSamplingParams.joinToString(" / ")}，已自动跳过。")
        }

        ReasoningSection(
            spec = spec,
            thinking = thinking,
            reasoning = reasoning,
            onThinkingChange = { thinking = it },
            onReasoningChange = { reasoning = it }
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("位置")

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "让猫猫知道大概在哪个城市",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = locationEnabled,
                onCheckedChange = { locationEnabled = it }
            )
        }
        Hint(
            if (locationEnabled) {
                "用出口 IP 推测城市，不需要任何权限。精度只到城市，" +
                    "运营商或 VPN 都可能让它偏得很远；每次刷新会把 IP 交给第三方定位服务。"
            } else {
                "已关闭：不会再发定位请求，猫猫也不会知道你在哪。"
            }
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onSave(current(), currentPersona(), locationEnabled) },
            enabled = baseUrl.isNotBlank() && model.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存")
        }

        Spacer(Modifier.height(14.dp))

        TextButton(
            onClick = {
                applyDefaults(spec)
                testOutcome = null
                testError = null
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("恢复当前模型默认参数")
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        BackupSection(
            status = backupStatus,
            onExport = onExportBackup,
            onImport = onImportBackup
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        UpdateSection(
            currentVersion = appVersion,
            status = updateStatus,
            onCheck = onCheckUpdate,
            onDownload = onDownloadUpdate
        )

        Spacer(Modifier.height(40.dp))
    }
}

/**
 * 「备份与恢复」区：把聊天记录、图片、记忆和设置打包成一个 `.ikitty` 文件，或者整体还原。
 *
 * 文件选择交给系统的 SAF：应用不需要任何存储权限，也不关心用户把备份放在哪。
 * 两个动作都要先过一次确认：导出会带走 API Key，导入会覆盖本机全部数据。
 */
@Composable
private fun BackupSection(
    status: BackupStatus,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit
) {
    var askExport by remember { mutableStateOf(false) }
    var askImport by remember { mutableStateOf(false) }
    val busy = status is BackupStatus.Working

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME)
    ) { uri -> uri?.let(onExport) }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImport) }

    SectionTitle("备份与恢复")

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(onClick = { askExport = true }, enabled = !busy) { Text("导出备份") }
        OutlinedButton(onClick = { askImport = true }, enabled = !busy) { Text("导入备份") }
    }

    when (status) {
        BackupStatus.Idle -> Hint(
            "备份包含聊天记录、聊天里的图片、猫猫的记忆和全部设置（含 API Key），" +
                "导出的是已经保存的设置。"
        )

        is BackupStatus.Working -> Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = status.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is BackupStatus.Done -> StatusText(status.message, highlight = true)

        is BackupStatus.Failed -> StatusText(status.message, isError = true)
    }

    if (askExport) {
        AlertDialog(
            onDismissRequest = { askExport = false },
            title = { Text("导出备份") },
            text = {
                Text(
                    "备份文件里包含全部聊天记录、图片、猫的记忆和设置，其中包含你的 API Key。" +
                        "请只保存在自己信得过的地方，分享出去等于泄露密钥。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askExport = false
                    createBackup.launch(defaultBackupFileName(System.currentTimeMillis()))
                }) { Text("继续导出") }
            },
            dismissButton = {
                TextButton(onClick = { askExport = false }) { Text("取消") }
            }
        )
    }

    if (askImport) {
        AlertDialog(
            onDismissRequest = { askImport = false },
            title = { Text("导入备份") },
            text = {
                Text(
                    "导入会用备份里的内容覆盖本机的聊天记录、图片、记忆和设置（含 API Key），" +
                        "覆盖之后无法撤销。建议先导出一份当前数据。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askImport = false
                    // .ikitty 没有注册 MIME，只能放开所有类型让用户自己选。
                    openBackup.launch(arrayOf("*/*"))
                }) { Text("选择文件") }
            },
            dismissButton = {
                TextButton(onClick = { askImport = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 「软件更新」区：检查 GitHub release、下载并交给系统安装器覆盖安装。
 *
 * 安装动作放在 UI 层，因为它需要 Activity 的 Context 去启动系统界面；
 * 版本检查与下载属于 ViewModel。
 */
@Composable
private fun UpdateSection(
    currentVersion: String,
    status: UpdateStatus,
    onCheck: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }

    /** 覆盖安装：先确认系统允许本应用装未知来源，再打开安装器。 */
    fun install(file: File) {
        if (!ApkInstaller.canInstall(context)) {
            val opened = runCatching {
                context.startActivity(ApkInstaller.unknownSourcesSettings(context))
            }.isSuccess
            notice = if (opened) {
                "请先允许 iKitty「安装未知应用」，然后回来点「安装更新」。"
            } else {
                "打不开系统的安装授权页，请到「设置 → 应用 → 安装未知应用」里手动允许。"
            }
            return
        }
        val started = runCatching { ApkInstaller.install(context, file) }.isSuccess
        if (!started) {
            notice = "没有找到可用的系统安装器，请手动安装缓存目录里的安装包。"
        }
    }

    SectionTitle("软件更新")

    when (status) {
        UpdateStatus.Idle -> StatusText("当前版本 $currentVersion")

        UpdateStatus.Checking -> Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "正在检查更新…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is UpdateStatus.UpToDate -> StatusText("已是最新版本 ${status.currentVersion}")

        is UpdateStatus.Available -> {
            StatusText(
                text = "发现新版本 ${status.info.version}（当前 ${status.currentVersion}）",
                highlight = true
            )
            if (status.info.notes.isNotBlank()) ReleaseNotes(status.info.notes)
        }

        is UpdateStatus.Downloading -> {
            Spacer(Modifier.height(4.dp))
            if (status.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = {
                        (status.downloadedBytes.toFloat() / status.totalBytes).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val progress = if (status.totalBytes > 0) {
                "${formatBytes(status.downloadedBytes)} / ${formatBytes(status.totalBytes)}"
            } else {
                formatBytes(status.downloadedBytes)
            }
            StatusText("正在下载 ${status.info.version}：$progress")
        }

        is UpdateStatus.Ready -> StatusText(
            text = "${status.info.version} 已下载并校验通过，点「安装更新」交给系统覆盖安装。",
            highlight = true
        )

        is UpdateStatus.Failed -> StatusText(status.message, isError = true)
    }

    Spacer(Modifier.height(10.dp))

    when (status) {
        is UpdateStatus.Available -> Button(onClick = onDownload) { Text("下载更新") }

        is UpdateStatus.Downloading -> Button(onClick = {}, enabled = false) { Text("下载中…") }

        is UpdateStatus.Ready -> Button(onClick = { install(status.file) }) { Text("安装更新") }

        // 已经拿到版本信息时失败的是下载，重试下载；否则重试检查。
        is UpdateStatus.Failed -> if (status.info != null) {
            Button(onClick = onDownload) { Text("重试下载") }
        } else {
            Button(onClick = onCheck) { Text("重新检查") }
        }

        else -> Button(onClick = onCheck, enabled = status !is UpdateStatus.Checking) {
            Text("检查更新")
        }
    }

    notice?.let { Hint(it) }
    Hint("更新是覆盖安装：聊天记录、图片和猫猫的记忆都留在原处，不会被清除。")
}

/** release 说明最多展示 8 行，避免长日志把设置页拉得很长。 */
@Composable
private fun ReleaseNotes(notes: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
    ) {
        Text(
            text = notes.trim(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun StatusText(text: String, highlight: Boolean = false, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            isError -> MaterialTheme.colorScheme.error
            highlight -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(top = 6.dp)
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** 模型能力摘要：让用户一眼看到这个模型能调什么、不能调什么。 */
@Composable
private fun CapabilityCard(spec: ModelSpec) {
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
                text = spec.label.ifBlank { spec.modelId },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "可调参数：${spec.supportedParamNames.joinToString(" · ").ifEmpty { "无" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "上下文窗口：${spec.contextWindow / 1024}K tokens",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            spec.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 思考 / 推理控件：按模型能力决定是开关、档位还是"不可调"。 */
@Composable
private fun ReasoningSection(
    spec: ModelSpec,
    thinking: ThinkingMode,
    reasoning: ReasoningEffort,
    onThinkingChange: (ThinkingMode) -> Unit,
    onReasoningChange: (ReasoningEffort) -> Unit
) {
    when (val mode = spec.reasoning) {
        ReasoningSpec.Unsupported -> Unit

        ReasoningSpec.AlwaysOn -> {
            Spacer(Modifier.height(12.dp))
            SectionTitle("思考")
            Hint("该模型始终思考，无法关闭，也不接受 reasoning_effort。")
        }

        is ReasoningSpec.Toggle -> {
            Spacer(Modifier.height(12.dp))
            SectionTitle("思考")
            ChipRow(
                options = ThinkingMode.entries,
                selected = thinking,
                label = { it.label },
                onSelect = onThinkingChange
            )
            Hint(
                if (thinking == ThinkingMode.AUTO) {
                    "不发送 thinking 参数，由服务商决定默认值（GLM-4.5 默认开启，Air / Flash 默认关闭）。"
                } else {
                    "通过 thinking.type 控制，关闭思考后可以用更低的 temperature 换稳定输出。"
                }
            )
        }

        is ReasoningSpec.Effort -> {
            Spacer(Modifier.height(12.dp))
            SectionTitle("思考深度")
            val options = remember(mode.supported) {
                ReasoningEffort.entries.filter { it == ReasoningEffort.OFF || mode.supported.contains(it) }
            }
            ChipRow(
                options = options,
                selected = reasoning,
                label = { it.label },
                onSelect = onReasoningChange
            )
            Hint("通过 reasoning_effort 发送；选「关闭」时该模型只接受默认采样参数。")
        }
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
}

/** 多选版 chip 行：点击即切换，超限时由 [onToggle] 自己决定怎么处理。 */
@Composable
private fun <T> MultiChipRow(
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onToggle: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onToggle(option) },
                label = { Text(label(option)) }
            )
        }
    }
}

/** 分组小标题，和 [SectionTitle] 区分开，用于段内的字段名。 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun TestResultCard(outcome: TestOutcome) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "连接成功 · ${outcome.latencyMillis} ms",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            ResultLine("模型", outcome.model)
            ResultLine("端点", outcome.endpoint)
            ResultLine("回复", outcome.reply.abbreviate())
            if (outcome.reasoningChars > 0) {
                ResultLine("思考", "返回了 ${outcome.reasoningChars} 字思考内容")
            }
            ResultLine("已发送", outcome.sentParams.joinToString(" · ").ifEmpty { "无" })
            if (outcome.skippedParams.isNotEmpty()) {
                ResultLine("已跳过", outcome.skippedParams.joinToString(" · "))
            }
            outcome.totalTokens?.let { ResultLine("tokens", it.toString()) }
        }
    }
}

@Composable
private fun TestErrorCard(message: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Text(
            text = "连接失败：$message",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun ResultLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 一个下拉选择框。[editable] 为 true 时可以直接输入，否则只能从列表里选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onValueChange: (String) -> Unit = {},
    placeholder: @Composable (() -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    val anchorType = if (editable) MenuAnchorType.PrimaryEditable else MenuAnchorType.PrimaryNotEditable

    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { if (options.isNotEmpty()) open = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !editable,
            singleLine = true,
            label = { Text(label) },
            placeholder = placeholder,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(anchorType).fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 320.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/**
 * 按 [NumberParam] 渲染的滑杆：范围、档位、显示精度都来自模型能力表。
 */@Composable
private fun ParamSlider(
    label: String,
    param: NumberParam,
    value: Float,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    if (param.max <= param.min) return
    val snapped = param.snap(value)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = snapped,
            onValueChange = { onValueChange(param.snap(it)) },
            valueRange = param.min..param.max,
            steps = param.sliderSteps
        )
    }
}

/** 测试结果里的回复可能很长（尤其推理模型），展示时截断。 */
private fun String.abbreviate(limit: Int = 140): String =
    if (length <= limit) this else take(limit).trimEnd() + "…"
