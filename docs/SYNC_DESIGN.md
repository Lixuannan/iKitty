# iKitty 云端同步设计（Cloudflare D1 + R2）

> 返回 [README](../README.md) · 相关：[详细设计文档](DOC.md)

本文定义 iKitty 的云端聊天记录同步：数据格式、协议、冲突模型、迁移步骤与验收标准。
面向要修改同步代码或部署 Worker 的人。

- 服务端：Cloudflare Worker（无状态）+ D1（SQLite）+ R2（图片对象）
- 客户端：`:shared`（Android 与 iOS 共用），直接 HTTPS 访问 Worker
- 版本：协议 `proto = 1`

---

## 1. 目标与模型

**模型：云端权威（cloud-authoritative），本地为缓存 + 待推队列。**

- 云端（D1）是消息顺序与内容的唯一权威。
- 本地 `chat_log.jsonl` 是"云端快照 + 尚未推送的本地消息"。
- 拉取时本地日志被**整体替换**；替换内容是"云端返回的全部消息 + 本次仍未被服务端确认的本地消息"。
- 因此**不需要**：`origin_device` 命名空间、全序合并算法、LWW 合并、版本向量。
- 但**需要墓碑**：删除必须显式告诉其他设备。增量游标让"这次响应里没有某条消息"
  既可能是被删了、也可能是它属于更早的一页——靠后者推断删除会把正常历史全部误删。

这与 v1 设计的差异集中在一点：不再假定"两台设备同时离线写入"。代价是两台设备都在离线状态下
各写各的时，后同步的一方排后面（接收顺序）；收益是客户端**没有一行合并代码**。

### 1.1 与备份的关系

`.ikitty` 备份（[BackupArchive](../shared/src/commonMain/kotlin/com/codingcow/ikitty/BackupArchive.kt)）
是**整份覆盖**语义，与同步的"云端快照覆盖本地"完全一致。备份仍然独立存在：它是用户自己保管的
离线副本，同步是云端副本。备份格式不变（`images` 仍是文件名列表，只是名字换成内容哈希）。

---

## 2. 架构

```
Android (OkHttp) ─┐
                  ├─ HTTPS ─→ Cloudflare Worker ─→ D1 (messages/kv/images)
iOS (Ktor Darwin) ─┘                       └─→ R2 (图片对象, 私有 bucket)
```

Worker 是必须的一层：D1 只能通过 Worker binding 访问，R2 需要私有 bucket，且必须在服务端做
鉴权、限流与体积校验。但它**无状态**：没有定时任务、没有队列、没有会话，任意实例都能处理任意请求。

---

## 3. 数据模型

```sql
CREATE TABLE messages (
  account_id  TEXT    NOT NULL,           -- sha256(account_key)，原始 key 永不落盘
  msg_id      TEXT    NOT NULL,           -- 客户端生成的 UUID，幂等键
  seq         INTEGER NOT NULL,           -- 服务端分配的全局序号，唯一排序依据
  role        TEXT    NOT NULL,
  content     TEXT    NOT NULL,
  created_at  INTEGER NOT NULL,
  local_error INTEGER NOT NULL DEFAULT 0,
  images      TEXT,                       -- JSON 数组，元素是 image_id
  rev         INTEGER NOT NULL,           -- 服务端分配的修订号，pull 游标
  PRIMARY KEY (account_id, msg_id)
);
CREATE UNIQUE INDEX idx_messages_seq ON messages(account_id, seq);
CREATE INDEX idx_messages_rev ON messages(account_id, rev);

CREATE TABLE kv (
  account_id TEXT    NOT NULL,
  key        TEXT    NOT NULL,            -- 'settings'
  payload    TEXT    NOT NULL,
  updated_at INTEGER NOT NULL,            -- 客户端墙钟，LWW 判据
  rev        INTEGER NOT NULL,
  PRIMARY KEY (account_id, key)
);
CREATE INDEX idx_kv_rev ON kv(account_id, rev);

-- 删除是显式的墓碑，不是"这一页没返回它"。
CREATE TABLE deleted (
  account_id TEXT    NOT NULL,
  msg_id     TEXT    NOT NULL,
  rev        INTEGER NOT NULL,
  PRIMARY KEY (account_id, msg_id)
);
CREATE INDEX idx_deleted_rev ON deleted(account_id, rev);

CREATE TABLE images (
  account_id TEXT    NOT NULL,
  image_id   TEXT    NOT NULL,            -- img_<sha256 前 32 位 hex>.jpg
  bytes      INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (account_id, image_id)
);
```

