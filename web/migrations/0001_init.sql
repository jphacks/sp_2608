-- 旅の記録（メタデータ）。写真は R2。
CREATE TABLE trips (
  id TEXT PRIMARY KEY,
  device_id TEXT,
  started_at INTEGER NOT NULL,
  arrived_at INTEGER NOT NULL,
  payload TEXT NOT NULL,
  has_photo INTEGER NOT NULL DEFAULT 0,
  photo_version INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX trips_device_time ON trips(device_id, started_at);

-- 旅中の音楽ログ。trip_id があれば旅と直接結び付く。無ければ同じ端末で時間が重なる旅に結び付ける。
CREATE TABLE music_logs (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  trip_id TEXT,
  started_at INTEGER NOT NULL,
  ended_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX music_logs_device_time ON music_logs(device_id, started_at, ended_at);
CREATE INDEX music_logs_trip ON music_logs(trip_id);

CREATE TABLE music_tracks (
  log_id TEXT NOT NULL REFERENCES music_logs(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  track_id TEXT NOT NULL,
  title TEXT NOT NULL,
  artist TEXT NOT NULL,
  genre TEXT,
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  PRIMARY KEY (log_id, position)
);

CREATE TABLE music_mood (
  log_id TEXT NOT NULL REFERENCES music_logs(id) ON DELETE CASCADE,
  at INTEGER NOT NULL,
  speech_ratio REAL NOT NULL,
  PRIMARY KEY (log_id, at)
);
