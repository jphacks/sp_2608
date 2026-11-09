import type { LatLon } from './geo'

const BASE = 'https://tile.googleapis.com/v1'
const SESSION_KEY = 'tabireco-simulator.streetview-session'

/** 写真1枚（パノラマ）の情報。Map Tiles API の streetview/metadata から。 */
export interface PanoMeta {
  id: string
  at: LatLon
  /** 写真の真ん中が向いている方角（北を 0 とした時計回りの角度） */
  heading: number
  /** 写真全体のいちばん細かい大きさ（ピクセル）。新しい写真は 16384×8192、古い写真は小さい */
  imageWidth: number
  imageHeight: number
  /** 画面に出す出典（例：© 2024 Google） */
  copyright: string
  /** 写真の問題を報告するリンク。画面の右下に出す決まり */
  reportProblemLink: string
}

/** これまでに Google へ送った数。費用の目安として画面に出す */
export interface Usage {
  tiles: number
  panoIds: number
  metadata: number
}

/**
 * Google のストリートビューの写真を、自前の表示に使う正式な API（Map Tiles API）。
 * 写真は走るたびに読み込み、保存しない。保存してよいのはパノラマの ID だけ（Map Tiles API のポリシー）。
 */
export class StreetViewApi {
  readonly usage: Usage = { tiles: 0, panoIds: 0, metadata: 0 }

  private constructor(private readonly key: string, private readonly session: string) {}

  static async create(key: string): Promise<StreetViewApi> {
    // セッションを使い回す。写真のタイルは「1時間は使い回してよい」（Cache-Control: max-age=3600）ので、
    // 同じセッションなら同じアドレスになり、1時間以内に同じ道を走るとブラウザに残った写真が使われる。
    // 残すのはセッションの文字列だけで、写真は残さない
    try {
      const saved = JSON.parse(localStorage.getItem(SESSION_KEY) ?? 'null') as { session: string; expiry: number } | null
      if (saved && saved.expiry * 1000 > Date.now() + 3_600_000) return new StreetViewApi(key, saved.session)
    } catch {
      // 読めなければ作り直す
    }
    const response = await fetch(`${BASE}/createSession?key=${encodeURIComponent(key)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mapType: 'streetview', language: 'ja', region: 'JP' }),
    })
    if (!response.ok) throw new Error(`ストリートビューの準備に失敗しました（${response.status}）`)
    const { session, expiry } = await response.json() as { session: string; expiry: string }
    try {
      localStorage.setItem(SESSION_KEY, JSON.stringify({ session, expiry: Number(expiry) }))
    } catch {
      // 残せなくても、この画面を開いている間は使える
    }
    return new StreetViewApi(key, session)
  }

  /** 地点ごとに、近くの写真の ID。写真がなければ undefined。1回で100地点まで。 */
  async panoIds(locations: LatLon[], radiusMeters: number): Promise<(string | undefined)[]> {
    this.usage.panoIds += 1
    const response = await fetch(`${BASE}/streetview/panoIds?${this.query()}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ locations: locations.map((p) => ({ lat: p.lat, lng: p.lon })), radius: radiusMeters }),
    })
    if (!response.ok) throw new Error(`写真の ID を取れませんでした（${response.status}）`)
    const { panoIds } = await response.json() as { panoIds: string[] }
    return panoIds.map((id) => id || undefined)
  }

  async metadata(id: string): Promise<PanoMeta> {
    this.usage.metadata += 1
    const response = await fetch(`${BASE}/streetview/metadata?${this.query()}&panoId=${encodeURIComponent(id)}`)
    if (!response.ok) throw new Error(`写真の情報を取れませんでした（${response.status}）`)
    const body = await response.json() as {
      lat: number; lng: number; heading: number; imageWidth: number; imageHeight: number; copyright?: string; reportProblemLink?: string
    }
    return {
      id,
      at: { lat: body.lat, lon: body.lng },
      heading: body.heading,
      imageWidth: body.imageWidth,
      imageHeight: body.imageHeight,
      copyright: body.copyright ?? '',
      reportProblemLink: body.reportProblemLink ?? '',
    }
  }

  /** 写真のタイル（最大 512×512、右端と下端は小さいことがある）。新しい写真は zoom 3 で横 8 枚・縦 4 枚になる。 */
  async tile(id: string, zoom: number, x: number, y: number): Promise<ImageBitmap> {
    this.usage.tiles += 1
    const response = await fetch(
      `${BASE}/streetview/tiles/${zoom}/${x}/${y}?${this.query()}&panoId=${encodeURIComponent(id)}`)
    if (!response.ok) throw new Error(`写真を取れませんでした（${response.status}）`)
    return createImageBitmap(await response.blob())
  }

  private query(): string {
    return `session=${encodeURIComponent(this.session)}&key=${encodeURIComponent(this.key)}`
  }
}
