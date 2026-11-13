// ライブ表示の開発用。車の端末の代わりに、模擬の走行で「推薦の様子」を作ってライブ表示へ送り続ける。
// 選曲は bgm-api の本物の推薦エンジン（engine.ts）と景観の変換（scenic.ts）、全曲カタログで計算するので、
// 画面の点数や候補は実際のアプリと同じ決め方になる。経路と景色の区間、車内の話し声は作り物。
//
// 使い方（web/ で。別のターミナルで npm run dev を動かしておく）:
//   npx --prefix ../infra/bgm-api tsx scripts/live-replay.ts [--speed 3] [--url http://localhost:8787] [--code <22文字>]
// トークンは環境変数 TRIP_UPLOAD_TOKEN（無ければ .dev.vars の値、それも無ければ dev-token）。
//
// --record public/live-demo.json を付けると、送らずに最後まで一気に走り、1秒ごとの様子をファイルに書き出す。
// /live/demo のページはこのファイルをブラウザで頭から再生する（いつでも同じ経路を見せるため）。

import { randomBytes } from "node:crypto";
import { readFileSync, existsSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { recommend, THEMES, type CabinState, type HistoryEntry, type PoolTrack, type Theme, type Vec } from "../../infra/bgm-api/src/engine";
import { sceneFromScenicRoute, type ScenicBlock } from "../../infra/bgm-api/src/scenic";

const here = path.dirname(fileURLToPath(import.meta.url));
const arg = (name: string, fallback: string) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};
const RECORD = arg("record", "");
const SPEED = RECORD ? 1 : Math.max(1, Number(arg("speed", "3"))); // 1秒で何秒ぶん進めるか
const BASE = arg("url", "http://localhost:8787").replace(/\/+$/, "");
const CODE = arg("code", randomBytes(16).toString("base64url").slice(0, 22));
const TOKEN = process.env.TRIP_UPLOAD_TOKEN ?? readDevVars() ?? "dev-token";

const PREVIEW_EVERY_S = 5; // 途中の評価の間隔（模擬の時間）
const DECIDE_BEFORE_MS = 30_000; // 曲の終わりの何秒前に次の曲を決めるか（アプリと同じ）

function readDevVars(): string | null {
  const file = path.join(here, "..", ".dev.vars");
  if (!existsSync(file)) return null;
  const m = /^TRIP_UPLOAD_TOKEN\s*=\s*"?([^"\n]+)"?/m.exec(readFileSync(file, "utf8"));
  return m ? m[1].trim() : null;
}

// ---- 全曲カタログ（bgm-api の rowToFeatures と同じ読み方） ----
function loadPool(): PoolTrack[] {
  const file = path.join(here, "..", "..", "infra", "bgm-api", "catalog", "catalog.jsonl");
  return readFileSync(file, "utf8").split("\n").filter(Boolean).map((line) => {
    const r = JSON.parse(line);
    let themes: Partial<Record<Theme, number>> | undefined;
    try {
      const o = JSON.parse(r.themes_json ?? "null");
      if (o) {
        themes = {};
        for (const th of THEMES) if (typeof o[th] === "number" && o[th] > 0 && o[th] <= 1) themes[th] = o[th];
      }
    } catch { /* 題材なし */ }
    return {
      track_id: r.track_id, title: r.title, artist: r.artist, duration_ms: r.duration_ms,
      energy: r.energy, valence: r.valence, tempo: r.tempo, acoustic: r.acoustic, spacious: r.spacious, vocal: r.vocal,
      energy_cap: r.energy_cap ?? undefined, conf: r.conf, popularity: r.popularity ?? r.jp_familiarity ?? undefined, themes,
    } as PoolTrack;
  });
}

// ---- デモ用の経路：鎌倉駅から海沿いを通って茅ヶ崎まで（手で置いた点を 50m ごとにつないだもの） ----
const WAYPOINTS: [number, number][] = [
  [139.5503, 35.3190], [139.5390, 35.3110], [139.5240, 35.3010], [139.5130, 35.3050], [139.4890, 35.3070],
  [139.4810, 35.3110], [139.4650, 35.3160], [139.4430, 35.3200], [139.4050, 35.3250], [139.4040, 35.3330],
];
function haversine(a: [number, number], b: [number, number]): number {
  const R = 6371008.8, p1 = (a[1] * Math.PI) / 180, p2 = (b[1] * Math.PI) / 180;
  const dp = p2 - p1, dl = ((b[0] - a[0]) * Math.PI) / 180;
  const h = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}
