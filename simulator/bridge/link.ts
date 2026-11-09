import net from 'node:net'
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Plugin } from 'vite'

/**
 * シミュレータで走った位置を、旅れこ（Debug ビルド）へ渡す中継。
 *
 * ブラウザ → POST /link/fix → この中継 → TCP（1行に1つ）→ スマホの SimulatorLinkReceiver
 *
 * スマホへは1行ずつの文字で送る。アプリ側で JSON の部品を足さずに読めるように。
 *   FIX <緯度> <経度> <速さ m/s> <向き 度>
 *   DEST <緯度> <経度> <名前>   （この目的地で案内を始める。道を選んだときに送る）
 *   STOP            （実機の測位へ戻す）
 */
export interface Fix {
  lat: number
  lon: number
  speed: number
  bearing: number
}

export const DEFAULT_PHONE_PORT = 8788

export function formatFix(fix: Fix): string {
  return `FIX ${fix.lat.toFixed(7)} ${fix.lon.toFixed(7)} ${fix.speed.toFixed(2)} ${fix.bearing.toFixed(1)}\n`
}

export interface Destination {
  lat: number
  lon: number
  name: string
}

export function formatDestination(destination: Destination): string {
  // 名前に改行が混ざると次の行として読まれるので、空白に置き換える
  return `DEST ${destination.lat.toFixed(7)} ${destination.lon.toFixed(7)} ${destination.name.replace(/\s+/g, ' ').trim()}\n`
}

export function parseDestination(body: unknown): Destination | undefined {
  if (typeof body !== 'object' || body === null) return undefined
  const { lat, lon, name } = body as Record<string, unknown>
  if (typeof lat !== 'number' || !Number.isFinite(lat) || lat < -90 || lat > 90) return undefined
  if (typeof lon !== 'number' || !Number.isFinite(lon) || lon < -180 || lon > 180) return undefined
  if (typeof name !== 'string' || !name.trim()) return undefined
  return { lat, lon, name }
}

export function parseFix(body: unknown): Fix | undefined {
  if (typeof body !== 'object' || body === null) return undefined
  const { lat, lon, speed, bearing } = body as Record<string, unknown>
  const numbers = [lat, lon, speed, bearing]
  if (!numbers.every((value) => typeof value === 'number' && Number.isFinite(value))) return undefined
  const fix = { lat, lon, speed, bearing } as Fix
  if (fix.lat < -90 || fix.lat > 90 || fix.lon < -180 || fix.lon > 180) return undefined
  return { ...fix, speed: Math.max(0, fix.speed), bearing: ((fix.bearing % 360) + 360) % 360 }
}

export class PhoneLink {
  private readonly server = net.createServer((socket) => this.accept(socket))
  private readonly phones = new Set<net.Socket>()
  private last: string | undefined
  private destination: string | undefined

  /** port に 0 を渡すと空いている番号を使う（テスト用）。実際に使った番号を返す。 */
  listen(port: number): Promise<number> {
    return new Promise((resolve, reject) => {
      this.server.once('error', reject)
      // 同じ Wi-Fi のスマホからもつなげるよう、全部の口で待つ
      this.server.listen(port, '0.0.0.0', () => resolve((this.server.address() as net.AddressInfo).port))
    })
  }

  get phoneCount(): number {
    return this.phones.size
  }

  sendFix(fix: Fix): void {
    this.last = formatFix(fix)
    this.broadcast(this.last)
  }

  /** 位置を先に送ってから呼ぶ。スマホは今の位置からルートを引く。 */
  sendDestination(destination: Destination): void {
    this.destination = formatDestination(destination)
    this.broadcast(this.destination)
  }

  sendStop(): void {
    this.last = undefined
    this.destination = undefined
    this.broadcast('STOP\n')
  }

  close(): Promise<void> {
    for (const phone of this.phones) phone.destroy()
    return new Promise((resolve) => this.server.close(() => resolve()))
  }

  private accept(socket: net.Socket): void {
    socket.setNoDelay(true)
    this.phones.add(socket)
    const forget = () => this.phones.delete(socket)
    socket.on('close', forget)
    socket.on('error', forget)
    // 途中からつないだスマホにも、すぐに今の位置を渡す
    if (this.last) socket.write(this.last)
    if (this.destination) socket.write(this.destination)
  }

  private broadcast(line: string): void {
    for (const phone of this.phones) phone.write(line)
  }
}

/** /link/fix・/link/destination・/link/stop・/link/status を受ける。Vite の開発サーバーに載せる。 */
export function linkHandler(link: PhoneLink) {
  return (request: IncomingMessage, response: ServerResponse, next: () => void) => {
    const path = request.url?.split('?')[0]
    if (request.method === 'GET' && path === '/status') {
      return reply(response, 200, { phones: link.phoneCount })
    }
    if (request.method === 'POST' && path === '/stop') {
      link.sendStop()
      return reply(response, 200, { phones: link.phoneCount })
    }
    if (request.method === 'POST' && (path === '/fix' || path === '/destination')) {
      let body = ''
      request.setEncoding('utf8')
      request.on('data', (chunk: string) => {
        body += chunk
      })
      request.on('end', () => {
        let parsed: unknown
        try {
          parsed = JSON.parse(body)
        } catch {
          return reply(response, 400, { error: 'json' })
        }
        if (path === '/destination') {
          const destination = parseDestination(parsed)
          if (!destination) return reply(response, 400, { error: 'destination' })
          link.sendDestination(destination)
        } else {
          const fix = parseFix(parsed)
          if (!fix) return reply(response, 400, { error: 'fix' })
          link.sendFix(fix)
        }
        reply(response, 200, { phones: link.phoneCount })
      })
      return
    }
    next()
  }
}

function reply(response: ServerResponse, status: number, body: object): void {
  response.statusCode = status
  response.setHeader('Content-Type', 'application/json')
  response.end(JSON.stringify(body))
}

export function linkPlugin(port = DEFAULT_PHONE_PORT): Plugin {
  return {
    name: 'tabireco-phone-link',
    configureServer(server) {
      // 設定や .env.local を直すと Vite は開発サーバーを作り直す。そのたびに口を開き直すと、
      // 古い口が閉じる前に同じ番号を開こうとして失敗し、スマホとのつながりも切れる。
      // 中継はプロセスに1つだけ持ち、作り直しのあとも使い回す
      const shared = globalThis as { tabirecoPhoneLink?: PhoneLink }
      let link = shared.tabirecoPhoneLink
      if (!link) {
        link = new PhoneLink()
        shared.tabirecoPhoneLink = link
        link.listen(port).then(
          (actual) => server.config.logger.info(`  スマホへの中継: TCP ${actual} で待っています`),
          (error: Error) => server.config.logger.error(`  スマホへの中継を開けませんでした: ${error.message}`),
        )
      }
      server.middlewares.use('/link', linkHandler(link))
    },
  }
}
