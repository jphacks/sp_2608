import type { LatLon } from './geo'

/** 実機の測位と同じく1秒ごとに送る。アプリの案内は1秒ごとの位置を前提にしている。 */
const SEND_INTERVAL_MS = 1_000

/** 開発サーバーの中継（bridge/link.ts）へ位置を送る。 */
export class LinkClient {
  /** つながっているスマホの台数。中継に届かないときは undefined */
  phones: number | undefined
  private lastSent = -Infinity
  private sending = false

  update(now: number, position: LatLon, headingDegrees: number, speed: number): void {
    if (this.sending || now - this.lastSent < SEND_INTERVAL_MS) return
    this.lastSent = now
    this.sending = true
    fetch('/link/fix', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lat: position.lat, lon: position.lon, speed, bearing: headingDegrees }),
    })
      .then((response) => response.json() as Promise<{ phones: number }>)
      .then((body) => { this.phones = body.phones })
      .catch(() => { this.phones = undefined })
      .finally(() => { this.sending = false })
  }

  /**
   * 道を選んだとき。出発点の位置を送ってから目的地を送り、スマホにその目的地で案内を始めさせる。
   * スマホは今の位置からルートを引くので、位置が先に届いている必要がある。
   */
  async start(position: LatLon, headingDegrees: number, destination: { name: string; position: LatLon }): Promise<void> {
    this.lastSent = performance.now()
    try {
      await this.post('/link/fix', { lat: position.lat, lon: position.lon, speed: 0, bearing: headingDegrees })
      const body = await this.post('/link/destination', { lat: destination.position.lat, lon: destination.position.lon, name: destination.name })
      this.phones = body.phones
    } catch {
      this.phones = undefined
    }
  }

  private async post(path: string, body: object): Promise<{ phones: number }> {
    const response = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    return response.json() as Promise<{ phones: number }>
  }

  /** スマホを実機の測位へ戻す。画面を閉じるときにも呼ぶ。 */
  stop(): void {
    navigator.sendBeacon('/link/stop')
  }
}
