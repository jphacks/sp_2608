-- Migration number: 0005 	 2026-11-13T00:00:00.000Z
-- 安全のための上限（運転負荷が高い・カーブ・休息中の energy の上限）に使う energy。
-- カタログの曲は、LLM の注釈と試聴の音から見た energy のうち高いほう（どちらかが激しいと見たら激しい扱い）。
-- NULL の曲（ユーザーのプレイリストの曲など）は energy で判定する
ALTER TABLE track_features ADD COLUMN energy_cap REAL;
