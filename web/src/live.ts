// 走行中の景観BGMの様子（ライブ表示）。車の端末が数秒ごとに送る最新の1件の形と、その読み取り。
// 送るのは画面に出す値だけ。歌詞・音声・同乗者の名前・プレイリストの持ち主は含めない。

import { ValidationError } from "./trip";

/** ライブ表示の URL に使う受付番号。推測されないよう、端末が 22 文字の乱数（base64url）で作る。 */
export const LIVE_CODE = /^[A-Za-z0-9_-]{22}$/;

/** 最後に届いてからこの時間が過ぎたら、終わったものとして扱う（電波が切れたまま放置されたときなど）。 */
export const LIVE_TTL_MILLIS = 30 * 60 * 1000;
/** 到着してからページに「着きました」を出しておく時間。 */
export const LIVE_ARRIVED_TTL_MILLIS = 10 * 60 * 1000;

export const AXES = ["energy", "valence", "tempo", "acoustic", "spacious", "vocal"] as const;
export type Axis = (typeof AXES)[number];
export const CABIN_STATES = ["lively", "normal", "quiet", "resting"] as const;
export type CabinState = (typeof CABIN_STATES)[number];

export type LiveSegment = { startM: number; endM: number; category: string };

export type LiveCandidate = {
  title: string;
  artist: string;
  score: number;
  prob: number;
  distance: number;
  theme: string | null;
  themeMatch: number;
  pref: "track" | "artist" | null;
  sameArtist: boolean;
  popularity: number;
  confPenalty: number;
  /** 曲の6軸（0〜1）。欲しい曲調と重ねて描き、景色に合っていることを見せる。無ければ null */
  axes: Record<Axis, number> | null;
};

/** 景観BGMで流した曲。startM / endM は流し始めた・終えた地点（経路の始点からの距離）。 */
export type LivePlayed = {
  title: string;
  artist: string;
  message: string | null;
  theme: string | null;
  /** その曲を決めたときに見ていた区間で一番多かった景色（理由の一言と同じ景色） */
  scene: string | null;
  startM: number | null;
  endM: number | null;
};

export type LiveEvaluation = {
  at: number;
  /** preview：今決めるならこの曲（途中の評価）／ decided：次の曲として決めた */
  kind: "preview" | "decided";
  /** 次の曲が流れる区間（経路の始点からの距離） */
  window: { startM: number; endM: number } | null;
  /** その区間の景色の内訳（カテゴリ → 0〜1） */
  shares: Record<string, number>;
  reasons: string[];
  target: Record<Axis, number>;
  themes: Record<string, number>;
  slot: "score" | "theme";
  candidates: LiveCandidate[];
  chosen: { title: string; artist: string; message: string | null } | null;
};

export type LiveSnapshot = {
  at: number;
  status: "driving" | "arrived";
  destination: string | null;
  car: { latitude: number; longitude: number; speedKmh: number; heading: number | null } | null;
  /** key は経路が変わったら変わる値（再探索で差し替わったとき、ブラウザが形を読み直す） */
  route: { key: string; positionM: number; totalM: number; segments: LiveSegment[] } | null;
  cabin: { state: CabinState; speechRatio: number; speaking: boolean } | null;
  nowPlaying: {
    title: string;
    artist: string;
    positionMs: number;
    durationMs: number;
    playing: boolean;
    kind: "bgm" | "request" | "other";
  } | null;
  evaluation: LiveEvaluation | null;
  /** 景観BGMで流した曲（新しい順、30曲まで） */
  played: LivePlayed[];
};

/** 経路の形。[経度, 緯度] の並び。route.key が変わったときだけ送られてくる。 */
export type LiveGeometry = [number, number][];

export type LiveUpload = { snapshot: LiveSnapshot; geometry: LiveGeometry | null };

const MAX_SEGMENTS = 400;
const MAX_CANDIDATES = 10;
const MAX_GEOMETRY = 5000;

function fail(msg: string): never {
  throw new ValidationError(msg);
}

function obj(v: unknown, name: string): Record<string, unknown> {
  if (typeof v !== "object" || v === null || Array.isArray(v)) fail(`${name} must be an object`);
  return v as Record<string, unknown>;
}