`rev` 与 `seq` 都是**服务端在一个 `batch()` 事务里分配**的：先 `SELECT MAX(...)` 取当前值，
再按批内顺序递增。D1 的 `batch()` 是事务、顺序执行、不并发，因此不会串号。

三张表的存在理由：

| 表 | 为什么需要 |
| --- | --- |
| `messages` | 增量拉取的游标 `rev` 与全局序 `seq` 都需要有地方落 |
| `kv` | 设置是**可变**状态，与只追加的消息语义不同 |
| `deleted` | 增量拉取分不清"被删了"和"不在这一页"，所以删除必须留一条显式记录 |
| `images` | 图片对象在 R2，D1 只留 id 与字节数，用于清单与配额 |

**不需要 `accounts` 表**：`account_id` 由 key 哈希推导，没有账号行需要创建，首次 push 即开户。
**不需要 `devices` 表**：设备只体现在"谁生成的 msgId"里，服务端不关心。

---

## 4. 协议

所有请求：`Authorization: Bearer <account_key>`，`Content-Type: application/json`。

### 4.1 `POST /sync` — 推拉合一

请求：

```json
{
  "proto": 1,
  "deviceId": "8f3a…",
  "sinceRev": 120,
  "pullLimit": 500,
  "messages": [
    { "msgId": "…", "seq": 57, "role": "user", "content": "…",
      "createdAt": 1774000000000, "localError": false, "images": ["img_ab…jpg"] }
  ],
  "kv": [
    { "key": "settings", "payload": "{…}", "updatedAt": 1774000001000 }
  ],
  "deleted": [ { "kind": "message", "msgId": "…" } ],
  "tombstonesOnly": false
}
```

响应：

```json
{
  "proto": 1,
  "rev": 178,
  "applied": 2,
  "rejected": [ { "msgId": "…", "reason": "empty_content" } ],
  "assigned": { "…": 178 },
  "pull": {
    "sinceRev": 120, "rev": 178, "headRev": 178, "hasMore": false,
    "messages": [ … ], "kv": [ … ],
    "deleted": [ { "msgId": "…", "rev": 173 } ],
    "images": [ { "imageId": "img_ab…jpg", "bytes": 204800 } ]
  }
}
```

要点：

- **`messages[].seq` 是客户端草稿值**，服务端重新分配权威 `seq` 并通过 `pull.messages[].seq` 回传；
  客户端不信任自己推上去的 `seq`。
- **`assigned`** 把 `msgId` 映射到服务端分配的 `seq`。客户端只在**没有立即拉到该消息**时才需要它
  （正常路径下 `pull` 里就会带上）。两者取其一即可，保留 `assigned` 是为了让"推成功但拉响应被截断"
  的情况下本地仍能填上真实序号，不至于倒退。
- `rejected` 逐条回报，坏记录不会让整批失败。
- 413 时客户端**真的把批砍半**再发（两半各自重试），并且只把服务端收下的那些记为已推——
  如果只记最后一次的结果，被拆出去的那半批会既没上云、又再也不会被重推。
- `mutations` 为空时退化为纯拉取。
- `images` 是**全量清单**（只有 id 和字节数，几千张也只有几十 KB），客户端据此算出缺哪些图。
- `deleted` 是墓碑。墓碑的 `rev` 会随着时间落到客户端游标后面，所以客户端在每次
  **下载**时都会补一趟 `tombstonesOnly: true`，让服务端把所有墓碑重新说一遍
  （这种请求不推进游标）。没有它，很久以前被别的设备删掉的消息会一直留在本机。

### 4.2 `POST /sync/deleteAll`

清空该账号的全部消息、kv 与图片登记。**不可撤销**：没有账号找回，也没有回收站。
客户端必须在 UI 上二次确认。

### 4.3 `POST /sync/image` — 上传

