// PC 版（Node.js 内蔵の SQLite）で、Workers 版と同じアプリが動くことを確かめる。
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import app from '../src/index';
import { SqliteDatabase } from '../src/sqlite';

const migrations = new URL('../migrations', import.meta.url).pathname;
let directory: string;
let db: SqliteDatabase;

beforeEach(() => {
  directory = mkdtempSync(join(tmpdir(), 'bgm-sqlite-'));
  db = new SqliteDatabase(join(directory, 'bgm.sqlite'));
});

afterEach(() => {
  db.close();
  rmSync(directory, { recursive: true, force: true });
});

const env = () => ({ bgm_db: db, GEMINI_API_KEY: '', SPOTIFY_CLIENT_ID: '', SPOTIFY_CLIENT_SECRET: '' });
const post = (path: string, body: unknown) =>
  app.fetch(new Request(`http://bgm.local${path}`, { method: 'POST', body: JSON.stringify(body) }), env());

async function seedCatalog() {
  const insert = db.prepare(
    `INSERT INTO track_features (track_id, title, artist, duration_ms, energy, valence, tempo, acoustic, spacious, vocal,
       conf, popularity, jp_familiarity, isrc, catalog_group, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  );
  await db.batch([
    insert.bind('isrc:JP0000000001', '海の曲', 'A', 200000, .6, .8, .5, .5, .9, .5, .9, .8, .8, 'JP0000000001', 'jp', 1),
    insert.bind('isrc:JP0000000002', '森の曲', 'B', 210000, .3, .5, .3, .9, .6, .3, .9, .6, .6, 'JP0000000002', 'jp', 1),
    insert.bind('isrc:JP0000000003', '街の曲', 'C', 220000, .8, .7, .7, .2, .3, .8, .9, .7, .7, 'JP0000000003', 'jp', 1),
  ]);
}

describe('SQLite 版', () => {
  it('migrations を順に一度だけ適用する', () => {
    expect(db.migrate(migrations)).toEqual(['0001_init.sql', '0002_catalog.sql', '0003_route_timeline.sql', '0004_party.sql']);
    expect(db.migrate(migrations)).toEqual([]);
  });

  it('batch は途中で失敗したらすべて取り消す', async () => {
    db.migrate(migrations);
    const insert = db.prepare('INSERT INTO track_link (track_id, service, service_track_id, matched_at) VALUES (?, ?, ?, ?)');
    await expect(db.batch([insert.bind('isrc:A', 'spotify', 'x', 1), insert.bind('isrc:A', 'spotify', 'y', 2)])).rejects.toThrow();
    const { results } = await db.prepare('SELECT * FROM track_link').all();
    expect(results).toEqual([]);
  });

  it('undefined の bind は拒否する（D1 と同じ）', () => {
    db.migrate(migrations);
    expect(() => db.prepare('SELECT ?').bind(undefined)).toThrow(TypeError);
  });

  it('カタログから推薦できる', async () => {
    db.migrate(migrations);
    await seedCatalog();
    const response = await post('/recommend', {
      mode: 'discover', members: [{ id: 'a', name: 'A' }], pool: [], seed: 1,
      scene: { features: { sea_proximity: 1, openness: .8 }, hour: 14 },
    });
    expect(response.status).toBe(200);
    const body: any = await response.json();
    expect(body.pool_size).toBe(3);
    expect(body.chosen.track_id).toMatch(/^isrc:/);
  });

  it('履歴の曲は候補から外れる', async () => {
    db.migrate(migrations);
    await seedCatalog();
    const response = await post('/recommend', {
      mode: 'discover', members: [{ id: 'a', name: 'A' }], pool: [], seed: 1, scene: { features: {} },
      history: [{ track_id: 'isrc:JP0000000001' }, { track_id: 'isrc:JP0000000002' }],
    });
    const body: any = await response.json();
    expect(body.chosen.track_id).toBe('isrc:JP0000000003');
  });
});

describe('Spotify の資格情報', () => {
  it('未設定なら 503 と理由を返す（500 にしない）', async () => {
    db.migrate(migrations);
    const insert = db.prepare('INSERT INTO track_features (track_id, title, artist, isrc, updated_at) VALUES (?, ?, ?, ?, ?)');
    await db.batch([insert.bind('isrc:JP0000000009', '曲', '人', 'JP0000000009', 1)]);
    const response = await post('/link/spotify', { track_ids: ['isrc:JP0000000009'] });
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ error: 'spotify_not_configured' });
  });
});
