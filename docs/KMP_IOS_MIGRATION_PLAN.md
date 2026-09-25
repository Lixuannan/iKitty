# iKitty iOS 迁移计划（KMP · 完整功能对齐）

> 目标：**iOS 实现与 Android 完整的现有功能对齐**（不是精简版）。
> 范围：只做 iOS，不做 macOS。当前不实现 UI 跨平台。
> 基线：v0.3.0 · `com.codingcow.ikitty` · KGP 2.0.21 / Gradle 9.7.1 / AGP 8.7.3 / JDK 25.0.2 · macOS 27 arm64
>
> 硬约束：不重写聊天逻辑 · 不为 KMP 大规模重构 · 不复制 iOS 版逻辑 ·
> Android 必须始终可构建可运行 · 每步可独立提交、独立回滚。

---

## 进度（滚动更新）

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| Phase 0 环境与基线 | ✅ | `xcode-select` 已指向 Xcode 27.0；KGP 2.4.20 + Gradle 9.7.0；Kotlin/Native 实测能编译**并链接**arm64 framework |
| Phase 1 Batch 1 | ✅ | CatState / CatPersona / Location / ChatModels / ModelCatalog / CatMemory 纯逻辑 + ImageNaming 进 commonMain |
| Phase 2 JSON 层 | ✅ | kotlinx-serialization 1.11.0（只用 JsonElement）；金标准比对按**结构**而非字节顺序（两端 org.json 的键顺序本就不同） |
| Phase 3 时间与上下文 | ✅ | kotlinx-datetime 0.8.0；`formatMoment` 与 `SimpleDateFormat` 在 3 个时区（含夏令时）逐字节一致 |
| Phase 4 存储与设置 | ✅ | okio 3.18.2；ChatLogStore / CatMemoryStore / AppPaths / KeyValueStore / SettingsRepository |
| Phase 5 HTTP seam | ✅ | `HttpTransport` 契约 + `ApiClient` / SSE / MemoryExtractor 进 commonMain；Android 侧 OkHttp 原样搬入 |
| Phase 6 iosMain | ✅ | Ktor Darwin 传输 · NSFileManager 路径 · NSUserDefaults 设置 · iosSimulatorArm64 目标 |
| Phase 7a ChatEngine | ✅ | 逐行搬出，Android ViewModel 变薄壳；新增 8 条编排测试（原本零覆盖） |
| Phase 7b iOS UI | ✅ | 手写 xcodeproj（同步文件组）+ SwiftUI 聊天页/设置页；`xcodebuild` 对模拟器 SDK **构建成功** |
| Phase 8 图片输入 | ✅ | 相册（PHPicker，零权限）+ 相机 + 缩略图；像素管线在 Swift（CoreGraphics），共享的是尺寸/质量/文件名/数据 URL 契约 |
| Phase 9 IP 定位 | ✅ | `IpLocationSource` 整体进 commonMain，走 `HttpTransport`；两端同一份多端点兜底逻辑 |
| Phase 10 猫咪动画 | ⏭️ **不需要** | Android 自己**也没显示**：`CatChatScreen.kt:206` 明确写着"猫咪画布暂时不显示，只保留聊天"，`CatView` 除了自己的预览之外没有任何调用点。所以它不属于"对齐现有功能" |
| Phase 11 备份恢复 | ✅ | `ZipCodec`（双向互操作已验证）+ `BackupArchive` 整体进 commonMain + iOS 导出/导入界面 |
| Phase 12 分发 | ⬜ | iOS 没有应用内 APK 更新，替代方案是 TestFlight / App Store |

**真实网络往返已实测**：`:shared:jvmTest` 里有 5 条集成测试起一个真的 `HttpServer`，
用**生产用的 OkHttp 传输**跑完整链路（SSE 流式、落盘、忽略 stream 的退化路径、401 错误、
请求体契约、带图片的 `image_url` 数据 URL）。这是项目里第一次真的走真实入口路径，
不需要模拟器也不需要 API Key。

**Phase 11 的进展与坑**：`ZipCodec` 已完成（commonMain，15 条测试）。
写侧只用 STORED（无压缩依赖、输出可复现）；读侧必须支持 DEFLATE，因为
Android 的 `ZipOutputStream` 默认就是 DEFLATE，否则读不了**已经存在的**备份。
解压复用 okio 的 `InflaterSource`，但这里踩了一个只有互操作测试才能发现的坑：
okio 的 `Inflater()` 无参构造是**带 zlib 头**的，拿它解 ZIP 的 raw deflate 会报
`incorrect header check`，必须 `Inflater(true)`。自己写、自己读是永远发现不了的
（我们写 STORED，根本不走解压）。

**iOS UI**：聊天页、记忆页、设置页（连接 / 采样参数 / 思考开关 / 角色设定 /
隐私开关 / 测试连接 / 模型列表）、备份与恢复、图片输入与缩略图都已完成。
设置里的参数控件由共享的模型能力表驱动，换模型会自动增减控件并改上下限。

**配色与 Android 对齐**：iOS 侧新增 `AppTheme.swift`，取 Android `CatLightColors`
（`MainActivity.kt`）里的对应角色——内容区底色 `#FFF8F2`、主色 `#E08A5F` 等。

**镀铬层改走 iOS 的 Liquid Glass**（`GlassChrome.swift`）：Apple 明确要求
「减少控件与导航元素上的自定义背景」，否则自定义底色会盖住玻璃材质和滚动边缘效果。
所以分工是——玻璃是镀铬层的语言，暖色是内容层的识别度：

| 层 | iOS 26+ | iOS 17–25 回退 |
| --- | --- | --- |
| 导航栏 / 工具栏 / 表单 / 弹层 | 系统自动采用 Liquid Glass | 系统自带的材质与配色 |
| 聊天输入栏 | `safeAreaBar` + `glassEffect(.regular)`：左侧「+」与文本框合成胶囊，发送键是**独立**的圆形玻璃按钮 | 同样排布 + `.ultraThinMaterial` |
| 发送按钮 | `buttonStyle(.glass)` + 品牌色 tint | 品牌色实心圆 |
| 内容区（气泡 / 卡片 / 底色） | 固定暖色，不叠玻璃 | 同左 |

为此移除了上一轮刷在导航栏、表单、输入栏上的自定义底色（`scrollContentBackground`、
输入栏实心白条与自定义阴影），并把消息列表交给 `safeAreaBar` 以获得滚动边缘效果。
`preferredColorScheme(.light)` 锁死浅色外观：这套暖奶油配色没有深色版本，
不锁的话系统切深色后标题与输入框占位会变成浅色，压在 `#FFF8F2` 上几乎看不见。

