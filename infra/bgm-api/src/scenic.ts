// 景観エンジン（scenic-routing v2）のタイムライン → 推薦に使う Scene への変換。
// 次の曲が流れる区間（窓）を到着時刻から先読みし、窓の中の景観カテゴリの占有率を特徴量にする。
// DB・HTTP に依存しない純粋関数（engine.ts と同じく、後で端末側に移植できるように）

import type { FeatureKey, Scene } from './engine';

// 景観エンジンの timeline[] の1ブロック。名道の固有名・物理道路ID（physical_position）は受け取らない
export type ScenicBlock = {
  start_m: number;
  end_m: number;
  // sea / forest / inland_water / open_nature / farmland / townscape / lake / river / valley / urban / unclassified / unknown
  category: string;
  confidence?: number;
  openness?: number | null;
  visibility_verified?: boolean;
  // recommended_roads[].rank のうち最上位（signature / panorama / recommended / scenic）。無ければ省略
  recommended_rank?: string | null;
  arrival_time?: string; // ISO 8601（オフセット付き）。その地点への到着予測時刻
};

export type ScenicRoute = {
  position_m: number; // 今の位置（経路始点からの距離）。次の曲はここから流れ始める想定
  timeline: ScenicBlock[];
  reveals?: { start_m: number }[]; // evaluation.reveals[]（海が見え始める地点）
  next_duration_ms?: number; // 次の曲の長さ。分からなければ既定値
};

export type ScenicWindow = {
  scene: Scene;
  window: { start_m: number; end_m: number; duration_s: number; speed_kmh: number };
  coverage: number; // 窓のうち景観が評価済みの割合（unknown 以外）
  shares: Partial<Record<string, number>>; // 窓の中のカテゴリ別の占有率（確認用）
  reveal: { start_m: number; eta_s: number } | null; // 次の曲の間に海が見え始める地点があれば
};

// 変換の係数（初期値。走行テストで調整する）
export const SCENIC_PARAMS = {
  defaultDurationMs: 210_000, // 次の曲の長さが分からないとき（3分30秒）
  defaultSpeedKmh: 40, // 到着時刻から速度が出せないとき
  // OSM 由来のカテゴリの信頼度。景観エンジンの confidence は補助データの有無で決まる定数（0.3/0.6）なので使わない
  osmQ: 1,
  highlightRanks: ['signature', 'panorama'], // 名道として扱う rank
  highlightMinShare: 0.3, // 窓の3割以上が名道なら highlight
};

const CATEGORY_TO_FEATURE: Record<string, FeatureKey> = {
  sea: 'sea_proximity',
  forest: 'forest',
  open_nature: 'nature',
  inland_water: 'water',
  // 景観エンジン 2.8 で OSM の水域・谷・市街地から直接分類されるようになったカテゴリ
  lake: 'water',
  river: 'water',
  valley: 'water',
  farmland: 'rural',
  townscape: 'urban',
  urban: 'urban',
};
// OSM から直接計算されるカテゴリ（それ以外は補助データ由来なので、ブロックの confidence を信頼度に使う）
const OSM_CATEGORIES = new Set(['sea', 'forest', 'open_nature', 'lake', 'river', 'valley', 'urban']);

