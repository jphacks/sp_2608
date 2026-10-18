// catalog/catalog.jsonl の1行の列。scripts/catalog.ts の書き出しと、PC版の取り込み（sqlite.ts）で共有する。
// 並びは scripts/catalog.ts の INSERT の列順と同じ。
export const CATALOG_COLUMNS = [
  'track_id', 'title', 'artist', 'duration_ms', 'energy', 'valence', 'tempo', 'acoustic', 'spacious', 'vocal',
  'conf', 'tags_json', 'source_json', 'model_version', 'updated_at', 'isrc', 'jp_familiarity', 'deezer_rank',
  'popularity', 'catalog_group',
] as const;