部署目标仍是 **iOS 17**，Liquid Glass 的可用性判断集中在 `GlassChrome.swift` 一处。

**两处刻意的偏离**（都朝"更少的平台代码"）：

1. **不引入 `expect/actual`**。文件系统、路径与 IO 调度器通过构造参数注入
   （`ChatLogStore(fileSystem, path, ioDispatcher, now)`），Android 侧由
   `androidChatLogStore(...)` 工厂传入 `Dispatchers.IO`。好处：`iosArm64`
   在 Phase 6 之前就能持续编译，且"这一端用哪个调度器"是显式的，
   不会像 `Dispatchers.IO` 那样漏改后静默降级（原风险 R7）。
2. **设置不走 `expect`**，而是 commonMain 的 `KeyValueStore` 接口 +
   两端的实现（DataStore / NSUserDefaults），键名与默认值在
   `SettingsRepository` 里只写一遍。

**iOS 侧已经真的跑起来了**：本机已安装 iOS 27.0 模拟器 runtime
（`xcodebuild -downloadPlatform iOS -architectureVariant arm64`，8.05 GB）。
`xcrun simctl list runtimes` 现在能看到它，于是：

```bash
# 共享逻辑在真的 iOS 运行时上跑（174 条：168 common + 6 平台层）
./gradlew :shared:iosSimulatorArm64Test

# 构建、安装、运行 iOS 应用
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iKitty \
  -sdk iphonesimulator -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath /tmp/ikitty-dd CODE_SIGNING_ALLOWED=NO build
xcrun simctl install "iPhone 17" /tmp/ikitty-dd/Build/Products/Debug-iphonesimulator/iKitty.app
xcrun simctl launch "iPhone 17" com.codingcow.ikitty
```

已经**实测**过的 iOS 行为：

| 项 | 证据 |
| --- | --- |
| 共享逻辑（JSON / 时间含夏令时 / ZIP / 备份 / 设置 / 编排 / 定位） | 168 条 commonTest 在模拟器上全绿 |
| iOS 平台层（`NSFileManager` 路径、`NSUserDefaults`、okio Native 图片落盘） | `iosTest` 6 条全绿 |
| 应用启动与渲染 | 截图：开场白来自共享的 `CatPersona.welcome()` |
| 落盘路径与格式 | `Library/Application Support/iKitty/chat/chat_log.jsonl`，共享 `AppPaths` + `StoredMessage` JSON |
| 重启后读回 | 重启后仍显示同一条，且没有重复追加开场白 |
| Ktor Darwin 传输 + ATS | 运行日志里 `http://ip-api.com/json/` 连接并 `finished successfully` |
| 三个 sheet（设置 / 记忆 / 备份） | 截图确认渲染正常且不崩 |
| **完整发送链路** | 起一个假的 OpenAI SSE 服务，应用真的发出请求（`stream: true` + 共享 `CatPersona` 生成的 system prompt），四段 SSE 增量被正确拼成 `喵～在呢，我听到啦`，落盘为第 3 条消息并渲染成气泡 |

**还没实测**：用真实的模型服务商发一次（需要 API Key）、相册/相机选图、
CoreGraphics 图片归一化、`fileImporter` / `ShareLink` 的完整交互。

**已知环境限制**：`simctl` 不支持点击，AppleScript 也够不到 Simulator GUI，
所以界面交互目前只能靠"临时启动参数打开某个页面 / 触发一次发送 + 截图"来取证；
取证用的临时钩子不会留在提交里（`ChatView.swift` 与提交版本逐字节一致）。

**踩到的坑：手工配置 source set 会让默认层级模板失效。**
加 `okhttpMain` 中间 source set 之后，`iosMain` 变成"被配置了但不属于任何编译"——
Gradle 依然 `BUILD SUCCESSFUL`、framework 依然链接成功，但里面**一行 iOS 代码都没有**，
`IosAppEnvironment` 从生成的 header 里消失。这类问题 Gradle 侧发现不了，
只有在构建 iOS App 时（Swift 找不到符号）才会暴露。
修法是显式 `applyDefaultHierarchyTemplate()`。

---

## 0. 环境核实（本次实测，非推测）

