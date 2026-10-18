// 経路線をステッカーの上部（写真の上）に描く SVG の計算。Android 側 StickerLayout.kt の projectRoute と同じ決め方。

import type { Coordinate } from "./trip";

export type Point = { x: number; y: number };
export type Projection = { points: Point[]; project: (c: Coordinate) => Point };

/** 余白は高さに対する割合。Android の STICKER_ROUTE_MARGIN と同じ値。 */
export const ROUTE_MARGIN = 0.12;

/**
 * 緯度経度を width×height の箱に収める。正距円筒図法で、緯度の中央値で横を縮める。
 * 外接矩形は経路と写真の位置の両方で作るので、経路から離れた場所で撮った写真も箱の中に入る。
 * 縦横比は保ち、箱の中央に寄せ、上下左右に height×margin の余白を取る。
 */
export function projectRoute(
  geometry: Coordinate[],
  width: number,
  height: number,
  photoLocation: Coordinate | null = null,
  margin = ROUTE_MARGIN,
): Projection {
  const all = photoLocation ? [...geometry, photoLocation] : geometry;
  if (all.length === 0) return { points: [], project: () => ({ x: width / 2, y: height / 2 }) };

  const lats = all.map((c) => c.latitude);
  const lons = all.map((c) => c.longitude);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const minLon = Math.min(...lons);
  const maxLon = Math.max(...lons);
  const cosLat = Math.max(Math.cos(((minLat + maxLat) / 2) * (Math.PI / 180)), 1e-6);

  const spanX = (maxLon - minLon) * cosLat;
  const spanY = maxLat - minLat;
  const padding = height * margin;
  const innerW = Math.max(width - padding * 2, 1);
  const innerH = Math.max(height - padding * 2, 1);
  // 全点が同じ場所なら scale 0 で中央に置く
  const scale = spanX <= 0 && spanY <= 0 ? 0 : Math.min(innerW / Math.max(spanX, 1e-12), innerH / Math.max(spanY, 1e-12));

  const centerX = ((minLon + maxLon) / 2) * cosLat;
  const centerY = (minLat + maxLat) / 2;
  const project = (c: Coordinate): Point => ({
    x: width / 2 + (c.longitude * cosLat - centerX) * scale,
    y: height / 2 - (c.latitude - centerY) * scale,
  });
  return { points: geometry.map(project), project };
}

export type LabelPlacement = { x: number; y: number; anchor: "start" | "end" };

/** ラベル 1 行の大きさ（font-size 24 の "PHOTO" 2 行ぶん）。経路線の太さぶん外側に広げて判定する。 */
const LABEL_W = 84;
const LABEL_H = 50;
const LABEL_GAP = 22;
const LINE_HALF_WIDTH = 10;

/**
 * 星のそばで経路線と重ならない向きに PHOTO SPOT のラベルを置く。
 * 左下・右下・左・右・左上・右上の順に候補を見て、ラベルの枠にかかる経路線の長さが最も短いものを選ぶ。
 * 同じなら先の候補（左下）を優先する。箱からはみ出す候補は除く。
 */
export function placeLabel(spot: Point, route: Point[], width: number, height: number): LabelPlacement {
  const candidates: LabelPlacement[] = [
    { x: spot.x - LABEL_GAP, y: spot.y + 14, anchor: "end" },
    { x: spot.x + LABEL_GAP, y: spot.y + 14, anchor: "start" },
    { x: spot.x - LABEL_GAP, y: spot.y - 6, anchor: "end" },
    { x: spot.x + LABEL_GAP, y: spot.y - 6, anchor: "start" },
    { x: spot.x - LABEL_GAP, y: spot.y - LABEL_H + 2, anchor: "end" },
    { x: spot.x + LABEL_GAP, y: spot.y - LABEL_H + 2, anchor: "start" },
  ];
  const rectOf = (c: LabelPlacement) => {
    const left = c.anchor === "end" ? c.x - LABEL_W : c.x;
    const top = c.y - 18; // ベースラインから 1 行目の上端まで
    return { left, top, right: left + LABEL_W, bottom: top + LABEL_H };
  };
  const inside = (r: ReturnType<typeof rectOf>) => r.left >= 0 && r.top >= 0 && r.right <= width && r.bottom <= height;
  const hit = (p: Point, r: ReturnType<typeof rectOf>) =>
    p.x >= r.left - LINE_HALF_WIDTH && p.x <= r.right + LINE_HALF_WIDTH && p.y >= r.top - LINE_HALF_WIDTH && p.y <= r.bottom + LINE_HALF_WIDTH;
  // 頂点だけでなく線分の途中も見る。4px おきに線分をたどり、枠にかかった点の数を線の長さの代わりにする
  const overlaps = (r: ReturnType<typeof rectOf>) => {
    let count = 0;
    for (let i = 0; i < route.length; i++) {
      const a = route[i];
      const b = route[i + 1] ?? a;
      const steps = Math.max(1, Math.ceil(Math.hypot(b.x - a.x, b.y - a.y) / 4));
      for (let s = 0; s < steps; s++) {
        const t = s / steps;
        if (hit({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t }, r)) count++;
      }
    }
    return count;
  };

  let best = candidates[0];
  let bestScore = Infinity;
  for (const c of candidates) {
    const r = rectOf(c);
    if (!inside(r)) continue;
    const score = overlaps(r);
    if (score < bestScore) {
      best = c;
      bestScore = score;
    }
  }
  return best;
}

/** 点列を SVG の path d 属性にする。小数 1 桁に丸める。 */
export function toPathData(points: Point[]): string {
  if (points.length === 0) return "";
  const f = (n: number) => n.toFixed(1);
  return points.map((p, i) => `${i === 0 ? "M" : "L"}${f(p.x)} ${f(p.y)}`).join(" ");
}

/**
 * 点が多すぎると SVG が重いので、一定の間隔以上に動いた点だけ残す。
 * 始点と終点は必ず残す。
 */
export function thinPoints(points: Point[], minStep: number): Point[] {
  if (points.length <= 2) return points;
  const out: Point[] = [points[0]];
  for (let i = 1; i < points.length - 1; i++) {
    const last = out[out.length - 1];
    const p = points[i];
    if (Math.hypot(p.x - last.x, p.y - last.y) >= minStep) out.push(p);
  }
  out.push(points[points.length - 1]);
  return out;
}
