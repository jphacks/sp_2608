// 景観エンジンのルート ID から、選曲に使うタイムラインを得る。
// アプリはルート ID と現在位置だけを送り、タイムラインはサーバー同士で受け渡す（同じ PC の Docker の中）。
// 景観エンジンは検索結果を1時間で消すので、最初に取ったものを route_timeline に保持してドライブの間使う。

import type { SqlDatabase } from './db';
import type { ScenicBlock } from './scenic';

export const ROUTE_ID = /^route_[a-f0-9]{32}$/;

export const ROUTE_SOURCE_PARAMS = {
  keepMs: 24 * 3600_000, // 保持する期間。1日を超えるドライブは想定しない
  fetchTimeoutMs: 5_000,
  maxBlocks: 20_000, // 異常に大きな応答を保存しない
};

export type RouteTimeline = { timeline: ScenicBlock[]; reveals: { start_m: number }[] };

const RANKS = ['signature', 'panorama', 'recommended', 'scenic'];
const REVEAL_TEMPLATE = 'forest_to_sea_reveal';

/**
 * 保持しているタイムラインを返す。無ければ景観エンジンから取得して保持する。
 * 景観エンジン側で消えていて、こちらにも無ければ null（景観なしで選曲する）。
 */
export async function routeTimeline(
  db: SqlDatabase,
  scenicUrl: string | undefined,
  routeId: string,
  now = Date.now(),
  fetchImpl: typeof fetch = fetch,
): Promise<RouteTimeline | null> {
  if (!ROUTE_ID.test(routeId)) return null;
  const { results } = await db
    .prepare('SELECT timeline_json, reveals_json FROM route_timeline WHERE route_id = ? AND fetched_at >= ?')
    .bind(routeId, now - ROUTE_SOURCE_PARAMS.keepMs)
    .all<{ timeline_json: string; reveals_json: string }>();
  if (results[0]) return { timeline: JSON.parse(results[0].timeline_json), reveals: JSON.parse(results[0].reveals_json) };
  if (!scenicUrl) return null;

  let evidence: any;
  try {
    const response = await fetchImpl(`${scenicUrl.replace(/\/$/, '')}/routes/${routeId}/evidence`, {
      signal: AbortSignal.timeout(ROUTE_SOURCE_PARAMS.fetchTimeoutMs),
    });
    if (!response.ok) return null;
    evidence = await response.json();
  } catch {
    return null;
  }
  const route = fromEvidence(evidence);
  if (!route) return null;
  await db.batch([
    db.prepare('DELETE FROM route_timeline WHERE fetched_at < ?').bind(now - ROUTE_SOURCE_PARAMS.keepMs),
    db.prepare(
      `INSERT INTO route_timeline (route_id, timeline_json, reveals_json, fetched_at) VALUES (?, ?, ?, ?)
       ON CONFLICT(route_id) DO UPDATE SET timeline_json = excluded.timeline_json, reveals_json = excluded.reveals_json, fetched_at = excluded.fetched_at`,
    ).bind(routeId, JSON.stringify(route.timeline), JSON.stringify(route.reveals), now),
  ]);
  return route;
}

/** 景観エンジンの GET /routes/{id}/evidence から、選曲に要る項目だけを取り出す。 */
export function fromEvidence(evidence: any): RouteTimeline | null {
  const blocks = evidence?.timeline;
  if (!Array.isArray(blocks) || blocks.length === 0 || blocks.length > ROUTE_SOURCE_PARAMS.maxBlocks) return null;
  const timeline: ScenicBlock[] = [];
  for (const b of blocks) {
    if (!Number.isFinite(b?.start_m) || !Number.isFinite(b?.end_m) || b.end_m <= b.start_m || typeof b.category !== 'string') continue;
    const block: ScenicBlock = { start_m: b.start_m, end_m: b.end_m, category: b.category };
    if (Number.isFinite(b.confidence)) block.confidence = b.confidence;
    if (b.openness === null || Number.isFinite(b.openness)) block.openness = b.openness;
    if (typeof b.visibility_verified === 'boolean') block.visibility_verified = b.visibility_verified;
    if (typeof b.arrival_time === 'string') block.arrival_time = b.arrival_time;
    // 名道は格付けの最上位だけ。固有名・物理道路IDは持たない
    const ranks = Array.isArray(b.recommended_roads) ? b.recommended_roads.map((r: any) => r?.rank).filter((r: unknown) => RANKS.includes(r as string)) : [];
    if (ranks.length) block.recommended_rank = RANKS.find((rank) => ranks.includes(rank));
    timeline.push(block);
  }
  if (timeline.length === 0) return null;
  const reveals = (Array.isArray(evidence.reasons) ? evidence.reasons : [])
    .filter((r: any) => r?.template === REVEAL_TEMPLATE && Array.isArray(r.geometry_ranges))
    .flatMap((r: any) => r.geometry_ranges.map((g: any) => g?.start_m))
    .filter((m: unknown): m is number => Number.isFinite(m))
    .sort((a: number, b: number) => a - b)
    .map((start_m: number) => ({ start_m }));
  return { timeline, reveals };
}