`Content-Type: image/jpeg`，请求体是原始 JPEG 字节。路径与 id 由服务端根据**内容哈希**校验：
客户端声明的 `X-Image-Id` 必须等于服务端算出的 `sha256(body)` 前 32 位，否则 400。
这样 id 与内容强绑定，图片天然去重，且客户端无法塞入不一致的对象。

上传幂等：同一个 id 重复上传直接覆盖，`INSERT OR IGNORE` 保证 D1 只有一行。

### 4.4 `GET /sync/image?id=…` — 下载

返回原始字节，`Cache-Control: private, max-age=31536000, immutable`（内容寻址，可永久缓存）。

### 4.4.1 账号密钥

`account_id = sha256(key)`，而服务端唯一的校验是**长度 ≥ 5**（`MIN_KEY_LENGTH`）。

下限刻意设得很低：这是自托管应用，用户想用一句好记的短口令是他自己的选择，服务端拒绝只会
让他换一个更长但同样弱的串。但代价必须说清，因为它决定了这份数据能不能被别人读到：

> `account_id` 是 key 的哈希，而哈希**离线可枚举**。5 位小写字母数字只有约 6000 万种，
> 任何人扫一遍就能找出真实存在的账号，进而读走聊天记录、以及明文存在 D1 里的 API Key。

所以强度由**界面**负责：低于 16 位（`RECOMMENDED_ACCOUNT_KEY_LENGTH`）时明确写出后果，
并提供一个「生成随机密钥」按钮（32 字节 → base64url，约 43 字符、256 bit）。
两端各自实现这个按钮，因为"随手生成一个随机串"本来就是各平台一行 API 的事；
阈值与文案口径则由 `SyncKeyStrength` 统一定义，避免两端说不同的标准。

客户端会在本地先挡掉低于下限的输入，并直接告诉你"现在是几位"——服务端那条 401 的文案是
"密钥无效或已失效"，用户只会以为同步坏了。

### 4.5 错误约定

| 状态码 | 含义 | 客户端行为 |
| --- | --- | --- |
| 400 | 请求格式/内容校验失败 | 不重试，报错并记录 |
| 401 | account_key 无效 | 清除凭据，提示重新填写；**不删本地数据** |
| 413 | 单批过大 | 批大小对半拆分重试；只剩一条仍被拒时明确报"有一条消息太大"，并保留在本地 |
| 429 | 触发限流 | 尊重 `Retry-After`，指数退避 |
| 5xx | 服务端暂时不可用 | 指数退避 + 抖动，最多 3 次 |

---

## 5. 图片

### 5.1 为什么是 R2 而不是 base64

| | R2 | base64 进消息 |
| --- | --- | --- |
| D1 单行上限 2 MB | 不涉及 | 一张 200 KB 图 → 267 KB，8 张就顶穿；D1 单条 SQL 上限 100 KB |
| 增量同步 | 图片独立，**拉一次永久缓存** | 图片跟着消息走，每次拉取都重下 |
| 去重 | 内容寻址，同一张图只存一份 | 每条消息各存一份 |
| 淘汰 | 可独立回收 | 只能连消息一起删 |

### 5.2 命名与迁移

- 新图文件名：`img_<sha256 前 32 位 hex>.jpg`，**哈希即 id**，本地文件名就是云端 id，不需要映射表。
- 旧图（`img_<随机 16 位 hex>.jpg`）：首次同步时对 `chat/images/` 下每个文件算哈希并重命名，
  同时改写日志里引用它的 `images` 字段。日志改写与图片重命名必须**同一批**完成，否则会丢引用。

### 5.3 时序

```
选图 → 立刻后台上传 POST /sync/image（与消息顺序无关，内容不可变）
发送消息 → 本地落盘（images 里是已经可用的 image_id）
点「上传到云端」→ POST /sync 推消息与设置（首次对账会先探问一次云端有没有设置）
点「从云端下载」→ POST /sync 拉取 → 对比 pull.images 与本地文件，缺的下 GET /sync/image 补齐
```

图片上传失败**不阻塞**消息发送：消息照常落盘与上传，图片在后续上传里补传。

---

## 6. API Key 同步开关

设置项 `sync_api_key`（默认 **关**）。

- **开**：`kv['settings']` 的 payload 里带 `apiKey`。新设备同步后直接可用。
- **关**：payload 里**不含** `apiKey` 字段。