function num(v: unknown, name: string, min = -Infinity, max = Infinity): number {
  if (typeof v !== "number" || !Number.isFinite(v) || v < min || v > max) fail(`${name} must be a number in ${min}..${max}`);
  return v;
}

function ratio(v: unknown, name: string): number {
  return num(v, name, 0, 1);
}

/** 画面に出す文字列。長すぎるものは切り詰める（弾くと、曲名が長いだけで表示が止まるため）。 */
function text(v: unknown, name: string, max = 120): string {
  if (typeof v !== "string") fail(`${name} must be a string`);
  return v.slice(0, max);
}

function optText(v: unknown, name: string, max = 120): string | null {
  return v == null ? null : text(v, name, max);
}

function list(v: unknown, name: string, max: number): unknown[] {
  if (!Array.isArray(v)) fail(`${name} must be an array`);
  if (v.length > max) fail(`${name} must have at most ${max} items`);
  return v;
}

function oneOf<T extends string>(v: unknown, values: readonly T[], name: string): T {
  if (typeof v !== "string" || !(values as readonly string[]).includes(v)) fail(`${name} must be one of ${values.join(", ")}`);
  return v as T;
}

/** カテゴリ名や題材名 → 0〜1 の対応。キーは短い英小文字だけにする。 */
function ratios(v: unknown, name: string): Record<string, number> {
  const o = obj(v, name);
  const out: Record<string, number> = {};
  const keys = Object.keys(o);
  if (keys.length > 32) fail(`${name} has too many keys`);
  for (const k of keys) {
    if (!/^[a-z_]{1,32}$/.test(k)) fail(`${name} has an invalid key`);
    out[k] = ratio(o[k], `${name}.${k}`);
  }
  return out;
}

function parseCandidate(v: unknown, i: number): LiveCandidate {
  const c = obj(v, `evaluation.candidates[${i}]`);
  const n = `evaluation.candidates[${i}]`;
  return {
    title: text(c.title, `${n}.title`),
    artist: text(c.artist, `${n}.artist`),
    score: num(c.score, `${n}.score`, -10, 10),
    prob: ratio(c.prob, `${n}.prob`),
    distance: num(c.distance, `${n}.distance`, 0, 10),
    theme: optText(c.theme, `${n}.theme`, 32),
    themeMatch: ratio(c.themeMatch ?? 0, `${n}.themeMatch`),
    pref: c.pref == null ? null : oneOf(c.pref, ["track", "artist"] as const, `${n}.pref`),
    sameArtist: c.sameArtist === true,
    popularity: ratio(c.popularity ?? 0, `${n}.popularity`),
    confPenalty: num(c.confPenalty ?? 0, `${n}.confPenalty`, 0, 1),
    axes: c.axes == null ? null : axesOf(c.axes, `${n}.axes`),
  };
}

function axesOf(v: unknown, name: string): Record<Axis, number> {
  const o = obj(v, name);
  return Object.fromEntries(AXES.map((a) => [a, ratio(o[a], `${name}.${a}`)])) as Record<Axis, number>;
}

function parseEvaluation(v: unknown): LiveEvaluation {
  const e = obj(v, "evaluation");
  const target = e.target;
  const window = e.window == null ? null : obj(e.window, "evaluation.window");
  const chosen = e.chosen == null ? null : obj(e.chosen, "evaluation.chosen");
  return {
    at: num(e.at, "evaluation.at", 0),
    kind: oneOf(e.kind, ["preview", "decided"] as const, "evaluation.kind"),
    window: window ? { startM: num(window.startM, "evaluation.window.startM", 0), endM: num(window.endM, "evaluation.window.endM", 0) } : null,
    shares: ratios(e.shares ?? {}, "evaluation.shares"),
    reasons: list(e.reasons ?? [], "evaluation.reasons", 10).map((r, i) => text(r, `evaluation.reasons[${i}]`, 60)),
    target: axesOf(target, "evaluation.target"),
    themes: ratios(e.themes ?? {}, "evaluation.themes"),
    slot: oneOf(e.slot ?? "score", ["score", "theme"] as const, "evaluation.slot"),
    candidates: list(e.candidates ?? [], "evaluation.candidates", MAX_CANDIDATES).map(parseCandidate),
    chosen: chosen
      ? { title: text(chosen.title, "evaluation.chosen.title"), artist: text(chosen.artist, "evaluation.chosen.artist"), message: optText(chosen.message, "evaluation.chosen.message", 200) }
      : null,
  };
}

