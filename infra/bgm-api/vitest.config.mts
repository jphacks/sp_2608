import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
	plugins: [
		cloudflareTest({
			wrangler: { configPath: "./wrangler.jsonc" },
		}),
	],
	// PC 版のテスト（node:sqlite を使う）は vitest.node.config.mts で別に動かす。
	test: { exclude: ["test-node/**", "node_modules/**"] },
});
