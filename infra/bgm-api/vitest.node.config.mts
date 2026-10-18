// PC 版（Node.js）のテスト。Workers のテストとは実行環境が違うので分ける。
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: { include: ['test-node/**/*.spec.ts'], environment: 'node' },
});
