import { appendFile, mkdir } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import type { Plugin } from 'vite'

/** ブラウザが送ってくる、ストリートビューの読み込みの記録を書く先。1行が1回分（JSON） */
export const USAGE_LOG = resolve(import.meta.dirname, '..', 'logs', 'streetview-usage.jsonl')

/**
 * ストリートビューを Google から実際に何枚読んだかを、ファイルに残す。
 * ブラウザが /usage-log へ送った JSON に時刻を付けて、logs/streetview-usage.jsonl に1行ずつ足す。
 */
export function usageLogPlugin(): Plugin {
  return {
    name: 'tabireco-usage-log',
    configureServer(server) {
      server.middlewares.use('/usage-log', (request, response) => {
        let body = ''
        request.on('data', (chunk) => { body += chunk })
        request.on('end', () => {
          let record: unknown
          try {
            record = JSON.parse(body)
          } catch {
            response.statusCode = 400
            response.end()
            return
          }
          const line = JSON.stringify({ time: new Date().toISOString(), ...(record as object) }) + '\n'
          mkdir(dirname(USAGE_LOG), { recursive: true })
            .then(() => appendFile(USAGE_LOG, line))
            .then(() => { response.statusCode = 204 }, () => { response.statusCode = 500 })
            .finally(() => response.end())
        })
      })
    },
  }
}
