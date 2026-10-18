// D1 に旅の記録と音楽ログ、R2 に写真を置く。

import type { StoredTrip, Trip } from "./trip";
import type { MoodSample, PlayedTrack, TripMusic } from "./music";
import { musicInPeriod } from "./music";

export type Env = {
  DB: D1Database;
  PHOTOS: R2Bucket;
  TRIP_UPLOAD_TOKEN?: string;
  PUBLIC_BASE_URL?: string;
};

export type MusicLog = {
  id: string;
  deviceId: string;
  startedAtEpochMillis: number;
  endedAtEpochMillis: number;
  tracks: PlayedTrack[];
  mood: MoodSample[];
};

type TripRow = {
  id: string;
  device_id: string | null;
  payload: string;
  has_photo: number;
  photo_version: number | null;
  created_at: number;
};

const photoKey = (id: string) => `${id}.jpg`;

function rowToTrip(row: TripRow): StoredTrip {
  const trip = JSON.parse(row.payload) as Trip;
  return {
    ...trip,
    deviceId: row.device_id,
    hasPhoto: row.has_photo === 1,
    photoVersion: row.photo_version,
    createdAtEpochMillis: row.created_at,
  };
}

export async function getTrip(env: Env, id: string): Promise<StoredTrip | null> {
  const row = await env.DB.prepare(
    "SELECT id, device_id, payload, has_photo, photo_version, created_at FROM trips WHERE id = ?1",
  )
    .bind(id)
    .first<TripRow>();
  return row ? rowToTrip(row) : null;
}

/** 旅を保存する。同じ id は上書き。`music` が付いていれば trip_id 付きの音楽ログとして置き換える。 */
export async function putTrip(env: Env, trip: StoredTrip, now: number): Promise<void> {
  const { music, ...rest } = trip;
  const { deviceId, hasPhoto, photoVersion, createdAtEpochMillis, ...payload } = rest;
  const statements: D1PreparedStatement[] = [
    env.DB.prepare(
      `INSERT INTO trips (id, device_id, started_at, arrived_at, payload, has_photo, photo_version, created_at, updated_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
       ON CONFLICT(id) DO UPDATE SET device_id = excluded.device_id, started_at = excluded.started_at, arrived_at = excluded.arrived_at,
         payload = excluded.payload, has_photo = excluded.has_photo, photo_version = excluded.photo_version, updated_at = excluded.updated_at`,
    ).bind(
      trip.id,
      deviceId,
      trip.startedAtEpochMillis,
      trip.arrivedAtEpochMillis,
      JSON.stringify({ ...payload, music: null }),
      hasPhoto ? 1 : 0,
      photoVersion,
      createdAtEpochMillis,
      now,
    ),
  ];
  if (music && (music.tracks.length > 0 || music.mood.length > 0)) {
    statements.push(env.DB.prepare("DELETE FROM music_logs WHERE trip_id = ?1").bind(trip.id));
    statements.push(
      ...musicLogStatements(env, {
        id: `trip:${trip.id}`,
        deviceId: deviceId ?? "",
        startedAtEpochMillis: trip.startedAtEpochMillis,
        endedAtEpochMillis: trip.arrivedAtEpochMillis,
        tracks: music.tracks,
        mood: music.mood,
      }, trip.id, now),
    );
  }
  await env.DB.batch(statements);
}

function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

function musicLogStatements(env: Env, log: MusicLog, tripId: string | null, now: number): D1PreparedStatement[] {
  const statements: D1PreparedStatement[] = [
    env.DB.prepare("DELETE FROM music_tracks WHERE log_id = ?1").bind(log.id),
    env.DB.prepare("DELETE FROM music_mood WHERE log_id = ?1").bind(log.id),
    env.DB.prepare(
      `INSERT INTO music_logs (id, device_id, trip_id, started_at, ended_at, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
       ON CONFLICT(id) DO UPDATE SET device_id = excluded.device_id, trip_id = excluded.trip_id,
         started_at = excluded.started_at, ended_at = excluded.ended_at`,
    ).bind(log.id, log.deviceId, tripId, log.startedAtEpochMillis, log.endedAtEpochMillis, now),
  ];
  // 1 文あたりの束縛値は 100 までなので、行をまとめて入れるときは少なめに刻む
  for (const rows of chunk(log.tracks.map((t, i) => [t, i] as const), 12)) {
    const placeholders = rows.map((_, r) => {
      const b = r * 8;
      return `(?${b + 1}, ?${b + 2}, ?${b + 3}, ?${b + 4}, ?${b + 5}, ?${b + 6}, ?${b + 7}, ?${b + 8})`;
    });
    const values = rows.flatMap(([t, i]) => [log.id, i, t.trackId, t.title, t.artist, t.genre, t.startedAtEpochMillis, t.endedAtEpochMillis]);
    statements.push(
      env.DB.prepare(
        `INSERT INTO music_tracks (log_id, position, track_id, title, artist, genre, started_at, ended_at) VALUES ${placeholders.join(", ")}`,
      ).bind(...values),
    );
  }
  for (const rows of chunk(log.mood, 30)) {
    const placeholders = rows.map((_, r) => `(?${r * 3 + 1}, ?${r * 3 + 2}, ?${r * 3 + 3})`);
    const values = rows.flatMap((m) => [log.id, m.atEpochMillis, m.speechRatio]);
    statements.push(
      env.DB.prepare(`INSERT OR REPLACE INTO music_mood (log_id, at, speech_ratio) VALUES ${placeholders.join(", ")}`).bind(...values),
    );
  }
  return statements;
}

