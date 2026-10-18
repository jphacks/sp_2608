// 旅中の音楽と車内の盛り上がり。Android の BgmMusicSession の履歴と CabinMoodTracker の観測を受ける。

import { ValidationError } from "./trip";

export type PlayedTrack = {
  trackId: string;
  title: string;
  artist: string;
  genre: string | null;
  startedAtEpochMillis: number;
  endedAtEpochMillis: number | null;
};

/** 1 分ごとの観測。speechRatio は直近 60 秒のうち発話があった秒の割合（CabinMoodTracker と同じ窓）。 */
export type MoodSample = { atEpochMillis: number; speechRatio: number };

export type TripMusic = { tracks: PlayedTrack[]; mood: MoodSample[] };

/** CabinMoodTracker の lively と同じしきい値。 */
export const LIVELY_RATIO = 0.4;
const MAX_TRACKS = 500;
const MAX_MOOD_SAMPLES = 24 * 60;
const UNKNOWN_GENRE = "不明";

function fail(msg: string): never {
  throw new ValidationError(msg);
}
function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}
function text(v: unknown, name: string, max: number): string {
  if (typeof v !== "string" || v.trim() === "") fail(`${name} must be a non-empty string`);
  return v.trim().slice(0, max);
}
function epoch(v: unknown, name: string): number {
  if (typeof v !== "number" || !Number.isInteger(v) || v < 0) fail(`${name} must be a non-negative integer`);
  return v;
}

/** 端末が別送する音楽ログ（POST /api/music-logs）。 */
export type MusicLogInput = TripMusic & { id: string; deviceId: string; startedAtEpochMillis: number; endedAtEpochMillis: number };

export function parseMusicLog(input: unknown): MusicLogInput {
  if (!isRecord(input)) fail("music log must be an object");
  const id = text(input.id, "id", 64);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) fail("id must be a UUID");
  const deviceId = text(input.deviceId, "deviceId", 128);
  if (!/^[A-Za-z0-9._-]+$/.test(deviceId)) fail("deviceId must be a short id");
  const startedAtEpochMillis = epoch(input.startedAtEpochMillis, "startedAtEpochMillis");
  const endedAtEpochMillis = epoch(input.endedAtEpochMillis, "endedAtEpochMillis");
  if (endedAtEpochMillis < startedAtEpochMillis) fail("endedAtEpochMillis must be >= startedAtEpochMillis");
  const music = parseTripMusic({ tracks: input.tracks ?? [], mood: input.mood ?? [] })!;
  if (music.tracks.length === 0 && music.mood.length === 0) fail("music log is empty");
  return { id: id.toLowerCase(), deviceId, startedAtEpochMillis, endedAtEpochMillis, ...music };
}

export function parseTripMusic(input: unknown): TripMusic | null {
  if (input == null) return null;
  if (!isRecord(input)) fail("music must be an object");
  const tracksRaw = input.tracks ?? [];
  const moodRaw = input.mood ?? [];
  if (!Array.isArray(tracksRaw)) fail("music.tracks must be an array");
  if (!Array.isArray(moodRaw)) fail("music.mood must be an array");
  if (tracksRaw.length > MAX_TRACKS) fail("music.tracks has too many items");
  if (moodRaw.length > MAX_MOOD_SAMPLES) fail("music.mood has too many samples");

  const tracks = tracksRaw.map((t, i): PlayedTrack => {
    const name = `music.tracks[${i}]`;
    if (!isRecord(t)) fail(`${name} must be an object`);
    const startedAtEpochMillis = epoch(t.startedAtEpochMillis, `${name}.startedAtEpochMillis`);
    const endedAtEpochMillis = t.endedAtEpochMillis == null ? null : epoch(t.endedAtEpochMillis, `${name}.endedAtEpochMillis`);
    if (endedAtEpochMillis !== null && endedAtEpochMillis < startedAtEpochMillis) fail(`${name}.endedAtEpochMillis must be >= startedAtEpochMillis`);
    return {
      trackId: text(t.trackId, `${name}.trackId`, 128),
      title: text(t.title, `${name}.title`, 200),
      artist: text(t.artist, `${name}.artist`, 200),
      genre: t.genre == null ? null : text(t.genre, `${name}.genre`, 60),
      startedAtEpochMillis,
      endedAtEpochMillis,
    };
  });
  tracks.sort((a, b) => a.startedAtEpochMillis - b.startedAtEpochMillis);

  const mood = moodRaw.map((m, i): MoodSample => {
    const name = `music.mood[${i}]`;
    if (!isRecord(m)) fail(`${name} must be an object`);
    const ratio = m.speechRatio;
    if (typeof ratio !== "number" || !Number.isFinite(ratio) || ratio < 0 || ratio > 1) fail(`${name}.speechRatio must be between 0 and 1`);
    return { atEpochMillis: epoch(m.atEpochMillis, `${name}.atEpochMillis`), speechRatio: ratio };
  });
  mood.sort((a, b) => a.atEpochMillis - b.atEpochMillis);

  return { tracks, mood };
}

