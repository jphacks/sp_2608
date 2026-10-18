-- Migration number: 0002 	 2026-10-17T00:00:00.000Z
-- 全曲カタログ用の列と、再生サービスごとの曲IDの対応表

-- カタログの曲は track_id を 'isrc:XXXX' にする。ISRC はサービスをまたいで使える共通キー
ALTER TABLE track_features ADD COLUMN isrc TEXT;
-- 日本の一般の人の認知度（LLM推定、0〜1）
ALTER TABLE track_features ADD COLUMN jp_familiarity REAL;
-- Deezer の rank（世界全体での人気の指標）
ALTER TABLE track_features ADD COLUMN deezer_rank INTEGER;
-- 選曲に使う知名度（0〜1）。カタログの曲は jp_familiarity と deezer_rank の順位の平均
ALTER TABLE track_features ADD COLUMN popularity REAL;
-- カタログの区分：jp（邦楽）/ inst（インスト・lo-fi・サントラ）/ western（洋楽）。カタログ外の曲は NULL
ALTER TABLE track_features ADD COLUMN catalog_group TEXT;

CREATE INDEX IF NOT EXISTS idx_track_features_isrc ON track_features (isrc);
CREATE INDEX IF NOT EXISTS idx_track_features_catalog_group ON track_features (catalog_group);

-- 再生サービスごとの曲ID（spotify など）。別のサービスに対応するときは行を足すだけで済む
CREATE TABLE IF NOT EXISTS track_link (
  track_id         TEXT NOT NULL,
  service          TEXT NOT NULL,
  service_track_id TEXT,            -- 照合できなかった曲は NULL（再試行しないための記録）
  matched_at       INTEGER NOT NULL,
  PRIMARY KEY (track_id, service)
);
CREATE INDEX IF NOT EXISTS idx_track_link_service_id ON track_link (service, service_track_id);