const geometry: [number, number][] = [];
const cumulative: number[] = [];
for (let i = 0; i < WAYPOINTS.length - 1; i++) {
  const a = WAYPOINTS[i], b = WAYPOINTS[i + 1];
  const n = Math.max(1, Math.ceil(haversine(a, b) / 50));
  for (let k = 0; k < n; k++) geometry.push([a[0] + ((b[0] - a[0]) * k) / n, a[1] + ((b[1] - a[1]) * k) / n]);
}
geometry.push(WAYPOINTS[WAYPOINTS.length - 1]);
geometry.forEach((p, i) => cumulative.push(i === 0 ? 0 : cumulative[i - 1] + haversine(geometry[i - 1], p)));
const TOTAL = cumulative[cumulative.length - 1];

// 景色の区間（全長に対する割合で置く）
const SEGMENTS = ([
  [0, 0.08, "urban"], [0.08, 0.2, "sea"], [0.2, 0.27, "forest"], [0.27, 0.52, "sea"], [0.52, 0.58, "urban"],
  [0.58, 0.8, "sea"], [0.8, 0.88, "forest"], [0.88, 1, "urban"],
] as const).map(([a, b, category]) => ({ startM: Math.round(a * TOTAL), endM: Math.round(b * TOTAL), category }));

function pointAt(m: number): [number, number] {
  let i = 1;
  while (i < cumulative.length - 1 && cumulative[i] < m) i++;
  const span = cumulative[i] - cumulative[i - 1];
  const t = span > 0 ? Math.min(1, Math.max(0, (m - cumulative[i - 1]) / span)) : 0;
  return [geometry[i - 1][0] + (geometry[i][0] - geometry[i - 1][0]) * t, geometry[i - 1][1] + (geometry[i][1] - geometry[i - 1][1]) * t];
}

// ---- 模擬の状態 ----
const pool = loadPool();
const members = [{ id: "me", name: "わたし" }];
// 書き出すデモは、夏の昼に固定する（朝や夜に計算すると時間帯の題材が前に出て、景色で選んでいることが伝わりにくい）
const startClock = RECORD ? Date.parse("2026-08-08T14:00:00+09:00") : Date.now();
// 書き出すデモは毎回同じになるよう、乱数を固定する
let rngState = 20260808;
const random = () => (RECORD ? ((rngState = (rngState * 1664525 + 1013904223) >>> 0) / 2 ** 32) : Math.random());
/** 日本時間の「時」。マシンのタイムゾーンによらない */
const jstHour = (ms: number) => new Date(ms + 9 * 3600_000).getUTCHours();
let simMs = 0; // 出発からの模擬の経過時間
let positionM = 0;
let speechRatio = 0.15;
let quietSinceMs: number | null = null;
const history: HistoryEntry[] = [];
const played: { title: string; artist: string; message: string | null; theme: string | null; startM: number; endM: number | null }[] = [];
let previousTarget: Partial<Vec> | undefined;
let current: { track: PoolTrack; startedAtMs: number; message: string | null } | null = null;
let next: { track: PoolTrack; message: string | null; theme: string | null } | null = null;
let evaluation: ReturnType<typeof toEvaluation> | null = null;
let seed = 1;
let lastPreviewS = -Infinity;

const speedKmh = (ms: number) => 38 + 14 * Math.sin(ms / 47_000) + 6 * Math.sin(ms / 13_000);
const nowIso = (ms: number) => new Date(startClock + ms + 9 * 3600_000).toISOString().replace(/\.\d+Z$/, "+09:00");

/** 景色の区間に、今の速さで走った場合の到着時刻を付ける（窓の長さと時間帯の計算に使われる）。 */
function timeline(): ScenicBlock[] {
  const v = speedKmh(simMs) / 3.6;
  return SEGMENTS.map((s) => ({
    start_m: s.startM, end_m: s.endM, category: s.category,
    arrival_time: nowIso(simMs + Math.max(0, (s.startM - positionM) / v) * 1000),
  }));
}

function cabinState(): CabinState {
  if (speechRatio >= 0.4) return "lively";
  if (quietSinceMs !== null && simMs - quietSinceMs >= 180_000) return "quiet";
  return "normal";
}

