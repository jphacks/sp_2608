-- Migration number: 0006 	 2026-11-13T00:00:00.000Z
-- 歌詞の題材（JSON。題材 → 確かさ 0〜1。例 {"sea":0.95,"summer":0.95}）。
-- カタログの曲は Gemini が歌詞の内容から付けたもの（catalog/themes.jsonl）。歌詞そのものは保存しない。
-- 場面に合う題材の曲を、曲調の近さとは別枠で加点するのに使う。NULL の曲は加点なし
ALTER TABLE track_features ADD COLUMN themes_json TEXT;
