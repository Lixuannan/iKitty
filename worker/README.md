# iKitty 云端同步 Worker

把 iKitty 的聊天记录与图片同步到**你自己的** Cloudflare 账号：D1 存消息与设置，R2 存图片。
无状态、无定时任务、无会话，任意实例都能处理任意请求。协议见 [../docs/SYNC_DESIGN.md](../docs/SYNC_DESIGN.md)。

## 部署

```bash
cd worker
npx wrangler login

npx wrangler d1 create ikitty-sync
# 把输出的 database_id 填到 wrangler.toml 的 database_id

npx wrangler r2 bucket create ikitty-images
npx wrangler deploy
```

**当前这份配置已经部署过**（`database_id` 已回填）：

| | |
| --- | --- |
| Worker 地址 | `https://ikitty-sync.leecodingcow.workers.dev` |
| D1 database | `ikitty-sync` · `fd26369f-51e3-4795-bc19-63ce4bb1057d` · 区域 WEUR |
| R2 bucket | `ikitty-images` |

换一个人部署时：把 `wrangler.toml` 里的 `database_id` 换成自己 `d1 create` 的输出即可
（`wrangler.toml` 里的 database id 是部署细节，不是机密；账号密钥才是）。

把上面的 Worker 地址填进应用设置页的「同步服务地址」，再填一个自己生成的账号密钥
（例如 `openssl rand -base64 32`）。

**账号密钥就是全部凭据**：服务端只保存它的 SHA-256（当作 `account_id`），
所以没有注册、没有找回，丢了就等于这份云端数据打不开了（本机数据不受影响）。

服务端对它的唯一校验是 **长度 ≥ 5**（源码里的 `MIN_KEY_LENGTH`）。这个下限很低是有意的：
好记的短口令也是合法选择。但请注意代价——**`account_id` 是 key 的哈希，而哈希可以被离线枚举**。
5 位小写字母数字只有约 6000 万种，扫一遍就能找出真实存在的账号，读走聊天记录与
明文存放的 API Key。应用会在密钥偏短时警告并提供「生成随机密钥」；**不设防的云端就不要再勾
"把 API Key 一并同步"**。

表结构不需要单独 migrate：第一次带鉴权的请求会自动建表。
`schema.sql` 是同一份结构的可读记录，改动它时必须同步改 `src/index.js` 里的 `SCHEMA` 常量。

## 本地验证

不需要 Cloudflare 账号，用 Node 内建的 `node:sqlite` 当 D1、用一个 Map 当 R2，
跑真实的 SQL 与完整路由：

```bash
node test/local-check.mjs
```

覆盖：建表、`msg_id` 幂等、`kv` 的 LWW 边界、增量分页与 `rev` 推进、墓碑、
图片内容哈希校验、跨账号隔离、`deleteAll`。

部署之后还可以对**真实 Worker** 跑一遍冒烟（无鉴权 401、推送与分配、跨设备拉取、幂等、
图片上传/下载字节一致、`tombstonesOnly`、`deleteAll`、账号隔离），
见 [docs/SYNC_DESIGN.md](../docs/SYNC_DESIGN.md) 的测试小节。

## 可调项

都在 `wrangler.toml` 的 `[vars]` 里，改完重新 `deploy` 即可：

| 变量 | 默认 | 作用 |
| --- | --- | --- |
| `rate_limit_per_minute` | 60 | 每个账号每分钟的请求数上限，超出返回 429 + `Retry-After` |
| `max_body_bytes` | 1048576 | 单次 JSON 请求体上限，超出返回 413（客户端据此拆批） |
| `max_batch_messages` | 500 | 单批消息条数上限 |
| `max_image_bytes` | 2097152 | 单张图片上限（归一化后的 JPEG 约 200 KB） |

限流状态存在**实例内存**里，所以是"每实例"而不是"全局"的精度。要做精确的全局限流就得引入
Durable Object 或 KV，那会推翻"无状态"这个前提；对单用户的同步流量来说这个精度足够。

## 数据与隐私

- 不做任何日志上报；Workers Logs 里只有错误栈，没有聊天正文。
- R2 bucket 保持私有，对象通过 Worker 校验账号后转发，不暴露公网地址。
- `POST /sync/deleteAll` 会清空该账号的 D1 数据。R2 里的孤儿对象不在这里删——
  误删用户照片是不可逆的，交给 R2 生命周期规则回收更稳妥。