function evaluate(kind: "preview" | "decided", durationMs: number) {
  const window = sceneFromScenicRoute({ position_m: positionM, timeline: timeline(), next_duration_ms: durationMs });
  const result = recommend({
    pool, members, history, seed, top_k: 10, cabin: cabinState(), previous_target: previousTarget,
    scene: { ...window.scene, hour: jstHour(startClock + simMs) },
  });
  return { result, evaluation: toEvaluation(kind, window, result) };
}

function toEvaluation(kind: "preview" | "decided", window: ReturnType<typeof sceneFromScenicRoute>, r: ReturnType<typeof recommend>) {
  const shares: Record<string, number> = {};
  for (const [k, v] of Object.entries(window.shares)) if (typeof v === "number") shares[k] = Math.round(v * 1000) / 1000;
  return {
    at: startClock + simMs,
    kind,
    window: { startM: window.window.start_m, endM: window.window.end_m },
    shares,
    reasons: r.plan.reasons ?? [],
    target: r.plan.target,
    themes: r.plan.themes ?? {},
    slot: r.slot,
    candidates: r.candidates.map((c: any) => ({
      title: c.title, artist: c.artist, score: c.score, prob: c.prob, distance: c.distance,
      theme: c.theme, themeMatch: c.theme_match, pref: c.pref, sameArtist: c.same_artist === 1,
      popularity: c.popularity, confPenalty: c.conf_penalty, axes: axesOf(c.track_id),
    })),
    chosen: r.chosen ? { title: r.chosen.title, artist: r.chosen.artist, message: r.chosen.message ?? null } : null,
  };
}

const byId = new Map(pool.map((t) => [t.track_id, t]));
function axesOf(id: string) {
  const t = byId.get(id);
  return t ? { energy: t.energy, valence: t.valence, tempo: t.tempo, acoustic: t.acoustic, spacious: t.spacious, vocal: t.vocal } : null;
}

/** 選んだ曲が題材で合っていれば、その題材（演出の一言と同じ判断） */
function chosenTheme(r: ReturnType<typeof recommend>): string | null {
  const c = r.candidates.find((x: any) => x.track_id === r.chosen?.track_id) as any;
  return c && c.theme && c.theme_match > 0 ? c.theme : null;
}

function trackOf(r: ReturnType<typeof recommend>): PoolTrack | null {
  return r.chosen ? pool.find((t) => t.track_id === r.chosen!.track_id) ?? null : null;
}

function startSong(track: PoolTrack, message: string | null, theme: string | null) {
  current = { track, startedAtMs: simMs, message };
  history.push({ track_id: track.track_id, artist: track.artist });
  if (played[0]) played[0] = { ...played[0], endM: Math.round(positionM) };
  played.unshift({ title: track.title, artist: track.artist, message, theme, startM: Math.round(positionM), endM: null });
  played.splice(30);
  seed++;
  next = null;
  lastPreviewS = -Infinity;
}

