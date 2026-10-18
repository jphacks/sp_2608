// 公開ページのグラフ。SVG をサーバ側で組み立てる。色は 1 系列なので sunset だけ。

import { escapeHtml } from "./html";
import type { MoodSample } from "./music";
import { LIVELY_RATIO } from "./music";

export type MoodChartPoint = { x: number; y: number; atEpochMillis: number; ratio: number };

const CHART_W = 640;
const CHART_H = 220;
const PAD = { top: 12, right: 12, bottom: 28, left: 40 };

/** 観測を描画座標に変える。x は出発から到着までの時間、y は発話割合 0〜1。 */
export function moodChartPoints(mood: MoodSample[], startedAt: number, arrivedAt: number): MoodChartPoint[] {
  const span = Math.max(arrivedAt - startedAt, 1);
  const innerW = CHART_W - PAD.left - PAD.right;
  const innerH = CHART_H - PAD.top - PAD.bottom;
  return mood
    .filter((m) => m.atEpochMillis >= startedAt && m.atEpochMillis <= arrivedAt)
    .map((m) => ({
      x: PAD.left + ((m.atEpochMillis - startedAt) / span) * innerW,
      y: PAD.top + (1 - m.speechRatio) * innerH,
      atEpochMillis: m.atEpochMillis,
      ratio: m.speechRatio,
    }));
}

export function moodChartSvg(mood: MoodSample[], startedAt: number, arrivedAt: number, labels: { start: string; end: string }): string {
  const pts = moodChartPoints(mood, startedAt, arrivedAt);
  const innerW = CHART_W - PAD.left - PAD.right;
  const innerH = CHART_H - PAD.top - PAD.bottom;
  const yOf = (ratio: number) => PAD.top + (1 - ratio) * innerH;
  const f = (n: number) => n.toFixed(1);
  const line = pts.map((p, i) => `${i === 0 ? "M" : "L"}${f(p.x)} ${f(p.y)}`).join(" ");
  const area = pts.length ? `${line} L${f(pts[pts.length - 1].x)} ${f(yOf(0))} L${f(pts[0].x)} ${f(yOf(0))} Z` : "";
  // 目盛りは 0・しきい値・100%。しきい値の文字を線の上に置くと山と重なることがあるので、左の目盛りに出す
  const gridRows = [0, LIVELY_RATIO, 1]
    .map((r) => `<line class="grid" x1="${PAD.left}" x2="${CHART_W - PAD.right}" y1="${f(yOf(r))}" y2="${f(yOf(r))}"/>` +
      `<text class="tick" x="${PAD.left - 8}" y="${f(yOf(r) + 4)}" text-anchor="end">${Math.round(r * 100)}%</text>`)
    .join("");
  const liveLine = `<line class="lively" x1="${PAD.left}" x2="${CHART_W - PAD.right}" y1="${f(yOf(LIVELY_RATIO))}" y2="${f(yOf(LIVELY_RATIO))}"/>`;
  const xLabels = `<text class="tick" x="${PAD.left}" y="${CHART_H - 8}">${escapeHtml(labels.start)}</text>` +
    `<text class="tick" x="${CHART_W - PAD.right}" y="${CHART_H - 8}" text-anchor="end">${escapeHtml(labels.end)}</text>`;
  const hover = pts.length
    ? `<line id="mood-cursor" class="cursor" y1="${PAD.top}" y2="${f(yOf(0))}" x1="0" x2="0" style="display:none"/>` +
      `<circle id="mood-dot" class="dot" r="5" style="display:none"/>`
    : "";
  return (
    `<svg class="mood-chart" viewBox="0 0 ${CHART_W} ${CHART_H}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="盛り上がりの推移">` +
    gridRows + liveLine +
    (area ? `<path class="area" d="${area}"/>` : "") +
    (line ? `<path class="line" d="${line}"/>` : "") +
    hover + xLabels +
    `</svg>`
  );
}

export type BarRow = { label: string; value: string; share: number };

/** 横棒。1 色。値は右に直接書く。 */
export function barListHtml(rows: BarRow[]): string {
  return `<ul class="bars">${rows
    .map(
      (r) =>
        `<li><span class="bar-label">${escapeHtml(r.label)}</span>` +
        `<span class="bar-track"><span class="bar-fill" style="width:${(Math.max(0, Math.min(1, r.share)) * 100).toFixed(1)}%"></span></span>` +
        `<span class="bar-value">${escapeHtml(r.value)}</span></li>`,
    )
    .join("")}</ul>`;
}