/** 端末から届いた音楽ログを保存する。同じ id は置き換え。 */
export async function putMusicLog(env: Env, log: MusicLog, now: number): Promise<{ existed: boolean }> {
  const existing = await env.DB.prepare("SELECT id FROM music_logs WHERE id = ?1").bind(log.id).first<{ id: string }>();
  await env.DB.batch(musicLogStatements(env, log, null, now));
  return { existed: existing !== null };
}

type TrackRow = { log_id: string; track_id: string; title: string; artist: string; genre: string | null; started_at: number; ended_at: number | null };
type MoodRow = { log_id: string; at: number; speech_ratio: number };

/**
 * 旅の音楽。旅に直接付いたログがあればそれ。無ければ同じ端末で時間が重なるログをまとめる。
 * どちらも無ければ null。
 */
export async function getTripMusic(env: Env, trip: StoredTrip): Promise<TripMusic | null> {
  type LogPeriod = { id: string; ended_at: number };
  let logs = (
    await env.DB.prepare("SELECT id, ended_at FROM music_logs WHERE trip_id = ?1").bind(trip.id).all<LogPeriod>()
  ).results;
  if (logs.length === 0 && trip.deviceId) {
    logs = (
      await env.DB.prepare(
        "SELECT id, ended_at FROM music_logs WHERE device_id = ?1 AND trip_id IS NULL AND started_at <= ?3 AND ended_at >= ?2 ORDER BY started_at",
      )
        .bind(trip.deviceId, trip.startedAtEpochMillis, trip.arrivedAtEpochMillis)
        .all<LogPeriod>()
    ).results;
  }
  const logIds = logs.map((log) => log.id);
  if (logIds.length === 0) return null;
  const marks = logIds.map((_, i) => `?${i + 1}`).join(", ");
  const [tracks, mood] = await Promise.all([
    env.DB.prepare(
      `SELECT log_id, track_id, title, artist, genre, started_at, ended_at FROM music_tracks WHERE log_id IN (${marks}) ORDER BY started_at, position`,
    )
      .bind(...logIds)
      .all<TrackRow>(),
    env.DB.prepare(`SELECT log_id, at, speech_ratio FROM music_mood WHERE log_id IN (${marks}) ORDER BY at`).bind(...logIds).all<MoodRow>(),
  ]);
  const byLog = new Map<string, TripMusic>(logIds.map((id) => [id, { tracks: [], mood: [] }]));
  for (const r of tracks.results) {
    byLog.get(r.log_id)!.tracks.push({
      trackId: r.track_id,
      title: r.title,
      artist: r.artist,
      genre: r.genre,
      startedAtEpochMillis: r.started_at,
      endedAtEpochMillis: r.ended_at,
    });
  }
  for (const r of mood.results) byLog.get(r.log_id)!.mood.push({ atEpochMillis: r.at, speechRatio: r.speech_ratio });
  const clipped = logs.map((log) => musicInPeriod(byLog.get(log.id)!, trip.startedAtEpochMillis, trip.arrivedAtEpochMillis, log.ended_at));
  const music = {
    tracks: clipped.flatMap((log) => log.tracks).sort((a, b) => a.startedAtEpochMillis - b.startedAtEpochMillis),
    mood: clipped.flatMap((log) => log.mood).sort((a, b) => a.atEpochMillis - b.atEpochMillis),
  };
  return music.tracks.length || music.mood.length ? music : null;
}

export async function putPhoto(env: Env, id: string, body: ArrayBuffer, contentType: string): Promise<void> {
  await env.PHOTOS.put(photoKey(id), body, { httpMetadata: { contentType } });
}

export async function getPhoto(env: Env, id: string): Promise<R2ObjectBody | null> {
  return env.PHOTOS.get(photoKey(id));
}
