import { defineConfig } from 'vite'
import { linkPlugin } from './bridge/link.ts'
import { usageLogPlugin } from './bridge/usageLog.ts'

export default defineConfig({
  server: {
    // Google のキーは http://localhost:5173 からの読み込みだけに絞っている
    port: 5173,
    strictPort: true,
  },
  build: {
    target: 'es2022',
  },
  plugins: [linkPlugin(), usageLogPlugin()],
})
