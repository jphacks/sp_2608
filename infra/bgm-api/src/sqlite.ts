// PC（Docker）で動かすときの DB。Node.js 内蔵の SQLite で、D1 と同じ使い方ができるようにする。
// Workers 版のビルドには含めない（node:sqlite は Workers に無い）。

import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { DatabaseSync, type SQLInputValue } from 'node:sqlite';
import { CATALOG_COLUMNS } from './catalog-columns';
import type { SqlDatabase, SqlStatement } from './db';

class Statement implements SqlStatement {
  constructor(
    private readonly db: DatabaseSync,
    private readonly sql: string,
    private readonly values: SQLInputValue[] = [],
  ) {}

  bind(...values: unknown[]): SqlStatement {
    return new Statement(this.db, this.sql, values.map(toInput));
  }

  async all<T = Record<string, unknown>>(): Promise<{ results: T[] }> {
    return { results: this.db.prepare(this.sql).all(...this.values) as T[] };
  }

  run() {
    this.db.prepare(this.sql).run(...this.values);
  }
}

export class SqliteDatabase implements SqlDatabase {
  private readonly db: DatabaseSync;

  constructor(path: string) {
    this.db = new DatabaseSync(path);
    // 推薦の読み取りと、注釈・照合結果の書き込みが重なっても待てるようにする。
    this.db.exec('PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000;');
  }

  prepare(sql: string): SqlStatement {
    return new Statement(this.db, sql);
  }

  async batch(statements: SqlStatement[]): Promise<unknown[]> {
    this.db.exec('BEGIN');
    try {
      for (const statement of statements) (statement as Statement).run();
      this.db.exec('COMMIT');
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
    return statements.map(() => ({ success: true }));
  }

  /**
   * migrations/ の SQL をファイル名の順に、まだ適用していないものだけ適用する。
   * D1 の `wrangler d1 migrations apply` と同じ順番・同じ SQL を使う。
   */
  migrate(directory: string): string[] {
    this.db.exec('CREATE TABLE IF NOT EXISTS bgm_migrations (name TEXT PRIMARY KEY, applied_at INTEGER NOT NULL)');
    const applied = new Set(
      (this.db.prepare('SELECT name FROM bgm_migrations').all() as { name: string }[]).map((row) => row.name),
    );
    const pending = readdirSync(directory).filter((name) => name.endsWith('.sql') && !applied.has(name)).sort();
    for (const name of pending) {
      this.db.exec('BEGIN');
      try {
        this.db.exec(readFileSync(join(directory, name), 'utf8'));
        this.db.prepare('INSERT INTO bgm_migrations (name, applied_at) VALUES (?, ?)').run(name, Date.now());
        this.db.exec('COMMIT');
      } catch (error) {
        this.db.exec('ROLLBACK');
        throw new Error(`migration ${name} failed: ${(error as Error).message}`);
      }
    }
    return pending;
  }

  /**
   * リポジトリの全曲カタログ（catalog/catalog.jsonl）を取り込む。内容が前回と同じなら何もしない。
   * カタログの曲だけを更新し、カタログから消えた曲はカタログ扱いを外す。
   * 注釈したプレイリストの曲と Spotify の照合結果には触れない。全行を検査してから1回の取引で書く。
   */
  importCatalog(path: string): { imported: number; retired: number } | null {
    const text = readFileSync(path, 'utf8');
    const digest = createHash('sha256').update(text).digest('hex');
    this.db.exec('CREATE TABLE IF NOT EXISTS bgm_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)');
    const current = this.db.prepare("SELECT value FROM bgm_meta WHERE key = 'catalog_sha256'").get() as { value: string } | undefined;
    if (current?.value === digest) return null;
    const rows = text.split('\n').filter((line) => line.trim()).map((line, index) => catalogRow(line, index + 1));
    const columns = CATALOG_COLUMNS.join(', ');
    const placeholders = CATALOG_COLUMNS.map(() => '?').join(', ');
    const updates = CATALOG_COLUMNS.filter((c) => c !== 'track_id').map((c) => `${c} = excluded.${c}`).join(', ');
    const upsert = this.db.prepare(
      `INSERT INTO track_features (${columns}) VALUES (${placeholders}) ON CONFLICT(track_id) DO UPDATE SET ${updates}`,
    );
    this.db.exec('BEGIN');
    try {
      for (const row of rows) upsert.run(...CATALOG_COLUMNS.map((c) => row[c] as SQLInputValue));
      this.db.exec('CREATE TEMP TABLE catalog_ids (track_id TEXT PRIMARY KEY)');
      const keep = this.db.prepare('INSERT INTO catalog_ids (track_id) VALUES (?)');
      for (const row of rows) keep.run(row.track_id as string);
      const retired = this.db.prepare(
        'UPDATE track_features SET catalog_group = NULL WHERE catalog_group IS NOT NULL AND track_id NOT IN (SELECT track_id FROM catalog_ids)',
      ).run().changes;
      this.db.exec('DROP TABLE catalog_ids');
      this.db.prepare("INSERT INTO bgm_meta (key, value) VALUES ('catalog_sha256', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").run(digest);
      this.db.exec('COMMIT');
      return { imported: rows.length, retired: Number(retired) };
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
  }

  close() {
    this.db.close();
  }
}

const CATALOG_GROUPS = new Set(['jp', 'inst', 'western', 'kpop']);
const UNIT_COLUMNS = ['energy', 'valence', 'tempo', 'acoustic', 'spacious', 'vocal', 'conf', 'jp_familiarity', 'popularity'] as const;

function catalogRow(line: string, lineNumber: number): Record<string, unknown> {
  const fail = (reason: string): never => { throw new Error(`catalog line ${lineNumber}: ${reason}`); };
  let row: Record<string, unknown>;
  try { row = JSON.parse(line); } catch { return fail('invalid JSON'); }
  if (typeof row.track_id !== 'string' || !row.track_id.startsWith('isrc:')) fail('track_id must be isrc:...');
  if (typeof row.title !== 'string' || typeof row.artist !== 'string') fail('title and artist are required');
  if (!CATALOG_GROUPS.has(row.catalog_group as string)) fail(`unknown catalog_group ${row.catalog_group}`);
  for (const column of UNIT_COLUMNS) {
    const value = row[column];
    if (value != null && (typeof value !== 'number' || !(value >= 0 && value <= 1))) fail(`${column} must be 0..1`);
  }
  for (const column of CATALOG_COLUMNS) if (row[column] === undefined) row[column] = null;
  return row;
}

// D1 は undefined を受け付けない。取り違えを早く見つけるため、同じく拒否する。
function toInput(value: unknown): SQLInputValue {
  if (value === undefined) throw new TypeError('undefined cannot be bound; use null');
  if (typeof value === 'boolean') return value ? 1 : 0;
  return value as SQLInputValue;
}