开关只决定"一份要推上去的设置里带不带 Key"，而设置的指纹**刻意不含 Key**（否则开关关着时
改 Key 也会触发一次只写同样内容的推送）。所以"只把开关打开"在指纹上看不出变化，需要另一层
判断：

- 客户端持久化一个 `sync_cloud_settings_has_api_key`，记录**云端那份当前有没有 Key**
  （推成功时按本次 `includeApiKey` 写，拉到云端设置时按 payload 实际字段写）。
- push 时，除了指纹变化，只要**开关开着且云端还没有 Key**，就补推一次。

因此"打开开关"这一动作本身能把一直没上过云的那把 Key 推上去。反过来：

- **关掉开关不会主动推一次**，云端已有的 Key 因此不会被这一动作立刻抹掉；
- 但如果随后因为别的设置变化推了一份设置，payload 是不带 Key 的**整份替换**，云端那份
  也就没有 Key 了（服务端只做整行替换，不合并字段）。也就是说"保留云端原值"只在
  "关掉开关后没有再推设置"时成立——把它当成"关掉开关就能从云端撤下 Key"更准确。

README 的隐私章节必须写明：开启后 API Key 以**明文**存放在 D1 中，Cloudflare 侧可读。

### 6.1 设置的首次对账

设置（模型服务、角色名字/性格/风格/补充设定、定位开关）整体存在 `kv['settings']` 里，
服务端按 `updated_at` 做 LWW。这条 LWW 有一个反直觉的后果，曾经造成"名字和 API Key
每换一台设备就没了"：

> 一台全新设备的墙钟**一定比另一台设备上一次同步更晚**。如果它同步时先把自己的
> 默认设置推上去，LWW 必然判它赢——云端那份真实设置被默认值覆盖。

手动模式下的规则是：**还没与这个云空间对过设置的账时，上传只推消息，并单独问一次云端有没有
设置**（`SyncEngine.fetchCloudSettings`，`sinceRev` 固定传 0、不推进游标）：

- 云端有设置 → 本机这份一个字段都不推，只回报一句"先「从云端下载」再上传"。用户下载之后
  本机就已经是云端那一份，之后的改动再上传才是正常的 LWW。这种情况下
  `SETTINGS_SYNCED` **保持未置上**，于是之后每一次上传都会再探问一次，直到用户真的下载过一次；
- 云端没有设置 → 本机这份就是唯一一份，推上去（默认值也无所谓，云端本来就是空的），
  并把 `SETTINGS_SYNCED` 置上。

这一问不能省成"顺手看一眼上传请求的响应"：本机没有待推消息时那一趟**根本不发请求**，
"云端有设置"与"什么都没问"会得到同一个 null，新设备的默认值照样会覆盖云端那份。

`SETTINGS_SYNCED` 在下载成功之后也会置上——下载完就已经知道云端那份长什么样了。
它随 `clearSyncState` 在换账号或清空云端时归零。

代价是空云空间、或本机没有待推消息时的**第一次**上传会多发一个探问请求。

---

## 7. 客户端设计

新增于 `commonMain`：

| 文件 | 职责 |
| --- | --- |
| `SyncModels.kt` | 线上模型 + JSON 编解码（显式 builder，与 `JsonSupport` 一致，不用 `@Serializable`） |
| `SyncApi.kt` | 端点路径、请求头、状态码→`SyncException` |
| `SyncEngine.kt` | 同步编排：上传待推（含首次设置对账）、下载并整体替换本地日志、图片补齐 |
| `SyncCoordinator.kt` | `SyncFacade`：两个手动入口 `push()` / `pull()`、状态翻译、同步后重读引擎 |
| `SyncCredentials.kt` | 基于 `KeyValueStore` 存 `account_key` / `install_id` / `since_rev` / 开关 |
| `SyncCredentialWriter.kt`（iosMain） | 凭据的**同步写入**：不挂起、不等网络，写完立刻在后台同步一次 |

两个平台的 `KeyValueStore` 读的必须是**同一批键名**，清单在 commonMain 的
`StoredKeyRegistry`。这里踩过一次真实的坑：iOS 的 `UserDefaultsKeyValueStore`
自己列了一份只有 `SettingsKeys` 的清单，于是同步凭据写得进 `NSUserDefaults`、
永远读不回来——设置页回显空白、`isConfigured()` 恒为 false，整个云端同步都用不了。
后来加进来的每类键（`SyncKeys`）都必须同时进这份清单。

