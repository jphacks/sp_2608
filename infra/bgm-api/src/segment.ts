// ルートの点列（200m間隔）をシーン区間に分ける。純粋関数だけで書く（後で端末側に移植できるように）

import { FEATURE_KEYS, type FeatureKey, type FeatureValue, type Scene } from './engine';

export type RoutePoint = {
  km?: number; // 出発地からの距離。省略時は 点の番号 × pointSpacingKm
  features: Partial<Record<FeatureKey, FeatureValue>>;
  speed_kmh?: number; // この点から次の点までの想定速度（ETAの計算に使う）
  load?: 'normal' | 'high';
  curvy?: boolean;
  highlight?: boolean;
};

export type SegmentFeature = { v: number; q: number };

export type Segment = {
  idx: number;
  start_km: number;
  end_km: number;
  start_s: number; // 出発からの経過秒（ETA）
  end_s: number;
  features: Partial<Record<FeatureKey, SegmentFeature>>;
  load: 'normal' | 'high';
  curvy: boolean;
  highlight: boolean;
  reveal_km: number | null; // この区間に海が見え始める地点があれば、その km
  scene: Scene; // computeTarget にそのまま渡せる形
};

// 区間化の係数（初期値。走行テストで調整する）
export const SEGMENT_PARAMS = {
  pointSpacingKm: 0.2,
  defaultSpeedKmh: 40,
  splitThreshold: 0.3, // 区間の平均との差（v×q の最大差）がこれを超えたら区切りの候補にする
  minSegmentSec: 180, // 1区間の最短（ETA換算で3分）
  revealMinQ: 0.7, // 海の可視性を「確認済み」とみなす信頼度
  revealMinV: 0.5, // 海が「見えている」とみなす可視性
  highlightMinRatio: 0.5, // 区間の点の半分以上が名道なら highlight にする
};

export type SegmentOptions = Partial<typeof SEGMENT_PARAMS> & {
  start_hour?: number; // 出発時刻（14.5 = 14:30）。指定すると区間ごとの hour を埋める
};

type Span = { from: number; to: number }; // 点の番号の範囲 [from, to)

export function segmentRoute(points: RoutePoint[], opts: SegmentOptions = {}): Segment[] {
  const P = { ...SEGMENT_PARAMS, ...opts };
  if (points.length === 0) return [];

  const km = points.map((p, i) => p.km ?? i * P.pointSpacingKm);
  // 最後の点も1区間分の長さを持つものとして扱う
  const endKm = km[km.length - 1] + P.pointSpacingKm;
  const kmAt = (i: number) => (i < points.length ? km[i] : endKm);

  // 各点の到着時刻（出発からの秒）
  const sec: number[] = [0];
  for (let i = 0; i < points.length; i++) {
    const dist = kmAt(i + 1) - km[i];
    const speed = points[i].speed_kmh ?? P.defaultSpeedKmh;
    sec.push(sec[i] + (dist / Math.max(1, speed)) * 3600);
  }

  // 1. 区切りの候補：今の区間の平均から大きく離れた点、または運転条件が変わった点
  const spans: Span[] = [];
  let cur: Span = { from: 0, to: 1 };
  for (let i = 1; i < points.length; i++) {
    const diff = featureDistance(aggregate(points, cur), effective(points[i]), true);
    if (diff > P.splitThreshold || drivingKey(points[i]) !== drivingKey(points[i - 1])) {
      spans.push(cur);
      cur = { from: i, to: i + 1 };
    } else {
      cur.to = i + 1;
    }
  }
  spans.push(cur);

  // 2. 短すぎる区間を、特徴が近いほうの隣に統合する（短い順に処理）
  const dur = (s: Span) => sec[s.to] - sec[s.from];
  while (spans.length > 1) {
    let k = -1;
    for (let i = 0; i < spans.length; i++) {
      if (dur(spans[i]) < P.minSegmentSec && (k < 0 || dur(spans[i]) < dur(spans[k]))) k = i;
    }
    if (k < 0) break;
    const me = aggregate(points, spans[k]);
    const dPrev = k > 0 ? featureDistance(me, aggregate(points, spans[k - 1])) : Infinity;
    const dNext = k < spans.length - 1 ? featureDistance(me, aggregate(points, spans[k + 1])) : Infinity;
    const j = dPrev <= dNext ? k - 1 : k + 1;
    const a = Math.min(j, k);
    spans.splice(a, 2, { from: spans[a].from, to: spans[a + 1].to });
  }

  // 3. 一瞬の変化で分かれた同じ景観の区間を、元に戻す（運転条件が同じで、平均の差が閾値以下の隣同士）
  for (let i = 0; i + 1 < spans.length; ) {
    const same = drivingKey(points[spans[i].from]) === drivingKey(points[spans[i + 1].from]);
    const close =
      featureDistance(aggregate(points, spans[i]), aggregate(points, spans[i + 1])) <= P.splitThreshold;
    if (same && close) spans.splice(i, 2, { from: spans[i].from, to: spans[i + 1].to });
    else i++;
  }

  // 4. 海が見え始める地点（確認済みの可視性が、見えていない状態から見える状態に変わった点）
  const visible = points.map((p) => {
    const f = toVQ(p.features.sea_visibility);
    return f != null && f.q >= P.revealMinQ && f.v >= P.revealMinV;
  });
  const reveals = new Set<number>();
  for (let i = 0; i < points.length; i++) {
    if (visible[i] && (i === 0 || !visible[i - 1])) reveals.add(i);
  }

  return spans.map((s, idx) => {
    const pts = points.slice(s.from, s.to);
    const features = aggregate(points, s);
    // 上限は区間の中で最も厳しい条件を採る（安全側）
    const load = pts.some((p) => p.load === 'high') ? 'high' : 'normal';
    const curvy = pts.some((p) => p.curvy);
    const highlight = pts.filter((p) => p.highlight).length / pts.length >= P.highlightMinRatio;
    let reveal_km: number | null = null;
    for (let i = s.from; i < s.to; i++) {
      if (reveals.has(i)) {
        reveal_km = round(km[i]);
        break;
      }
    }
    const scene: Scene = { features, load, curvy, highlight };
    if (opts.start_hour != null) {
      const mid = (sec[s.from] + sec[s.to]) / 2;
      scene.hour = Math.floor(opts.start_hour + mid / 3600) % 24;
    }
    return {
      idx,
      start_km: round(km[s.from]),
      end_km: round(kmAt(s.to)),
      start_s: Math.round(sec[s.from]),
      end_s: Math.round(sec[s.to]),
      features,
      load,
      curvy,
      highlight,
      reveal_km,
      scene,
    };
  });
}

