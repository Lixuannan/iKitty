# iKitty 详细设计文档

> [English](DOC_EN.md) · [返回 README](../README.md)

本文是 iKitty 的架构地图与参考手册：模块契约、数据格式、关键算法、扩展点和测试策略。
面向要修改或扩展代码的人。使用方式、配置步骤和隐私说明在 [README](../README.md) 中。

- 版本：1.0.0 · 包名：`com.codingcow.ikitty`
- 源码：Android `app/src/main/java/com/codingcow/ikitty/` · 跨平台 `shared/src/commonMain/kotlin/com/codingcow/ikitty/` · iOS `iosApp/iosApp/`
- 技术栈：Kotlin 2.4.20、Jetpack Compose（Material3）、Kotlin Multiplatform（`:shared`，含 iOS 目标）、OkHttp 4.12.0 / Ktor 3.6.0、okio 3.18.2、kotlinx-serialization 1.11.0
- 构建：AGP 8.7.3、Gradle 9.7.0、Java 17 字节码目标、minSdk 26 / targetSdk 35、iOS 17+（Xcode 27.0）

---

## 1. 系统概览

### 1.1 分层

```
MainActivity (ComponentActivity + MaterialTheme)
│
└── CatChatScreen            ← 顶层 Composable，用布尔状态切换三个页面
    ├── SettingsScreen       ← 设置页
    ├── CatMemoryScreen      ← 记忆页
    └── 聊天页（Header + LazyColumn + InputBar）
            │  collectAsState / 事件回调
            ▼
    CatChatViewModel (AndroidViewModel)   ← 薄壳：只保留不可移植的两块
        ├── ChatEngine           → 全部聊天编排（见 1.1.1）
        ├── UpdateClient         → GitHub release 检查与 APK 下载
        ├── ApkInstaller         → 覆盖安装前的包名校验 / 签名校验
        └── .ikitty 备份的 content:// 出入口
```

依赖方向始终由 UI 指向 ViewModel，再由 ViewModel 指向存储与网络。
反向只通过 `StateFlow`：UI 不直接读写任何文件或网络。

### 1.1.1 跨平台结构（Android + iOS 共用 `:shared`）

与平台无关的逻辑集中在 `:shared`（Kotlin Multiplatform），Android 应用与 iOS 应用
（`iosApp/`，SwiftUI）共用同一份实现：

```
CatChatViewModel (Android)              ChatView / AppModel (iOS, SwiftUI)
        └──────────────┬─────────────────────────┘
                       ▼
                  ChatEngine                ← 编排：发送、流式、落盘、记忆、情绪
        ┌──────────────┼───────────────┬──────────────────────┐
        ▼              ▼               ▼                      ▼
   ApiClient     ChatLogStore    CatMemoryStore      ContextAssembler / CatPersona
        │              │               │              CatMemory / AmbientContext
        ▼              │               │              ModelCatalog / StoredMessage
  HttpTransport        │               │              CatReply / JsonSupport
   ├ OkHttp (JVM/Android)              │              PromptTime（进 prompt 的时间）
   └ Ktor Darwin (iOS)                 │              BackupArchive / ZipCodec
                            okio FileSystem            SettingsRepository
                    （androidMain / iosMain 各自给出根目录与调度器）
```

平台差异全部通过**构造参数注入**，不用 `expect/actual`：文件系统、根路径、IO 调度器、
图片归一化、设置存储（Android DataStore / iOS NSUserDefaults）各由平台提供；
键名、默认值与回退只在 `SettingsRepository` 里写一遍。

两端各有一条不可移植的尾巴：Android 是应用内 APK 更新与 `content://` 备份读写，
iOS 是 CoreGraphics 图片归一化与 `fileImporter` / `ShareLink`。

分阶段计划与进度见 [`KMP_IOS_MIGRATION_PLAN.md`](KMP_IOS_MIGRATION_PLAN.md)。

### 1.2 无 Android 依赖的纯逻辑层

下列对象不引用任何 Android API，因此能在纯 JVM 单元测试里直接调用：

| 对象 | 职责 |
| --- | --- |
| `CatPersona` | 角色设定 → system prompt |
| `ModelCatalog` | 服务商与模型能力、参数区间、名称启发式 |
| `ContextAssembler` / `TokenEstimator` | 上下文装配与 token 估算 |
| `CatMemoryRules` / `CatMemoryRender` / `parseMemoryUpdate` | 记忆合并、渲染、解析 |
| `CatReply` / `parseCatReply` | 模型回复解析 |
| `ModelCatalog` / `ChatModels` | 领域数据模型 |
| `TimeFormat` | 时间格式化 |
| `parseIpPlace` / `Place` | IP 返回解析 |
| `AmbientContext` | 「此刻」背景块 |
| `parseLatestRelease` / `compareVersions` | release JSON 解析与版本比较 |
| `BackupArchive` / `settingsToJson` / `settingsFromJson` / `parseCatMemory` | 备份归档的读写、设置序列化、记忆解析 |
| `exifTransformFor` | EXIF 方向标签 → 旋转角度与是否镜像 |
| `clampPan` | 大图放大后的拖动范围钳制 |

Android 相关的适配层：`ChatLogStore`、`CatMemoryStore`（文件 + `Context` 构造函数）、
`SettingsStore`（DataStore）、`IpLocationSource`（OkHttp）、`CatChatViewModel`（`AndroidViewModel`）、
以及全部 Compose UI。

### 1.3 状态清单

`CatChatViewModel` 暴露的全部状态：