同步复用**同一个** `HttpTransport` 实例（图片要 `postBytes` / `getBytes`，聊天只用 JSON 那三个
方法，所以 `ApiClient` 的字段是更窄的 `JsonHttpTransport`）。该实例由平台层在装配处同时喂给
两者：让 `ChatEngine` "顺带"提供一个可能不满足完整契约的对象，只会在运行期才炸，
而这个错误应该在编译期暴露。测试里换成 [FakeSyncServer](../../shared/src/commonTest/kotlin/com/codingcow/ikitty/FakeSyncServer.kt)
——它实现了完整的 `HttpTransport`，同时按协议扮演服务端。

### 7.1 本地落盘的唯一改动

`StoredMessage` 增加 `msgId`（写盘字段 `id`）。`seq` 变成"草稿序号"：本地分配、同步后被
服务端值覆盖。缺失 `id` 的老记录在**首次同步时**补一个随机 UUID 并回写，之后不再变化。

`ChatLogStore` 增加 `replaceAll(messages)`：写临时文件 → 原子重命名。这是拉取路径唯一的重写，
本地发送路径仍然是 append，读路径完全不变。

### 7.2 触发：只有两个手动按钮

同步没有任何自动触发。设置页里只有两个入口，各自等自己那一轮结束：

- 「上传到云端」→ `SyncFacade.push()`：把本机还没推上去的消息与设置交给服务端，**不拉取**；
- 「从云端下载」→ `SyncFacade.pull()`：拉云端快照、整体替换本地日志、继承设置、补齐图片，**不上传**。

两个动作都会**等这一轮结束**（带超时兜底）才收工，期间整页盖一块阻塞的进度动画
（Android 是 `Dialog` 遮罩，iOS 是整页 `.overlay`），按钮与返回键都被挡住。用户点完就知道
成功还是失败。等的是这一轮的终态**返回值**，不是"状态变了"：没配服务地址时前后都是同一个
`Disabled`，StateFlow 不会重新发射，"等状态变化"会一直等到超时——那正是"点按钮就卡死"的来源。

**落盘与同步必须是两件事**。门面里的三个 setter（地址、密钥、开关）都只写本地存储，
不发起网络请求；一次上传或下载只能由调用方在凭据**全部写完之后**显式触发。两个按钮因此都
先 `await` 三个写入、再发起这一轮——分成两个入口迟早会漂移：用户改完地址直接点上传，请求
就发到上一次保存的旧地址去了。

设置页的「保存」与「返回」只落盘、不碰网络，关闭页面不会挂在一次往返上。没有系统级后台调度
（`WorkManager` / `BGTaskScheduler`），也没有实时同步：另一台设备的新消息要等本机自己点
「从云端下载」才出现。

上传与下载之所以不合成一次往返：拉取会整体替换本地日志，把它藏在"上传"里，会让一次本该只写
云端的操作顺带改写本机。

### 7.3 不变量

> 本地落盘永远是本地数据的权威；任何同步失败都**不删除**本地数据。
> 只有一次**成功的完整拉取**才会替换本地日志。

---

## 8. 测试

| 层 | 覆盖 |
| --- | --- |
| Worker（`worker/test/local-check.mjs`） | 用 `node:sqlite` 跑真实 SQL：建表、幂等、LWW、增量分页、墓碑、图片哈希校验、账号隔离 |
| `commonTest`（`SyncEngineTest`） | `SyncEngine` 对着一个按协议实现的假服务端跑完整链路：上传 → 服务端分配序号 → 下载 → 整体替换；以及墓碑、待推不丢、设置开关、401、重试、首次对账 |
| `commonTest`（`SyncIntegrationTest`） | 验收标准 1、2 与 7：两个真实的 `ChatEngine` + `createSyncFacade`，各自的文件系统与设置存储，经由同一个假服务端收敛到同一份记录（含设置与 API Key 开关）；以及"新设备墙钟更晚时上传不会覆盖云端设置、下载后继承云端设置" |
| `commonTest`（`SettingsPersistenceTest`） | 验收标准 8：设置保存在**只发射一次**的存储上也能更新引擎内存状态、分步保存不会互相覆盖、一次保存只写一次 |
| `commonTest` | 协议编解码、图片 id 形状与拒绝规则 |
| `iosTest`（`IosPlatformTest`） | `NSUserDefaults` 的真实读写：设置往返、绕过 `put` 的写入仍能读到、`put` 之后 `values` 重新发射 |
| 手工 | 双端（Android + iOS）交替改、交替同步，验证顺序与图片补齐 |