// 区間内の点の平均。v は q で重み付けし、q は単純平均（不明な点が多いほど信頼度が下がる）
function aggregate(points: RoutePoint[], s: Span): Partial<Record<FeatureKey, SegmentFeature>> {
  const out: Partial<Record<FeatureKey, SegmentFeature>> = {};
  const n = s.to - s.from;
  for (const key of FEATURE_KEYS) {
    let sv = 0;
    let sq = 0;
    for (let i = s.from; i < s.to; i++) {
      const f = toVQ(points[i].features[key]);
      if (!f) continue;
      sv += f.v * f.q;
      sq += f.q;
    }
    if (sq > 0) out[key] = { v: round(sv / sq), q: round(sq / n) };
  }
  return out;
}

function effective(p: RoutePoint): Partial<Record<FeatureKey, SegmentFeature>> {
  const out: Partial<Record<FeatureKey, SegmentFeature>> = {};
  for (const key of FEATURE_KEYS) {
    const f = toVQ(p.features[key]);
    if (f) out[key] = f;
  }
  return out;
}

// 特徴の差：v×q の差の最大値（キーが無いものは 0 とみなす）
// skipUnknown のときは、b 側で q = 0（不明）の特徴を比べない。不明な点だけで区切らないようにするため
function featureDistance(
  a: Partial<Record<FeatureKey, SegmentFeature>>,
  b: Partial<Record<FeatureKey, SegmentFeature>>,
  skipUnknown = false,
): number {
  let d = 0;
  for (const key of FEATURE_KEYS) {
    if (skipUnknown && b[key]?.q === 0) continue;
    const x = a[key] ? a[key]!.v * a[key]!.q : 0;
    const y = b[key] ? b[key]!.v * b[key]!.q : 0;
    d = Math.max(d, Math.abs(x - y));
  }
  return d;
}

function drivingKey(p: RoutePoint): string {
  return `${p.load ?? 'normal'}|${p.curvy ? 1 : 0}|${p.highlight ? 1 : 0}`;
}

function toVQ(raw: FeatureValue | undefined): SegmentFeature | null {
  if (raw == null) return null;
  const v = typeof raw === 'number' ? raw : raw.v;
  const q = typeof raw === 'number' ? 1 : (raw.q ?? 1);
  if (!Number.isFinite(v) || !Number.isFinite(q)) return null;
  return { v: clamp01(v), q: clamp01(q) };
}

function clamp01(n: number): number {
  return Math.min(1, Math.max(0, n));
}

function round(n: number): number {
  return Math.round(n * 1000) / 1000;
}