/** 曲の終わり。無ければ次の曲の始まり、それも無ければ到着時刻。 */
export function trackEnd(tracks: PlayedTrack[], index: number, arrivedAtEpochMillis: number): number {
  const t = tracks[index];
  if (t.endedAtEpochMillis !== null) return t.endedAtEpochMillis;
  const next = tracks[index + 1];
  const end = next ? next.startedAtEpochMillis : arrivedAtEpochMillis;
  return Math.max(end, t.startedAtEpochMillis);
}

export type GenreShare = { genre: string; minutes: number; share: number };

/** ジャンルごとの再生時間（分）と割合。長い順。 */
export function genreShares(music: TripMusic, arrivedAtEpochMillis: number): GenreShare[] {
  const byGenre = new Map<string, number>();
  music.tracks.forEach((t, i) => {
    const ms = trackEnd(music.tracks, i, arrivedAtEpochMillis) - t.startedAtEpochMillis;
    const key = t.genre ?? UNKNOWN_GENRE;
    byGenre.set(key, (byGenre.get(key) ?? 0) + ms);
  });
  const total = [...byGenre.values()].reduce((a, b) => a + b, 0);
  return [...byGenre.entries()]
    .map(([genre, ms]) => ({ genre, minutes: Math.round(ms / 60_000), share: total > 0 ? ms / total : 0 }))
    .sort((a, b) => b.minutes - a.minutes || a.genre.localeCompare(b.genre, "ja"));
}

export type RankedTrack = { track: PlayedTrack; livelyMinutes: number; averageRatio: number };

/**
 * 盛り上がった時間に流れていた曲の順位。
 * 曲が流れていた間の観測のうち、発話割合が lively のしきい値以上だった分数で並べ、同じなら平均の発話割合で並べる。
 */
export function livelyRanking(music: TripMusic, arrivedAtEpochMillis: number, limit = 5): RankedTrack[] {
  const ranked = music.tracks.map((track, i): RankedTrack => {
    const end = trackEnd(music.tracks, i, arrivedAtEpochMillis);
    const samples = music.mood.filter((m) => m.atEpochMillis >= track.startedAtEpochMillis && m.atEpochMillis < end);
    const livelyMinutes = samples.filter((m) => m.speechRatio >= LIVELY_RATIO).length;
    const averageRatio = samples.length ? samples.reduce((a, m) => a + m.speechRatio, 0) / samples.length : 0;
    return { track, livelyMinutes, averageRatio };
  });
  return ranked
    .filter((r) => r.livelyMinutes > 0)
    .sort((a, b) => b.livelyMinutes - a.livelyMinutes || b.averageRatio - a.averageRatio || a.track.startedAtEpochMillis - b.track.startedAtEpochMillis)
    .slice(0, limit);
}
