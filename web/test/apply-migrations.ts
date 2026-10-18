import { applyD1Migrations, env } from "cloudflare:test";

// 各テストファイルの前に D1 のスキーマを作る
await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
