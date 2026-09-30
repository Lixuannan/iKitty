/**
 * Worker 的本地验证：用 node:sqlite 当 D1、用 Map 当 R2，跑真实的 SQL 与路由。
 *
 * 这不是单元测试的替代品，而是"部署前最小证据"：D1 的语法特性（ON CONFLICT ... WHERE、
 * batch 的顺序执行、MAX(seq) 分配）如果写错，只有真跑一遍 SQL 才会暴露。
 *
 * 运行：
 *   cd worker && node test/local-check.mjs
 */

import { DatabaseSync } from 'node:sqlite';
import assert from 'node:assert/strict';

const worker = (await import('../src/index.js')).default;

const db = new DatabaseSync(':memory:');
const objects = new Map();

/** 把 D1 的 prepare/bind/all/first/run 映射到 node:sqlite。 */
function d1Shim() {
  return {
    prepare(sql) {
      let bound = [];
      const statement = {
        bind(...values) {
          bound = values;
          return statement;
        },
        async all() {
          return { results: db.prepare(sql).all(...bound) };
        },
        async first() {
          const row = db.prepare(sql).get(...bound);
          return row ?? null;
        },
        async run() {
          db.prepare(sql).run(...bound);
          return { success: true };
        },
      };
      return statement;
    },
    /** D1 的 batch 是事务：这里也用事务，才能验证"整批成功或整批回滚"。 */
    async batch(statements) {
      db.exec('BEGIN');
      try {
        const results = [];
        for (const statement of statements) results.push(await statement.run());
        db.exec('COMMIT');
        return results;
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    },
  };
}

const env = {
  DB: d1Shim(),
  IMAGES: {
    async put(key, bytes) {
      objects.set(key, bytes);
    },
    async get(key) {
      const bytes = objects.get(key);
      if (!bytes) return null;
      return {
        body: bytes,
        size: bytes.byteLength,
      };
    },
  },
  rate_limit_per_minute: '1000',
  max_body_bytes: '1048576',
  max_batch_messages: '500',
  max_image_bytes: '2097152',
};

const KEY = 'test-account-key-0123456789';
const BASE = 'https://sync.test';

function call(path, { method = 'POST', body, headers = {}, raw } = {}) {
  const init = { method, headers: { Authorization: `Bearer ${KEY}`, ...headers } };
  if (raw !== undefined) {
    init.body = raw;
  } else if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  return worker.fetch(new Request(BASE + path, init), env);
}

async function post(path, body) {
  const response = await call(path, { body });
  return { status: response.status, json: await response.json() };
}

const tests = [];
function test(name, fn) {
  tests.push([name, fn]);
}

// ---- 用例 ----

test('健康检查与鉴权', async () => {
  const health = await worker.fetch(new Request(`${BASE}/health`, { method: 'GET' }), env);
  assert.equal(health.status, 200);

  const unauth = await worker.fetch(new Request(`${BASE}/sync`, { method: 'POST', body: '{}' }), env);
  assert.equal(unauth.status, 401, '没有 Bearer key 必须 401');

  const shortKey = await worker.fetch(
    new Request(`${BASE}/sync`, { method: 'POST', body: '{}', headers: { Authorization: 'Bearer abcd' } }),
    env
  );
  assert.equal(shortKey.status, 401, '低于下限（5 位）的 key 不接受');
});

test('恰好达到下限的短密钥也能开户', async () => {
  // 下限刻意设得很低（5 位），因为这是自托管应用：用户可以选择好记的口令。
  // 代价由客户端在设置页警告，而不是由服务端拒绝——服务端拒绝只会让人换一个更长的、同样弱的串。
  const shortKey = 'abcde';
  const accepted = await worker.fetch(
    new Request(`${BASE}/sync`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${shortKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ proto: 1, deviceId: 'device-s', sinceRev: 0 }),
    }),
    env
  );
  assert.equal(accepted.status, 200);

  // 短密钥与长密钥是不同的账号空间：哈希不同，不会串数据。
  const other = await worker.fetch(
    new Request(`${BASE}/sync`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${shortKey}x`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ proto: 1, deviceId: 'device-s', sinceRev: 0 }),
    }),
    env
  );
  const body = await other.json();
  assert.equal(body.pull.messages.length, 0, '不同密钥之间必须互不可见');
});

test('首次 push 自动建表并分配 seq/rev', async () => {
  const { status, json } = await post('/sync', {
    proto: 1,
    deviceId: 'device-a',
    sinceRev: 0,
    messages: [
      { msgId: 'm1', seq: 1, role: 'user', content: '你好', createdAt: 1000 },
      { msgId: 'm2', seq: 2, role: 'assistant', content: '在呢', createdAt: 1001 },
    ],
  });
  assert.equal(status, 200);
  assert.equal(json.applied, 2);
  assert.equal(json.rejected.length, 0);
  assert.equal(json.assigned.m1, 1);
  assert.equal(json.assigned.m2, 2);
  assert.equal(json.pull.messages.length, 2);
  assert.equal(json.pull.messages[0].content, '你好');
  // 服务端分配的 seq 是全序，客户端草稿值被覆盖。
  assert.deepEqual(json.pull.messages.map((m) => m.seq), [1, 2]);
  assert.deepEqual(json.pull.messages.map((m) => m.rev), [1, 2]);
});

test('重复推送同一条消息是幂等的，seq 不被改写', async () => {
  const first = await post('/sync', {
    proto: 1,
    deviceId: 'device-a',
    sinceRev: 2,
    messages: [{ msgId: 'm1', seq: 999, role: 'user', content: '你好', createdAt: 1000 }],
  });
  assert.equal(first.json.applied, 1, '依然计入 applied（客户端确实推了）');
  const rows = db.prepare('SELECT COUNT(*) AS n, MIN(seq) AS seq FROM messages WHERE msg_id = ?').all('m1');
  assert.equal(rows[0].n, 1, '不允许产生第二行');
  assert.equal(rows[0].seq, 1, 'seq 必须保持首次分配的值');

  // 客户端用旧游标重放时，仍然拿得到这条消息（rev 没有变化）。
  const replay = await post('/sync', { proto: 1, deviceId: 'device-a', sinceRev: 0 });
  assert.equal(replay.json.pull.messages.find((m) => m.msgId === 'm1').seq, 1);
});

test('坏记录被拒但整批不失败', async () => {
  const { json } = await post('/sync', {
    proto: 1,
    deviceId: 'device-a',
    sinceRev: 2,
    messages: [
      { msgId: 'bad-role', seq: 3, role: 'system', content: 'x', createdAt: 1 },
      { msgId: 'bad-empty', seq: 4, role: 'user', content: '', createdAt: 1 },
      { msgId: 'good', seq: 5, role: 'user', content: 'ok', createdAt: 1 },
    ],
  });
  assert.equal(json.applied, 1);
  assert.deepEqual(
    json.rejected.map((r) => r.msgId).sort(),
    ['bad-empty', 'bad-role']
  );
});

test('纯图片消息允许空正文', async () => {
  const { json } = await post('/sync', {
    proto: 1,
    deviceId: 'device-a',
    sinceRev: 3,
    messages: [
      {
        msgId: 'image-only',
        seq: 6,
        role: 'user',
        content: '',
        createdAt: 1,
        images: ['img_' + 'a'.repeat(32) + '.jpg'],
      },
    ],
  });
  assert.equal(json.applied, 1);
  const imageOnly = json.pull.messages.find((m) => m.msgId === 'image-only');
  assert.equal(imageOnly.images.length, 1);
  assert.equal(imageOnly.content, '');
});

test('仅接受白名单 kv 键，且 LWW 不会被老数据顶回去', async () => {
  const newer = await post('/sync', {
    proto: 1,
    deviceId: 'device-a',
    sinceRev: 6,
    kv: [{ key: 'settings', payload: '{"model":"new"}', updatedAt: 5000 }],
  });
  assert.equal(newer.json.applied, 1);

  const older = await post('/sync', {
    proto: 1,
    deviceId: 'device-b',
    sinceRev: 0,
    kv: [{ key: 'settings', payload: '{"model":"old"}', updatedAt: 1000 }],
  });
  const settings = older.json.pull.kv.find((row) => row.key === 'settings');
  assert.equal(settings.payload, '{"model":"new"}', '老设备不能覆盖更新的设置');

  const unknown = await post('/sync', {
    proto: 1,
    deviceId: 'device-a',
    kv: [{ key: 'evil', payload: 'x', updatedAt: 9999 }],
  });
  assert.equal(unknown.json.rejected[0].reason, 'unknown_key');
});

test('增量拉取按 rev 推进，不重复下发', async () => {
  const full = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: 0 });
  const top = full.json.rev;

  const incremental = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: top });
  assert.equal(incremental.json.pull.messages.length, 0, '没有新数据时不应重复下发');
  assert.equal(incremental.json.pull.rev, top);

  await post('/sync', {
    proto: 1,
    deviceId: 'device-a',
    messages: [{ msgId: 'later', seq: 7, role: 'user', content: '后来的', createdAt: 2000 }],
  });
  const delta = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: top });
  assert.equal(delta.json.pull.messages.length, 1);
  assert.equal(delta.json.pull.messages[0].msgId, 'later');
  assert.ok(delta.json.pull.rev > top);
});

test('pullLimit 生效并给出 hasMore', async () => {
  for (let i = 0; i < 5; i += 1) {
    await post('/sync', {
      proto: 1,
      deviceId: 'device-a',
      messages: [{ msgId: `bulk-${i}`, seq: 100 + i, role: 'user', content: `第 ${i} 条`, createdAt: i }],
    });
  }
  const page = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: 0, pullLimit: 2 });
  assert.equal(page.json.pull.messages.length, 2);
  assert.equal(page.json.pull.hasMore, true);

  // 按返回的 rev 接着拉，不重不漏。
  const next = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: page.json.pull.rev, pullLimit: 2 });
  assert.equal(next.json.pull.messages.length, 2);
  assert.ok(next.json.pull.messages[0].rev > page.json.pull.messages[1].rev);
});

test('删除消息后不再下发，并给出显式墓碑', async () => {
  const before = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: 0, pullLimit: 1000 });
  const cursor = before.json.pull.rev;
  assert.ok(before.json.pull.messages.some((m) => m.msgId === 'later'));

  const removed = await post('/sync', {
    proto: 1,
    deviceId: 'device-a',
    sinceRev: cursor,
    deleted: [{ kind: 'message', msgId: 'later' }],
  });
  assert.equal(removed.json.applied, 1);

  // 墓碑必须出现在增量拉取里，否则客户端无法区分"被删了"和"这一页没有"。
  const delta = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: cursor });
  assert.deepEqual(delta.json.pull.deleted.map((d) => d.msgId), ['later']);
  assert.equal(delta.json.pull.messages.some((m) => m.msgId === 'later'), false);

  // 全量拉取时也不会再出现。
  const after = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: 0, pullLimit: 1000 });
  assert.equal(after.json.pull.messages.some((m) => m.msgId === 'later'), false);
});

test('图片上传校验内容哈希', async () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);
  const digest = await crypto.subtle.digest('SHA-256', jpeg);
  const id =
    'img_' +
    [...new Uint8Array(digest)]
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('')
      .slice(0, 32) +
    '.jpg';

  const mismatch = await call('/sync/image', {
    raw: jpeg,
    headers: { 'X-Image-Id': 'img_' + 'b'.repeat(32) + '.jpg', 'Content-Type': 'image/jpeg' },
  });
  assert.equal(mismatch.status, 400, 'id 与内容不一致必须拒绝');

  const ok = await call('/sync/image', {
    raw: jpeg,
    headers: { 'X-Image-Id': id, 'Content-Type': 'image/jpeg' },
  });
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).imageId, id);

  const download = await call(`/sync/image?id=${id}`, { method: 'GET' });
  assert.equal(download.status, 200);
  assert.equal(download.headers.get('Content-Type'), 'image/jpeg');
  assert.deepEqual(new Uint8Array(await download.arrayBuffer()), jpeg);

  // 清单出现在 pull 响应里，客户端据此算缺哪些图。
  const pull = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: 0 });
  assert.ok(pull.json.pull.images.some((image) => image.imageId === id));
});

test('图片 id 不能越权到别的账号或路径', async () => {
  const traversal = await call('/sync/image?id=../other/secret.jpg', { method: 'GET' });
  assert.equal(traversal.status, 400);

  const missing = await call(`/sync/image?id=img_${'c'.repeat(32)}.jpg`, { method: 'GET' });
  assert.equal(missing.status, 404);
});

test('不同账号的数据互相隔离', async () => {
  const otherKey = 'another-account-key-9876543210';
  const response = await worker.fetch(
    new Request(`${BASE}/sync`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${otherKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ proto: 1, deviceId: 'device-x', sinceRev: 0 }),
    }),
    env
  );
  const body = await response.json();
  assert.equal(body.pull.messages.length, 0, '新账号必须看不到别人的消息');
  assert.equal(body.rev, 0);
});

test('错误的协议版本被拒绝', async () => {
  const { status } = await post('/sync', { proto: 2, deviceId: 'd' });
  assert.equal(status, 400);
});

test('超大请求体返回 413', async () => {
  const huge = { proto: 1, deviceId: 'd', messages: [] , padding: 'x'.repeat(1024 * 1024 + 10) };
  const { status } = await post('/sync', huge);
  assert.equal(status, 413);
});

test('deleteAll 清空云端但不动 R2 对象', async () => {
  const before = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: 0 });
  assert.ok(before.json.pull.messages.length > 0);

  const cleared = await post('/sync/deleteAll', {});
  assert.equal(cleared.status, 200);

  const after = await post('/sync', { proto: 1, deviceId: 'device-b', sinceRev: 0 });
  assert.equal(after.json.pull.messages.length, 0);
  assert.equal(after.json.pull.kv.length, 0);
  assert.equal(after.json.pull.images.length, 0);
  assert.ok(objects.size > 0, 'R2 对象保留，交给生命周期规则回收');
});

// ---- 跑一遍 ----

let failed = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log(`  ok   ${name}`);
  } catch (error) {
    failed += 1;
    console.log(`  FAIL ${name}\n       ${error.message}`);
  }
}
console.log(`\n${tests.length - failed}/${tests.length} 通过`);
process.exit(failed === 0 ? 0 : 1);
