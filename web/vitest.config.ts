import path from "node:path";
import { defineWorkersConfig, readD1Migrations } from "@cloudflare/vitest-pool-workers/config";

// テストは本物の Workers 実行環境（workerd）で動かし、D1 と R2 はローカルの一時ストレージを使う。
export default defineWorkersConfig(async () => {
  const migrations = await readD1Migrations(path.join(__dirname, "migrations"));
  return {
    test: {
      include: ["test/**/*.test.ts"],
      setupFiles: ["./test/apply-migrations.ts"],
      poolOptions: {
        workers: {
          // R2 の隔離ストレージが同梱の workerd と合わないので隔離はせず、テスト側で毎回消す
          isolatedStorage: false,
          singleWorker: true,
          wrangler: { configPath: "./wrangler.jsonc" },
          miniflare: {
            // テスト用プールに同梱の workerd は少し古いので、互換日付をそれに合わせる
            compatibilityDate: "2025-09-01",
            bindings: { TEST_MIGRATIONS: migrations, TRIP_UPLOAD_TOKEN: "secret-token", PUBLIC_BASE_URL: "https://example.test" },
          },
        },
      },
    },
  };
});