| 项 | 实测结果 |
| --- | --- |
| Xcode | **27.0（build 27A266a）已安装** 在 `/Applications/Xcode.app` |
| iOS SDK | `iPhoneOS27.0.sdk` + `iPhoneSimulator27.0.sdk` 均存在 |
| **`xcode-select -p`** | **`/Library/Developer/CommandLineTools`** ← 仍指向命令行工具 |
| `xcodebuild -version` | ❌ `requires Xcode, but active developer directory ... is a command line tools instance` |
| `xcrun simctl` | ❌ `unable to find utility "simctl"` |
| `~/.konan` | 不存在（Kotlin/Native 工具链尚未下载） |
| Kotlin 稳定版 | **2.4.20**（2026-09-14 发布） |
| 网络 | Maven Central / plugins.gradle.org / download.jetbrains.com 均可达 |
| Android 基线 | `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**，94 用例 |

> **⚠️ Xcode 装好了但还没"生效"**：`xcode-select` 仍指向 CommandLineTools，
> 所以 `xcodebuild` / `simctl` 全都不可用。需要执行（要 sudo，我无法代执行）：
>
> ```bash
> sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
> sudo xcodebuild -runFirstLaunch
> xcodebuild -version && xcrun simctl list runtimes
> ```
>
> 这一步必须由你完成，它是整个 iOS 路线的闸门。

---

## 1. 版本决策（本计划最关键的一节）

官方兼容矩阵（来源：Kotlin 官方文档 *Compatibility guide for Kotlin Multiplatform*）：

| KMP 插件版本 | Gradle | AGP | Xcode |
| --- | --- | --- | --- |
| **2.4.20**（目标） | 7.6.3 – **9.7.0** | 8.5.2 – **9.3.1** | **26.4** |
| 2.4.0 – 2.4.10 | 7.6.3 – 9.5.0 | 8.5.2 – 9.1.0 | 26.4 |
| 2.3.20 – 2.3.21 | 7.6.3 – 9.3.0 | 8.2.2 – 9.0.0 | 26.0 |
| 2.2.21 | 7.6.3 – 8.14 | 7.3.1 – 8.11.1 | 26.0 |
| **2.0.21（现状）** | **7.5 – 8.8** | **7.4.2 – 8.5** | **16.0** |

### 1.1 现状的三个"越界"

| 项 | 项目现状 | KGP 2.0.21 支持 | 结论 |
| --- | --- | --- | --- |
| Gradle | 9.7.1 | 7.5 – 8.8 | **越界** |
| AGP | 8.7.3 | 7.4.2 – 8.5 | **越界** |
| Xcode | 27.0 | 16.0 | **严重越界** |

Android 之所以能跑，是因为 KGP 对 Gradle/AGP 只做"警告"而非硬失败。
但 **KMP 插件路径不能由 Android 插件路径推证**——它依赖更多 Gradle 内部 API。
所以"Android 现在能构建"完全不代表"能加上 KMP"。

### 1.2 目标版本组合

| 组件 | 现状 | 目标 | 理由 |
| --- | --- | --- | --- |
| **KGP** | 2.0.21 | **2.4.20** | 当前稳定版；Xcode 支持到 26.4，是离 27.0 最近的一档 |
| **AGP** | 8.7.3 | **8.7.3（不动）** | 8.7.3 落在 2.4.20 的 8.5.2–9.3.1 区间内 ✅ **不需要升 AGP 9** |
| **Gradle** | 9.7.1 | **9.7.0** | 9.7.1 比上限高 1 个补丁；降 1 个补丁比冒险更划算 |
| **Compose 编译器插件** | 2.0.21 | **2.4.20** | 必须与 KGP 同版本 |
| **compose-bom** | 2024.12.01 | **待实测** | 新 Compose 编译器对 `androidx.compose.runtime` 有最低版本要求；若报错则最小幅度上调（当前最新 2026.09.00） |
| compileSdk / minSdk / 签名 | 35 / 26 / debug 签名 | **一律不动** | release 沿用 debug 签名是数据安全约束，见 `app/build.gradle.kts` 注释 |
| `androidTarget` DSL | — | **用 `androidTarget`** | Kotlin 2.3.0 曾弃用它，**2.3.10 已 revert**；2.4.20 + AGP 8.x 下是官方推荐形态。新的 `com.android.kotlin.multiplatform.library` 需要 AGP 9，**本计划不采用** |

### 1.3 Xcode 27.0 与 Kotlin 2.4.20 的落差：三条路

Kotlin 2.4.20 的发布说明明确写着 **"计划在 Kotlin 2.5.0 中移除 watchosArm32，以确保与 Xcode 27 兼容"**——
即 **Xcode 27 的正式支持要等 Kotlin 2.5.0**（尚未发布）。

| 路线 | 成本 | 评价 |
| --- | --- | --- |
| **(a) 补装 Xcode 26.4，用 `xcode-select`/`DEVELOPER_DIR` 指向它** | 数 GB 下载 | **官方支持路径，最稳**。Xcode 可多版本共存 |
| **(b) 直接用 Xcode 27.0 + Kotlin 2.4.20** | 0 | 可能只打警告并正常工作（Kotlin/Native 链接的是系统 SDK），也可能在 cinterop/链接阶段失败。**廉价，先试** |
| **(c) 等 Kotlin 2.5.0** | 时间 | 不可控 |

**推荐：(b) 先探测，(a) 作为兜底。** 探测成本极低（见 Phase 0），失败再补装 26.4。

### 1.4 依赖版本清单（实测自 Maven Central / Google Maven）

| 依赖 | 版本 | 用途 | 阶段 |
| --- | --- | --- | --- |
| `kotlinx-serialization-json` | **1.11.0** | JSON（只用 `JsonElement`，**不需要编译器插件**） | Phase 2 |
| `kotlinx-datetime` | **0.8.0** | 时间格式化 | Phase 3 |
| `kotlinx-coroutines-core` | **1.11.0** | 对齐 core 与 android 版本（现为 1.9.0 的 android 版） | Phase 1 |
| `okio` | **3.18.2** | 文件 IO（**已是 OkHttp 传递依赖，不算新增框架**） | Phase 4 |
| `ktor-client-core` + `ktor-client-darwin` | **3.6.0** | iOS HTTP + SSE | Phase 6 |
| `compose-gradle-plugin`（CMP） | 1.12.0 | 仅当 iOS UI 选 CMP（Phase 7 才决定） | Phase 7 |
| `androidx.datastore:datastore-preferences` | 1.1.1（**不动**） | Android 设置存储 | — |

---

## 2. 完整功能对齐矩阵

**"完整功能"的定义**：iOS 与 Android **现有的全部功能**对齐。
Android 本身没有 Live2D / 语音 / TTS，因此它们不属于"对齐"，单列为新增功能（§2.2）。

### 2.1 Android 功能 → iOS 实现

| # | Android 功能 | iOS 实现方案 | 难度 | 阶段 |
| --- | --- | --- | --- | --- |
| 1 | 任意 OpenAI 兼容服务（17 预设 + 自定义） | commonMain 同一份代码 | 低 | 1 |
| 2 | 按模型能力动态出参数 | commonMain `ModelCatalog` | 低 | 1 |
| 3 | 测试连接 | commonMain + `HttpTransport` | 低 | 5 |
| 4 | 流式回复（+ 忽略 `stream` 时退化非流式） | Ktor Darwin + commonMain SSE 解析 | 中 | 5–6 |
| 5 | 角色设定 Persona | commonMain | 低 | 1 |
| 6 | 结构化长期记忆 | commonMain + okio | 中 | 2–4 |
| 7 | token 预算上下文 | commonMain | 低 | 3 |
| 8 | 本地聊天记录（JSONL 追加 + 末尾 N 条） | commonMain + okio | 中 | 4 |
| 9 | 基本设置 + API Key | commonMain `KeyValueStore` + iOS `NSUserDefaults` | 中 | 4 |
| 10 | **图片输入（相册）** | `PHPickerViewController`（**零权限**）+ CoreGraphics/ImageIO 管线 | 高 | **8** |
| 11 | **图片输入（相机）** | `UIImagePickerController`（需 `NSCameraUsageDescription`）+ 同上 | 高 | **8** |
| 12 | 图片缩略图显示 / 待发送条删除 | iOS UI + ImageIO 解码 | 中 | 8 |
| 13 | IP 城市定位 | 仅用 https 端点（**ATS 阻断明文 HTTP**） | 低 | 9 |
| 14 | **猫咪动画（7 情绪 + 6 动作）** | 图形方案迁移（见 §2.3） | 中–高 | **10** |
| 15 | **`.ikitty` 备份恢复** | ZIP 编解码（Apple 无 `java.util.zip` 等价物） | 高 | **11** |
| 16 | 应用版本号 | `NSBundle` | 低 | 6 |
| 17 | 消息时间显示 | iOS UI（或用 commonMain 的 `formatMessageTime`） | 低 | 7 |
| 18 | **应用内更新（GitHub release 下载 APK）** | **不可移植** | — | 12 |

### 2.2 不属于"对齐"的新增功能

| 功能 | Android 现状 | 说明 |
| --- | --- | --- |
| Live2D | **没有** | 需要 Live2D Cubism SDK 的 iOS 绑定 + 资源管线，是一个独立立项 |
| 语音输入 | **没有** | |
| TTS | **没有** | |

> **需要你确认**：如果"完整功能"包含 Live2D / 语音 / TTS，那它们是**在 Android 上也要先做**的新功能，
> 不应混进"iOS 迁移"的范围里。本计划只覆盖"iOS 对齐 Android 现有能力"。

### 2.3 三个高难度项的方案细节

**图片输入（#10–12）**
- 权限：**PHPicker 不需要任何权限**，`Info.plist` 连 `NSPhotoLibraryUsageDescription` 都不用写；
  相机才需要 `NSCameraUsageDescription`。
- 图形管线（`ImageStore.kt` 185 行的 iOS 对等物）：

  | Android | iOS |
  | --- | --- |
  | `inJustDecodeBounds` 探测尺寸 | `CGImageSourceCreateWithURL` + `kCGImagePropertyPixelWidth` |
  | `inSampleSize` 降采样 | `CGImageSourceCreateThumbnailAtIndex` + `kCGImageSourceThumbnailMaxPixelSize` |
  | `Bitmap.createScaledBitmap` | CoreGraphics `CGContextDrawImage` |
  | `flattenAlpha`（透明填白） | CoreGraphics 先填白再合成 |
  | `Bitmap.compress(JPEG, 85)` | `CGImageDestination` + `kUTTypeJPEG` |
  | `android.util.Base64` | `NSData.base64EncodedStringWithOptions` |

- 需要 `expect ImageCodec` + 约 150–200 行 cinterop。
- **好消息**：多模态线上协议（`ChatMessage.images`、`chatContent()` 的 `image_url` 数组、
  `TokenEstimator.IMAGE_TOKENS`）**已经在 Batch 1/Batch 5 的 commonMain 范围内**，这部分白拿。
- 另外 `ImageStore` 的 `synchronized` 与 `LinkedHashMap.removeEldestEntry` 是 JVM 专有，
  需要 `expect` 锁。

**猫咪动画（#14）**
`CatView.kt` 957 行，当前**未接入聊天页**（`CatChatScreen` 里留了位置注释）。
`design/cat_v1/` 提供 15 张分层 PNG + `layers.json` 锚点。
- 若 iOS UI 选 **CMP**：`CatView` 的 Canvas / `drawImage` 逻辑基本可复用，成本最低。
- 若 iOS UI 选 **SwiftUI**：需要重写为 `Canvas`/`CALayer` 分层渲染 + 同一个 `layers.json` 锚点模型。
- `CatAnimation` 的 `durationMillis` 与 `CatMood.defaultFor()` 在 commonMain，两端共享。

**`.ikitty` 备份恢复（#15）**
`java.util.zip` 在 Apple 目标不存在；**okio 只能读 zip、不能写**。三条路：

| 方案 | 成本 | 兼容性 |
| --- | --- | --- |
| (a) 纯 Kotlin ZIP 读写器，写侧用 **STORED（不压缩）** | 约 250 行（含 CRC32） | ✅ Android 的 `java.util.zip` 能正常读 |
| (b) cinterop `libz` / `libcompression` | 中，引入构建复杂度 | ✅ |
| (c) iOS 不做备份 | 0 | ❌ 与"完整功能"冲突 |

推荐 **(a)**：零依赖、格式字节兼容、可跨平台互导（Android 导出的 `.ikitty` 能在 iOS 导入，反之亦然）。
`BackupArchiveTest` 的 11 个用例正好可以当验收基准。

---

## 3. 推荐架构

```
iKitty/
├── app/                                  Android 应用（继续现有 Jetpack Compose）
│   └── src/test/                         仅剩 Android-only 测试（20 用例）
├── shared/
│   ├── commonMain/kotlin/
│   │   ├── chat/      ChatMessage · StoredMessage · CatReply · ContextAssembler
│   │   ├── catalog/   ModelCatalog · ProviderSpec · ModelSpec · ApiConfig
│   │   ├── memory/    CatMemory · CatMemoryRules · CatMemoryRender · MemoryExtractor
│   │   ├── persona/   CatPersona · CatTrait · CatSpeechStyle · CatFlavor
│   │   ├── context/   AmbientContext · TimeFormat · Location
│   │   ├── api/       ApiClient · SSE 解析 · 载荷构建 · HttpTransport(契约)
│   │   ├── json/      JsonSupport（镜像现有 org.json 语义）
│   │   ├── store/     ChatLogStore · CatMemoryStore · KeyValueStore(契约) · AppPaths(契约)
│   │   ├── image/     ImageCodec(契约) · ImageNaming
│   │   ├── backup/    BackupArchive · ZipCodec(契约)
│   │   └── engine/    ChatEngine（Phase 7）
│   ├── androidMain/kotlin/   OkHttp 传输 · filesDir · DataStore · BitmapFactory · java.util.zip
│   └── iosMain/kotlin/       Ktor Darwin · NSFileManager · NSUserDefaults · CoreGraphics · 纯 Kotlin ZIP
└── iosApp/                               Phase 7 才创建
```

**依赖方向（可强制校验）**：`app → shared`、`iosApp → shared`，**禁止反向**。
用 Gradle 依赖校验或 CI 断言固化，防止迁移中间态出现"两边各留一份实现"。

**为什么必须拆 `:shared`**：`com.android.application` 不能叠加 KMP 插件。
拆成 `:app` + `:shared` 是让"Android 继续正常构建"与"逐步迁移"同时成立的唯一结构。
（附带好处：AGP 9.0 明确移除了"同一模块内 `com.android.application` + KMP"的支持，这个结构天然免疫。）

---

## 4. 文件迁移清单

### 4.1 迁往 `commonMain`

> **重要纠正**：`docs/DOC.md` §1.2 那份"无 Android 依赖的纯逻辑层"清单**不能直接当迁移顺序**——
> 它只保证"不 import android.*"，忽略了传递依赖。实测：
> `ContextAssembler → StoredMessage`、`AmbientContext → TimeFormat`、`MemoryExtractor → ApiClient`、
> `ChatModels ↔ ModelCatalog` 互相依赖。所以必须按**依赖拓扑**分批。

#### Batch 1 — 零逻辑改动

| 文件 | 行数 | 改动 |
| --- | --- | --- |
| `CatState.kt` | 43 | 无 |
| `CatPersona.kt` | 131 | 无 |
| `Location.kt` | 40 | 无（`Place` + `LocationSource` 接口） |
| `ChatModels.kt` | 81 | 无 |
| `ModelCatalog.kt` | 651 | 仅 `NumberParam.format` 的 `String.format(Locale.US, …)` → common 定点格式化（约 5 行） |
| `CatMemory.kt` | 233 | **拆两半**：纯逻辑进 commonMain；`toJson`/`fromJson`/`parseMemoryUpdate` 暂留 `androidMain` 的 `MemoryJson.kt` |
| `ImageStore.kt` 的纯函数 | ~10 | `sampleSizeFor` / `mimeFor` 抽成 commonMain `ImageNaming.kt` |

> Batch 1 **刻意零 `expect/actual`**：一旦引入，`androidTarget()` 与 `jvm()` 都要实际现，
> 会凭空增加两套实现。纯逻辑搬迁应当零 `expect`。

#### Batch 2 — JSON 层

前置：`commonMain/json/JsonSupport.kt`。
迁入：`StoredMessage.kt`(84) · `CatReply.kt`(102) · `CatMemory.kt` 的 JSON 部分 · `parseCatMemory`

#### Batch 3 — 时间与装配

`TimeFormat.kt`（**只迁 `formatMoment` / `formatElapsed`**，这两个进 prompt；
`formatMessageTime` 是 UI 展示用，可留各平台） · `AmbientContext.kt`(21) · `ContextAssembler.kt`(168)

#### Batch 4 — 存储与设置

`ChatLogStore.kt`(113) · `CatMemoryStore.kt`(71) · 新增 `AppPaths` 与 `KeyValueStore`
**路径契约不得变**：`chat/chat_log.jsonl`、`chat/cat_memory.json`

#### Batch 5 — HTTP 与 SSE

`ApiClient.kt`(423) 本体 + `buildChatPayload` + `chatContent` + SSE 解析 + `parseCompletion` +
`parseErrorMessage` + `httpFailure` + `listModels` + `test` · `MemoryExtractor.kt`(71) ·
`IpLocationSource.kt` 的 `parseIpPlace`(107 的部分)
Android 侧 OkHttp 代码**原样**搬入 `androidMain`（3 个 timeout、`call.cancel()` 取消、
`IOException` → 中文提示映射全部保留）。

#### Batch 6–8 — 图片、备份、动画（对齐"完整功能"）

| Batch | 内容 |
| --- | --- |
| 6 | `ImageStore` 的 `expect ImageCodec` + Android actual（原 `BitmapFactory` 管线）+ iOS actual（CoreGraphics）+ `ChatImage` 的 iOS 版 |
| 7 | `BackupArchive`(431) 的编排逻辑进 commonMain + `expect ZipCodec`（Android `java.util.zip` / iOS 纯 Kotlin ZIP） |
| 8 | `CatView` 的分层渲染与锚点模型（视 UI 选型） |

### 4.2 永久保留 `androidMain` / `:app`

`MainActivity`(43) · `CatChatScreen`(619) · `SettingsScreen`(1167) · `CatMemoryScreen`(376，
**本身无任何 Android import**，是将来最早可迁的 UI) · `CatView`(957) · `ChatImage`(57) ·
`CatChatViewModel`(660，Phase 7 变薄壳) · `ApkInstaller`(109) · `UpdateClient`(128) ·
`UpdateModels`(143) · 全部 `res/**` / `AndroidManifest.xml` / `network_security_config.xml` / `file_paths.xml`

### 4.3 最终规模

`commonMain` 约 **3000 行 / 25 个文件**，占 Android 现有 6066 行主源码的约 **50%**。
（比不含图片/备份的版本多约 600 行，因为"完整功能"把这两个拉回来了。）

---

## 5. 测试迁移（94 用例）

### 5.1 逐文件去向

| 测试文件 | 用例 | 去向 | 阶段 |
| --- | --- | --- | --- |
| `CatPersonaTest` | 8 | ✅ 直接迁 | Batch 1 |
| `ModelCatalogTest` | 8 | ✅ 直接迁 | Batch 1 |
| `CatMemoryTest` | 11 | 8 条直接迁；3 条（JSON 解析）等 Batch 2 | 1 / 2 |
| `ImageStoreTest` | 4 | ✅ Batch 1 抽出纯函数后即可迁 | 1 |
| `StoredMessageTest` | 7 | ✅ | 2 |
| `MultimodalPayloadTest` | 5 | ✅ | 2 |
| `ContextAssemblerTest` | 13 | ✅ | 3 |
| `LocationTest` | 10 | 分三批：`Place` 部分 / `AmbientContext` 部分(B3) / `parseIpPlace` 部分(B5) | 1/3/5 |
| `ChatLogStoreTest` | 8 | ✅ 改用 `okio.fakefilesystem.FakeFileSystem` | 4 |
| `BackupArchiveTest` | 11 | ✅ **现在也要迁**（因为备份是"完整功能"的一部分） | 7（需 ZIP 层） |
| `UpdateModelsTest` | 9 | ✘ 留 `app/src/test`（APK 更新不可移植） | — |

**可迁 `commonTest`：85 / 94 用例（90%）**，仅 `UpdateModelsTest` 留在 Android。

### 5.2 断言框架迁移：JUnit4 → `kotlin.test`

| 现在 | 迁移后 |
| --- | --- |
| `org.junit.Test` | `kotlin.test.Test` |
| `org.junit.Assert.assertEquals(expected, actual)` | `kotlin.test.assertEquals(expected, actual)`（**参数顺序一致**） |
| `assertTrue` / `assertFalse` / `assertNull` | `kotlin.test.assertTrue` / `assertFalse` / `assertNull` |
| `assertThrows` | `kotlin.test.assertFailsWith` |
| `@Before` / `@After` | `kotlin.test.BeforeTest` / `AfterTest` |
| `kotlinx.coroutines.runBlocking` | `kotlinx.coroutines.test.runTest` |

### 5.3 双 target 跑测

`shared` 同时声明 `androidTarget()` 与 `jvm()`：

```bash
./gradlew :shared:testDebugUnitTest   # Android target
./gradlew :shared:jvmTest             # 纯 JVM，最快
./gradlew :shared:iosSimulatorArm64Test   # Phase 6 起
```

同一份 `commonTest` 三处都跑，等于免费获得 Android / JVM / iOS 三重验证。

### 5.4 顺带补的测试缺口

- `formatMoment` / `formatElapsed` **目前无独立测试**，但输出直接进 system prompt → Batch 3 前补断言。
- `CatChatViewModel` 无测试覆盖（`docs/DOC.md` §14 明确列出未覆盖项），Phase 7 要动它 → 先补 `send()` 关键分支。

---

## 6. 数据格式与兼容性契约

### 6.1 先分清"需要跨平台共享"和"不需要"

| 契约 | 载体 | 是否需要跨平台一致 |
| --- | --- | --- |
| OpenAI 兼容请求体 | 线上 | **必须逐字节等价** |
| OpenAI 响应 / SSE 解析 | 线上 | **必须等价** |
| system prompt / memory block / ambient block | 线上（提示词） | **必须逐字节等价** |
| `chat/chat_log.jsonl` | 本地 | 保持一致（保留跨平台恢复的可能） |
| `chat/cat_memory.json` | 本地 | 保持一致 |
| `.ikitty` 归档 | 本地 | **必须互读**（"完整功能"下 iOS 也要能读 Android 的备份） |
| DataStore `cat_settings.preferences_pb` | 本地 | **不需要**（Android 专有 protobuf；iOS 用 `NSUserDefaults`） |

### 6.2 Provider / ModelCatalog 的兼容风险是**零**

`ProviderSpec` / `ModelSpec` / `ModelCatalog` **不是持久化格式**，是纯代码常量。
唯一被持久化的是 `providerId` 字符串，而 `ModelCatalog.provider(id)` 对未知 id 已回退 `CUSTOM`。

> 只要它们是**同一份 commonMain 代码**，iOS 与 Android 的能力判定天然一致，
> 不存在"两边能力表漂移"的问题。这正是"优先抽取而不是复制"的最大收益。

### 6.3 需要守护的 5 个契约

1. **请求体 JSON**：字段是否出现 + 数值精度。现有 `NumberParam.jsonNumber` 在 `Double` 上按精度取整，
   规避了 `0.8f.toDouble() == 0.800000011920929` 被服务端拒绝的问题 —— **必须原样保留**。
2. **落盘格式**：`StoredMessage.toJson` 的键与条件写入（有图片才写 `images`，`localError` 才写 `error`）。
3. **容错解析语义**：`stringOrEmpty`（`optString` 对 JSON `null` 会返回字符串 `"null"`）、
   `reasoning_content` → `reasoning` 回退、坏行返回 `null` 而不抛异常。
4. **记忆文件结构**：`{version, lastExtractedSeq, lastExtractedAt, facts:[…]}`。
5. **prompt 输出**：`CatPersona.systemPrompt()` / `CatMemoryRender.block()` / `AmbientContext.block()`。

### 6.4 抗风险的关键做法：**不要用 `@Serializable` 数据类**

现有代码是**显式 builder 风格**（`JSONObject().apply { put(...) }`），输出形状由代码决定。
迁移时保持这个风格，只把 `org.json` 换成 kotlinx.serialization 的 `buildJsonObject` / `JsonObject` 读写
——**只用 `JsonElement` API，不加 `@Serializable`，因此也不需要 serialization 编译器插件**。

好处：输出形状仍由显式代码决定（不由反射/插件决定）；不新增编译器插件（Android 构建风险面更小）；
语义差异集中收敛在 `JsonSupport.kt` 一处。

### 6.5 验收方法

Phase 0 保存 4 份金标准样本（`chat_log.jsonl`、`cat_memory.json`、`cat_settings.preferences_pb`、
一份 `.ikitty`），Batch 2 后做**字节级往返比对**，Batch 7 做**跨端归档互导**验证。

---

## 7. 分阶段迁移计划

每一步结束都必须满足：**Android 可构建、可运行、测试不减、APK 行为不变。**

### Phase 0 — 环境与基线（不写业务代码）

| # | 任务 | 验收 |
| --- | --- | --- |
| 0.1 | **你执行**：`sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` + `-runFirstLaunch` | `xcodebuild -version` 与 `xcrun simctl list runtimes` 可用 |
| 0.2 | 存基线：94 用例结果 + `assembleRelease` 产物哈希 | 有可比对基准 |
| 0.3 | 存 4 份金标准磁盘样本 | 有格式基准 |
| 0.4 | **版本升级（独立提交）**：KGP 2.0.21→2.4.20、Gradle wrapper 9.7.1→9.7.0、compose 插件同版本 | `testDebugUnitTest` + `assembleRelease` 全绿，UI 无回归 |
| 0.5 | **Kotlin/Native 探测**：空 `shared` 只声明 `iosArm64()`，跑 `./gradlew :shared:compileKotlinIosArm64` | 通过 → 走路线 (b)；失败 → 补装 Xcode 26.4 走路线 (a) |

**Phase 0.4 若失败的回滚**：`git revert` 单个提交，回到 2.0.21 的绿色状态。

### Phase 1 — `shared` 模块 + Batch 1

`com.android.library` + `kotlin("multiplatform")`，targets = `androidTarget()` + `jvm()`。
搬 Batch 1 的 6 个文件 + `ImageNaming.kt`，对应测试进 `commonTest`。
`app` 改为 `implementation(project(":shared"))`。

**验收**：`./gradlew :app:testDebugUnitTest :shared:jvmTest :app:assembleDebug` 全绿，
`app/src/main` 除 import 外无改动。

### Phase 2 — JSON 层（Batch 2）

引入 `kotlinx-serialization-json 1.11.0`，新增 `JsonSupport.kt`，替换 4 处。
**验收**：金标准文件字节级往返；`StoredMessageTest` / `MultimodalPayloadTest` 在 commonTest 通过。

### Phase 3 — 时间与上下文（Batch 3）

引入 `kotlinx-datetime 0.8.0`；`formatMoment`/`formatElapsed` **逐字节比对**；
迁 `AmbientContext` / `ContextAssembler`。
**验收**：`ContextAssemblerTest` 13 条 + 新补的 prompt 断言通过。

### Phase 4 — 存储与设置（Batch 4）

引入 `okio 3.18.2`；`ChatLogStore` / `CatMemoryStore` 逻辑进 commonMain；新增 `AppPaths` / `KeyValueStore`。
**这是第一个引入 `expect` 的阶段**，因此 `androidTarget()` 与 `jvm()` 都要 actual。
**验收**：**升级安装后老用户聊天记录 / 记忆 / 设置原样可见**（"不破坏 Android"的关键一步）。

### Phase 5 — HTTP 传输 seam（Batch 5）

定义 `HttpTransport` 契约；Android 侧 OkHttp **原样**搬入；`ApiClient` 本体 + SSE 进 commonMain；
`MemoryExtractor` 随之迁移。
**验收**：Android 上 `testConnection` + 流式聊天 + "服务商忽略 `stream` 退化为非流式"路径全部复测。

### Phase 6 — iosMain：让 `shared` 能在 iOS 上编译与跑测

targets 增加 `iosArm64()` + `iosSimulatorArm64()`。补齐全部 actual：
`AppPaths`（`NSFileManager`）· `HttpTransport`（Ktor Darwin 3.6.0）· `AppInfo`（`NSBundle`）·
`KeyValueStore`（`NSUserDefaults`）· `io` dispatcher · `Clock`。
新增依赖：`ktor-client-core` + `ktor-client-darwin`。

**验收**：`./gradlew :shared:iosSimulatorArm64Test` 通过（commonTest 在模拟器上跑）。
**此阶段仍不碰任何 UI。**

### Phase 7 — ChatEngine 抽取 + iOS UI 选型

**7a 抽取 `ChatEngine`**：把 `CatChatViewModel` 中与 Android 无关的部分
（`send` / `busy` / `streamingReply` / `messages` / `memory` / `memoryStatus` / `contextPlan` / `mood`）
抽成 commonMain 的普通类（自持 `CoroutineScope`，构造注入 `ApiClient` 与各 store）。
Android 的 `CatChatViewModel` 变薄壳。

> **性质：这是"移动"，不是"重写"——逐行搬迁，行为不变。**
> 它是唯一会让 `send()` 代码位置变化的步骤，必须单独提交、单独回滚。
> 不做这一步，iOS 就得自己持有一份编排逻辑，直接违反"不复制一套 iOS 逻辑"。

**7b UI 选型**（CMP 1.12.0 vs SwiftUI，见 §9）并搭 `iosApp/` 壳。
**验收**：iOS 上能完成一次真实对话（发送 → 流式回复 → 落盘 → 重启后可见）。

### Phase 8 — 图片输入（对齐 #10–12）

`expect ImageCodec`（Android `BitmapFactory` / iOS CoreGraphics）·
`PHPickerViewController` 选图（零权限）· `UIImagePickerController` 拍照（`NSCameraUsageDescription`）·
iOS 版 `ChatImage` 缩略图。
**验收**：iOS 选图 → 发送 → 请求体与 Android 同样走 `image_url` data URL → 缩略图在会话里可见。

### Phase 9 — IP 城市定位（对齐 #13）

只用 https 端点（`ipwho.is` / `ipapi.co`）；`ip-api.com` 的明文 HTTP 端点被 ATS 阻断，需去掉或加例外。
**验收**：设置开关生效，`AmbientContext` 出现"主人大致在：X"。

### Phase 10 — 猫咪动画（对齐 #14）

分层 PNG + `layers.json` 锚点模型的 iOS 渲染；7 情绪 / 6 动作/ `CatAnimation.durationMillis` 共享。
**验收**：iOS 上 `CatView` 的待机与全部动作可复现。

### Phase 11 — `.ikitty` 备份恢复（对齐 #15）

`BackupArchive` 编排逻辑进 commonMain + `expect ZipCodec`（Android `java.util.zip` / iOS 纯 Kotlin ZIP）。
**验收**：`BackupArchiveTest` 11 条在 commonTest 通过；**Android 导出的 `.ikitty` 能在 iOS 导入，反之亦然**。

### Phase 12 — 发布与更新策略（#18）

Android 的应用内 APK 更新在 iOS 不可移植。替代方案：TestFlight / App Store 分发，
最多做"有新版本"提示（只读 GitHub release 的 `tag_name`，不下载）。
**验收**：iOS 有可安装的分发渠道。

---

## 8. iOS 平台依赖清单（完整版）

| 能力 | 方案 | 依赖 | 阶段 |
| --- | --- | --- | --- |
| JSON | `kotlinx-serialization-json` 1.11.0（只用 `JsonElement`） | JetBrains 官方 | 2 |
| 时间 | `kotlinx-datetime` 0.8.0 | JetBrains 官方 | 3 |
| 协程 | `kotlinx-coroutines-core` 1.11.0 | 官方 | 1 |
| 文件 IO | `okio` 3.18.2（**已是 OkHttp 传递依赖**） | Square | 4 |
| 根路径 | `NSFileManager` → `NSApplicationSupportDirectory` | 平台库 | 4/6 |
| HTTP + SSE | `ktor-client-core` + `ktor-client-darwin` 3.6.0 | JetBrains | 6 |
| 设置 | `NSUserDefaults` | 平台库 | 4/6 |
| 应用版本 | `NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString")` | 平台库 | 6 |
| 锁 | `expect fun <T> locked(...)` → `NSLock` | 平台库 | 4 |
| 图片解码/编码 | CoreGraphics + ImageIO（cinterop） | 平台库 | 8 |
| 相册 | `PHPickerViewController`（**零权限**） | 平台库 | 8 |
| 相机 | `UIImagePickerController` + `NSCameraUsageDescription` | 平台库 | 8 |
| ZIP | 纯 Kotlin 读写器（STORED），或 `libz` cinterop | 平台库 / 自研 | 11 |
| Xcode 集成 | `embedAndSignAppleFrameworkForXcode` | Gradle 内置任务 | 6 |

### 8.1 需要注意的 iOS 平台细节

- **`Dispatchers.IO` 在 commonMain 不存在**（7 处用到：`ApiClient`、`ChatLogStore`、`CatMemoryStore`、
  `ImageStore`、`IpLocationSource`、`UpdateClient`、`BackupArchive`）。
  漏改**不会编译报错**，只会悄悄退化成 `Default`。这是最容易静默出错的一处。
- **`NSApplicationSupportDirectory` 不会自动创建**，首次写入前必须显式建目录。
- **ATS 默认阻断明文 HTTP**，影响 `IpLocationSource` 的第一候选端点。
- **`expect/actual` 全有或全无**：一旦加 iOS 目标，所有 `expect` 都必须有 iOS `actual`，
  否则整个 `shared` 编译失败 → 所以 Phase 4 起再加 `expect`，且 Apple 目标推到 Phase 6 一次补齐。

### 8.2 iOS 上不需要的东西

`ApkInstaller` · `UpdateClient` 的 APK 下载 · `FileProvider` · `<queries>` ·
`network_security_config` · `REQUEST_INSTALL_PACKAGES` · `Context` 次构造函数 ·
`preferencesDataStore` 委托 · `allowBackup`。

---

## 9. iOS UI 路线成本（Phase 7 才决定，现在不实现）

现有 Android UI 规模：`CatChatScreen` 619 + `SettingsScreen` 1167 + `CatMemoryScreen` 376 +
`CatView` 957 + `ChatImage` 57 + `MainActivity` 43 ≈ **3219 行**。

### Compose Multiplatform 1.12.0

**收益**：`CatMemoryScreen` 可直接复用（无 Android import）；`CatView` 的 Canvas 逻辑基本可复用
（对 Phase 10 的动画是明显优势）；iOS 需要新写的 UI 最少。
**成本**：
- **会改变 Android 的 Compose 依赖管理方式**——commonMain 必须用 `org.jetbrains.compose.*` 的
  material3，由 CMP 插件在 Android 侧重定向。这是对"保持现有 Android 稳定"的真实扰动点。
- iOS 端是 Skia 渲染：包体增加（数 MB）；中文 IME / 键盘避让 / 橡皮筋滚动 / 动态字体 / 无障碍需逐项实测。
- `rememberLauncherForActivityResult` / `LocalContext` 等需要 `expect/actual` 抽象。

### SwiftUI

**收益**：原生观感 / 键盘 / 滚动 / 无障碍 / 包体 / 调试体验都更好；
**完全不扰动 Android 的依赖树**（与 CMP 的最大区别）。
**成本**：
- UI 全写一遍（iOS 完整功能下约 1500–2000 行 SwiftUI，因为包含图片与动画）；
- 桥接细节：`sealed interface` → Swift 类层次；`data class` → class；
  `StateFlow` 需手写订阅（或用 SKIE，属第三方，与"不引不必要框架"冲突）；
  `suspend` 函数在 Kotlin 2.x 下可直接映射 Swift `async/await`（这部分免费）；
- Phase 10 的猫咪动画要用 `Canvas`/`CALayer` 重写。

**建议**：Phase 1–6 与 UI 选型完全无关。真正决定因素是"是否愿意为了不扰动 Android 依赖树
而多写约 2000 行 SwiftUI"。我倾向 **SwiftUI**（Android 零扰动是本项目的首要约束），
但 CMP 对 Phase 8 和 Phase 10 的复用收益确实可观。**Phase 7 再定。**

---

## 10. 风险

### 阻断级

- **R1 `xcode-select` 未切换**（实测）。Xcode 27.0 已装但未生效，`xcodebuild` / `simctl` 不可用。
  需 `sudo`，只能由你执行。**这是当前唯一的硬闸门。**
- **R2 Xcode 27.0 超出 Kotlin 2.4.20 的支持范围（26.4）**。Xcode 27 的正式支持要等 Kotlin 2.5.0。
  缓解：Phase 0.5 用探测决定走 (b) 直接用 27.0 还是 (a) 补装 26.4。

### 高

- **R3 版本升级破坏 Android 构建**。现状 KGP 2.0.21 在 Gradle/AGP/Xcode 三项上全部越界，
  升级是必须的。缓解：Phase 0.4 独立提交、独立回滚；**AGP 不用动**（8.7.3 在 2.4.20 的支持区间内），
  升级面比预想小；Compose BOM 可能需小幅上调，必须实测。
- **R4 `org.json` → kotlinx.serialization 改变线/盘格式**。缓解：坚持 builder 风格而非 `@Serializable`；
  金标准字节比对；85 个 commonTest 锁死。
- **R5 ZIP 编解码是"完整功能"下新增的最大平台工作量**（Apple 无 `java.util.zip`，okio 不能写 zip）。

### 中

- **R6 迁移顺序不能照 `docs/DOC.md` §1.2 的清单走**（传递依赖，见 §4.1）。
- **R7 `Dispatchers.IO` 静默降级**（7 处，漏改不报错）。
- **R8 路径兼容**：`filesDir/chat/*` 漂移 = 老用户数据"消失"。
- **R9 `ChatEngine` 抽取零测试兜底**（`CatChatViewModel` 无覆盖）。
- **R10 iOS 图片管线 + 相机权限流程**的实测成本可能超预期。
- **R11 模型可见输出漂移**：`formatMoment` 进 prompt 却无独立测试。

### 低

- R12 iOS 构建复杂度（Kotlin/Native 首次下载 1–2 GB、Xcode 集成）
- R13 `app ↔ shared` 依赖方向腐化（用工具固化单向）
- R14 混合 transport（Android OkHttp + iOS Ktor）的语义一致性需由契约收敛
- R15 Gradle 9.7.1 已警告"与 Gradle 10 不兼容"（本计划降到 9.7.0，更长远的升级另立）

---

## 11. 需要你做出的技术决策

| # | 决策 | 我的建议 | 影响 |
| --- | --- | --- | --- |
| **1** | 执行 `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` | 必须 | 当前唯一硬闸门 |
| **2** | Xcode 27.0 直接试，还是补装 26.4？ | **先试 27.0**（Phase 0.5 探测），失败再补装 | 决定 Phase 6 能否开始 |
| **3** | "完整功能"是否包含 Live2D / 语音 / TTS？ | **不包含**（Android 也没有，属独立新功能） | 决定是否新增立项 |
| **4** | 是否接受 Phase 7a 抽取 `ChatEngine`？ | **接受** | 不接受则 iOS 必须自持一份编排逻辑，违反"不复制"原则 |
| **5** | iOS UI：CMP 1.12.0 还是 SwiftUI？ | **Phase 7 再定**，我倾向 SwiftUI | 决定 Android 依赖树是否被扰动 |
| **6** | iOS HTTP：Ktor Darwin？ | **是**（3.6.0） | cinterop 自己做 `NSURLSessionDataDelegate` 成本高得多 |
| **7** | `.ikitty` 的 iOS ZIP：纯 Kotlin STORED 还是 `libz` cinterop？ | **纯 Kotlin STORED** | 决定能否跨端互导 |
| **8** | Compose BOM 是否允许小幅上调？ | 若编译器报错则允许 | 升级 KGP 的必要连带 |

---

## 12. 一页速查

| 问题 | 结论 |
| --- | --- |
| Xcode 就绪了吗 | **没有**。27.0 已装，但 `xcode-select` 仍指向 CommandLineTools |
| 现在能编 iOS 吗 | 不能，差上面那一条命令 + Kotlin/Native 首次下载 |
| 该升到哪个 Kotlin | **2.4.20**（稳定版）；Xcode 支持到 26.4 |
| AGP 要升吗 | **不用**。8.7.3 落在 2.4.20 的 8.5.2–9.3.1 区间内 |
| Gradle 要动吗 | 9.7.1 → **9.7.0**（超上限 1 个补丁） |
| 升级时机 | **Phase 0.4**，独立提交、独立回滚；不推迟到 Phase 6 |
| 零改动可迁的文件 | 5 个 + 1 个 5 行 helper |
| 最终可跨平台比例 | 约 3000 / 6066 行（50%），25 个文件 |
| 测试可迁比例 | 85 / 94 用例（90%） |
| 最大新工作量 | 图片 CoreGraphics 管线 + ZIP 编解码 |
| 最大技术风险 | `org.json` → kotlinx.serialization 改变线/盘格式 |
| 最容易静默出错 | `Dispatchers.IO`（7 处，漏改不报错） |
| Provider/ModelCatalog 兼容风险 | **零**（不是持久化格式，是同一份代码） |
| 唯一触及聊天编排的步骤 | Phase 7a 的 `ChatEngine` 抽取（移动，非重写） |
| 不可移植的功能 | 应用内 APK 更新（iOS 走 App Store / TestFlight） |
| 第一步 | **你执行 `xcode-select`**，然后 Phase 0.2–0.5 |
