import { beforeEach, describe, expect, it } from "vitest";
import { env } from "cloudflare:test";
import worker from "../src/index";
import { LIVE_TTL_MILLIS, parseLiveUpload } from "../src/live";
import { getLive, putLive } from "../src/store";

beforeEach(async () => {
  await env.DB.prepare("DELETE FROM live_sessions").run();
});

const code = "AbCdEfGhIjKlMnOpQrStUv";
const auth = { authorization: "Bearer secret-token" };

const snapshot = (over: Record<string, unknown> = {}) => ({
  at: 1_800_000_000_000,
  status: "driving",
  destination: "江の島",
  car: { latitude: 35.3, longitude: 139.48, speedKmh: 48, heading: 120 },
  route: { key: "r1", positionM: 1200, totalM: 20000, segments: [{ startM: 0, endM: 5000, category: "urban" }, { startM: 5000, endM: 20000, category: "sea" }] },
  cabin: { state: "lively", speechRatio: 0.52, speaking: true },
  nowPlaying: { title: "海の曲", artist: "A", positionMs: 60000, durationMs: 200000, playing: true, kind: "bgm" },
  evaluation: {
    at: 1_800_000_000_000,
    kind: "preview",
    window: { startM: 1200, endM: 4800 },
    shares: { urban: 0.7, sea: 0.3 },
    reasons: ["海が近い"],
    target: { energy: 0.6, valence: 0.7, tempo: 0.5, acoustic: 0.4, spacious: 0.6, vocal: 0.5 },
    themes: { sea: 0.9 },
    slot: "score",
    candidates: [{ title: "海の曲", artist: "A", score: -0.2, prob: 0.3, distance: 0.25, theme: "sea", themeMatch: 0.7, pref: null, sameArtist: false, popularity: 0.5, confPenalty: 0 }],
    chosen: null,
  },
  played: [],
  ...over,
});

async function put(body: unknown, headers: Record<string, string> = auth, path = `/api/live/${code}`) {
  const text = JSON.stringify(body);
  return worker.fetch(
    new Request(`https://example.test${path}`, {
      method: "PUT",
      headers: { ...headers, "content-type": "application/json", "content-length": String(new TextEncoder().encode(text).byteLength) },
      body: text,
    }),
    env,
  );
}

const get = (path: string) => worker.fetch(new Request(`https://example.test${path}`), env);

describe("ライブ表示の受け口", () => {
  it("トークンが無いと受け付けない", async () => {
    expect((await put(snapshot(), {})).status).toBe(401);
    expect(await (await get(`/api/live/${code}`)).json()).toMatchObject({ open: false });
  });

  it("最新の様子で上書きし、だれでも読める", async () => {
    const res = await put({ ...snapshot(), geometry: [[139.4, 35.3], [139.5, 35.31]] });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ code, url: `https://example.test/live/${code}` });
    await put(snapshot({ car: { latitude: 35.31, longitude: 139.49, speedKmh: 52 } }));
    const live = await get(`/api/live/${code}`);
    expect(live.headers.get("cache-control")).toBe("no-store");
    const body = (await live.json()) as { open: boolean; routeKey: string; snapshot: { car: { speedKmh: number; heading: number | null } } };
    expect(body.open).toBe(true);
    expect(body.routeKey).toBe("r1");
    expect(body.snapshot.car).toEqual({ latitude: 35.31, longitude: 139.49, speedKmh: 52, heading: null });
  });

  it("経路の形は送られたときだけ差し替え、経路が変わったら捨てる", async () => {
    await put({ ...snapshot(), geometry: [[139.4, 35.3], [139.5, 35.31]] });
    await put(snapshot());
    expect(await (await get(`/api/live/${code}/route`)).json()).toEqual({ routeKey: "r1", geometry: [[139.4, 35.3], [139.5, 35.31]] });
    // 再探索で経路が変わったのに形が届いていない：古い形を新しい経路として見せない
    await put(snapshot({ route: { key: "r2", positionM: 0, totalM: 9000, segments: [] } }));
    expect((await get(`/api/live/${code}/route`)).status).toBe(404);
  });

  it("届かなくなって時間がたったら、終わったものとして扱う", async () => {
    const now = 1_800_000_000_000;
    await putLive(env, code, parseLiveUpload(snapshot()), now);
    expect(await getLive(env, code, now + LIVE_TTL_MILLIS - 1)).not.toBeNull();
    expect(await getLive(env, code, now + LIVE_TTL_MILLIS)).toBeNull();
  });

  it("おかしな値は 400 で弾き、受付番号の形が違えば 404", async () => {
    expect((await put(snapshot({ cabin: { state: "sleeping", speechRatio: 0.1 } }))).status).toBe(400);
    expect((await put(snapshot({ car: { latitude: 91, longitude: 0 } }))).status).toBe(400);
    expect((await put({ ...snapshot(), geometry: [[0]] })).status).toBe(400);
    expect((await put(snapshot(), auth, "/api/live/short")).status).toBe(404);
    expect((await get("/live/short")).status).toBe(404);
  });

  it("長すぎる曲名は切り詰めて受ける", () => {
    const long = "あ".repeat(500);
    const parsed = parseLiveUpload(snapshot({ nowPlaying: { title: long, artist: "A", positionMs: 0, durationMs: 1, playing: true, kind: "bgm" } }));
    expect(parsed.snapshot.nowPlaying?.title.length).toBe(120);
  });

  it("デモのページは車から受け取らず、デモの走行を再生する", async () => {
    const res = await get("/live/demo");
    expect(res.status).toBe(200);
    const html = await res.text();
    expect(html).toContain('"demo":true');
    expect(html).toContain("/live/demo?speed=2");
    expect(html).not.toContain('id="mode-auto"');
  });

  it("ページは枠だけを返し、受付番号を埋め込む", async () => {
    const res = await get(`/live/${code}`);
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    const html = await res.text();
    expect(html).toContain(`"code":"${code}"`);
    expect(html).toContain('id="cands"');
    // 受付番号のページは、実際の走行とデモを切り替えられる
    expect(html).toContain('id="mode-auto"');
    expect(html).toContain('id="mode-demo"');
  });
});
