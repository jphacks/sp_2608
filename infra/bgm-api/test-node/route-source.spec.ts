// ルート ID からのタイムライン取得と保持（PC 版）。
import { mkdtempSync, rmSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import app from '../src/index';
import { ROUTE_SOURCE_PARAMS, routeTimeline } from '../src/route-source';
import { SqliteDatabase } from '../src/sqlite';

const migrations = new URL('../migrations', import.meta.url).pathname;
const catalog = new URL('../catalog/catalog.jsonl', import.meta.url).pathname;
const ROUTE = 'route_' + 'a'.repeat(32);
const hour = (h: number) => `2026-10-18T${String(h).padStart(2, '0')}:00:00+09:00`;

// 景観エンジンの GET /routes/{id}/evidence と同じ形。0〜20km が川、20〜60km が海。
const evidence = {
  route_id: ROUTE, revision: 1, snapshot_id: 's',
  timeline: [
    { start_m: 0, end_m: 20000, category: 'river', confidence: 0.3, openness: null, visibility_verified: false,
      arrival_time: hour(14), physical_position: { way: 123 }, sea_side: 'left', source_ids: ['osm'],
      recommended_roads: [{ id: 'r1', name: '秘密の道', rank: 'recommended' }, { id: 'r2', name: '名道', rank: 'signature' }] },
    { start_m: 20000, end_m: 60000, category: 'sea', confidence: 0.3, openness: 0.8, visibility_verified: true,
      arrival_time: hour(15), physical_position: { way: 456 }, source_ids: ['osm'], recommended_roads: [] },
  ],
  reasons: [{ template: 'forest_to_sea_reveal', geometry_ranges: [{ start_m: 20000, end_m: 21000 }] },
            { template: 'sea_proximity', geometry_ranges: [{ start_m: 20000, end_m: 60000 }] }],
  evidence: [], warnings: [],
};

let directory: string;
let db: SqliteDatabase;
let server: Server;
let scenicUrl: string;
let requests: number;
let alive: boolean;

beforeEach(async () => {
  directory = mkdtempSync(join(tmpdir(), 'bgm-route-'));
  db = new SqliteDatabase(join(directory, 'bgm.sqlite'));
  db.migrate(migrations);
  requests = 0;
  alive = true;
  server = createServer((request, response) => {
    requests++;
    if (alive && request.url === `/routes/${ROUTE}/evidence`) {
      response.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(evidence));
    } else response.writeHead(404).end('{"error":"route_expired"}');
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  scenicUrl = `http://127.0.0.1:${(server.address() as any).port}`;
});

afterEach(async () => {
  await new Promise((resolve) => server.close(resolve));
  db.close();
  rmSync(directory, { recursive: true, force: true });
});

describe('ルート ID からのタイムライン', () => {
  it('選曲に要る項目だけを取り出し、座標・道路名・物理道路IDは持たない', async () => {
    const route = await routeTimeline(db, scenicUrl, ROUTE, 0);
    expect(route!.timeline[0]).toEqual({
      start_m: 0, end_m: 20000, category: 'river', confidence: 0.3, openness: null,
      visibility_verified: false, arrival_time: hour(14), recommended_rank: 'signature',
    });
    expect(route!.reveals).toEqual([{ start_m: 20000 }]);
    const { results } = await db.prepare('SELECT timeline_json FROM route_timeline').all<{ timeline_json: string }>();
    expect(results[0].timeline_json).not.toMatch(/秘密の道|名道|physical_position|way|sea_side/);
  });

  it('一度取ったら、景観エンジン側で消えても保持したものを使う', async () => {
    await routeTimeline(db, scenicUrl, ROUTE, 0);
    alive = false;
    const later = await routeTimeline(db, scenicUrl, ROUTE, 3 * 3600_000);
    expect(later!.timeline).toHaveLength(2);
    expect(requests).toBe(1);
  });

  it('保持の期間を過ぎたら取り直し、取れなければ null', async () => {
    await routeTimeline(db, scenicUrl, ROUTE, 0);
    alive = false;
    expect(await routeTimeline(db, scenicUrl, ROUTE, ROUTE_SOURCE_PARAMS.keepMs + 1)).toBeNull();
    expect(requests).toBe(2);
  });

  it('形の違う ID や、接続先が無いときは問い合わせない', async () => {
    expect(await routeTimeline(db, scenicUrl, 'route_../../x', 0)).toBeNull();
    expect(await routeTimeline(db, undefined, ROUTE, 0)).toBeNull();
    expect(requests).toBe(0);
  });
});

describe('/recommend に route_id を送る', () => {
  const env = () => ({ bgm_db: db, GEMINI_API_KEY: '', SPOTIFY_CLIENT_ID: '', SPOTIFY_CLIENT_SECRET: '', SCENIC_INTERNAL_URL: scenicUrl });
  const recommend = (route: Record<string, unknown>) => app.fetch(new Request('http://bgm.local/recommend', {
    method: 'POST',
    body: JSON.stringify({ mode: 'discover', members: [{ id: 'a', name: 'A' }], pool: [], seed: 1, route }),
  }), env());

  it('タイムラインを送らなくても、位置の先の景観で選ぶ', async () => {
    db.importCatalog(catalog);
    const body: any = await (await recommend({ route_id: ROUTE, position_m: 25000, next_duration_ms: 240000 })).json();
    expect(body.route_status).toBe('resolved');
    expect(body.route_window.shares).toEqual({ sea: 1 });
    expect(body.route_window.scene.features.sea_visibility.v).toBe(1);
    expect(body.chosen.track_id).toMatch(/^isrc:/);
  });

  it('ルートが取れなくても、景観なしで選曲は続ける', async () => {
    db.importCatalog(catalog);
    alive = false;
    const response = await recommend({ route_id: ROUTE, position_m: 0 });
    const body: any = await response.json();
    expect(response.status).toBe(200);
    expect(body.route_status).toBe('unavailable');
    expect(body.route_window).toBeNull();
    expect(body.chosen.track_id).toMatch(/^isrc:/);
  });

  it('timeline を直接送る従来の形もそのまま使える', async () => {
    const body: any = await (await recommend({ position_m: 0, timeline: evidence.timeline })).json();
    expect(body.route_status).toBe('given');
    expect(requests).toBe(0);
  });
});
