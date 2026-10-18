import { beforeEach, describe, expect, it } from "vitest";
import { env } from "cloudflare:test";
import worker from "../src/index";
import type { Env } from "../src/store";
import { sampleMusic, sampleTrip } from "./fixtures";

// 本物の D1 / R2（テスト用の一時ストレージ）を使う。隔離はしないので、テストごとに空にする
const fakeEnv = () => env;

beforeEach(async () => {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM music_mood"),
    env.DB.prepare("DELETE FROM music_tracks"),
    env.DB.prepare("DELETE FROM music_logs"),
    env.DB.prepare("DELETE FROM trips"),
  ]);
  const objects = await env.PHOTOS.list();
  await Promise.all(objects.objects.map((o) => env.PHOTOS.delete(o.key)));
});

// Node の Request は本文の長さを自動では付けないので、本物の HTTP クライアントと同じように Content-Length を付けて送る
async function call(env: Env, path: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  let body = init?.body ?? null;
  if (body !== null) {
    const blob = await new Response(body).blob();
    if (!headers.has("content-type") && blob.type) headers.set("content-type", blob.type);
    if (!headers.has("content-length")) headers.set("content-length", String(blob.size));
    body = blob;
  }
  return worker.fetch(new Request(`https://example.test${path}`, { ...init, headers, body }), env);
}

const auth = { authorization: "Bearer secret-token" };

describe("POST /api/trips", () => {
  it("トークンが無ければ 401", async () => {
    const res = await call(fakeEnv(), "/api/trips", { method: "POST", body: "{}" });
    expect(res.status).toBe(401);
  });

  it("JSON だけでも登録でき、公開 URL を返す", async () => {
    const env = fakeEnv();
    const res = await call(env, "/api/trips", {
      method: "POST",
      headers: { ...auth, "content-type": "application/json" },
      body: JSON.stringify(sampleTrip),
    });
    expect(res.status).toBe(201);
    const body = await res.json<{ id: string; url: string }>();
    expect(body).toEqual({ id: sampleTrip.id, url: `https://example.test/t/${sampleTrip.id}` });

    const page = await call(env, `/t/${sampleTrip.id}`);
    expect(page.status).toBe(200);
    expect(await page.text()).toContain("art-placeholder");
  });

  it("multipart で写真も登録できる。再送は 200 で、写真なしの再送でも写真は残る", async () => {
    const env = fakeEnv();
    const form = new FormData();
    form.set("trip", JSON.stringify(sampleTrip));
    form.set("photo", new File([new Uint8Array([0xff, 0xd8, 0xff])], "photo.jpg", { type: "image/jpeg" }));
    const res = await call(env, "/api/trips", { method: "POST", headers: auth, body: form });
    expect(res.status).toBe(201);

    const photo = await call(env, `/t/${sampleTrip.id}/photo.jpg`);
    expect(photo.status).toBe(200);
    expect(photo.headers.get("content-type")).toBe("image/jpeg");

    const again = await call(env, "/api/trips", {
      method: "POST",
      headers: { ...auth, "content-type": "application/json" },
      body: JSON.stringify({ ...sampleTrip, members: ["tuu"] }),
    });
    expect(again.status).toBe(200);
    const api = await call(env, `/api/trips/${sampleTrip.id}`);
    const stored = await api.json<{ members: string[]; photoUrl: string | null }>();
    expect(stored.members).toEqual(["tuu"]);
    expect(stored.photoUrl).toMatch(new RegExp(`^https://example\\.test/t/${sampleTrip.id}/photo\\.jpg\\?v=\\d+$`));
  });

  it("Content-Length が上限を超えると本文を読まずに 413、無ければ 411", async () => {
    const env = fakeEnv();
    const big = new Request("https://example.test/api/trips", {
      method: "POST",
      headers: { ...auth, "content-type": "application/json", "content-length": String(9 * 1024 * 1024 + 1) },
      body: "x",
    });
    const res = await worker.fetch(big, env);
    expect(res.status).toBe(413);
    expect(big.bodyUsed).toBe(false);

    const noLength = new Request("https://example.test/api/trips", { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: "{}" });
    expect(noLength.headers.has("content-length")).toBe(false);
    expect((await worker.fetch(noLength, env)).status).toBe(411);

    // 上限ちょうどまでは通る（本文が JSON として正しければ登録される）
    const ok = await call(env, "/api/trips", {
      method: "POST",
      headers: { ...auth, "content-type": "application/json", "content-length": String(9 * 1024 * 1024) },
      body: JSON.stringify(sampleTrip),
    });
    expect([200, 201, 400]).toContain(ok.status);
    expect(ok.status).not.toBe(413);
  });

  it("不正な JSON は 400 で理由を返す", async () => {
    const res = await call(fakeEnv(), "/api/trips", {
      method: "POST",
      headers: { ...auth, "content-type": "application/json" },
      body: JSON.stringify({ ...sampleTrip, id: "x" }),
    });
    expect(res.status).toBe(400);
    expect((await res.json<{ error: string }>()).error).toContain("id");
  });

  it("trip がファイル名付きのパートでも受ける。大文字の id は小文字の URL で引ける", async () => {
    const env = fakeEnv();
    const form = new FormData();
    form.set("trip", new File([JSON.stringify({ ...sampleTrip, id: sampleTrip.id.toUpperCase() })], "trip.json", { type: "application/json" }));
    const res = await call(env, "/api/trips", { method: "POST", headers: auth, body: form });
    expect(res.status).toBe(201);
    expect((await res.json<{ id: string }>()).id).toBe(sampleTrip.id);
    expect((await call(env, `/t/${sampleTrip.id}`)).status).toBe(200);
  });

  it("空の photo（0 バイト）は写真なしとして扱い、R2 に書かず hasPhoto も立たない", async () => {
    const env = fakeEnv();
    const form = new FormData();
    form.set("trip", JSON.stringify(sampleTrip));
    form.set("photo", new File([], "photo.jpg", { type: "image/jpeg" }));
    const res = await call(env, "/api/trips", { method: "POST", headers: auth, body: form });
    expect(res.status).toBe(201);
    const api = await call(env, `/api/trips/${sampleTrip.id}`);
    const stored = await api.json<{ hasPhoto: boolean; photoUrl: string | null }>();
    expect(stored.hasPhoto).toBe(false);
    expect(stored.photoUrl).toBeNull();
    expect((await call(env, `/t/${sampleTrip.id}/photo.jpg`)).status).toBe(404);
    expect(await (await call(env, `/t/${sampleTrip.id}`)).text()).toContain("art-placeholder");
  });

  it("JPEG 以外の写真は 400", async () => {
    const form = new FormData();
    form.set("trip", JSON.stringify(sampleTrip));
    form.set("photo", new File([new Uint8Array(3)], "p.png", { type: "image/png" }));
    const res = await call(fakeEnv(), "/api/trips", { method: "POST", headers: auth, body: form });
    expect(res.status).toBe(400);
  });
});