function step() {
  const arrived = positionM >= TOTAL;
  if (!arrived) {
    positionM = Math.min(TOTAL, positionM + (speedKmh(simMs) / 3.6) * SPEED);
    // 話し声の割合はゆっくり上下させ、ときどき盛り上がる
    // 話し声の割合は 0.15 前後をゆっくり上下させる（デモでは景色で選ぶ様子を見せたいので、盛り上がりは控えめ）
    speechRatio = Math.min(0.9, Math.max(0, speechRatio + (0.15 - speechRatio) * 0.05 + (random() - 0.5) * 0.05));
    if (speechRatio < 0.05) quietSinceMs ??= simMs; else quietSinceMs = null;
  }

  if (!current) {
    const { result, evaluation: e } = evaluate("decided", 210_000);
    const t = trackOf(result);
    evaluation = e;
    if (t) startSong(t, result.chosen?.message ?? null, chosenTheme(result));
  } else if (!arrived) {
    const duration = current.track.duration_ms ?? 210_000;
    const elapsed = simMs - current.startedAtMs;
    if (elapsed >= duration && next) {
      previousTarget = evaluation?.target;
      startSong(next.track, next.message, next.theme);
    } else if (!next && duration - elapsed <= DECIDE_BEFORE_MS) {
      const { result, evaluation: e } = evaluate("decided", 210_000);
      evaluation = e;
      const t = trackOf(result);
      if (t) next = { track: t, message: result.chosen?.message ?? null, theme: chosenTheme(result) };
    } else if (!next && simMs / 1000 - lastPreviewS >= PREVIEW_EVERY_S) {
      lastPreviewS = simMs / 1000;
      evaluation = evaluate("preview", 210_000).evaluation;
    }
  }

  const [lon, lat] = pointAt(positionM);
  const [lon2, lat2] = pointAt(Math.min(TOTAL, positionM + 30));
  const heading = (Math.atan2(lon2 - lon, lat2 - lat) * 180) / Math.PI;
  const duration = current?.track.duration_ms ?? 0;
  return {
    at: startClock + simMs,
    status: arrived ? "arrived" : "driving",
    destination: "茅ヶ崎",
    car: { latitude: lat, longitude: lon, speedKmh: arrived ? 0 : Math.round(speedKmh(simMs)), heading: (heading + 360) % 360 },
    route: { key: "demo-shonan", positionM: Math.round(positionM), totalM: Math.round(TOTAL), segments: SEGMENTS },
    cabin: { state: cabinState(), speechRatio: Math.round(speechRatio * 100) / 100, speaking: random() < speechRatio },
    nowPlaying: current
      ? { title: current.track.title, artist: current.track.artist, positionMs: Math.min(duration, simMs - current.startedAtMs), durationMs: duration, playing: !arrived, kind: "bgm" }
      : null,
    evaluation,
    played,
  };
}

async function send(body: unknown): Promise<void> {
  const text = JSON.stringify(body);
  const res = await fetch(`${BASE}/api/live/${CODE}`, {
    method: "PUT",
    headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json", "content-length": String(Buffer.byteLength(text)) },
    body: text,
  });
  if (!res.ok) throw new Error(`PUT /api/live → ${res.status} ${await res.text()}`);
}

console.log(`カタログ ${pool.length} 曲、経路 ${(TOTAL / 1000).toFixed(1)} km、${SPEED} 倍速`);
if (RECORD) {
  record(RECORD);
  process.exit(0);
}
console.log(`ライブ表示: ${BASE}/live/${CODE}`);

/**
 * 1秒ごとの様子を書き出す。経路の形と景色の区間は1回だけ、評価は変わったときだけ持ち、
 * 各秒の様子からは番号で指す（ファイルを小さくするため）。時刻は出発からの秒（at は再生時に付け直す）。
 */
function record(file: string) {
  const evaluations: unknown[] = [];
  const frames: unknown[] = [];
  let lastEval: unknown = null;
  let arrivedFrames = 0;
  for (;;) {
    const s = step();
    if (s.evaluation && s.evaluation !== lastEval) {
      lastEval = s.evaluation;
      evaluations.push({ ...s.evaluation, at: Math.round((s.evaluation.at - startClock) / 1000) });
    }
    const { evaluation: _e, route, at: _at, ...rest } = s;
    frames.push({ ...rest, route: { positionM: route.positionM }, e: s.evaluation ? evaluations.length - 1 : -1, played: s.played.map((p) => ({ ...p })) });
    if (s.status === "arrived" && ++arrivedFrames >= 3) break;
    simMs += 1000;
  }
  const out = {
    destination: "茅ヶ崎",
    route: { key: "demo-shonan", totalM: Math.round(TOTAL), segments: SEGMENTS },
    geometry: geometry.map(([lon, lat]) => [Math.round(lon * 1e5) / 1e5, Math.round(lat * 1e5) / 1e5]),
    evaluations,
    frames,
  };
  writeFileSync(file, JSON.stringify(out));
  console.log(`${frames.length} 秒ぶん（評価 ${evaluations.length} 回）を ${file} に書き出しました`);
}

let tick = 0;
let arrivedTicks = 0;
for (;;) {
  const snapshot = step();
  // 経路の形は最初と、ときどき送る（サーバーが入れ替わっても形が戻るように）
  await send(tick % 30 === 0 ? { ...snapshot, geometry } : snapshot).catch((e) => console.error(String(e)));
  if (snapshot.status === "arrived" && ++arrivedTicks >= 3) break;
  tick++;
  simMs += SPEED * 1000;
  await new Promise((r) => setTimeout(r, 1000));
}
console.log("到着しました。");