export function sceneFromScenicRoute(route: ScenicRoute, params: Partial<typeof SCENIC_PARAMS> = {}): ScenicWindow {
  const P = { ...SCENIC_PARAMS, ...params };
  const blocks = [...route.timeline]
    .filter((b) => Number.isFinite(b.start_m) && Number.isFinite(b.end_m) && b.end_m > b.start_m)
    .sort((a, b) => a.start_m - b.start_m);
  const durationS = (route.next_duration_ms ?? P.defaultDurationMs) / 1000;
  const start = route.position_m;
  const { endM, speedKmh } = windowEnd(blocks, start, durationS, P.defaultSpeedKmh);

  // 窓と重なる長さを、カテゴリごとに集計する
  const len: Record<string, number> = {};
  const qSum: Record<string, number> = {};
  let total = 0;
  let unknown = 0;
  let highlightLen = 0;
  let seaVerified = 0;
  let opennessLen = 0;
  let opennessSum = 0;
  for (const b of blocks) {
    const overlap = Math.min(b.end_m, endM) - Math.max(b.start_m, start);
    if (overlap <= 0) continue;
    total += overlap;
    if (b.category === 'unknown' || (b.confidence ?? 1) <= 0) {
      unknown += overlap;
      continue;
    }
    len[b.category] = (len[b.category] ?? 0) + overlap;
    qSum[b.category] = (qSum[b.category] ?? 0) + overlap * clamp01(b.confidence ?? 1);
    if (b.recommended_rank && P.highlightRanks.includes(b.recommended_rank)) highlightLen += overlap;
    if (b.category === 'sea' && b.visibility_verified) seaVerified += overlap;
    if (b.openness != null && Number.isFinite(b.openness)) {
      opennessLen += overlap;
      opennessSum += overlap * clamp01(b.openness);
    }
  }

  const known = total - unknown;
  const coverage = total > 0 ? known / total : 0;
  const features: Scene['features'] = {};
  const shares: Partial<Record<string, number>> = {};
  if (known > 0) {
    // 湖・川・谷のように同じ特徴になるカテゴリは、長さを足し合わせてから占有率にする
    const keyLen: Partial<Record<FeatureKey, number>> = {};
    const keyQ: Partial<Record<FeatureKey, number>> = {};
    for (const [cat, l] of Object.entries(len)) {
      shares[cat] = round(l / known);
      const key = CATEGORY_TO_FEATURE[cat];
      if (!key) continue; // unclassified など：評価済みで特に景観なし（分母には含める）
      const q = OSM_CATEGORIES.has(cat) ? P.osmQ : qSum[cat] / l;
      keyLen[key] = (keyLen[key] ?? 0) + l;
      keyQ[key] = (keyQ[key] ?? 0) + l * q;
    }
    for (const [key, l] of Object.entries(keyLen) as [FeatureKey, number][]) {
      // 未評価の部分は景観なしと断定しない：占有率は評価済みの部分で割り、その分だけ信頼度を下げる
      features[key] = { v: round(l / known), q: round((keyQ[key]! / l) * coverage) };
    }
    if (seaVerified > 0) features.sea_visibility = { v: round(seaVerified / known), q: round(coverage) };
    if (opennessLen > 0) features.openness = { v: round(opennessSum / opennessLen), q: round(opennessLen / total) };
  }

  const scene: Scene = {
    features,
    highlight: total > 0 && highlightLen / total >= P.highlightMinShare,
  };
  const hour = hourAt(blocks, start);
  if (hour != null) scene.hour = hour;

  // 次の曲の間に海が見え始める地点があれば、何秒後かを返す（曲の切り替えを合わせるため）
  const r = (route.reveals ?? [])
    .filter((x) => x.start_m >= start && x.start_m < endM)
    .sort((a, b) => a.start_m - b.start_m)[0];
  const reveal = r ? { start_m: r.start_m, eta_s: Math.round(((r.start_m - start) / 1000 / speedKmh) * 3600) } : null;

  return {
    scene,
    window: { start_m: start, end_m: round(endM), duration_s: durationS, speed_kmh: round(speedKmh) },
    coverage: round(coverage),
    shares,
    reveal,
  };
}

// 窓の終わり：次の曲の長さの間に進む距離。速度はブロックの到着時刻の差から出す
function windowEnd(blocks: ScenicBlock[], start: number, durationS: number, defaultSpeed: number) {
  const pts = blocks
    .map((b) => ({ m: b.start_m, t: b.arrival_time ? Date.parse(b.arrival_time) : NaN }))
    .filter((p) => Number.isFinite(p.t));
  // 今の位置をはさむ2地点の到着時刻から速度を出す。後ろに地点が無ければ（最後のブロック）、直前の2地点を使う
  let i = pts.findIndex((p) => p.m > start);
  if (i === -1) i = pts.length - 1;
  const a = pts[Math.max(0, i - 1)];
  const b = pts[Math.max(1, i)];
  let speedKmh = defaultSpeed;
  if (a && b && b.m > a.m && b.t > a.t) speedKmh = (b.m - a.m) / 1000 / ((b.t - a.t) / 3_600_000);
  if (!Number.isFinite(speedKmh) || speedKmh <= 0) speedKmh = defaultSpeed;
  const endM = start + (speedKmh * durationS) / 3.6;
  return { endM, speedKmh };
}

// その地点の到着時刻の「時」（現地時刻）。ISO 8601 の文字列から読む（タイムゾーン変換をしないため）
function hourAt(blocks: ScenicBlock[], position: number): number | null {
  const b =
    blocks.find((x) => x.start_m <= position && position < x.end_m && x.arrival_time) ??
    [...blocks].reverse().find((x) => x.start_m <= position && x.arrival_time) ??
    blocks.find((x) => x.arrival_time);
  const m = b?.arrival_time?.match(/T(\d{2}):/);
  return m ? Number(m[1]) : null;
}

function clamp01(n: number): number {
  return Math.min(1, Math.max(0, n));
}

function round(n: number): number {
  return Math.round(n * 1000) / 1000;
}
