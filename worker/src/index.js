/**
 * iKitty 云端同步的后端：Cloudflare Worker + D1（消息与 kv）+ R2（图片对象）。
 *
 * 设计见仓库根目录的 docs/SYNC_DESIGN.md。这里只有四件事：
 * 1. 鉴权：把 Bearer key 哈希成 account_id（原始 key 永不落盘）；
 * 2. 分配：在一次 D1 batch 事务里给本批记录分配全局 seq 与 rev；
 * 3. 转发：图片原样进 R2，id 由内容哈希校验；
 * 4. 限流：Worker 无状态，所以限流只能按实例内存做，作用是把明显的滥用挡在 D1 之前。
 *
 * 刻意保持无状态：没有会话、没有队列、没有定时任务，任意实例都能处理任意请求。
 */

/** 与 worker/schema.sql 必须逐字一致；改动要同时改两处。 */
const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS messages (
    account_id  TEXT    NOT NULL,
    msg_id      TEXT    NOT NULL,
    seq         INTEGER NOT NULL,
    role        TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    local_error INTEGER NOT NULL DEFAULT 0,
    images      TEXT,
    rev         INTEGER NOT NULL,
    PRIMARY KEY (account_id, msg_id)
  )`,
  `CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_seq ON messages(account_id, seq)`,
  `CREATE INDEX IF NOT EXISTS idx_messages_rev ON messages(account_id, rev)`,
  `CREATE TABLE IF NOT EXISTS kv (
    account_id TEXT    NOT NULL,
    key        TEXT    NOT NULL,
    payload    TEXT    NOT NULL,
    updated_at INTEGER NOT NULL,
    rev        INTEGER NOT NULL,
    PRIMARY KEY (account_id, key)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_kv_rev ON kv(account_id, rev)`,
  `CREATE TABLE IF NOT EXISTS deleted (
    account_id TEXT    NOT NULL,
    msg_id     TEXT    NOT NULL,
    rev        INTEGER NOT NULL,
    PRIMARY KEY (account_id, msg_id)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_deleted_rev ON deleted(account_id, rev)`,
  `CREATE TABLE IF NOT EXISTS images (
    account_id TEXT    NOT NULL,
    image_id   TEXT    NOT NULL,
    bytes      INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (account_id, image_id)
  )`,
];

/** 只有设置会同步。记忆将来加一个 key 即可，不用改协议。 */
const ALLOWED_KV_KEYS = new Set(['settings']);

const MESSAGE_ROLES = new Set(['user', 'assistant']);

let schemaReady = false;

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (error) {
      // 不把内部错误细节回给客户端：它既没有用，又可能带出 SQL 片段。
      console.error('sync_failed', error?.stack ?? String(error));
      return json(500, { error: 'internal_error' });
    }
  },
};

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, '');

  if (path === '/health') return json(200, { ok: true });

  const accountId = await accountIdFrom(request);
  if (!accountId) return json(401, { error: 'invalid_key' });

  const limited = rateLimited(accountId, env);
  if (limited) {
    return json(429, { error: 'rate_limited', retryAfterSeconds: limited }, { 'Retry-After': String(limited) });
  }

  await ensureSchema(env);

  if (path === '/sync' && request.method === 'POST') return handleSync(request, env, accountId);
  if (path === '/sync/deleteAll' && request.method === 'POST') return handleDeleteAll(env, accountId);
  if (path === '/sync/image' && request.method === 'POST') return handleImageUpload(request, env, accountId);
  if (path === '/sync/image' && request.method === 'GET') return handleImageDownload(url, env, accountId);

  return json(404, { error: 'not_found' });
}

// ---- 鉴权与限流 ----

/**
 * 账号密钥就是全部凭据：没有账号表，`account_id` 直接由 key 哈希推导。
 *
 * 长度下限刻意设得很低（[MIN_KEY_LENGTH]），因为这是自托管应用：用户想用一句
 * 好记的短口令是他自己的选择。但要说清代价——**`account_id` 是 key 的哈希，
 * 而哈希是离线可枚举的**。5 位小写字母数字只有约 6000 万种，任何人扫一遍就能
 * 找出真实存在的账号，进而读走聊天记录与明文存在 D1 里的 API Key。
 * 所以客户端会在密钥偏短时明确警告，并建议用随机串。
 */
const MIN_KEY_LENGTH = 5;

async function accountIdFrom(request) {
  const header = request.headers.get('Authorization') ?? '';
  const key = header.startsWith('Bearer ') ? header.slice('Bearer '.length).trim() : '';
  if (key.length < MIN_KEY_LENGTH) return null;
  return hex(await sha256(new TextEncoder().encode(key)));
}

/**
 * 每账号每分钟的请求数上限。
 *
 * 存在实例内存里，所以它是"每实例"而不是"全局"的限制。这是无状态方案的必然取舍：
 * 要精确的全局限流就得引入 Durable Object 或 KV，那会推翻"无状态"这个前提。
 * 对单用户的同步流量来说，这个精度远远够用。
 */
const rateWindows = new Map();

function rateLimited(accountId, env) {
  const limit = intVar(env, 'rate_limit_per_minute', 60);
  const now = Date.now();
  const window = rateWindows.get(accountId);

  if (!window || now - window.start >= 60_000) {
    rateWindows.set(accountId, { start: now, count: 1 });
    return 0;
  }
  window.count += 1;
  if (window.count > limit) {
    return Math.max(1, Math.ceil((window.start + 60_000 - now) / 1000));
  }
  return 0;
}

// ---- 表结构 ----

/**
 * 首次带鉴权的写入前建表。
 *
 * `CREATE TABLE IF NOT EXISTS` 是幂等的，所以不需要单独的 migrate 步骤，也不需要
 * 分布式锁：万一两个实例同时跑，第二次是空操作。
 */
async function ensureSchema(env) {
  if (schemaReady) return;
  await env.DB.batch(SCHEMA.map((sql) => env.DB.prepare(sql)));
  schemaReady = true;
}

// ---- /sync ----

async function handleSync(request, env, accountId) {
  const body = await readJson(request, env);
  if (body.error) return body.error;
  const payload = body.value;

  if (payload.proto !== 1) return json(400, { error: 'unsupported_proto' });

  const pullLimit = clamp(intValue(payload.pullLimit, 500), 1, 1000);
  // 客户端在没有消息可推、且不是首次同步时请求"墓碑全量"：
  // 删除用的是 rev，等到客户端下次拉取时那条墓碑的 rev 已经在游标后面了，
  // 增量拉取永远看不到它——而被删的消息就会在本机一直留着。
  const tombstonesOnly = payload.tombstonesOnly === true;
  const maxMessages = intVar(env, 'max_batch_messages', 500);

  const incoming = Array.isArray(payload.messages) ? payload.messages : [];
  if (incoming.length > maxMessages) return json(413, { error: 'batch_too_large' });

  const deleted = Array.isArray(payload.deleted) ? payload.deleted : [];
  const kvIncoming = Array.isArray(payload.kv) ? payload.kv : [];

  // 取当前水位：本批的 seq 与 rev 都从这里往上排。
  const headMessage = await env.DB
    .prepare('SELECT COALESCE(MAX(seq), 0) AS seq, COALESCE(MAX(rev), 0) AS rev FROM messages WHERE account_id = ?')
    .bind(accountId)
    .first();
  const headKv = await env.DB
    .prepare('SELECT COALESCE(MAX(rev), 0) AS rev FROM kv WHERE account_id = ?')
    .bind(accountId)
    .first();

  let rev = Math.max(headMessage?.rev ?? 0, headKv?.rev ?? 0);
  let seq = headMessage?.seq ?? 0;

  const statements = [];
  const rejected = [];
  const assigned = {};
  let applied = 0;

  for (const message of incoming) {
    const clean = validateMessage(message);
    if (clean.error) {
      rejected.push({ msgId: stringValue(message?.msgId), reason: clean.error });
      continue;
    }
    rev += 1;
    seq += 1;
    assigned[clean.msgId] = seq;
    applied += 1;

    // msg_id 冲突时什么都不做：同一条消息重复推送是幂等的，
    // 而且服务端分配过的 seq 必须保持稳定，不能被后到的草稿值改写。
    statements.push(
      env.DB.prepare(
        `INSERT INTO messages (account_id, msg_id, seq, role, content, created_at, local_error, images, rev)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (account_id, msg_id) DO NOTHING`
      ).bind(
        accountId,
        clean.msgId,
        seq,
        clean.role,
        clean.content,
        clean.createdAt,
        clean.localError ? 1 : 0,
        clean.images.length ? JSON.stringify(clean.images) : null,
        rev
      )
    );
  }

  for (const entry of kvIncoming) {
    const key = stringValue(entry?.key);
    if (!ALLOWED_KV_KEYS.has(key)) {
      rejected.push({ key, reason: 'unknown_key' });
      continue;
    }
    const payloadText = typeof entry?.payload === 'string' ? entry.payload : '';
    if (!payloadText) {
      rejected.push({ key, reason: 'empty_payload' });
      continue;
    }
    rev += 1;
    applied += 1;
    // LWW：v 更早的那份不能覆盖。注意判据是"严格更早"而不是"不更新"——
    // 两台设备的 updatedAt 完全相等时，如果拒绝覆盖，旧设备先推的那份就永远改不动了
    // （客户的墙钟停在同一毫秒时，快照会永久停在第一次写进去的内容上）。
    statements.push(
      env.DB.prepare(
        `INSERT INTO kv (account_id, key, payload, updated_at, rev)
         VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (account_id, key) DO UPDATE SET
           payload = excluded.payload,
           updated_at = excluded.updated_at,
           rev = excluded.rev
         WHERE excluded.updated_at >= kv.updated_at`
      ).bind(accountId, key, payloadText, intValue(entry?.updatedAt, Date.now()), rev)
    );
  }

  for (const entry of deleted) {
    const msgId = stringValue(entry?.msgId);
    if (!msgId) {
      rejected.push({ reason: 'missing_msg_id' });
      continue;
    }
    rev += 1;
    applied += 1;
    statements.push(
      env.DB.prepare('DELETE FROM messages WHERE account_id = ? AND msg_id = ?').bind(accountId, msgId)
    );
    // 墓碑要留下来告诉其他设备"这条被删了"；重复删除只更新 rev，仍然是一条变更。
    statements.push(
      env.DB.prepare(
        `INSERT INTO deleted (account_id, msg_id, rev) VALUES (?, ?, ?)
         ON CONFLICT (account_id, msg_id) DO UPDATE SET rev = excluded.rev`
      ).bind(accountId, msgId, rev)
    );
  }

  if (statements.length) await env.DB.batch(statements);

  const sinceRev = clamp(intValue(payload.sinceRev, 0), 0, Number.MAX_SAFE_INTEGER);
  const pull = await pullSince(env, accountId, sinceRev, rev, pullLimit, tombstonesOnly);

  return json(200, {
    proto: 1,
    rev,
    applied,
    rejected,
    assigned,
    pull,
  });
}

/**
 * 增量拉取。
 *
 * 每个类别各取 `limit + 1` 行来判断是否还有更多。使用 `limit + 1` 而不是分别
 * `COUNT(*)`：多取一行既知道有没有下一页，又不额外查一次。
 *
 * 客户端必须循环到 `hasMore` 为 false 才算拉完；`rev` 取本页实际覆盖到的最大 rev，
 * 所以中途断开也能安全地接着拉。
 */
async function pullSince(env, accountId, sinceRev, headRev, limit, tombstonesOnly) {
  const [messages, kv, deleted, images] = await Promise.all([
    env.DB
      .prepare('SELECT * FROM messages WHERE account_id = ? AND rev > ? ORDER BY rev LIMIT ?')
      .bind(accountId, sinceRev, limit + 1)
      .all(),
    env.DB
      .prepare('SELECT * FROM kv WHERE account_id = ? AND rev > ? ORDER BY rev LIMIT ?')
      .bind(accountId, sinceRev, limit + 1)
      .all(),
    // 墓碑全量请求时取回全部墓碑（它们只是 id，量很小）；否则按 rev 增量取。
    tombstonesOnly
      ? env.DB.prepare('SELECT msg_id, rev FROM deleted WHERE account_id = ? ORDER BY rev').bind(accountId).all()
      : env.DB
          .prepare('SELECT msg_id, rev FROM deleted WHERE account_id = ? AND rev > ? ORDER BY rev LIMIT ?')
          .bind(accountId, sinceRev, limit + 1)
          .all(),
    env.DB
      .prepare('SELECT image_id, bytes FROM images WHERE account_id = ? ORDER BY image_id')
      .bind(accountId)
      .all(),
  ]);

  const messageRows = messages.results ?? [];
  const kvRows = kv.results ?? [];
  const deletedRows = deleted.results ?? [];
  const hasMore = tombstonesOnly
    ? false
    : messageRows.length > limit || kvRows.length > limit;

  const pageMessages = messageRows.slice(0, limit);
  const pageKv = kvRows.slice(0, limit);
  const pageDeleted = tombstonesOnly ? deletedRows : deletedRows.slice(0, limit);
  // 墓碑全量请求不推进游标：它只是一次"把已有的事实重新说一遍"。
  const coveredRev = tombstonesOnly
    ? sinceRev
    : Math.max(
        sinceRev,
        ...pageMessages.map((row) => row.rev),
        ...pageKv.map((row) => row.rev),
        ...pageDeleted.map((row) => row.rev)
      );

  return {
    sinceRev,
    rev: coveredRev,
    headRev,
    hasMore,
    messages: pageMessages.map(messageWire),
    // 显式墓碑：客户端据此删掉本机对应记录，并且不再把它推回来。
    deleted: pageDeleted.map((row) => ({ msgId: row.msg_id, rev: row.rev })),
    kv: pageKv.map((row) => ({
      key: row.key,
      payload: row.payload,
      updatedAt: row.updated_at,
      rev: row.rev,
    })),
    // 图片是全量清单：只有 id 和字节数，几千张也只有几十 KB，客户端据此算缺哪些。
    images: (images.results ?? []).map((row) => ({ imageId: row.image_id, bytes: row.bytes })),
  };
}

async function handleDeleteAll(env, accountId) {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM messages WHERE account_id = ?').bind(accountId),
    env.DB.prepare('DELETE FROM kv WHERE account_id = ?').bind(accountId),
    env.DB.prepare('DELETE FROM deleted WHERE account_id = ?').bind(accountId),
    env.DB.prepare('DELETE FROM images WHERE account_id = ?').bind(accountId),
  ]);
  // R2 对象不在这里删：先清 D1 登记，让所有设备立刻看不到它们。
  // 孤儿对象由一个生命周期规则回收——误删用户照片是不可逆的，不值得为省一点存储去冒这个险。
  return json(200, { ok: true });
}

// ---- 图片 ----

async function handleImageUpload(request, env, accountId) {
  const declared = request.headers.get('X-Image-Id') ?? '';
  const bytes = new Uint8Array(await request.arrayBuffer());

  const maxBytes = intVar(env, 'max_image_bytes', 2 * 1024 * 1024);
  if (bytes.byteLength === 0) return json(400, { error: 'empty_image' });
  if (bytes.byteLength > maxBytes) return json(413, { error: 'image_too_large' });

  // id 必须等于内容哈希：这样 id 与内容强绑定，图片天然去重，
  // 客户端也无法声明一个与内容不一致的 id（那会让本地的内容寻址缓存永久错位）。
  const digest = hex(await sha256(bytes)).slice(0, 32);
  const expected = `img_${digest}.jpg`;
  if (declared !== expected) {
    return json(400, { error: 'image_id_mismatch', expected });
  }

  await env.IMAGES.put(`${accountId}/${expected}`, bytes, {
    httpMetadata: { contentType: 'image/jpeg' },
  });
  await env.DB
    .prepare(
      `INSERT INTO images (account_id, image_id, bytes, created_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT (account_id, image_id) DO UPDATE SET bytes = excluded.bytes`
    )
    .bind(accountId, expected, bytes.byteLength, Date.now())
    .run();

  return json(200, { imageId: expected, bytes: bytes.byteLength });
}

async function handleImageDownload(url, env, accountId) {
  const imageId = url.searchParams.get('id') ?? '';
  // 只接受自己算出来的那种名字：带路径分隔符或 `..` 的 id 会指到别的账号的对象上。
  if (!/^img_[0-9a-f]{32}\.jpg$/.test(imageId)) return json(400, { error: 'bad_image_id' });

  const object = await env.IMAGES.get(`${accountId}/${imageId}`);
  if (!object) return json(404, { error: 'image_not_found' });

  return new Response(object.body, {
    status: 200,
    headers: {
      'Content-Type': 'image/jpeg',
      'Content-Length': String(object.size),
      // 内容寻址：同一个 id 永远对应同一份字节，可以永久缓存。
      'Cache-Control': 'private, max-age=31536000, immutable',
    },
  });
}

// ---- 校验 ----

function validateMessage(message) {
  const msgId = stringValue(message?.msgId);
  if (!msgId) return { error: 'missing_msg_id' };

  const role = stringValue(message?.role);
  if (!MESSAGE_ROLES.has(role)) return { error: 'bad_role' };

  const content = typeof message?.content === 'string' ? message.content : '';
  const images = Array.isArray(message?.images)
    ? message.images.filter((id) => typeof id === 'string' && /^img_[0-9a-f]{32}\.jpg$/.test(id))
    : [];
  // 与 StoredMessage.fromJson 的约定一致：纯文字要求 content 非空，只有图片的消息允许空正文。
  if (!content && images.length === 0) return { error: 'empty_content' };

  return {
    msgId,
    role,
    content,
    images,
    createdAt: intValue(message?.createdAt, Date.now()),
    localError: message?.localError === true,
  };
}

async function readJson(request, env) {
  const maxBytes = intVar(env, 'max_body_bytes', 1024 * 1024);
  const declared = Number(request.headers.get('Content-Length') ?? '0');
  if (declared > maxBytes) return { error: json(413, { error: 'body_too_large' }) };

  const text = await request.text();
  if (text.length > maxBytes) return { error: json(413, { error: 'body_too_large' }) };

  try {
    const value = JSON.parse(text);
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return { error: json(400, { error: 'bad_body' }) };
    }
    return { value };
  } catch {
    return { error: json(400, { error: 'bad_json' }) };
  }
}

function messageWire(row) {
  return {
    msgId: row.msg_id,
    seq: row.seq,
    rev: row.rev,
    role: row.role,
    content: row.content,
    createdAt: row.created_at,
    localError: row.local_error === 1,
    images: parseImages(row.images),
  };
}

function parseImages(text) {
  if (!text) return [];
  try {
    const value = JSON.parse(text);
    return Array.isArray(value) ? value.filter((item) => typeof item === 'string') : [];
  } catch {
    return [];
  }
}

// ---- 小工具 ----

function json(status, value, extraHeaders = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...extraHeaders },
  });
}

async function sha256(bytes) {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
}

function hex(bytes) {
  let out = '';
  for (const byte of bytes) out += byte.toString(16).padStart(2, '0');
  return out;
}

function stringValue(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function intValue(value, fallback) {
  const number = typeof value === 'number' ? value : Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(number) ? Math.trunc(number) : fallback;
}

function intVar(env, name, fallback) {
  return intValue(env?.[name], fallback);
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}
