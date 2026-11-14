-- 走行中の景観BGMの様子（ライブ表示）。車の端末が数秒ごとに最新の1件を上書きする。
-- 経路の形は大きく、変わるのは再探索のときだけなので、様子とは別の列に持つ。
CREATE TABLE live_sessions (
  code TEXT PRIMARY KEY,
  snapshot TEXT NOT NULL,
  route_key TEXT,
  geometry TEXT,
  updated_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX live_sessions_expires ON live_sessions(expires_at);