function parseGeometry(v: unknown): LiveGeometry {
  return list(v, "geometry", MAX_GEOMETRY).map((p, i) => {
    const pair = list(p, `geometry[${i}]`, 2);
    if (pair.length !== 2) fail(`geometry[${i}] must be [longitude, latitude]`);
    return [num(pair[0], `geometry[${i}][0]`, -180, 180), num(pair[1], `geometry[${i}][1]`, -90, 90)];
  });
}

export function parseLiveUpload(raw: unknown): LiveUpload {
  const s = obj(raw, "body");
  const car = s.car == null ? null : obj(s.car, "car");
  const route = s.route == null ? null : obj(s.route, "route");
  const cabin = s.cabin == null ? null : obj(s.cabin, "cabin");
  const now = s.nowPlaying == null ? null : obj(s.nowPlaying, "nowPlaying");
  const snapshot: LiveSnapshot = {
    at: num(s.at, "at", 0),
    status: oneOf(s.status ?? "driving", ["driving", "arrived"] as const, "status"),
    destination: optText(s.destination, "destination", 60),
    car: car
      ? {
          latitude: num(car.latitude, "car.latitude", -90, 90),
          longitude: num(car.longitude, "car.longitude", -180, 180),
          speedKmh: num(car.speedKmh ?? 0, "car.speedKmh", 0, 400),
          heading: car.heading == null ? null : num(car.heading, "car.heading", 0, 360),
        }
      : null,
    route: route
      ? {
          key: text(route.key, "route.key", 64),
          positionM: num(route.positionM, "route.positionM", 0),
          totalM: num(route.totalM, "route.totalM", 0),
          segments: list(route.segments ?? [], "route.segments", MAX_SEGMENTS).map((v, i) => {
            const g = obj(v, `route.segments[${i}]`);
            return {
              startM: num(g.startM, `route.segments[${i}].startM`, 0),
              endM: num(g.endM, `route.segments[${i}].endM`, 0),
              category: text(g.category, `route.segments[${i}].category`, 32),
            };
          }),
        }
      : null,
    cabin: cabin
      ? { state: oneOf(cabin.state, CABIN_STATES, "cabin.state"), speechRatio: ratio(cabin.speechRatio, "cabin.speechRatio"), speaking: cabin.speaking === true }
      : null,
    nowPlaying: now
      ? {
          title: text(now.title, "nowPlaying.title"),
          artist: text(now.artist ?? "", "nowPlaying.artist"),
          positionMs: num(now.positionMs ?? 0, "nowPlaying.positionMs", 0),
          durationMs: num(now.durationMs ?? 0, "nowPlaying.durationMs", 0),
          playing: now.playing === true,
          kind: oneOf(now.kind ?? "other", ["bgm", "request", "other"] as const, "nowPlaying.kind"),
        }
      : null,
    evaluation: s.evaluation == null ? null : parseEvaluation(s.evaluation),
    played: list(s.played ?? [], "played", 30).map((v, i) => {
      const p = obj(v, `played[${i}]`);
      return {
        title: text(p.title, `played[${i}].title`),
        artist: text(p.artist ?? "", `played[${i}].artist`),
        message: optText(p.message, `played[${i}].message`, 200),
        theme: optText(p.theme, `played[${i}].theme`, 32),
        scene: optText(p.scene, `played[${i}].scene`, 32),
        startM: p.startM == null ? null : num(p.startM, `played[${i}].startM`, 0),
        endM: p.endM == null ? null : num(p.endM, `played[${i}].endM`, 0),
      };
    }),
  };
  return { snapshot, geometry: s.geometry == null ? null : parseGeometry(s.geometry) };
}

/** 最後に届いた時刻と状態から、いつまで見せるかを決める。 */
export function liveExpiresAt(snapshot: LiveSnapshot, receivedAt: number): number {
  return receivedAt + (snapshot.status === "arrived" ? LIVE_ARRIVED_TTL_MILLIS : LIVE_TTL_MILLIS);
}