跑法：

```bash
./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test
cd worker && node test/local-check.mjs
```

对**真实部署**的冒烟清单（每次改完 Worker 都值得跑一遍，全部用 curl + 一个临时账号密钥）：

1. `GET /health` → 200；无 `Authorization` 的 `POST /sync` → 401；
2. 首次 push → 返回 `assigned`，`pull.messages` 里是服务端分配的 `seq`；
3. 换一个 `deviceId`、`sinceRev=0` 再拉 → 拿到同一批消息（跨设备）；
4. 重复推同一条 `msgId` → 只有一行，`seq` 不变；
5. 上传一张图：id 与内容哈希一致 → 200，不一致 → 400；`GET /sync/image?id=…` 的字节与上传一致；
6. `pull.images` 里出现该图；
7. `kv['settings']`：旧 `updatedAt` 不能覆盖新值，白名单外的 key 被拒；
8. 删除一条消息 → `pull.deleted` 给出墓碑；游标越过之后增量拉取拿不到它，
   而 `tombstonesOnly: true` 能拿到且**不推进** `rev`；
9. `POST /sync/deleteAll` → 三张表（`messages` / `kv` / `images`）清空；
10. 换一个账号密钥 → 看不到任何数据。

---

## 9. 验收标准

1. 设备 A 发 N 条消息（含图片）→ 点「上传到云端」→ 设备 B 点「从云端下载」后，
   消息顺序、内容、图片与 A 一致。
2. 设备 B 离线发消息 → 点「上传到云端」→ A 点「从云端下载」后能看到 B 的消息，
   且 A 本地还没上传的消息不丢。
3. 关闭 API Key 开关后，设置上传的 payload 里不含 `apiKey`，且**关闭开关这一动作本身不发请求**
   （不会立刻抹掉云端已有的 Key）；打开开关后，若云端还没有 Key，下一次「上传到云端」
   会把本机的 Key 补上去。
4. 上传或下载过程中断网：本地日志、图片、设置均不被破坏；恢复网络后可重试。
5. 账号密钥填错 → 401 → 给出明确提示，本地数据完好。
6. `deleteAll` 后云端三张表清空，且本地数据不受影响。
7. 新设备（墙钟比另一台设备更晚、本机是默认设置）点「上传到云端」不会覆盖云端设置，
   点「从云端下载」之后继承云端的角色名字、性格、说话风格、补充设定与 API Key。
8. 两端保存设置（API Key、名字、性格…）后立刻生效、重启不回退；连续分步修改设置
   不会让后一次把前一次刚写的字段冲回去（iOS 的 `NSUserDefaults` 路径尤其要盯）。

---

## 10. 部署

```bash
cd worker
npx wrangler login
npx wrangler d1 create ikitty-sync          # 输出 database_id 填入 wrangler.toml
npx wrangler r2 bucket create ikitty-images
npx wrangler deploy                         # 输出形如 https://ikitty-sync.<子域>.workers.dev
```

部署完之后在应用的设置页里填**两样**：Worker 地址与一个自己生成的账号密钥
（例如 `openssl rand -base64 32`）。密钥不在应用里生成、也不在服务端登记：
服务端只保存它的 SHA-256（当作 `account_id`），所以谁也不知道你的密钥是什么，
丢了也没有找回——这是"没有账号体系"的必然代价。

首次带鉴权的写入会自动建表（`CREATE TABLE IF NOT EXISTS`），所以不需要单独的 migrate 步骤；
`schema.sql` 保留下来是为了让表结构有一份可读、可复查的独立记录。

`wrangler.toml` 里的 `ACCOUNT_ID` 推导自 key 哈希，无需配置。唯一的密钥是
`RATE_LIMIT_PER_MINUTE` 之类的可调项，直接写在 `[vars]` 里。
