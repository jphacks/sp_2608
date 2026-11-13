import { defineConfig } from 'vitest/config'

// vite.config.ts を読むと、スマホへの中継（TCP 8788）までテスト中に開いてしまうので分ける
export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
  },
})
