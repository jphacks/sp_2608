// 旅れこ「旅のステッカー」Web 版の Worker。
//   POST /api/trips         アプリから記録と写真を登録（共有トークン）
//   POST /api/music-logs    アプリから旅中の音楽と盛り上がりのログを登録（共有トークン）
//   GET  /api/trips/{id}    記録の JSON
//   GET  /t/{id}            公開ページ
//   GET  /t/{id}/photo.jpg  デフォルメ後の写真

import { parseDeviceId, parseTrip, UUID, ValidationError, type StoredTrip } from "./trip";
import { parseMusicLog } from "./music";
import { renderNotFoundPage, renderTripPage } from "./page";
import { getPhoto, getTrip, getTripMusic, putMusicLog, putPhoto, putTrip, type Env } from "./store";

const MAX_PHOTO_BYTES = 8 * 1024 * 1024;
const MAX_JSON_BYTES = 2 * 1024 * 1024;
// 本文を読む前に Content-Length で弾く上限。写真 8 MB に JSON と multipart の枠の分を足した値。
const MAX_REQUEST_BYTES = 9 * 1024 * 1024;

const json = (body: unknown, status = 200, headers: HeadersInit = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8", ...headers } });

const html = (body: string, status = 200, cacheControl = "public, max-age=60") =>
  new Response(body, { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": cacheControl } });

export function publicBaseUrl(env: Env, request: Request): string {
  const configured = env.PUBLIC_BASE_URL?.trim();
  return (configured || new URL(request.url).origin).replace(/\/+$/, "");
}

export function tripUrl(env: Env, request: Request, id: string): string {
  return `${publicBaseUrl(env, request)}/t/${id}`;
}

function constantTimeEqual(a: string, b: string): boolean {
  const ea = new TextEncoder().encode(a);
  const eb = new TextEncoder().encode(b);
  if (ea.byteLength !== eb.byteLength) return false;
  let diff = 0;
  for (let i = 0; i < ea.byteLength; i++) diff |= ea[i] ^ eb[i];
  return diff === 0;
}

export function isAuthorized(request: Request, env: Env): boolean {
  const token = env.TRIP_UPLOAD_TOKEN;
  if (!token) return false;
  const header = request.headers.get("authorization") ?? "";
  const m = /^Bearer\s+(.+)$/i.exec(header);
  return m !== null && constantTimeEqual(m[1].trim(), token);
}

/** multipart（trip=JSON 文字列, photo=JPEG）か、JSON 本文だけ（写真なし）を受ける。 */
async function readUpload(request: Request): Promise<{ trip: unknown; photo: File | null }> {
  const contentType = request.headers.get("content-type") ?? "";
  if (contentType.startsWith("multipart/form-data")) {
    const form = await request.formData();
    const tripField = form.get("trip");
    // 文字列でも、ファイル名付きのパート（OkHttp の addFormDataPart(name, filename, body) など）でも受ける
    let tripText: string;
    if (typeof tripField === "string") tripText = tripField;
    else if (tripField instanceof File) {
      if (tripField.size > MAX_JSON_BYTES) throw new ValidationError("trip JSON is too large");
      tripText = await tripField.text();
    } else throw new ValidationError("multipart field 'trip' (JSON) is required");
    if (tripText.length > MAX_JSON_BYTES) throw new ValidationError("trip JSON is too large");
    const photo = form.get("photo");
    if (photo !== null && !(photo instanceof File)) throw new ValidationError("multipart field 'photo' must be a file");
    // 空のファイル（size 0）は「写真なし」。0 バイトの JPEG を R2 に置かない。
    return { trip: parseJson(tripText), photo: photo && photo.size > 0 ? photo : null };
  }
  if (contentType.startsWith("application/json")) {
    const text = await request.text();
    if (text.length > MAX_JSON_BYTES) throw new ValidationError("trip JSON is too large");
    return { trip: parseJson(text), photo: null };
  }
  throw new ValidationError("content-type must be multipart/form-data or application/json");
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    throw new ValidationError("trip is not valid JSON");
  }
}

/** 本文を読む前の大きさの判定。Content-Length が無い（chunked など）リクエストも受けない。 */
export function checkContentLength(request: Request): Response | null {
  const header = request.headers.get("content-length");
  if (header === null) return json({ error: "content-length header is required" }, 411);
  const length = Number(header);
  if (!Number.isInteger(length) || length < 0) return json({ error: "content-length is invalid" }, 400);
  if (length > MAX_REQUEST_BYTES) return json({ error: "request body is too large" }, 413);
  return null;
}

async function handleCreateTrip(request: Request, env: Env): Promise<Response> {
  if (!isAuthorized(request, env)) return json({ error: "unauthorized" }, 401, { "www-authenticate": "Bearer" });
  const tooLarge = checkContentLength(request);
  if (tooLarge) return tooLarge;
  try {
    const { trip: raw, photo } = await readUpload(request);
    const trip = parseTrip(raw);
    // 端末 ID は JSON の deviceId か X-Device-Id ヘッダ
    const deviceId = parseDeviceId((raw as { deviceId?: unknown }).deviceId) ?? parseDeviceId(request.headers.get("x-device-id"), "X-Device-Id");
    if (photo) {
      if (photo.size > MAX_PHOTO_BYTES) throw new ValidationError("photo is too large");
      const type = photo.type || "image/jpeg";
      if (type !== "image/jpeg") throw new ValidationError("photo must be image/jpeg");
      await putPhoto(env, trip.id, await photo.arrayBuffer(), type);
    }
    // 同じ id は上書き（アプリの再送や、あとからメンバー・写真を足す場合）。写真なしの再送でも既存の写真は残す。
    const existing = await getTrip(env, trip.id);
    const now = Date.now();
    const stored: StoredTrip = {
      ...trip,
      deviceId: deviceId ?? existing?.deviceId ?? null,
      hasPhoto: photo !== null || existing?.hasPhoto === true,
      photoVersion: photo !== null ? now : (existing?.photoVersion ?? null),
      createdAtEpochMillis: existing?.createdAtEpochMillis ?? now,
    };
    await putTrip(env, stored, now);
    const url = tripUrl(env, request, trip.id);
    return json({ id: trip.id, url }, existing ? 200 : 201, { location: url });
  } catch (e) {
    if (e instanceof ValidationError) return json({ error: e.message }, 400);
    throw e;
  }
}

/** 写真のパス。差し替えてもブラウザや CDN のキャッシュに残らないよう、置いた時刻を ?v= に付ける。 */
function photoPath(trip: StoredTrip): string {
  return `/photo.jpg${trip.photoVersion ? `?v=${trip.photoVersion}` : ""}`;
}

async function handleCreateMusicLog(request: Request, env: Env): Promise<Response> {
  if (!isAuthorized(request, env)) return json({ error: "unauthorized" }, 401, { "www-authenticate": "Bearer" });
  const tooLarge = checkContentLength(request);
  if (tooLarge) return tooLarge;
  try {
    if (!(request.headers.get("content-type") ?? "").startsWith("application/json")) throw new ValidationError("content-type must be application/json");
    const text = await request.text();
    if (text.length > MAX_JSON_BYTES) throw new ValidationError("music log is too large");
    const log = parseMusicLog(parseJson(text));
    const { existed } = await putMusicLog(env, log, Date.now());
    return json({ id: log.id }, existed ? 200 : 201);
  } catch (e) {
    if (e instanceof ValidationError) return json({ error: e.message }, 400);
    throw e;
  }
}

/** 旅に音楽ログを付ける。旅の JSON に同梱されたものも、別送されたものも、D1 から同じ形で読む。 */
async function withMusic(env: Env, trip: StoredTrip): Promise<StoredTrip> {
  return { ...trip, music: await getTripMusic(env, trip) };
}

async function handleGetTripJson(request: Request, env: Env, id: string): Promise<Response> {
  const found = await getTrip(env, id);
  if (!found) return json({ error: "not found" }, 404);
  const trip = await withMusic(env, found);
  const photoUrl = trip.hasPhoto ? `${tripUrl(env, request, id)}${photoPath(trip)}` : null;
  return json({ ...trip, url: tripUrl(env, request, id), photoUrl }, 200, { "cache-control": "public, max-age=60" });
}

async function handleTripPage(env: Env, id: string): Promise<Response> {
  const found = await getTrip(env, id);
  if (!found) return html(renderNotFoundPage(), 404, "no-store");
  const trip = await withMusic(env, found);
  return html(renderTripPage(trip, trip.hasPhoto ? `/t/${id}${photoPath(trip)}` : null));
}

async function handlePhoto(env: Env, id: string): Promise<Response> {
  const obj = await getPhoto(env, id);
  if (!obj) return new Response("not found", { status: 404 });
  const headers = new Headers();
  obj.writeHttpMetadata(headers);
  headers.set("etag", obj.httpEtag);
  headers.set("cache-control", "public, max-age=86400");
  return new Response(obj.body, { headers });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/api/trips") {
      if (request.method === "POST") return handleCreateTrip(request, env);
      return json({ error: "method not allowed" }, 405, { allow: "POST" });
    }
    if (path === "/api/music-logs") {
      if (request.method === "POST") return handleCreateMusicLog(request, env);
      return json({ error: "method not allowed" }, 405, { allow: "POST" });
    }

    let m = /^\/api\/trips\/([^/]+)$/.exec(path);
    if (m) {
      if (request.method !== "GET") return json({ error: "method not allowed" }, 405, { allow: "GET" });
      if (!UUID.test(m[1])) return json({ error: "not found" }, 404);
      return handleGetTripJson(request, env, m[1]);
    }

    m = /^\/t\/([^/]+)\/photo\.jpg$/.exec(path);
    if (m) {
      if (!UUID.test(m[1])) return new Response("not found", { status: 404 });
      return handlePhoto(env, m[1]);
    }

    m = /^\/t\/([^/]+)\/?$/.exec(path);
    if (m) {
      if (!UUID.test(m[1])) return html(renderNotFoundPage(), 404, "no-store");
      return handleTripPage(env, m[1]);
    }

    if (path === "/") return html(renderNotFoundPage(), 404, "no-store");
    return new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
