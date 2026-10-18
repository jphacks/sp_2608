-- Migration number: 0001 	 2026-10-16T20:41:29.562Z
CREATE TABLE IF NOT EXISTS track_features (
  track_id      TEXT PRIMARY KEY,
  title         TEXT NOT NULL,
  artist        TEXT NOT NULL,
  duration_ms   INTEGER,
  energy        REAL,
  valence       REAL,
  tempo         REAL,
  acoustic      REAL,
  spacious      REAL,
  vocal         REAL,
  conf          REAL,
  tags_json     TEXT,
  source_json   TEXT,
  model_version TEXT,
  updated_at    INTEGER NOT NULL
);