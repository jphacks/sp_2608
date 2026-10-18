// PC（Docker）での入口。Workers 版（index.ts の default export）と同じアプリを Node.js で動かす。
// 認証は前段のゲートウェイ（nginx）が経路 API と同じトークンで行うので、APP_TOKEN は通常設定しない。

import { serve } from '@hono/node-server';
import app from './index';
import { SqliteDatabase } from './sqlite';

const env = process.env;
const db = new SqliteDatabase(env.BGM_DATABASE ?? '/state/bgm.sqlite');
const applied = db.migrate(env.BGM_MIGRATIONS ?? new URL('../migrations', import.meta.url).pathname);
if (applied.length) console.log(`bgm-api: applied migrations ${applied.join(', ')}`);
// 全曲カタログはリポジトリのファイルから入れる。新しい環境でも Gemini を使わずに同じカタログになる。
const catalog = db.importCatalog(env.BGM_CATALOG ?? new URL('../catalog/catalog.jsonl', import.meta.url).pathname);
if (catalog) console.log(`bgm-api: catalog ${catalog.imported} tracks imported, ${catalog.retired} retired`);

const bindings = {
  bgm_db: db,
  GEMINI_API_KEY: env.GEMINI_API_KEY ?? '',
  GEMINI_MODEL: env.GEMINI_MODEL || undefined,
  GEMINI_THINKING_LEVEL: env.GEMINI_THINKING_LEVEL || undefined,
  SPOTIFY_CLIENT_ID: env.SPOTIFY_CLIENT_ID ?? '',
  SPOTIFY_CLIENT_SECRET: env.SPOTIFY_CLIENT_SECRET ?? '',
  APP_TOKEN: env.APP_TOKEN || undefined,
  SCENIC_INTERNAL_URL: env.SCENIC_INTERNAL_URL || undefined,
};

const port = Number(env.BGM_PORT ?? 8005);
const hostname = env.BGM_BIND ?? '127.0.0.1';
const server = serve({ fetch: (request) => app.fetch(request, bindings), port, hostname }, () => {
  console.log(`bgm-api: listening on ${hostname}:${port}`);
});

// docker stop で書き込み途中の DB を閉じてから終わる。
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => server.close(() => { db.close(); process.exit(0); }));
}
