-- iKitty 云端同步的 D1 表结构（见 docs/SYNC_DESIGN.md）。
--
-- 这份文件是表结构的**可读记录**；Worker 在首次带鉴权的请求上会执行同样的
-- CREATE TABLE IF NOT EXISTS，所以部署时不需要单独跑 migrate。
-- 改动这里时，务必同步改 worker/src/index.js 里的 SCHEMA 常量，否则两处会漂移。

CREATE TABLE IF NOT EXISTS messages (
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
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_seq ON messages(account_id, seq);
CREATE INDEX IF NOT EXISTS idx_messages_rev ON messages(account_id, rev);

CREATE TABLE IF NOT EXISTS kv (
  account_id TEXT    NOT NULL,
  key        TEXT    NOT NULL,
  payload    TEXT    NOT NULL,
  updated_at INTEGER NOT NULL,
  rev        INTEGER NOT NULL,
  PRIMARY KEY (account_id, key)
);

CREATE INDEX IF NOT EXISTS idx_kv_rev ON kv(account_id, rev);

-- 删除是显式的墓碑，而不是"这一页没返回它"。
--
-- 必须有这张表：消息用 rev 做增量游标，所以"某条消息不在这次的响应里"既可能是被删了，
-- 也可能是它属于更早的一页。没有墓碑，客户端就只能猜，而猜错的代价是把删掉的消息又推回来。
-- 行很小（两个 id + 一个 rev），单用户的删除量远达不到需要清理的规模。
CREATE TABLE IF NOT EXISTS deleted (
  account_id TEXT    NOT NULL,
  msg_id     TEXT    NOT NULL,
  rev        INTEGER NOT NULL,
  PRIMARY KEY (account_id, msg_id)
);

CREATE INDEX IF NOT EXISTS idx_deleted_rev ON deleted(account_id, rev);

CREATE TABLE IF NOT EXISTS images (
  account_id TEXT    NOT NULL,
  image_id   TEXT    NOT NULL,
  bytes      INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (account_id, image_id)
);
