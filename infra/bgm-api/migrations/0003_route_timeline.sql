-- Migration number: 0003 	 2026-10-18T00:00:00.000Z
-- 景観エンジンのルートのタイムラインを、選曲に要る項目だけ保持する。
-- 景観エンジンは検索結果を1時間で消すので、案内の最初に取ったものをドライブの間使う。座標・道路名・物理道路IDは持たない。
CREATE TABLE IF NOT EXISTS route_timeline (
  route_id      TEXT PRIMARY KEY,
  timeline_json TEXT NOT NULL,
  reveals_json  TEXT NOT NULL,
  fetched_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_route_timeline_fetched_at ON route_timeline (fetched_at);
