# iKitty 详细设计文档

> [English](DOC_EN.md) · [返回 README](../README.md)

本文是 iKitty 的架构地图与参考手册：模块契约、数据格式、关键算法、扩展点和测试策略。
面向要修改或扩展代码的人。使用方式、配置步骤和隐私说明在 [README](../README.md) 中。

- 版本：0.1.0 · 包名：`com.example.aicat` · 源码根目录：`app/src/main/java/com/example/aicat/`
- 技术栈：Kotlin 2.0.21、Jetpack Compose（Material3）、OkHttp 4.12.0、DataStore Preferences 1.1.1
- 构建：AGP 8.7.3、Gradle 9.7.1、Java 17 字节码目标、minSdk 26 / targetSdk 35

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
    CatChatViewModel (AndroidViewModel)   ← 唯一的状态持有者与编排者
        ├── ApiClient            → 模型服务（HTTP）
        ├── SettingsStore        → DataStore
        ├── ChatLogStore         → JSONL 追加
        ├── CatMemoryStore       → 记忆 JSON
        ├── MemoryExtractor      → 记忆整理请求
        └── LocationSource ─ IpLocationSource → IP 城市定位
```

依赖方向始终由 UI 指向 ViewModel，再由 ViewModel 指向存储与网络。
反向只通过 `StateFlow`：UI 不直接读写任何文件或网络。

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

---

## 2. 一次消息的完整生命周期

1. 用户在输入框打字。`onInputChanged` 在非空时把情绪切到 `LISTENING`，清空后回到 `IDLE`；
   `busy` 期间不覆盖当前情绪。
2. `send(text)`：`trim` 后生成 `StoredMessage`，`seq = nextSeq++`，`role = user`，
   追加到 `messages` 并以 JSONL 落盘；同时若定位开关打开且缓存过期，后台触发一次刷新。
3. `busy = true`，情绪切到 `THINKING`。
4. `ContextAssembler.assemble(...)` 装配请求：system = 人设 + 记忆块 + 「此刻」背景块，
   历史按 token 预算裁剪；结果写入 `contextPlan` 供记忆页展示。
5. `ApiClient.chat(config, plan.messages)` 请求 `POST {Base}/chat/completions`。
6. `parseCatReply(raw.text)` 解析回复：得到文本、可选情绪、可选动作。解析失败一律退回纯文本。
7. 追加 assistant 消息并落盘；情绪按 `autoReset = true` 设置，4 秒后自动回到 `IDLE`；
   动作按模型指定，未指定时由 `CatAnimation.defaultFor(mood)` 兜底。
8. 出错时追加一条 `localError = true` 的 assistant 消息（显示为红色，但**不进请求、不进记忆**），
   情绪切 `SAD` 并播放 `SHAKE`。
9. `finally` 中 `busy = false`。
10. `maybeExtractMemory()`：若距提取游标已有 ≥6 条非错误消息，触发一次后台记忆整理。

清空聊天记录会把 `nextSeq` 归 1、清空 `contextPlan`，并把记忆的 `lastExtractedSeq` 一并归零，
否则新 `seq` 会被旧游标当成「早就整理过」。空会话会补一条开场白。

---

## 3. 领域模型

### 3.1 消息

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `StoredMessage` | `seq` / `role` / `content` / `createdAt` / `localError` | 落盘消息；`seq` 既是排序依据也是稳定 id |
| `ChatMessage` | `role` / `content` | 发给服务商的形式，元数据不外泄 |

`StoredMessage.toWire()` 是唯一的转换点，`localError`、`seq`、`createdAt` 都不会进请求体。
`role` 常量是 `"user"` / `"assistant"`。

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
猫的感觉（含「不要大量使用颜文字」）→ JSON 回复契约 → 安慰原则 → 补充设定（仅在非空时）。
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

智谱 GLM → DeepSeek → Z.AI（GLM 国际站）→ OpenAI → Moonshot / Kimi → 通义千问 →
硅基流动 → OpenRouter → Ollama 本地 → 自定义（`CUSTOM_PROVIDER_ID = "custom"`）。

`providerIdForBaseUrl(url)` 由去掉尾部斜杠的 Base URL 反查预设；设置页在用户编辑地址时据此自动切换服务商，
匹配不到则落到 `custom`。

### 5.3 能力解析优先级（`resolve`）

1. **内置精确表** `MODEL_SPECS`：以 `(providerId, modelId)` 为键。GLM 表同时注册给 `zhipu` 与 `zai`。
2. **名称启发式** `genericSpec`：按顺序匹配
   - `^(o[1-9](-|$)|gpt-5)` → `Effort(LOW/MEDIUM/HIGH)`；
   - `reasoner|reasoning|thinking|(^|[-_/])r1([-_/]|$)|z1` → `AlwaysOn`；
   - `glm-4\.[5-9]` → `Toggle(defaultOn = true)`；
   - 否则 `Unsupported`。
3. **通用兜底**：`AlwaysOn` 的模型不发送 `temperature` / `top_p`；其余给 `temperature`（GLM 前缀或
   `moonshot` 服务商上限 1.0，其他 2.0）、`top_p`、`max_tokens`（默认 8192，步长 512），
   并标注「没有该模型的内置参数表」。

### 5.4 上下文窗口

`defaultContextWindow(modelId)`：名字里 `数字k` → ×1024，`数字m` → ×1024×1024，否则 32768。
内置表里的显式值覆盖它：GLM 全系 128000（`glm-4-long` 为 1000000）、
`deepseek-chat` / `deepseek-reasoner` 64000、`gpt-4o*` 128000、`gpt-4.1*` 1000000、`o4-mini` 200000。

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
- `test(config)`：用**与聊天完全相同**的 `buildPayload` 发一条极短请求（`"只回复两个字：在呢"`），
  返回 `TestOutcome`：`latencyMillis`、`endpoint`、`model`、`reply`、`reasoningChars`、
  `sentParams`、`skippedParams`、`totalTokens`。只要测试通过，就说明地址 / Key / 模型 / 采样参数整套可用。
- `listModels(config)`：`GET {Base}{modelsPath}`，返回 `Available(models)` 或 `NotSupported`。

`buildPayload` 只写入 `resolvedFor(spec)` 里非空的值；`model` 为空时抛 `ApiException`。
鉴权头仅在 `apiKey` 非空时发送。

超时：连接 20s、写 30s、读 90s。

### 6.2 响应解析与容错

`parseCompletion`：要求 `choices` 非空且 `choices[0].message` 存在；正文取 `content`，
思考内容取 `reasoning_content`，为空时回退 `reasoning`；两者都为空则报「模型没有返回任何内容」；
`content` 为空但 `reasoning` 非空时用思考内容兜底。`total_tokens` 仅在 > 0 时返回。

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
4. 计算每轮的 token 成本。
5. 从**最后一轮**开始向前装：最后一轮无条件保留（宁可让服务端报上下文超长，也不发只有 system 的请求），
   然后 `while (used + costs[index] <= budget - systemTokens)` 继续向前。
6. `dropWhile { role != user }` 丢掉领先的 assistant（例如开场白），保证请求不以 assistant 开头。
7. 输出 `[system] + kept`，并回报 `keptMessages`、`droppedMessages`、`estimatedTokens`、`inputBudget`。

`droppedMessages` 是「可发送消息数 − 实际发送数」，开场白被丢弃也会计入。

---

## 8. 聊天记录（`ChatLogStore.kt`）

- 文件：`filesDir/chat/chat_log.jsonl`，一行一条消息。
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
- 等待回复时追加一个三点跳动的 `ThinkingBubble`。
- 输入栏：多行（≤5 行），IME 动作是发送；发送按钮在输入为空或 `busy` 时禁用。
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
| 新增页面 | 在 `CatChatScreen` 加一个布尔状态分支，或引入 Navigation |

---

## 14. 测试策略

```bash
./gradlew testDebugUnitTest
```

46 个用例，全部是纯 JVM 测试（无需设备/模拟器）：

| 测试文件 | 用例 | 覆盖的契约 |
| --- | --- | --- |
| `ChatLogStoreTest` | 6 | 追加/读末尾往返、只返回最新、跨 8192 字节块的中文不损坏、`readAfter` 游标、坏行不影响其余、清空 |
| `CatMemoryStoreTest` | 2 | 记忆保存/加载往返、损坏文件读成空记忆 |
| `CatMemoryTest` | 11 | 合并只增不减、同 key 覆盖、未变化保留旧时间戳、`forget` 不动固定项、超限淘汰、重命名 key、超长裁剪、渲染分组、三种 JSON 形态解析、解析失败返回 null、未知分类回退 |
| `CatPersonaTest` | 7 | 默认 prompt 含名字/性格/JSON 契约、性格渲染顺序与可空、补充设定、`HUMAN` 无「喵」、空名回退、性格存储往返、枚举反查 |
| `ContextAssemblerTest` | 10 | 以 system 开头且不以 assistant 开头、超预算整轮丢弃、最后一轮永远保留、本地错误不进请求、附加块按存在拼接、稳定块在易变块前、预算非负、中文比等长 ASCII 贵、模型名带窗口、背景块计入预算 |
| `LocationTest` | 10 | ip-api/ipapi.co/ipwho.is 三种返回解析、JSON null 不成字符串、失败与垃圾拒绝、`display` 回退、背景块含时间/间隔/城市且标注「可能不准」、缺信息时不输出 |

`testImplementation("org.json:json:20240303")` 是刻意的：单元测试跑在 JVM 上，
`android.jar` 里的 `org.json` 只是会抛异常的桩，补一份真实现才能测记忆解析这类纯逻辑。

未覆盖：Compose UI、真实网络请求、`SettingsStore` 的 DataStore 读写、`IpLocationSource` 的实际 HTTP。
这些需要在设备上做集成/端到端验证。

---

## 15. 安全与隐私边界

- **权限**：仅 `android.permission.INTERNET`。
- **明文流量**：`network_security_config.xml` 的 `base-config` 对所有域名放开，
  以便直连本地/局域网模型服务；收紧时改为按域名/地址的 `domain-config`。
- **数据落盘**：聊天记录与记忆都是应用私有目录下的明文文件；API Key 明文存 DataStore，无额外加密。
- **数据外发**：聊天内容只发往用户配置的 Base URL；开启定位时出口 IP 会发给第三方定位服务。
- **错误信息**：接口错误体最多截取前 200 字符回显，避免把整页网关 HTML 塞进界面。
- **上下文隔离**：`localError` 消息与开场白不会进入请求，`StoredMessage` 的元数据不会进请求体。

---

## 16. 已知技术债

1. 猫咪画布未接入聊天页（见 [README 当前形态](../README.md#当前形态只有聊天)）。
2. 非流式请求，长回复需完整等待。
3. 服务商配置只有一套，切服务商互相覆盖。
4. 历史只载入最近 400 条，无向上分页。
5. 只存 UTC 毫秒时间戳，没记写入时的时区偏移。
6. token 数只有估算。
7. 定位仅城市级、依赖第三方 IP 服务。
8. API Key 明文存储。
9. 界面文案未做多语言资源。
