// スマホがネットにつながっていないときに、USB の線（adb reverse）で Mac のネットを使わせる中継。
// Debug ビルドの旅れこの接続先を http://127.0.0.1:8790/… にして使う（Debug ビルドは http を通す）。
//
//   node simulator/tools/phone-proxy.mjs
//   adb reverse tcp:8790 tcp:8790
//
//   /routing/… → 経路サーバー（環境変数 VALHALLA_URL、例：Cloud Run）
//   /ofm/…     → 地図の配信元 https://tiles.openfreemap.org/…
//
// 地図の設定ファイル（style.json や TileJSON）の中には配信元のアドレスが書かれているので、
// そこも中継を通るよう書き換えて返す。
import http from 'node:http'

const PORT = Number(process.env.PORT ?? 8790)
const SELF = `http://127.0.0.1:${PORT}`
const ROUTING = (process.env.VALHALLA_URL ?? '').replace(/\/$/, '')
const MAP = 'https://tiles.openfreemap.org'

if (!ROUTING) {
  console.error('VALHALLA_URL を指定してください')
  process.exit(1)
}

const server = http.createServer(async (request, response) => {
  const url = request.url ?? '/'
  const upstream = url.startsWith('/routing/') ? ROUTING + url.slice('/routing'.length)
    : url.startsWith('/ofm/') ? MAP + url.slice('/ofm'.length)
      : undefined
  if (!upstream) {
    response.writeHead(404).end()
    return
  }
  try {
    const body = request.method === 'GET' || request.method === 'HEAD' ? undefined
      : Buffer.concat(await Array.fromAsync(request))
    const headers = {}
    for (const name of ['authorization', 'content-type', 'accept', 'user-agent']) {
      if (request.headers[name]) headers[name] = request.headers[name]
    }
    const reply = await fetch(upstream, { method: request.method, headers, body })
    const type = reply.headers.get('content-type') ?? ''
    let data = Buffer.from(await reply.arrayBuffer())
    if (type.includes('json')) data = Buffer.from(data.toString('utf8').replaceAll(MAP, `${SELF}/ofm`))
    const out = { 'content-type': type || 'application/octet-stream' }
    const cache = reply.headers.get('cache-control')
    if (cache) out['cache-control'] = cache
    response.writeHead(reply.status, out).end(data)
  } catch (error) {
    console.error(`${request.method} ${url}: ${error.message}`)
    response.writeHead(502).end()
  }
})

server.listen(PORT, '127.0.0.1', () => console.log(`スマホ用の中継: ${SELF}（経路 ${ROUTING}）`))