describe("音楽ログ", () => {
  const deviceId = "device-abc";
  const log = {
    id: "d4e5f6a7-0000-4000-8000-000000000004",
    deviceId,
    startedAtEpochMillis: sampleTrip.startedAtEpochMillis + 60_000,
    arrivedAtEpochMillis: undefined,
    endedAtEpochMillis: sampleTrip.arrivedAtEpochMillis - 60_000,
    tracks: sampleMusic.tracks,
    mood: sampleMusic.mood,
  };

  it("別送の音楽ログは、同じ端末で時間が重なる旅に結び付く", async () => {
    const env = fakeEnv();
    const posted = await call(env, "/api/music-logs", { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify(log) });
    expect(posted.status).toBe(201);
    const again = await call(env, "/api/music-logs", { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify(log) });
    expect(again.status).toBe(200);

    await call(env, "/api/trips", { method: "POST", headers: { ...auth, "content-type": "application/json", "x-device-id": deviceId }, body: JSON.stringify(sampleTrip) });
    const api = await call(env, `/api/trips/${sampleTrip.id}`);
    const stored = await api.json<{ deviceId: string; music: { tracks: unknown[]; mood: unknown[] } }>();
    expect(stored.deviceId).toBe(deviceId);
    expect(stored.music.tracks).toHaveLength(sampleMusic.tracks.length);
    expect(stored.music.mood).toHaveLength(sampleMusic.mood.length);
    const page = await (await call(env, `/t/${sampleTrip.id}`)).text();
    expect(page).toContain("Lively Tracks");
    expect(page).toContain("峠を越えて");
  });

  it("端末が違う旅や時間が重ならない旅には付かない", async () => {
    const env = fakeEnv();
    await call(env, "/api/music-logs", { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify(log) });
    await call(env, "/api/trips", { method: "POST", headers: { ...auth, "content-type": "application/json", "x-device-id": "other" }, body: JSON.stringify(sampleTrip) });
    expect((await (await call(env, `/api/trips/${sampleTrip.id}`)).json<{ music: unknown }>()).music).toBeNull();

    const later = { ...sampleTrip, id: "e5f6a7b8-0000-4000-8000-000000000005", startedAtEpochMillis: sampleTrip.arrivedAtEpochMillis + 1, arrivedAtEpochMillis: sampleTrip.arrivedAtEpochMillis + 3600_000 };
    await call(env, "/api/trips", { method: "POST", headers: { ...auth, "content-type": "application/json", "x-device-id": deviceId }, body: JSON.stringify(later) });
    expect((await (await call(env, `/api/trips/${later.id}`)).json<{ music: unknown }>()).music).toBeNull();
  });

  it("旅の JSON に同梱した music は別送のログより優先される", async () => {
    const env = fakeEnv();
    await call(env, "/api/music-logs", { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify(log) });
    const inline = { ...sampleTrip, deviceId, music: { tracks: [sampleMusic.tracks[0]], mood: [] } };
    await call(env, "/api/trips", { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify(inline) });
    const stored = await (await call(env, `/api/trips/${sampleTrip.id}`)).json<{ music: { tracks: { title: string }[] } }>();
    expect(stored.music.tracks.map((t) => t.title)).toEqual([sampleMusic.tracks[0].title]);
  });

  it("不正なログは 400、トークン無しは 401", async () => {
    const env = fakeEnv();
    expect((await call(env, "/api/music-logs", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" })).status).toBe(401);
    const bad = await call(env, "/api/music-logs", { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify({ ...log, deviceId: "" }) });
    expect(bad.status).toBe(400);
    const empty = await call(env, "/api/music-logs", { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify({ ...log, tracks: [], mood: [] }) });
    expect(empty.status).toBe(400);
  });
});

describe("GET", () => {
  it("無い id は 404 ページ", async () => {
    const res = await call(fakeEnv(), "/t/00000000-0000-0000-0000-000000000000");
    expect(res.status).toBe(404);
    expect(await res.text()).toContain("見つかりませんでした");
  });

  it("UUID でないパスは 404", async () => {
    expect((await call(fakeEnv(), "/t/../etc")).status).toBe(404);
    expect((await call(fakeEnv(), "/api/trips/abc")).status).toBe(404);
  });
});
