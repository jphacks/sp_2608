-- Migration number: 0004 	 2026-10-18T00:00:00.000Z
-- 同乗者のリクエスト（QR コードから開くページ）。1回の案内ごとに party を作り、案内の終わりで閉じる。
-- 同乗者の名前は本人が入力した表示名だけを持つ。端末やアカウントの情報は持たない。
CREATE TABLE IF NOT EXISTS party (
  code        TEXT PRIMARY KEY,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  closed_at   INTEGER,
  -- 車の端末が送ってくる「今の曲」と予約の列。同乗者のページに出す。
  state_json  TEXT,
  state_at    INTEGER
);
CREATE INDEX IF NOT EXISTS idx_party_expires_at ON party (expires_at);

CREATE TABLE IF NOT EXISTS party_request (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  code        TEXT NOT NULL,
  guest_id    TEXT NOT NULL,
  guest_name  TEXT NOT NULL,
  uri         TEXT NOT NULL,
  title       TEXT NOT NULL,
  artist      TEXT NOT NULL,
  duration_ms INTEGER,
  image_url   TEXT,
  mode        TEXT NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_party_request_code ON party_request (code, id);
