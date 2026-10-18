// 旅の記録。Android 側 com.theacar.memory.TripMemory と揃える。

import { parseTripMusic, type TripMusic } from "./music";

export type Coordinate = { latitude: number; longitude: number };
export type PhotoStyle = "WATERCOLOR" | "POSTER" | "FILM";

export type Trip = {
  id: string;
  startedAtEpochMillis: number;
  arrivedAtEpochMillis: number;
  timeZoneId: string;
  distanceMeters: number;
  durationSeconds: number;
  destinationTitle: string;
  destinationCoordinate: Coordinate;
  placeName: string | null;
  geometry: Coordinate[];
  members: string[];
  photoStyle: PhotoStyle | null;
  photoLocation: Coordinate | null;
  /** 旅中の音楽と車内の盛り上がり（任意。Android の BGM 機能が送る） */
  music: TripMusic | null;
};

/** 保存時に足す情報。photoVersion は写真を置いた時刻で、写真 URL の ?v= に使う（差し替え時にキャッシュを避ける）。 */
export type StoredTrip = Trip & {
  /** 送ってきた端末の ID（任意）。別送の音楽ログと結び付けるのに使う */
  deviceId: string | null;
  hasPhoto: boolean;
  photoVersion: number | null;
  createdAtEpochMillis: number;
};

export function parseDeviceId(v: unknown, name = "deviceId"): string | null {
  if (v == null || v === "") return null;
  if (typeof v !== "string" || v.length > 128 || !/^[A-Za-z0-9._-]+$/.test(v)) fail(`${name} must be a short id`);
  return v;
}

export class ValidationError extends Error {}

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PHOTO_STYLES: PhotoStyle[] = ["WATERCOLOR", "POSTER", "FILM"];
const MAX_GEOMETRY_POINTS = 20_000;
const MAX_MEMBERS = 20;
const MAX_NAME_CHARS = 200;

function fail(msg: string): never {
  throw new ValidationError(msg);
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function finiteNumber(v: unknown, name: string): number {
  if (typeof v !== "number" || !Number.isFinite(v)) fail(`${name} must be a finite number`);
  return v;
}

function nonNegativeInt(v: unknown, name: string): number {
  const n = finiteNumber(v, name);
  if (n < 0 || !Number.isInteger(n)) fail(`${name} must be a non-negative integer`);
  return n;
}

function nonBlank(v: unknown, name: string, max = 200): string {
  if (typeof v !== "string" || v.trim() === "") fail(`${name} must be a non-empty string`);
  if (v.length > max) fail(`${name} is too long`);
  return v;
}

// 座標は {latitude, longitude} と [lat, lon]（Android の toJson 形式）の両方を受ける
export function parseCoordinate(v: unknown, name: string): Coordinate {
  let lat: unknown;
  let lon: unknown;
  if (Array.isArray(v) && v.length === 2) [lat, lon] = v;
  else if (isRecord(v)) ({ latitude: lat, longitude: lon } = v);
  else fail(`${name} must be a coordinate`);
  const latitude = finiteNumber(lat, `${name}.latitude`);
  const longitude = finiteNumber(lon, `${name}.longitude`);
  if (latitude < -90 || latitude > 90) fail(`${name}.latitude out of range`);
  if (longitude < -180 || longitude > 180) fail(`${name}.longitude out of range`);
  return { latitude, longitude };
}

function optional(o: Record<string, unknown>, ...keys: string[]): unknown {
  for (const k of keys) if (o[k] !== undefined) return o[k];
  return undefined;
}

/** アプリから受けた JSON を検証して Trip にする。ブリーフのキー名と Android の toJson のキー名の両方を受ける。 */
export function parseTrip(input: unknown): Trip {
  if (!isRecord(input)) fail("trip must be an object");
  const o = input;

  const id = nonBlank(o.id, "id", 64).toLowerCase(); // KV / R2 のキーは大文字小文字を区別するので小文字に揃える
  if (!UUID.test(id)) fail("id must be a UUID");

  const startedAtEpochMillis = nonNegativeInt(optional(o, "startedAtEpochMillis", "startedAt"), "startedAtEpochMillis");
  const arrivedAtEpochMillis = nonNegativeInt(optional(o, "arrivedAtEpochMillis", "arrivedAt"), "arrivedAtEpochMillis");
  if (arrivedAtEpochMillis < startedAtEpochMillis) fail("arrivedAtEpochMillis must be >= startedAtEpochMillis");

  // Android の dateLines と同じく、知らないタイムゾーンは UTC に倒す（拒否すると QR の先のページが作れなくなる）
  let timeZoneId = typeof o.timeZoneId === "string" && o.timeZoneId.trim() !== "" ? o.timeZoneId : "UTC";
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: timeZoneId });
  } catch {
    timeZoneId = "UTC";
  }

  const distanceMeters = nonNegativeInt(o.distanceMeters, "distanceMeters");
  const durationSeconds = nonNegativeInt(o.durationSeconds, "durationSeconds");
  // Android 側は長さを制限しないので、長すぎる名前は拒否せず切る
  const destinationTitle = nonBlank(o.destinationTitle, "destinationTitle", Infinity).slice(0, MAX_NAME_CHARS);
  const destinationCoordinate = parseCoordinate(optional(o, "destinationCoordinate", "destination"), "destinationCoordinate");

  const placeName = o.placeName == null ? null : nonBlank(o.placeName, "placeName", Infinity).slice(0, MAX_NAME_CHARS);

  const geometryRaw = o.geometry ?? [];
  if (!Array.isArray(geometryRaw)) fail("geometry must be an array");
  if (geometryRaw.length > MAX_GEOMETRY_POINTS) fail("geometry has too many points");
  const geometry = geometryRaw.map((p, i) => parseCoordinate(p, `geometry[${i}]`));

  const membersRaw = o.members ?? [];
  if (!Array.isArray(membersRaw)) fail("members must be an array");
  if (membersRaw.length > MAX_MEMBERS) fail("too many members");
  const members = membersRaw.map((m, i) => nonBlank(m, `members[${i}]`, 40).trim());

  let photoStyle: PhotoStyle | null = null;
  if (o.photoStyle != null) {
    if (typeof o.photoStyle !== "string" || !PHOTO_STYLES.includes(o.photoStyle as PhotoStyle)) fail("photoStyle is invalid");
    photoStyle = o.photoStyle as PhotoStyle;
  }

  const photoLocation = o.photoLocation == null ? null : parseCoordinate(o.photoLocation, "photoLocation");
  const music = parseTripMusic(o.music);

  return {
    id,
    startedAtEpochMillis,
    arrivedAtEpochMillis,
    timeZoneId,
    distanceMeters,
    durationSeconds,
    destinationTitle,
    destinationCoordinate,
    placeName,
    geometry,
    members,
    photoStyle,
    photoLocation,
    music,
  };
}
