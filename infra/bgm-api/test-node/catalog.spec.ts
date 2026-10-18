// 全曲カタログ（catalog/catalog.jsonl）の取り込み。
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import app from '../src/index';
import { SqliteDatabase } from '../src/sqlite';

const migrations = new URL('../migrations', import.meta.url).pathname;
const repositoryCatalog = new URL('../catalog/catalog.jsonl', import.meta.url).pathname;
let directory: string;
let db: SqliteDatabase;

beforeEach(() => {
  directory = mkdtempSync(join(tmpdir(), 'bgm-catalog-'));
  db = new SqliteDatabase(join(directory, 'bgm.sqlite'));
  db.migrate(migrations);
});

afterEach(() => {
  db.close();
  rmSync(directory, { recursive: true, force: true });
});

const row = (id: string, extra: Record<string, unknown> = {}) => JSON.stringify({
  track_id: `isrc:${id}`, title: id, artist: 'A', duration_ms: 200000, energy: .5, valence: .5, tempo: .5, acoustic: .5,
  spacious: .5, vocal: .5, conf: .9, tags_json: '[]', source_json: '{}', model_version: 'm', updated_at: 1,
  isrc: id, jp_familiarity: .5, deezer_rank: 1, popularity: .5, catalog_group: 'jp', ...extra,
});
const write = (name: string, lines: string[]) => {
  const path = join(directory, name);
  writeFileSync(path, lines.join('\n') + '\n');
  return path;
};
const count = async (where = '1') => (await db.prepare(`SELECT COUNT(*) AS n FROM track_features WHERE ${where}`).all<{ n: number }>()).results[0].n;

describe('カタログの取り込み', () => {
  it('リポジトリのカタログをすべて取り込み、推薦に使える', async () => {
    const lines = readFileSync(repositoryCatalog, 'utf8').trim().split('\n').length;
    expect(db.importCatalog(repositoryCatalog)).toEqual({ imported: lines, retired: 0 });
    expect(await count('catalog_group IS NOT NULL')).toBe(lines);
    const response = await app.fetch(new Request('http://bgm.local/recommend', {
      method: 'POST',
      body: JSON.stringify({ mode: 'discover', members: [{ id: 'a', name: 'A' }], pool: [], seed: 1, scene: { features: { sea_proximity: 1 } } }),
    }), { bgm_db: db, GEMINI_API_KEY: '', SPOTIFY_CLIENT_ID: '', SPOTIFY_CLIENT_SECRET: '' });
    const body: any = await response.json();
    expect(body.pool_size).toBe(lines);
    expect(body.chosen.track_id).toMatch(/^isrc:/);
  });

  it('内容が同じなら何もしない', () => {
    const path = write('a.jsonl', [row('A1'), row('A2')]);
    expect(db.importCatalog(path)).toEqual({ imported: 2, retired: 0 });
    expect(db.importCatalog(path)).toBeNull();
  });

  it('カタログから消えた曲はカタログ扱いを外し、プレイリストの曲には触れない', async () => {
    await db.batch([db.prepare(
      "INSERT INTO track_features (track_id, title, artist, updated_at) VALUES ('local:曲|人', '曲', '人', 1)",
    )]);
    db.importCatalog(write('a.jsonl', [row('A1'), row('A2')]));
    expect(db.importCatalog(write('b.jsonl', [row('A1', { title: '新しい題' })]))).toEqual({ imported: 1, retired: 1 });
    expect(await count('catalog_group IS NOT NULL')).toBe(1);
    expect(await count("track_id = 'isrc:A2'")).toBe(1);
    expect(await count("track_id = 'local:曲|人'")).toBe(1);
    const { results } = await db.prepare("SELECT title FROM track_features WHERE track_id = 'isrc:A1'").all<{ title: string }>();
    expect(results[0].title).toBe('新しい題');
  });

  it('壊れた行があれば何も書き込まない', async () => {
    expect(() => db.importCatalog(write('bad.jsonl', [row('A1'), row('A2', { energy: 2 })]))).toThrow(/line 2: energy/);
    expect(() => db.importCatalog(write('bad2.jsonl', [row('A1'), '{oops']))).toThrow(/line 2: invalid JSON/);
    expect(() => db.importCatalog(write('bad3.jsonl', [row('A1', { catalog_group: 'other' })]))).toThrow(/catalog_group/);
    expect(await count()).toBe(0);
  });
});
