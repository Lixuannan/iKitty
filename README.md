# iKitty 🐱

> 一个不需要后端、直连 OpenAI 兼容接口的 Android AI 猫咪聊天应用。
>
> [English](README_EN.md) · [详细设计文档](docs/DOC.md) · [角色素材规范](design/cat_v1/README.md)

iKitty 用 Jetpack Compose 写了一个极简聊天界面，把「角色设定 + 结构化长期记忆 + token 预算上下文」
拼成 system prompt 直接发给任意 OpenAI 兼容服务。聊天记录、记忆和设置全部存在本机，
除了你自己配置的模型服务和可选的 IP 定位，不经过任何第三方服务器。

- 应用名：**iKitty** · 版本：**0.1.0** · 包名：`com.example.aicat`
- 仓库：<https://github.com/Lixuannan/iKitty>

---

## 功能特性

- **任意 OpenAI 兼容服务**：内置智谱 GLM、DeepSeek、Z.AI、OpenAI、Moonshot/Kimi、通义千问、
  硅基流动、OpenRouter、Ollama 本地共 9 个预设，外加自定义 Base URL。
- **按模型能力动态出参数**：不同服务商、不同模型可调的参数和取值范围不同，设置页只渲染该模型真正支持的
  控件，请求体也只发送它真正接受的字段（详见[模型能力表](#模型能力表)）。
- **测试连接即真实请求**：用与聊天完全相同的参数发一条极短消息，回报耗时、端点、实际发送/自动跳过的参数和 tokens。
- **猫猫角色设定**：名字、性格（多选）、说话风格、猫味浓度、补充设定，拼装成 system prompt 并持久化。
- **7 种情绪 + 6 种动作**：待机 / 在听 / 思考 / 开心 / 难过 / 兴奋 / 困倦，配眨眼、左右看、摇尾、弹跳、甩头、打哈欠；
  模型可用 JSON 直接指定，也可退化为纯文本聊天。
- **本地聊天记录**：JSONL 追加写，重启不丢；读取末尾 N 条与历史长度无关。
- **结构化长期记忆**：定期把对话里的长期事实整理成「分类 + key + value」，注入 system prompt；只增不减，可查看可编辑。
- **token 预算上下文**：按窗口大小从最近往前装，绝不切开一问一答，超长对话自动省略最老的部分。
- **IP 城市定位**：让猫猫知道「大概在哪个城市」，零权限、无弹窗，设置里可关闭。
- **不需要后端**。

## 当前形态：只有聊天

聊天页目前不显示猫咪画布，只有标题、消息气泡和输入框；消息左侧的小猫头像仍在。
`CatView.kt` 里的完整猫咪组件、情绪与动作解析都还在，恢复显示只需在 `CatChatScreen`
里把 `CatView(mood, animation)` 放回 Header 下方（代码里留了位置注释）。

角色素材（分层 PNG、锚点、驱动建议）见 [`design/cat_v1/README.md`](design/cat_v1/README.md)。

## 快速开始

### 环境要求

| 项 | 要求 |
| --- | --- |
| Android | 8.0 及以上（minSdk 26），targetSdk 35 |
| Android Studio | Ladybug 或更新（需支持 AGP 8.7.3 / Kotlin 2.0.21） |
| JDK | 17 及以上（源码与字节码目标为 Java 17） |
| 设备 | 模拟器或真机均可；真机需要能访问你填写的模型服务地址 |

### 构建与运行

```bash
git clone https://github.com/Lixuannan/iKitty.git
cd iKitty

./gradlew assembleDebug        # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # 安装到已连接的设备 / 模拟器
./gradlew testDebugUnitTest    # 运行单元测试（纯 JVM，不需要设备）
```

也可以直接用 Android Studio 打开仓库根目录，首次 Gradle 同步后运行。

### 首次配置

1. 打开 App，点右上角齿轮进入设置；
2. 选择服务商（或直接填自定义 Base URL），填 API Key，选择或输入模型名；
3. 点「测试连接」确认整条链路可用；
4. 点「保存」，回到聊天页开始对话。

## 配置模型服务

设置页从上到下依次是：猫猫设定、模型服务、连接、生成参数、位置、保存。

- **Base URL** 填 OpenAI 兼容服务地址（例如 `https://example.com/v1`）。
  程序请求 `POST {Base URL}/chat/completions`；模型列表走 `GET {Base URL}/models`，
  服务商不支持该接口时自动回退到内置预设，不算错误。
- **API Key** 以 `Authorization: Bearer <key>` 发送；Key 留空时不发送该请求头
  （Ollama 等本地服务因此可以免 Key 使用）。
- **模型名**可从服务商 `/models` 拉取的列表中选择，也可以手动输入任意模型名。
  手填的模型走名称启发式判断能力（见[模型能力表](#模型能力表)）。
- **本地 / 局域网服务**（Ollama、LM Studio、vLLM 等）常用 `http://`，已放行明文流量；
  Android 模拟器访问宿主机用 `10.0.2.2`，真机请改成电脑的局域网 IP。

> ⚠️ 明文流量当前对所有域名放行（`network_security_config.xml` 的 `base-config`），
> 以便直连本地模型服务；如需收紧，请改为按域名/地址的 `domain-config`。

## 猫猫设定

设置页顶部的「猫猫设定」决定 system prompt，全部字段都会持久化：

| 设定 | 作用 |
| --- | --- |
| 名字 | 用在标题、开场白和 prompt 里，留空回退到「猫猫」 |
| 性格 | 多选（最多 3 个），每项对应一句写进 prompt 的描述 |
| 说话风格 | 单选：日常口语 / 简洁直接 / 软萌撒娇 / 文艺 / 元气满满 |
| 猫味 | 单选：像朋友 / 偶尔猫叫 / 猫味浓，控制「喵」和猫动作的浓度 |
| 补充设定 | 自由文本，附在 prompt 末尾，可写称呼、背景、禁忌 |

拼装规则集中在 `CatPersona.systemPrompt()`，新增一项设定只要改这一个文件；
模型能力表（`ModelCatalog`）和它互不影响。

system prompt 还会要求模型在合适时用一个 JSON 回答，从而驱动表情与动作：

```json
{"reply": "想说的话", "emotion": "neutral|happy|sad|excited|sleepy", "animation": "none|blink|look_around|tail_wag|bounce|shake|yawn"}
```

解析器同时兼容普通 JSON、``` 代码块包裹的 JSON 和纯文本；解析不出来就当作普通消息，绝不报错。

## 聊天记录与上下文

聊天记录落在 `filesDir/chat/chat_log.jsonl`：一条消息一行 JSON，追加写。
用文件而不是 DataStore / 数据库，是因为追加一条消息的写入量和历史长度无关，
崩溃也最多坏掉最后一行；代价是查询能力弱，所以只提供聊天真正需要的两种读法——
读末尾 N 条（界面）和读某个序号之后的记录（记忆提取）。读末尾是从文件末尾按块倒着读的，
历史有几万条也不会变慢。

每条消息存了 `seq` 和时间戳。排序用 `seq` 而不是墙钟，因为用户改系统时间或 NTP 校正都会让时间戳倒退。

**上下文按 token 预算装，而不是按条数截断**（`ContextAssembler`）：

- 窗口大小来自 `ModelCatalog` 的 `contextWindow`，名字里带 `8k` / `128k` / `1m` 的模型会自动识别，
  其余走保守默认值（32K），内置能力表的模型用显式值覆盖；
- 扣掉输出预留和估算余量之后才是输入预算，估算器宁可高估；
- 从最近往前按**轮**装，绝不切开一问一答，请求也不会以 assistant 开头（开场白因此不会进请求），最近一轮永远保留；
- 被省略的老消息仍然在本机，只是不再发出去，记忆页会显示上次带了多少条。

估算的 token 数是粗估值，准确用量看设置页「测试连接」返回的 tokens。

## 长期记忆

`MemoryExtractor` 每攒够 6 条新消息就整理一次（记忆页也能手动触发），把长期事实抽取成
`分类 + key + value` 存进 `chat/cat_memory.json`，之后的请求会把它渲染成一段文字拼在 system prompt 后面。

- 分类固定五类：主人 / 喜好 / 关系 / 经历 / 近况；
- 合并规则**只增不减**：模型没有提到的旧条目一律保留，删除只能靠它明确列进 `forget`，或者用户手动删；
- 条目上限 60 条，超出时优先淘汰最久没更新且没有被固定的条目；
- 整理失败不会影响聊天，提取游标也不前进，所以下一次会自动重试同一批消息。

聊天页右上角的心形按钮打开记忆页：查看、新增、修改、删除、固定每条记忆，也能清空记忆或清空聊天记录。
两者互不影响——清空聊天记录保留记忆，清空记忆保留提取游标，免得被下一次整理重新学回来。

> 提示词、合并规则、上限等细节见[详细设计文档](docs/DOC.md#9-结构化记忆)。

## 时间与位置

每条消息都存了时间戳，聊天流里按「当天只显示时分 / 昨天 / 更早带日期」展示。
每次请求还会注入一小块「此刻」背景（`AmbientContext`）：现在几点（含年月日和星期）、
距离上一条消息过了多久、主人大致在哪个城市。

位置走 IP 定位（`IpLocationSource`），**不需要任何权限**，也不会弹窗：把出口 IP
发给第三方定位服务换一个城市名，按 ip-api → ipwho.is → ipapi.co 的顺序尝试，
第一个给出城市的胜出（ip-api 是唯一返回中文地名的）。结果缓存半小时，只在后台刷新，
发消息的关键路径上永远只读缓存——定位拿不到就只是少一行背景，不会拖慢消息。

代价必须说清楚：精度只到城市，手机走运营商 NAT 时出口 IP 可能落在很远的城市，
开着 VPN 时拿到的就是 VPN 的位置。所以提示词里明确标了「按网络 IP 推测，可能不准」，
设置页也有开关，关掉之后不会再发任何定位请求。

时间、位置和记忆块都拼在 system prompt 的末尾、人设之后：稳定的前缀在前、易变的在后，
这样服务商的提示词缓存能尽量复用。

## 数据与隐私

| 数据 | 位置 | 说明 |
| --- | --- | --- |
| API 设置 | DataStore 文件 `cat_settings` | Base URL、Key、模型、采样参数、服务商、定位开关 |
| 猫猫设定 | 同上 | 名字、性格、说话风格、猫味、补充设定 |
| 聊天记录 | `filesDir/chat/chat_log.jsonl` | 明文 JSONL，只在本应用私有目录 |
| 结构化记忆 | `filesDir/chat/cat_memory.json` | 明文 JSON，含提取游标 |

- 应用只申请 `INTERNET` 一个权限；
- 没有后端，聊天内容只发给你配置的模型服务；
- 开启定位时，出口 IP 会交给第三方定位服务（ip-api / ipwho.is / ipapi.co）；
- API Key 以明文存放在应用私有 DataStore 中，未做额外加密——这是当前的已知限制。

## 模型能力表

`ModelCatalog` 是「哪些参数能调、取值范围多少」的唯一来源，设置页与请求体都由它驱动：

| 服务商 | 模型 | temperature | top_p | max_tokens | 思考 |
| --- | --- | --- | --- | --- | --- |
| 智谱 GLM | glm-4.5 / glm-4.6 | 0–1 | 0.01–1 | ≤32768 | `thinking.type` 开关（默认开） |
| 智谱 GLM | glm-4.5-air / flash | 0–1 | 0.01–1 | ≤32768 | `thinking.type` 开关（默认关） |
| 智谱 GLM | glm-4-plus / air / flash / long | 0–1 | 0.01–1 | ≤4095 | 不支持 |
| 智谱 GLM | glm-z1-air / flash | 0–1 | 0.01–1 | ≤4095 | 始终思考，不可关 |
| DeepSeek | deepseek-chat | 0–2 | 0.01–1 | ≤8192 | 不支持 |
| DeepSeek | deepseek-reasoner | 不发送 | 不发送 | ≤65536 | 始终思考，不可关 |
| OpenAI | gpt-4o / 4.1 系列 | 0–2 | 0.01–1 | 见内置值 | 不支持 |
| OpenAI | o4-mini | 不发送 | 不发送 | ≤100000 | `reasoning_effort` |

其它服务商和手动输入的模型名走名称启发式（`reasoner` / `z1` / `r1` → 始终思考，
`glm-4.5+` → thinking 开关，`o*` / `gpt-5` → reasoning_effort），兜底按通用 OpenAI 兼容规则处理并在设置页标注。
新增服务商或模型只需改这一张表。

`max_tokens` 的 0 表示「不限制、不发送」，设置页显示为「不限制」。

## 项目结构

```
iKitty/
├── app/src/main/java/com/example/aicat/
│   ├── MainActivity.kt            入口 Activity 与主题
│   ├── CatChatScreen.kt           聊天页 UI
│   ├── CatChatViewModel.kt        聊天状态与全部编排
│   ├── SettingsScreen.kt          设置页 UI
│   ├── CatMemoryScreen.kt         记忆页 UI
│   ├── CatView.kt                 Canvas 猫咪与头像
│   ├── CatState.kt                情绪 / 动作枚举
│   ├── CatReply.kt                模型回复解析
│   ├── CatPersona.kt              角色设定 → system prompt
│   ├── CatMemory.kt               记忆模型、合并规则、渲染、解析
│   ├── CatMemoryStore.kt          记忆文件读写
│   ├── MemoryExtractor.kt         记忆整理提示词与调用
│   ├── ModelCatalog.kt            服务商、模型能力、参数区间
│   ├── ChatModels.kt              消息、参数、配置模型
│   ├── ApiClient.kt               OpenAI 兼容 HTTP 客户端
│   ├── ContextAssembler.kt        token 估算与上下文装配
│   ├── ChatLogStore.kt            JSONL 追加式聊天记录
│   ├── StoredMessage.kt           落盘消息模型
│   ├── SettingsStore.kt           DataStore 持久化
│   ├── AmbientContext.kt          「此刻」背景块
│   ├── Location.kt / IpLocationSource.kt  IP 城市定位
│   └── TimeFormat.kt              时间与间隔格式化
├── app/src/test/java/com/example/aicat/   46 个纯 JVM 单元测试
├── design/cat_v1/                 分层猫咪角色素材与规范
└── docs/DOC.md                    详细设计文档
```

## 测试

```bash
./gradlew testDebugUnitTest
```

当前 46 个用例覆盖纯逻辑契约：聊天记录的读写与坏行容错、记忆合并与解析、上下文装配、
角色 prompt、IP 返回解析与「此刻」背景块。UI 与真实网络请求不在单元测试范围内。
详见[详细设计文档](docs/DOC.md#14-测试策略)。

## 已知限制与路线图

1. 猫咪画布未在聊天页显示（详见[当前形态](#当前形态只有聊天)）；
2. 模型回复不是流式输出，长回复要等完整返回；
3. 只保存当前一套服务商配置，切换服务商时 Key / 模型会互相覆盖；
4. 聊天页一次只载入最近 400 条，没有向上翻页加载更早记录；
5. 消息只存 UTC 毫秒时间戳，没有记下写入时的时区偏移，跨时区回看「当时是几点」不准；
6. token 数只是估算，装配按「宁可高估」处理；
7. 定位精度只到城市，且依赖第三方 IP 服务；
8. API Key 明文存储，未接入 EncryptedSharedPreferences / Keystore；
9. 界面文案目前只有中文，没有做多语言资源。

后续可做：把猫咪画布放回聊天页或做成可开关、换 Rive/Lottie 动画、流式输出、
按服务商分别保存配置、聊天记录向上分页、写入时记录时区偏移、
给 `LocationSource` 增加系统定位实现（需运行时权限与失败降级）、
需要「永不遗忘」时再上检索（消息切块 + 向量）。

## 相关文档

- [docs/DOC.md](docs/DOC.md)：架构、模块契约、数据格式、算法与扩展点
- [design/cat_v1/README.md](design/cat_v1/README.md)：猫咪分层素材的坐标系、图层与驱动建议

## 许可

仓库当前未包含 `LICENSE` 文件。