| StateFlow | 类型 | 含义 |
| --- | --- | --- |
| `messages` | `List<StoredMessage>` | 当前会话（最多最近 400 条） |
| `memory` | `CatMemory` | 事实列表 + 提取游标 |
| `memoryStatus` | `MemoryStatus` | 整理中 / 上次整理时间 / 上次错误 |
| `contextPlan` | `ContextPlan?` | 上一次请求实际装配的上下文 |
| `mood` | `CatMood` | 当前情绪 |
| `animation` | `CatAnimation` | 当前一次性动作 |
| `busy` | `Boolean` | 是否正在等待模型回复 |
| `config` | `ApiConfig` | 模型服务配置 |
| `persona` | `CatPersona` | 角色设定 |
| `locationEnabled` | `Boolean` | 是否允许 IP 定位 |
| `updateStatus` | `UpdateStatus` | 更新流程状态（见 [18. 软件更新](#18-软件更新)） |
| `backupStatus` | `BackupStatus` | 导出 / 导入的进行状态（见 [19. 备份与恢复](#19-备份与恢复)） |

---

## 2. 一次消息的完整生命周期

1. 用户在输入框打字。`onInputChanged` 在非空时把情绪切到 `LISTENING`，清空后回到 `IDLE`；
   `busy` 期间不覆盖当前情绪。
2. `send(text, attachments)`：`trim` 后生成 `StoredMessage`，`seq = nextSeq++`，`role = user`，
   `images` 记录已导入的本机文件名；追加到 `messages` 并以 JSONL 落盘。
   文字与图片都为空、或 `busy` 时直接返回。同时若定位开关打开且缓存过期，后台触发一次刷新。
3. `busy = true`，情绪切到 `THINKING`。
4. 在 IO 线程把历史消息用到的图片编码成数据 URL（[ImageStore.dataUrls]），
   然后 `ContextAssembler.assemble(...)` 装配请求：system = 人设 + 记忆块 + 「此刻」背景块，
   历史按 token 预算裁剪，图片名经解析器转成数据 URL；结果写入 `contextPlan` 供记忆页展示。
5. `ApiClient.chatStream(config, plan.messages, onDelta)` 请求 `POST {Base}/chat/completions`，
   带 `stream: true`；每段增量把 `_streamingReply` 追加一次，界面显示流式气泡。
6. 流结束后 `parseCatReply(raw.text)` 解析完整回复：得到文本、可选情绪、可选动作。
   解析失败一律退回纯文本。`_streamingReply` 清空，追加 assistant 消息并落盘；
   情绪按 `autoReset = true` 设置，4 秒后自动回到 `IDLE`；动作按模型指定，未指定时由
   `CatAnimation.defaultFor(mood)` 兜底。
7. 出错时：若已经流出半截回复就先落成一条 assistant 消息，再追加一条 `localError = true` 的提示
   （显示为红色，但**不进请求、不进记忆**），情绪切 `SAD` 并播放 `SHAKE`。
8. `finally` 中清空 `_streamingReply` 并置 `busy = false`。
9. `maybeExtractMemory()`：若距提取游标已有 ≥6 条非错误消息，触发一次后台记忆整理。

清空聊天记录会把 `nextSeq` 归 1、清空 `contextPlan`，并把记忆的 `lastExtractedSeq` 一并归零，
否则新 `seq` 会被旧游标当成「早就整理过」。空会话会补一条开场白。

---

## 3. 领域模型

### 3.1 消息

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `StoredMessage` | `seq` / `role` / `content` / `createdAt` / `localError` / `images` | 落盘消息；`seq` 既是排序依据也是稳定 id；`images` 是本机文件名 |
| `ChatMessage` | `role` / `content` / `images` | 发给服务商的形式；`images` 是已编码的数据 URL |

`StoredMessage.toWire()` 是唯一的转换点，`localError`、`seq`、`createdAt`、图片文件名都不会进请求体，
图片在这里被解析成数据 URL（见 [17. 图片存储](#17-图片存储imagestorekt)）。
`role` 常量是 `"user"` / `"assistant"`。
一条消息只要 `content` 与 `images` 不同时为空就有效：纯图片消息允许不带文字。

### 3.2 情绪与动作

`CatMood`：`IDLE`、`LISTENING`、`THINKING`、`HAPPY`、`SAD`、`EXCITED`、`SLEEPY`。
`CatAnimation`：`NONE`、`BLINK(260ms)`、`LOOK_AROUND(2400ms)`、`TAIL_WAG(1600ms)`、
`BOUNCE(700ms)`、`SHAKE(800ms)`、`YAWN(1900ms)`。

两者完全解耦：同一情绪可搭配不同动作。`defaultFor(mood)` 在模型没指定动作时给出默认值
（`HAPPY→TAIL_WAG`、`EXCITED→BOUNCE`、`SLEEPY→YAWN`，其余为 `NONE`）。
ViewModel 在动作时长 + 200ms 缓冲后把 `animation` 清回 `NONE`，使同一动作可以重复触发。

### 3.3 配置与参数

`ApiConfig` 保存 `providerId`、`baseUrl`、`apiKey`、`model`、`temperature`、`topP`、
`maxTokens`（0 = 不限制）、`thinking`、`reasoningEffort`。

默认值是 DeepSeek 的 `deepseek-flash`（`providerId = "deepseek"`、`baseUrl = https://api.deepseek.com/v1`）：
首次安装没有存过任何设置时，填个 API Key 就能直接对话。`SettingsStore` 读不到偏好时落到同一组默认值。

`ApiConfig.resolvedFor(spec)` 是「配置值 → 实际发送值」的唯一收敛点：

- `temperature` / `topP`：模型不支持时为 `null`（不发送），支持时按精度 `snap`；
- `maxTokens`：≤0 时为 `null`，否则 `snap` 后取整；
- `thinking`：仅当模型是 `ThinkingMode` 开关型时保留，否则强制 `AUTO`；
- `reasoningEffort`：仅当模型是 `Effort` 型、档位受支持且不为 `OFF` 时保留，否则 `OFF`。

`ReasoningEffort` 的线值：`OFF → null`、`LOW → "low"`、`MEDIUM → "medium"`、`HIGH → "high"`。

---

## 4. 角色设定（`CatPersona.kt`）

`CatPersona` 是 system prompt 的唯一来源。字段：`name`、`traits: Set<CatTrait>`、
`speechStyle`、`flavor`、`notes`。

| 枚举 | 取值 |
| --- | --- |
| `CatTrait`（多选，≤3） | 温柔体贴 / 活泼调皮 / 高冷傲娇 / 黏人 / 毒舌吐槽 / 沉稳可靠 / 好奇爱问 / 慵懒 |
| `CatSpeechStyle`（单选） | 日常口语 / 简洁直接 / 软萌撒娇 / 文艺 / 元气满满 |
| `CatFlavor`（单选） | 像朋友 / 偶尔猫叫 / 猫味浓 |

默认值：名字「猫猫」，性格 `{温柔体贴, 活泼调皮}`，风格 `日常口语`，猫味 `偶尔猫叫`。

`systemPrompt()` 的拼装顺序：身份句 → 性格段（未选则不出现，按枚举声明顺序）→ 说话风格 →
猫的感觉（`flavor.prompt` + 随猫味变化的 `flavor.hint`）→ JSON 回复契约 → 安慰原则 → 补充设定（仅在非空时）。
`hint` 承担「带不带猫的动作」这条规则：「像朋友」只说「不要大量使用颜文字」、不推动作，
「偶尔猫叫」与「猫味浓」则明确建议在回复里描述猫的动作。
JSON 契约里 `emotion` 与 `animation` 的取值就是 `CatReply` 解析表的输入。

`welcome()` 随猫味变化：`HUMAN` 开场白不带「喵」，其余带。`displayName()` 在名字留空时回退到默认名，
所有界面位置都用它取名字。

持久化编码：性格用「枚举名逗号分隔、按声明顺序」写成字符串（`encodeTraits`）。
`parseTraits` 区分两种情况——`null` 表示从未存过、回退默认值；空字符串表示「一个都没选」；
认不出的名字直接丢弃。

---

## 5. 模型目录（`ModelCatalog.kt`）

### 5.1 数据结构

- `NumberParam(min, max, step, default, decimals)`：数值型参数区间。`sliderSteps` 给出滑杆档位；
  `snap` 收敛并取整；`format` 用于显示；`jsonNumber` 在 `Double` 上取整，避免 `0.8f`
  被写成 `0.800000011920929` 而被服务商拒绝。
- `ReasoningSpec`：`Unsupported`（无开关）、`AlwaysOn`（始终思考）、`Toggle(defaultOn)`（走 `thinking.type`）、
  `Effort(supported)`（走 `reasoning_effort`）。
- `ModelSpec`：`temperature` / `topP` / `maxTokens` 为 `null` 表示模型不接受该字段，既不渲染控件也不发送。
  `supportedParamNames` 与 `unsupportedSamplingParams` 由这三者派生，供设置页展示。
  `contextWindow` 默认按模型名解析。
- `ProviderSpec`：`baseUrl`、`keyHint`、`models`、`authRequired`、`modelsPath`（`null` = 无模型列表接口）、
  `chatPath`、`note`。`defaultModel` 取 `models` 首个。

### 5.2 内置服务商（`providers` 顺序即设置页顺序）

智谱 GLM → Z.AI（GLM 国际站）→ DeepSeek → OpenAI → Anthropic → Google Gemini → xAI →
通义千问 → Moonshot / Kimi → MiniMax → 豆包（火山方舟）→ 腾讯混元 → 百度文心（千帆）→ Mistral →
硅基流动 → OpenRouter → Ollama 本地 → 自定义（`CUSTOM_PROVIDER_ID = "custom"`），共 17 个预设 + 自定义。

除 Anthropic / Google 走各自官方的 OpenAI 兼容层外，其余都是原生的 OpenAI 兼容端点，所以
`chatPath` 与 `modelsPath` 保持默认。Meta 的 Llama 只以开放权重形式发布，没有官方托管 API，
同一个权重经 OpenRouter（`meta-llama/llama-4-maverick`）、硅基流动与 Ollama（`llama4:maverick`）
接入，三者的模型 ID 各不相同。

`providerIdForBaseUrl(url)` 由去掉尾部斜杠的 Base URL 反查预设；设置页在用户编辑地址时据此自动切换服务商，
匹配不到则落到 `custom`。

### 5.3 能力解析优先级（`resolve`）

1. **内置精确表** `MODEL_SPECS`：以 `(providerId, modelId)` 为键，由 `builtIn(...)` 构造。
   GLM 表同时注册给 `zhipu` 与 `zai`；`builtIn` 的 `temperatureMax = null` 或
   `ReasoningSpec.AlwaysOn` 都表示该模型不接受 `temperature` / `top_p`（GPT-5 系列与始终思考的模型）。
2. **名称启发式** `genericSpec`：按顺序匹配
   - `^(o[1-9](-|$)|gpt-5)` → `Effort(LOW/MEDIUM/HIGH)`；
   - `glm-[5-9]\.` → `Effort(LOW/MEDIUM/HIGH)`（GLM-5 起改用 `reasoning_effort`）；
   - `reasoner|reasoning|thinking|(^|[-_/])r1([-_/]|$)|z1` → `AlwaysOn`；
   - `glm-4\.[5-9]` → `Toggle(defaultOn = true)`；
   - 否则 `Unsupported`。
3. **通用兜底**：`AlwaysOn` 的模型不发送 `temperature` / `top_p`；其余给 `temperature`（GLM 前缀、
   `moonshot`、`anthropic` 上限 1.0，其他 2.0）、`top_p`、`max_tokens`（默认 8192，步长 512），
   并标注「没有该模型的内置参数表」。

### 5.4 上下文窗口

`defaultContextWindow(modelId)`：名字里 `数字k` → ×1024，`数字m` → ×1024×1024，否则 32768。
内置表里的显式值覆盖它，新表里：GLM-5.3 / MiniMax M3 / Gemini 3 / Llama 4 Maverick 为 1000000、
GLM-5.3-Flash 200000、GPT-5.x 400000、Grok 4 / Qwen3.6 / Kimi K3 / 豆包 256000、
Claude 4.x 200000、DeepSeek V4 / 混元 / 文心 / Mistral 128000。

除 GLM-5.3 与 MiniMax M3 的 1M 已核实外，其余新模型的窗口取同系列上一代的公开保守值；
官方调整时改 `ModelCatalog.builtIn` 对应调用处的 `window` 参数即可。没有内置条目的模型仍走 32768 兜底。

### 5.5 请求构建中的思考字段

| `ReasoningSpec` | 请求里出现什么 |
| --- | --- |
| `Toggle` + `ThinkingMode.ON` | `"thinking": {"type": "enabled"}` |
| `Toggle` + `ThinkingMode.OFF` | `"thinking": {"type": "disabled"}` |
| `Toggle` + `ThinkingMode.AUTO` | 不发送 |
| `Effort` + 非 OFF 档位 | `"reasoning_effort": "low" / "medium" / "high"` |
| `AlwaysOn` / `Unsupported` | 不发送任何思考字段 |

---

## 6. API 客户端（`ApiClient.kt`）

### 6.1 请求

- `chat(config, messages)`：`POST {Base}{chatPath}`，返回 `ChatCompletion(text, reasoning, totalTokens)`。
  记忆整理等需要完整 JSON 的场景用它（非流式）。
- `chatStream(config, messages, onDelta)`：同样的地址，额外带 `stream: true`，逐段回调增量文本，
  返回值和 `chat` 相同，调用方不必区分。协程被取消时会取消底层 HTTP 调用。
- `test(config)`：用**与聊天完全相同**的 `buildChatPayload` 发一条极短请求（`"只回复两个字：在呢"`），
  返回 `TestOutcome`：`latencyMillis`、`endpoint`、`model`、`reply`、`reasoningChars`、
  `sentParams`、`skippedParams`、`totalTokens`。只要测试通过，就说明地址 / Key / 模型 / 采样参数整套可用。
- `listModels(config)`：`GET {Base}{modelsPath}`，返回 `Available(models)` 或 `NotSupported`。

`buildChatPayload` 只写入 `resolvedFor(spec)` 里非空的值；`model` 为空时抛 `ApiException`。
鉴权头仅在 `apiKey` 非空时发送。

`content` 的取值由 `chatContent` 决定：没有图片时是纯字符串（与加入图片功能之前逐字节一致），
有图片时是 OpenAI 兼容的 content 数组——文字段 `{"type":"text"}`，每张图片一段
`{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`。
只有图片没有文字时不写空文本段，避免个别服务商拒绝空 text 块。这里没有任何服务商特有字段，
能否理解图片由所选模型决定。

超时：连接 20s、写 30s、读 90s。

### 6.2 响应解析与容错

`parseCompletion`：要求 `choices` 非空且 `choices[0].message` 存在；正文取 `content`，
思考内容取 `reasoning_content`，为空时回退 `reasoning`；两者都为空则报「模型没有返回任何内容」；
`content` 为空但 `reasoning` 非空时用思考内容兜底。`total_tokens` 仅在 > 0 时返回。

流式读取（`executeStream`）：逐行读取 SSE，只处理 `data:` 开头的行，`data: [DONE]` 结束；
解析不出 JSON 的行直接跳过，个别心跳行不会中断回复。增量正文取 `choices[0].delta.content`，
思考过程取 `delta.reasoning_content`（回退 `reasoning`），两者都累积后按非流式规则收敛。
若服务商忽略 `stream` 参数、整段返回普通 JSON（没有任何 `data:` 行），则用读到的原文走
`parseCompletion`，于是「不支持流式」的服务商也能正常工作。
请求被取消会让阻塞中的读取抛 `IOException`，这里在协程已取消时还原成 `CancellationException`，
避免把主动取消显示成网络错误。

`listModels` 把 404 / 405 视为「服务商不支持」（不是错误）；返回体不是 JSON 时报
「返回内容里没有 data 数组」；模型 id 去空排序。

### 6.3 错误映射

`httpFailure` 把状态码转成中文提示：

| 状态 | 提示 |
| --- | --- |
| 400 / 422 | 请求参数被拒绝，试试恢复默认参数或调小 max_tokens |
| 401 / 403 | API Key 无效，或没有该模型的权限 |
| 404 | 接口地址或模型名称不存在 |
| 429 | 触发限流或额度不足 |
| 5xx | 服务端暂时不可用，稍后再试 |

错误体按 `{"error":{"message"|"code"}}` → `{"message"}` → 原文前 200 字符依次兼容；
网络层 `IOException` 统一转成「网络请求失败…」。所有面向用户的错误都是 `ApiException`，
`message` 可直接展示。

---

## 7. 上下文装配（`ContextAssembler.kt`）

### 7.1 Token 估算

`TokenEstimator` 刻意不引入 tokenizer，只保证「不超预算」，因此宁可高估：

- 逐字符分类：CJK、假名、韩文、全角标点等宽字符算 1 token；其余按 4 字符 1 token（向上取整）。
- 每条消息再加 `MESSAGE_OVERHEAD = 4`。
- 每张图片加 `IMAGE_TOKENS = 1100` 的固定值。服务端按图片分辨率计算真实用量，
  这里只取一个偏高的固定值来保证不超预算。
- 真实用量由服务端 `usage` 校准（设置页「测试连接」展示）。

### 7.2 输入预算

```
requested = maxTokens > 0 ? snap(maxTokens) : 0
reserve   = min(max(requested, MIN_REPLY_RESERVE=1024), contextWindow / 2)
budget    = max(contextWindow - reserve - SAFETY_TOKENS=512, MIN_INPUT_BUDGET=1024)
```

输出预留最多占窗口一半，避免用户把 `max_tokens` 设得比窗口还大时算出负数预算。

### 7.3 装配算法

1. system = `systemPrompt.trimEnd()` + 非空的记忆块 + 非空的背景块，用空行连接。
   **顺序固定为稳定 → 易变**，让服务商的提示词缓存尽量复用前缀。
2. 过滤掉 `localError` 的消息。
3. `buildUnits` 按轮分组：遇到 `user` 或列表为空时开新轮，其余消息并入当前轮。
4. 计算每轮的 token 成本，图片按张数计入。
5. 从**最后一轮**开始向前装：最后一轮无条件保留（宁可让服务端报上下文超长，也不发只有 system 的请求），
   然后 `while (used + costs[index] <= budget - systemTokens)` 继续向前。
6. `dropWhile { role != user }` 丢掉领先的 assistant（例如开场白），保证请求不以 assistant 开头。
7. 输出 `[system] + kept`，kept 里的每条消息经 `toWire(imageUrl)` 把图片名解析成数据 URL
   （解析不到的图片跳过，删掉图片文件不会让历史消息无法发送），并回报 `keptMessages`、
   `droppedMessages`、`estimatedTokens`、`inputBudget`。

`droppedMessages` 是「可发送消息数 − 实际发送数」，开场白被丢弃也会计入。

---

## 8. 聊天记录（`ChatLogStore.kt`）

- 文件：`filesDir/chat/chat_log.jsonl`，一行一条消息。
- 每行是 `{"seq","role","content","at","error"?,"images"?}`；`images` 是本机图片文件名数组，
  纯文字消息不写这个字段。只有图片、没有文字的消息 `content` 为空字符串，仍然合法。
- 为什么不是 DataStore / Room：追加写入量与历史长度无关、崩溃最多坏最后一行、不引入 Room/KSP。
- 代价是查询能力弱，因此只提供两种读法：
  - `tail(limit)`：读末尾 N 条（界面）；
  - `readAfter(seq, limit)`：读 `seq` 之后的记录（记忆提取）。
- `append` / `clear` 均为 `Dispatchers.IO`。
- `tail` 从文件末尾按 8192 字节块倒着读，凑够 `limit + 1` 个换行即停，所以耗只与这 N 条的大小有关。
  必须先拼接字节再整体 UTF-8 解码——一个汉字 3 字节可能横跨两个块，逐块解码会在边界产生替换字符。
  若未读到文件开头，第一行是从中间截断的，丢弃。
- `readAfter` 顺序扫描，坏行跳过。
- 每行解析由 `StoredMessage.fromJson` 完成，`role` 或 `content` 为空 / JSON 非法都返回 `null`。

---

## 9. 结构化记忆

### 9.1 模型与分类（`CatMemory.kt`）

`MemoryCategory` 五类固定：主人 / 喜好 / 关系 / 经历 / 近况。`fromName` 同时接受枚举名（忽略大小写）
和中文标签，认不出时落到 `SITUATION`（近况），而不是丢弃。

`MemoryFact`：`category`、`key`、`value`、`updatedAt`、`sourceSeq`、`pinned`。
`key` 是同一件事的稳定标识，也是去重依据。

`CatMemory`：`facts` + `lastExtractedSeq`（提取游标）+ `lastExtractedAt`。

### 9.2 合并规则（`CatMemoryRules`）

上限：`MAX_FACTS = 60`、`MAX_KEY_CHARS = 12`、`MAX_VALUE_CHARS = 60`。

`merge(existing, incoming, forget, now)`：

1. 保留 `existing` 中「不在 `forget` 里」的条目，**`pinned` 的条目对 `forget` 免疫**。
2. 逐条处理 `incoming`：`sanitized` 清洗（key 取前 12 字符、value 换行转空格并取前 60 字符，
   空则丢弃整条）；按 `key` 找已有条目：
   - 不存在 → 追加；
   - 存在且 `category` 与 `value` 都没变 → **保留旧条目原文**，避免每次整理都刷新 `updatedAt`，
     否则老事实会一直排在最「新」的位置、逃过淘汰；
   - 存在但内容变化 → 用新值，继承旧的 `pinned`，`updatedAt = now`（不采信模型给的时间）。
3. `evict`：超出上限时，按 `updatedAt` 升序淘汰未固定的条目；若全部已固定则宁可超出上限，
   也不静默丢掉用户明确要保留的东西。

`upsert`（用户手动新增/修改）、`remove`、`togglePin` 是记忆页的直接操作，同样走 `sanitized` 与 `evict`。

### 9.3 渲染（`CatMemoryRender.block`）

空记忆返回空串。否则输出「【你记得的事】」，按 `MemoryCategory` 声明顺序分组，
每行 `- key：value`，末尾附一句「自然地用起来，不要逐条复述，也不要说「根据我的记忆」」。
这段文字会拼在 system prompt 的**人设之后、背景块之前**。

### 9.4 解析（`parseMemoryUpdate`）

依次兼容纯 JSON、``` 围栏、前后带废话的 JSON。任何失败返回 `null`，由调用方保留旧记忆——
绝不用半截结果覆盖。`facts` 里认不出的 category 落到近况，key/value 为空的条目丢弃；
`forget` 收成字符串集合。

### 9.5 持久化（`CatMemoryStore.kt`）

文件 `filesDir/chat/cat_memory.json`，结构：

```json
{
  "version": 1,
  "lastExtractedSeq": 42,
  "lastExtractedAt": 1700000000000,
  "facts": [
    {"category": "OWNER", "key": "名字", "value": "小明", "at": 1700000000000, "seq": 41, "pinned": true}
  ]
}
```

写入采用「先写 `*.tmp` 再 `renameTo`」：改名是原子的，进程在写一半时被杀不会留下半个 JSON；
个别文件系统上改名失败则退回直接写，至少不丢数据。读取时文件损坏当作空记忆，而不是让聊天起不来。

### 9.6 整理器（`MemoryExtractor.kt`）

复用用户当前配置的模型与参数，只换提示词。system prompt 规定：只记长期有效事实、
不记一次性寒暄与推测、key 为 2–6 个汉字且必须复用已有 key、value ≤40 字只写结论、
只有确认不再成立才放进 `forget`，并只输出 JSON。user prompt 依次给出当前时间、已有记忆、最近对话。

触发：`maybeExtractMemory` 在「距游标 ≥ `MEMORY_BATCH = 6` 条非错误消息」时调用；
记忆页的「现在整理」手动触发。每次最多读 `MEMORY_WINDOW = 40` 条。
成功后游标推进到 `recent.last().seq`；失败时游标**不前进**，错误写入 `memoryStatus.lastError`，
聊天不受影响，下一次自动重试同一批消息。

---

## 10. 时间、位置与背景块

### 10.1 时间格式化（`TimeFormat.kt`）

- `formatMessageTime`：当天 `HH:mm`、昨天 `昨天 HH:mm`、更早 `MM-dd HH:mm`。
  按本地时区计算「哪一天」用 `floorDiv(epochMillis + zoneOffset, 86400000)`，避免直接用时长除法在时区边界算错。
- `formatMoment`：`yyyy-MM-dd HH:mm EEEE`，中文 Locale，给模型看。
- `formatElapsed`：`刚刚` / `N 分钟` / `N 小时` / `N 天`，负数夹到 0。

### 10.2 定位契约（`Location.kt`）

`Place(city, region, country, fetchedAt)`，`display` 依次回退 city → region → country，
`isEmpty` 表示三者全空。

`LocationSource` 接口三个方法：`cached()`（不发网络请求，关键路径上只读它）、
`isFresh(now)`、`refresh(now)`（失败时保留旧结果，不抛异常）。接入系统定位只需另写一个实现。

### 10.3 IP 定位（`IpLocationSource.kt`）

按顺序尝试，第一个给出城市的胜出：

1. `http://ip-api.com/json/?lang=zh-CN&...`——唯一返回中文地名，但免费档只有 http；
2. `https://ipwho.is/`——https，免费额度宽松；
3. `https://ipapi.co/json/`——https，共享出口 IP 上常被限流，所以放最后。

TTL 30 分钟；超时连接 5s / 读 5s / 整体 8s。全部失败则保留上一次结果（过期的城市名也比「不知道」强）。
`parseIpPlace` 兼容两家的字段名（`regionName`/`region`、`country`/`country_name`），
拒绝 `status == "fail"`、`error == true` 和非 JSON 返回。

### 10.4 背景块（`AmbientContext.block`）

```
【此刻】
- 现在：<formatMoment>
- 距离上一条消息：<formatElapsed>        // 仅在知道时
- 主人大致在：<place.display>（按网络 IP 推测，只到城市，可能不准）
```

末尾附「这些只是背景，不要复述这几行，也不要假装知道具体的地址」。
时间与间隔总会带上；「在哪个城市」只有在定位开关打开且缓存有结果时才出现。

---

## 11. 设置持久化（`SettingsStore.kt`）

DataStore Preferences，文件名 `cat_settings`。键：

| 键 | 类型 | 默认 / 回退 |
| --- | --- | --- |
| `base_url` / `api_key` / `model` | String | `ApiConfig` 默认值 |
| `temperature` / `top_p` | Float | `ApiConfig` 默认值 |
| `max_tokens` | Int | `ApiConfig` 默认值 |
| `thinking` | String | 枚举名，缺失时 `AUTO` |
| `reasoning_effort` | String | 枚举名，缺失时 `OFF` |
| `provider_id` | String | 缺失时按 Base URL 反查，再落到 `custom` |
| `cat_name` / `cat_traits` / `cat_speech_style` / `cat_flavor` / `cat_notes` | String | `CatPersona` 默认值 |
| `location_enabled` | Boolean | `true` |

保存时统一 `trim`，Base URL 去尾部斜杠。读取用 `Flow`，ViewModel 在 `init` 里 collect 到各自 StateFlow。

---

## 12. UI 层

### 12.1 页面切换

`CatChatScreen` 用两个 `remember` 布尔量（`showSettings` / `showMemory`）切换页面，
命中时提前 `return`。项目没有引入 Navigation 组件；新增页面沿用这个模式或在引入导航时一并替换。

### 12.2 聊天页（`CatChatScreen.kt`）

- Header：名字、当前情绪文案、`服务商 · 模型`、记忆按钮（心形，带数量）、设置按钮。
- 消息列表：`LazyColumn`，key 用 `msg.seq`；时间只在「首条 / 说话人变化 / 间隔 ≥5 分钟」时显示。
  用户气泡靠右用主色，猫猫靠左带小猫头像；`localError` 用错误色。
  带图片的消息在气泡内用 `FlowRow` 每行两张显示缩略图，纯图片消息不渲染空文本；
  点缩略图打开全屏大图（`ImagePreviewDialog`，见 [20. 大图查看](#20-大图查看imageviewerkt)）。
- 等待回复时追加一个三点跳动的 `ThinkingBubble`；开始流式输出后由 `StreamingBubble` 取代，
  内容是 `streamingReply` 的当前值。
- 输入栏：左侧「+」弹出「从相册选择 / 拍照」，中间多行输入框（≤5 行，IME 动作是发送）。
  已选图片显示为可横向滚动的缩略图条，每张右上角可单独删除。
  发送按钮在「文字为空且没有图片」或 `busy` 时禁用。
- 相册走 `PickMultipleVisualMedia`（最多 9 张，老设备回退系统文件选择器）；拍照走 `TakePicture`，
  目标地址由 `ImageStore.newCameraTarget()` 通过 FileProvider 生成，返回后 `finishCamera` 收编或删除。
- 这两个 launcher 和其余 `remember` 一样必须在提前 `return` 之前调用，否则切到设置页再回来会错位。
- 自动滚动：有新消息时用 `animateScrollToItem`，流式期间改用 `scrollToItem`，避免每个增量重启动画。
- 猫咪画布当前不显示，代码中留了恢复位置的注释。

### 12.3 设置页（`SettingsScreen.kt`）

自上而下：猫猫设定 → 模型服务 → 连接 → 生成参数 → 位置 → 保存。
所有输入都是本地 `remember` 草稿，只有点「保存」才写回 ViewModel。

- 切换服务商：套用该服务商默认 Base URL 与默认模型，并把采样参数拉回默认值。
- 换模型：只做**收敛**——不支持的参数复位，超出范围的值拉回区间。
- 模型列表：填好地址和 Key 后延迟 700ms 自动拉取一次，也可手动「刷新」；
  服务商无 `/models` 或返回 404/405 时回退内置预设。
- 能力卡片：展示当前模型可调参数、上下文窗口和备注。
- 测试连接：用当前草稿参数发真实请求，成功展示耗时、端点、回复、思考字数、已发送/已跳过参数与 tokens。
- 生成参数滑杆的范围、档位、显示精度全部来自 `ModelSpec`。
- 「恢复当前模型默认参数」把草稿重置为该模型默认值。
- 「备份与恢复」：导出 / 导入 `.ikitty`，两个动作各自先过一次确认对话框（见 [19. 备份与恢复](#19-备份与恢复)）。
  文件选择用 `rememberLauncherForActivityResult` + SAF（`CreateDocument` / `OpenDocument`），
  应用不申请存储权限；解包、校验、落盘都在 ViewModel。
- 底部「软件更新」：检查 / 下载 / 安装三段状态驱动（见 [18. 软件更新](#18-软件更新)）。
  安装动作放在 UI 层，因为它需要 Activity 的 Context 启动系统界面；检查与下载在 ViewModel。

### 12.4 记忆页（`CatMemoryScreen.kt`）

顶部状态卡（整理中 / 上次整理时间 / 错误 / 「现在整理」/「清空记忆」），
按分类分组的记忆卡片（固定 / 修改 / 删除），底部是对话记录卡（条数 + 「清空聊天记录（记忆保留）」），
以及「上次请求的上下文」卡（带了多少条、省略多少、估算 tokens / 预算）。
新增与修改共用一个对话框。

### 12.5 猫咪渲染（`CatView.kt`）

`CatView(mood, animation)` 是自包含组件：造型（`drawBody` / `drawHead` / `drawTail` / 五官）、
表情（`expressionFor(mood)` 映射到 `CatExpression`）、动作状态机与待机行为都在内部完成，
外部只传当前状态。动画值在 `Canvas` 的 draw lambda 内读取，只触发重绘、不触发重组。

待机行为：仅当 `mood == IDLE` 且 `animation == NONE` 时，在 `repeatOnLifecycle(RESUMED)` 下
每 3–8 秒随机播放一次眨眼 / 左右看 / 摇尾 / 打哈欠；App 退到后台即停止调度。

`CatAvatar` 复用同一套头部画法，供消息列表的小头像使用。
要换成 Rive / Lottie，整体替换本文件即可，外部接口不变。

---

## 13. 扩展点

| 想做什么 | 改哪里 |
| --- | --- |
| 新增服务商 | `ModelCatalog.providers` 加一个 `ProviderSpec` |
| 新增/修正模型能力 | `ModelCatalog` 的内置表加 `ModelSpec`，或在 `genericSpec` 调整启发式 |
| 新增角色设定项 | `CatPersona` 加字段 + 对应枚举；`systemPrompt()` 与 `SettingsStore` 同步 |
| 新增记忆分类 | `MemoryCategory` 加枚举项（提示词、分组、渲染自动跟随） |
| 调整记忆上限/清洗 | `CatMemoryRules` 的常量与 `sanitized` |
| 换猫咪渲染 | 整体替换 `CatView.kt`，保持 `CatView(mood, animation)` / `CatAvatar(modifier)` 签名 |
| 接入系统定位 | 实现 `LocationSource`，在 ViewModel 里替换 `IpLocationSource` |
| 换聊天记录存储 | 替换 `ChatLogStore`（保持 `append` / `tail` / `readAfter` / `clear`） |
| 改图片压缩或存储位置 | 只改 `ImageStore` 的导入管线与常量 |
| 改图片在界面上的呈现 | 只改 `ChatImage`、`ImageViewer` 与 `CatChatScreen` 的气泡、附件条 |
| 改历史图片的携带策略 | 只改 `ContextAssembler` 的 `imageUrl` 解析器与图片成本计算 |
| 换更新来源 | 改 `UpdateModels.kt` 的 `RELEASE_API_URL` 与 `parseLatestRelease` |
| 改更新包的下载/校验/安装 | `UpdateClient`（下载）/ `ApkInstaller`（校验与安装）/ ViewModel 的 `UPDATE_DIR` |
| 改备份里装什么 | `BackupArchive` 的条目常量、`manifestJson` 与 `settingsToJson` / `settingsFromJson`；格式不兼容时把 `FORMAT_VERSION` +1 |
| 新增页面 | 在 `CatChatScreen` 加一个布尔状态分支，或引入 Navigation |

---

## 14. 测试策略

```bash
./gradlew testDebugUnitTest
```

101 个用例，全部是纯 JVM 测试（无需设备/模拟器）：

| 测试文件 | 用例 | 覆盖的契约 |
| --- | --- | --- |
| `ChatLogStoreTest` | 6 | 追加/读末尾往返、只返回最新、跨 8192 字节块的中文不损坏、`readAfter` 游标、坏行不影响其余、清空 |
| `CatMemoryStoreTest` | 2 | 记忆保存/加载往返、损坏文件读成空记忆 |
| `CatMemoryTest` | 11 | 合并只增不减、同 key 覆盖、未变化保留旧时间戳、`forget` 不动固定项、超限淘汰、重命名 key、超长裁剪、渲染分组、三种 JSON 形态解析、解析失败返回 null、未知分类回退 |
| `CatPersonaTest` | 8 | 默认 prompt 含名字/性格/JSON 契约、性格渲染顺序与可空、补充设定、`HUMAN` 无「喵」、只有非「像朋友」的猫味建议带猫的动作、空名回退、性格存储往返、枚举反查 |
| `StoredMessageTest` | 7 | 带图片消息 JSON 往返、纯图片消息合法、纯文字不写 `images`、无文字无图片被拒、空图片名被丢弃、`toWire` 解析并跳过缺失图片、纯文字 wire 不带图片 |
| `MultimodalPayloadTest` | 5 | 纯文本仍是字符串 content 且无 `stream`、图片转成 `image_url` content 数组、纯图片不写空 text 段、流式只加 `stream` 不改 content、发送参数仍受能力表约束 |
| `ContextAssemblerTest` | 13 | 以 system 开头且不以 assistant 开头、超预算整轮丢弃、最后一轮永远保留、本地错误不进请求、附加块按存在拼接、稳定块在易变块前、预算非负、中文比等长 ASCII 贵、模型名带窗口、背景块计入预算、每张图片固定开销、图片解析进 wire、图片挤占历史预算 |
| `ImageStoreTest` | 6 | 采样倍率落在上限内、按长边采样、采样后尺寸不超上限、数据 URL 的 MIME 兜底、EXIF 八个方向值的旋转/镜像映射、认不出的方向不转 |
| `ImageViewerTest` | 3 | 未放大时不接受拖动、放大后拖动被钳制在溢出范围内、钳制范围随倍数增长 |
| `LocationTest` | 10 | ip-api/ipapi.co/ipwho.is 三种返回解析、JSON null 不成字符串、失败与垃圾拒绝、`display` 回退、背景块含时间/间隔/城市且标注「可能不准」、缺信息时不输出 |
| `UpdateModelsTest` | 9 | release JSON 解析版本/说明/APK 地址/大小/发布时间、多 APK 时优先同名、无 APK 返回 null、预发布不算更新、非 JSON 返回 null、缺 tag 返回 null、版本比较新旧与相等、预发布更旧、`v` 前缀归一化 |
| `ModelCatalogTest` | 10 | 每个预设的默认模型都命中自己的内置能力表、预设清单覆盖全部厂商、新增预设都有 Base URL 且能反查、GLM-5.3 走 reasoning_effort 且 1M 窗口、`glm-5` 名称启发式、GPT-5.5 不发送采样参数、Meta Llama 经聚合平台与本地接入、旧模型仍能走通用兜底、默认模型是 deepseek-flash、deepseek-flash 可调思考深度 |
| `BackupArchiveTest` | 11 | 导出/导入往返还原消息、记忆、图片与设置、覆盖时删掉旧图片与旧记忆、非 zip 与缺清单被拒、格式版本过新被拒、越界图片条目被忽略、空备份清空本机历史、聊天记录损坏时拒绝覆盖、设置序列化全字段往返、缺字段回退默认值、记忆解析容忍垃圾、默认文件名带后缀 |

`testImplementation("org.json:json:20240303")` 是刻意的：单元测试跑在 JVM 上，
`android.jar` 里的 `org.json` 只是会抛异常的桩，补一份真实现才能测记忆解析这类纯逻辑。

未覆盖：Compose UI、真实网络请求、SSE 解析、图片的真实解码与压缩（依赖 `BitmapFactory`）、
`SettingsStore` 的 DataStore 读写、`IpLocationSource` 的实际 HTTP、
`UpdateClient` 的真实 GitHub 请求与下载、`ApkInstaller` 的签名校验和系统安装器跳转。
备份测试覆盖归档本身，而 SAF 文件选择与 `ContentResolver` 的读写同样需要设备验证。
这些需要在设备上做集成/端到端验证。

---

## 15. 安全与隐私边界

- **权限**：`android.permission.INTERNET` 与 `android.permission.REQUEST_INSTALL_PACKAGES`；
  后者只用于把官方 release 的更新包交给系统安装器。
- **明文流量**：`network_security_config.xml` 的 `base-config` 对所有域名放开，
  以便直连本地/局域网模型服务；收紧时改为按域名/地址的 `domain-config`。
- **数据落盘**：聊天记录、图片与记忆都是应用私有目录下的文件；API Key 明文存 DataStore，无额外加密。
- **备份文件**：`.ikitty` 里含明文的 API Key 与全部聊天内容，导出前必须提示用户；
  导入时只接受自己写出的格式，且解包路径做了越界检查（见 [19. 备份与恢复](#19-备份与恢复)）。
- **图片**：相册图片复制进 `filesDir/chat/images/`，拍照临时文件写在缓存目录并在返回后立即收编或删除；
  应用只拿自己的 `FileProvider` 授权，不申请存储或相机权限（相机由系统应用完成）。
- **数据外发**：聊天内容只发往用户配置的 Base URL；开启定位时出口 IP 会发给第三方定位服务；
  检查更新时只向 `api.github.com` 读取 release 元数据并下载 APK，不上报任何本机信息。
- **错误信息**：接口错误体最多截取前 200 字符回显，避免把整页网关 HTML 塞进界面。
- **上下文隔离**：`localError` 消息与开场白不会进入请求，`StoredMessage` 的元数据不会进请求体。

---

## 16. 已知技术债

1. 猫咪画布未接入聊天页（见 [README 当前形态](../README.md#当前形态只有聊天)）。
2. 服务商配置只有一套，切服务商互相覆盖。
3. 历史只载入最近 400 条，无向上分页。
4. 只存 UTC 毫秒时间戳，没记写入时的时区偏移。
5. token 数只有估算。
6. 定位仅城市级、依赖第三方 IP 服务。
7. API Key 明文存储。
8. 界面文案未做多语言资源。
9. 图片统一降采样到最长边 1280 并转 JPEG：画质有损、透明区域填白，单条消息上限 9 张。
10. 历史图片每轮都会重新编码并重发（`dataUrls` 只有内存缓存），图片多时请求体与内存压力明显。
11. 图片可以在应用内点开查看大图并缩放，但不能保存到相册或分享出去。
12. 更新包只校验长度、包名与签名，没有 release 提供的校验和；也没有后台自动检查更新。
13. 新一批内置模型的上下文窗口多为同系列上一代的保守值（只有 GLM-5.3 / MiniMax M3 的 1M 已核实），
    且只为 GPT-5.x、GLM-5.3 与 deepseek-flash 声明了推理控制方式；两者都只影响参数与预算，不影响能否请求。
14. 备份是明文 ZIP，没有密码或加密；导入只能整体覆盖，不能只挑其中几项恢复；
    也没有自动/定时备份，导出会带走 API Key。
15. 内存中只载入最近 400 条而备份是全量的：导入一份很长的备份后，界面依然只显示末尾 400 条。

## 17. 图片存储（`ImageStore.kt`）

选中或拍摄的图片**先复制进应用私有目录**再记录文件名，因为相册返回的 `content://` URI
只在本次进程内可读，不复制的话重启后记录里的图片会变成空白。

- 目录：`filesDir/chat/images/`；拍照临时文件在 `cacheDir/chat_camera/`。
- 导入：读边界 → 读 EXIF 方向 → 按 `sampleSizeFor` 采样解码 →
  缩放到最长边 `MAX_DIMENSION = 1280` → 按方向把像素转正 → 有透明通道先铺白底 →
  压缩成 JPEG（质量 85）写入 `img_<uuid>.jpg`。
  任一步失败返回 `null`，由调用方跳过这张图，而不是让发送整体失败。
  注意 `inJustDecodeBounds` 模式下 `decodeStream` 返回 `null` 是正常行为（尺寸只写进 Options），
  只有「流打不开」才代表失败。
- EXIF 方向：`BitmapFactory` **不会**按 `TAG_ORIENTATION` 摆正像素——竖拍照片的像素其实是横的，
  方向只写在标签里。重新编码成 JPEG 会把标签抹掉，所以必须在导入时按 `exifTransformFor`
  把像素转正，否则缩略图、查看大图与发给模型的原图都会躺倒。
  `exifTransformFor` 把标签 1–8 译成「旋转 N 度 + 可选左右镜像」，读不出或认不出的取值一律不转。
- 数据 URL：`dataUrl` / `dataUrls` 把文件编码成 `data:image/jpeg;base64,...`，
  结果按文件名缓存在一个上限 12 条的 LRU 里（文件不会被改写，缓存永远有效）。
- 相机：`newCameraTarget()` 用 `FileProvider`（authority `${applicationId}.fileprovider`，
  路径配置 `res/xml/file_paths.xml`）生成可写 URI；`commitCamera` 成功后走同一条导入管线，
  取消则删除临时文件。
- 缩略图：`decodeSampledBitmap` 按最长边 512 采样，供 `ChatImage` 显示，避免整图进内存；
  查看大图时同一条解码路径改按 2048 采样（见 [20. 大图查看](#20-大图查看imageviewerkt)）。

---

## 18. 软件更新

### 18.1 数据来源与版本比较（`UpdateModels.kt`）

- 来源固定为 `RELEASE_API_URL = https://api.github.com/repos/Lixuannan/iKitty/releases/latest`。
- `parseLatestRelease(body)` 读取 `tag_name` / `body` / `published_at` 与 `assets`：
  - `draft` / `prerelease` 为真直接返回 `null`；
  - `normalizeVersion` 去掉 `v` 前缀与首尾空白；
  - 优先挑文件名包含版本号的 `.apk`，其次任意 `.apk`，都没有则返回 `null`；
  - `published_at` 不是合法 ISO-8601 时只丢时间，不影响其他字段。
- `compareVersions(a, b)` 只比较数字段；数字段相同时，带预发布后缀的一方更旧
  （`0.2.0-beta.1 < 0.2.0`）。认不出的段落按 0 处理，不因为版本号写法奇怪就报错。
- `UpdateStatus` 是界面状态机：`Idle` / `Checking` / `UpToDate` / `Available` / `Downloading` /
  `Ready` / `Failed`；`Failed.info` 非空表示「已经拿到版本信息但下载失败」，界面据此给「重试下载」。

### 18.2 下载（`UpdateClient.kt`）

- `fetchLatest()`：`GET releases/latest`，带 `Accept: application/vnd.github+json`。
  返回 `null` 表示没有可下载的 APK；HTTP / 网络失败抛 `ApiException`
  （403 / 429 提示限流，404 提示没有 release）。
- `download(info, destination, onProgress)`：先写 `<name>.part` 再 `renameTo`，改名失败退回复制；
  任何异常（含协程取消）都删掉 `.part`，绝不留下一个看起来完整的坏包。
  每 64KB 回调一次进度，并在循环里 `ensureActive()` 响应取消。
  下载完成后比对实际字节数与 release 声明的 `size`，对不上报「下载不完整」。
- 目标目录由 ViewModel 决定：`cacheDir/updates/`；下载开始前清掉其他版本的安装包。

### 18.3 校验与安装（`ApkInstaller.kt`）

- `check(context, apk)` 用 `getPackageArchiveInfo` 读下载包的包名与签名，与已安装应用比对，
  返回 `COMPATIBLE` / `PACKAGE_MISMATCH` / `SIGNATURE_MISMATCH` / `UNREADABLE`。
- 两边签名都读不出时返回 `UNREADABLE`，而不是当作一致——那等于跳过校验。
- `install(context, apk)` 用 `FileProvider`（authority `${applicationId}.fileprovider`，
  路径来自 `res/xml/file_paths.xml` 的 `updates`）拿到只授权给安装器的 `content://` URI，
  再用 `ACTION_VIEW` + `application/vnd.android.package-archive` 打开系统安装器。
- `canInstall` 走 `canRequestPackageInstalls()`；未授权时 UI 跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`。

### 18.4 为什么更新不会清空聊天记录

- 覆盖安装只替换代码，`filesDir` / `cacheDir` / DataStore 都留在原处：聊天记录
  （`chat/chat_log.jsonl`）、图片、记忆（`cat_memory.json`）与设置全部保留；
- 应用**从不**执行「先卸载再安装」，也不使用任何会删除应用数据的安装 API；
- 下载只写 `cacheDir/updates/`，FileProvider 只授权这一个文件，更新流程不会触碰 `filesDir/chat/`；
- 签名不一致时系统本来就会拒绝覆盖安装。代码提前校验、删包并说明原因，
  是为了避免用户被「装不上」误导去卸载重装——那才会真的清空聊天记录。

### 18.5 界面（`SettingsScreen.UpdateSection`）

`Idle` 显示当前版本与「检查更新」；`Available` 显示新版本号与 release 说明（最多 8 行）与「下载更新」；
`Downloading` 显示进度条与已下载/总量；`Ready` 显示「安装更新」；
`Failed` 按 `info` 是否为空给出「重试下载」或「重新检查」。
安装动作放在 UI 层，因为它需要 Activity 的 Context 启动系统界面；检查与下载留在 ViewModel。

---

## 19. 备份与恢复

### 19.1 容器格式（`BackupArchive.kt`）

导出文件后缀 `.ikitty`，实际是一个普通 ZIP，条目如下：

| 条目 | 内容 |
| --- | --- |
| `manifest.json` | `format = "ikitty-backup"`、`version`、`appVersion`、`exportedAt` 与三类条目数量 |
| `chat/chat_log.jsonl` | 与 [8. 聊天记录](#8-聊天记录chatlogstore) 落盘格式完全一致的 JSONL |
| `chat/cat_memory.json` | 与 [9.5 持久化](#95-持久化catmemorystorekt) 一致的记忆 JSON |
| `chat/images/<名字>` | 消息引用到的原始 JPEG，文件名不变 |
| `settings.json` | 模型配置、角色设定、位置开关（**含 API Key**） |

选 ZIP 而不是单个大 JSON 的理由：聊天记录本身就是 JSONL，可以整段搬进搬出；图片按原始
JPEG 存，不必 base64（base64 会平白多出三分之一体积，还要全部读进内存）。
归档里的 JSON 都是明文，用户用任何解压工具都能检查自己备份了什么。

`MIME` 用 `application/octet-stream`：`.ikitty` 没有注册类型，声明成 `application/zip`
会让部分文件选择器把文件名改回 `.zip`。

### 19.2 导出

1. 逐行扫描本机 JSONL，统计能解析的消息条数与引用到的图片名（坏行按 [8 节](#8-聊天记录chatlogstore) 的约定跳过）；
2. 只把**真实存在**的图片写进归档，历史里指向已删除图片的名字不会变成空条目；
3. 设置由 ViewModel 传入，取的是**已保存**的值——设置页里改了但没点「保存」的草稿不会被带走；
4. 返回 `BackupSummary`，界面据此告诉用户导出了多少条消息、几张图片、几条记忆。

### 19.3 导入：先全部解包校验，再动本机数据

导入是**整体覆盖**语义，所以顺序刻意做成两段：

1. `stage(input)`：把归档解到 `cacheDir/backup_staging/`，期间做全部校验——
   清单存在且 `format` 正确、`version` 不超过本机支持的 `FORMAT_VERSION`、
   `settings.json` 存在、聊天记录里非空行只要有就得至少解析出一条（否则视为损坏）；
   任何一步失败都清掉暂存目录并抛 `BackupException`，**本机数据一个字节都不会被碰**；
2. `commit(contents)`：把暂存的记录与记忆用「临时文件 + 改名」替换，
   清空 `filesDir/chat/images/` 后按原名放回归档里的图片，最后删掉暂存目录。

防御措施：

- 条目名只接受 `chat/images/` 下的裸文件名，带 `/`、`\`、`..` 或前缀 `.` 的一律忽略，
  避免 zip slip 把文件写到归档目录之外；
- 条目数上限 20000、解压总量上限 2 GiB、单张图片 32 MiB、JSON 条目 1 MiB，
  畸形文件在写满缓存盘之前就会失败；
- 一个条目坏掉就整包拒绝，不做"能读多少算多少"的部分导入——半个备份比没有备份更危险。

### 19.4 导入之后的状态刷新

`commit` 只处理文件；设置要写回 DataStore，界面状态要重读：

- ViewModel 依次 `store.save(config)` / `save(persona)` / `saveLocationEnabled`；
- `reloadFromDisk()` 重读 DataStore、记忆文件与聊天记录末尾 `LOAD_LIMIT` 条，
  重置 `nextSeq`、清掉 `contextPlan`，并调用 `images.invalidateCache()`——
  归档里的图片按原名覆盖，缓存里可能还留着旧编码；
- 导入的记录为空时补一条开场白，否则导入完会是一片空白。

### 19.5 界面（`SettingsScreen.BackupSection`）

- 「导出备份」→ 确认框（说明文件含 API Key）→ SAF `CreateDocument`，默认文件名
  `iKitty-yyyyMMdd-HHmm.ikitty`（`defaultBackupFileName`）；
- 「导入备份」→ 确认框（说明整体覆盖且不可撤销）→ SAF `OpenDocument`（`*/*`，
  `.ikitty` 没有注册 MIME）；
- `BackupStatus` 四态（`Idle` / `Working` / `Done` / `Failed`）驱动进度与结果文案；
  有动作在跑或正在等模型回复时不允许再发起导入。

---

## 20. 大图查看（`ImageViewer.kt`）

聊天记录里的缩略图点击后由 `CatChatScreen` 的 `previewImage` 状态打开
`ImagePreviewDialog(name, onDismiss)`：一个全屏 `Dialog`（`usePlatformDefaultWidth = false`），
黑底、`ContentScale.Fit`，点右上角或按返回键关闭（返回键由 `Dialog` 的 `onDismissRequest` 接管）。

- 解码：`decodeSampledBitmap(file, PREVIEW_PIXELS = 2048)`，比列表缩略图的 512 更清楚，
  又不至于把 1280px 的原图整张原样读进内存；解码在 IO 线程，完成前显示转圈。
- 三种状态：`Loading` / `Missing`（文件已被删除或解不出来，显示「这张图片已经找不到了」）/
  `Ready`。区分 `Missing` 是为了不让占位圈永远转下去。
- 手势：`rememberTransformableState` + `transformable` 做双指缩放（1–5 倍）与拖动；
  未放大时忽略平移，放大后由纯函数 `clampPan` 把位移钳制在溢出范围内，避免把图片拖出屏幕找不回来。
- 只有消息气泡里的缩略图传 `onClick`；输入栏待发送的附件条不打开大图。
- 兼容性：EXIF 修复只作用于**新导入**的图片。修复前导入的竖拍照片在导入时就已经丢掉了方向标签，
  文件本身就是横的，显示阶段无法还原；备份里的图片按原样搬运，同样是修复前就躺倒的仍旧躺倒。


