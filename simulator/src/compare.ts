import { bearingDegrees, distanceMeters, type LatLon } from './geo'

/** 走り比べる2本の道。tools/fetch_compare.py が書く形。 */
export type RouteKind = 'normal' | 'scenic'

export interface ComparedRoute {
  /** google は Google の Routes API、valhalla は旅れこの経路サーバーの寄り道なしの経路、tabireco は旅れこのおすすめ */
  source: 'google' | 'valhalla' | 'tabireco'
  role: string
  km: number
  minutes: number
  shape: [number, number][]
  /** [始まり m, 終わり m, 景色の種類]。地図からの推定。Google の経路には無い */
  timeline: [number, number, string][]
  reasons: string[]
}

export interface Comparison {
  fetched: string
  normal: ComparedRoute
  scenic: ComparedRoute
}

const comparisons = new Map<string, Promise<Comparison | undefined>>()

/** 一度読んだ道は使い回す */
export function loadComparison(id: string): Promise<Comparison | undefined> {
  let loading = comparisons.get(id)
  if (!loading) {
    loading = fetchComparison(id)
    comparisons.set(id, loading)
  }
  return loading
}

async function fetchComparison(id: string): Promise<Comparison | undefined> {
  try {
    const response = await fetch(`/compare/${id}.json`)
    // 無い道は、開発サーバーが index.html を返す
    if (!response.ok || !response.headers.get('content-type')?.includes('json')) return undefined
    return await response.json() as Comparison
  } catch {
    return undefined
  }
}

/** 景色の帯の上で、[from, to] の区間が占める位置 */
export function rangeOnStrip([from, to]: [number, number], length: number): { left: string; width: string } {
  return { left: `${(from / length * 100).toFixed(2)}%`, width: `${((to - from) / length * 100).toFixed(2)}%` }
}

export function kindLabel(kind: RouteKind, route: ComparedRoute): string {
  if (kind === 'scenic') return '景色のいい道'
  return route.source === 'google' ? 'ふつうの道（Google マップ）' : 'ふつうの道'
}

/** 景色の種類の、画面での呼び方と色。 */
const SCENERY: Record<string, { label: string; color: string }> = {
  sea: { label: '海', color: '#2f7fdb' },
  lake: { label: '湖', color: '#2fb6c9' },
  river: { label: '川', color: '#6aa8e6' },
  inland_water: { label: '水辺', color: '#6aa8e6' },
  forest: { label: '森', color: '#2f9e5c' },
  open_nature: { label: '草原', color: '#8fcf6a' },
  farmland: { label: '畑', color: '#cdb94a' },
  valley: { label: '渓谷', color: '#7d70d1' },
  urban: { label: '街', color: '#8c8c8c' },
}
const OTHER = { label: 'その他', color: '#4a4a4a' }

export function scenery(category: string): { label: string; color: string } {
  return SCENERY[category] ?? OTHER
}

/** 景色の種類ごとの長さ（km）。長い順。街とその他は外す。 */
export function sceneryKm(route: ComparedRoute): { label: string; color: string; km: number }[] {
  const meters = new Map<string, number>()
  for (const [start, end, category] of route.timeline) {
    if (category === 'urban' || !(category in SCENERY)) continue
    const label = SCENERY[category].label
    meters.set(label, (meters.get(label) ?? 0) + end - start)
  }
  const color = (label: string) => Object.values(SCENERY).find((s) => s.label === label)!.color
  return [...meters].map(([label, m]) => ({ label, color: color(label), km: m / 1000 }))
    .filter((s) => s.km >= 0.5).sort((a, b) => b.km - a.km)
}

/** 道の始まりから終わりまでの景色を、横長の帯にした CSS の背景。景色のデータが無ければ空。 */
export function sceneryStrip(route: ComparedRoute): string {
  const total = route.timeline.at(-1)?.[1]
  if (!total) return ''
  const stops = route.timeline.map(([start, end, category]) => {
    const { color } = scenery(category)
    return `${color} ${(start / total * 100).toFixed(2)}% ${(end / total * 100).toFixed(2)}%`
  })
  return `linear-gradient(to right, ${stops.join(', ')})`
}

/** 走る道の線。車が道のどこまで来たかを追う。 */
export class RouteLine {
  readonly points: LatLon[]
  /** points[i] までの道のり */
  readonly along: number[] = [0]
  readonly length: number
  /** 前に見つけた線分。次はこの近くから探す */
  private last = 1

  constructor(readonly route: ComparedRoute) {
    this.points = route.shape.map(([lat, lon]) => ({ lat, lon }))
    for (let i = 1; i < this.points.length; i++) {
      this.along.push(this.along[i - 1] + distanceMeters(this.points[i - 1], this.points[i]))
    }
    this.length = this.along[this.along.length - 1]
  }

  get start(): LatLon {
    return this.points[0]
  }

  get startHeading(): number {
    return bearingDegrees(this.points[0], this.pointAt(15))
  }

  /**
   * 車にいちばん近い道の上の点までの道のりと、そこまでの距離。
   * 道が折り返して自分の近くを通ることがあるので、前の位置の少し手前から先を先に探し、見つからなければ全体から探す。
   */
  locate(car: LatLon): { along: number; distance: number } {
    const near = this.search(car, Math.max(1, this.last - 10), Math.min(this.points.length - 1, this.last + 80))
    const found = near.distance <= 80 ? near : this.search(car, 1, this.points.length - 1)
    this.last = found.index
    return found
  }

  private search(car: LatLon, from: number, to: number): { along: number; distance: number; index: number } {
    let best = { along: 0, distance: Infinity, index: from }
    const scale = Math.cos(car.lat * Math.PI / 180)
    for (let i = from; i <= to; i++) {
      const a = this.points[i - 1]
      const b = this.points[i]
      const bx = (b.lon - a.lon) * scale
      const by = b.lat - a.lat
      const lengthSquared = bx * bx + by * by
      const t = lengthSquared === 0 ? 0
        : Math.max(0, Math.min(1, (((car.lon - a.lon) * scale) * bx + (car.lat - a.lat) * by) / lengthSquared))
      const point = { lat: a.lat + by * t, lon: a.lon + (b.lon - a.lon) * t }
      const distance = distanceMeters(point, car)
      if (distance < best.distance) best = { along: this.along[i - 1] + (this.along[i] - this.along[i - 1]) * t, distance, index: i }
    }
    return best
  }

  pointAt(along: number): LatLon {
    const target = Math.max(0, Math.min(this.length, along))
    let low = 1
    let high = this.along.length - 1
    while (low < high) {
      const middle = (low + high) >> 1
      if (this.along[middle] < target) low = middle + 1
      else high = middle
    }
    const t = (target - this.along[low - 1]) / Math.max(1e-9, this.along[low] - this.along[low - 1])
    const a = this.points[low - 1]
    const b = this.points[low]
    return { lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t }
  }

  /** 道のり along の辺りで、道が向いている方角。 */
  headingAt(along: number): number {
    return bearingDegrees(this.pointAt(along - 10), this.pointAt(along + 10))
  }

  /** 道のり along の地点の景色。景色のデータが無い所と、種類の分からない所は undefined。 */
  sceneryAt(along: number): { label: string; color: string } | undefined {
    const range = this.route.timeline.find(([start, end]) => along >= start && along < end)
    return range && SCENERY[range[2]]
  }
}

/** points の全部が、余白 margin を空けて width × height に収まる、地図の位置から画面の位置への変換。 */
function projector(points: LatLon[], width: number, height: number, margin: number): (p: LatLon) => [number, number] {
  const lats = points.map((p) => p.lat)
  const lons = points.map((p) => p.lon)
  const south = Math.min(...lats)
  const north = Math.max(...lats)
  const west = Math.min(...lons)
  const east = Math.max(...lons)
  const lonScale = Math.cos((south + north) / 2 * Math.PI / 180)
  const scale = Math.min((width - margin * 2) / Math.max(1e-9, (east - west) * lonScale), (height - margin * 2) / Math.max(1e-9, north - south))
  const left = (width - (east - west) * lonScale * scale) / 2
  const top = (height - (north - south) * scale) / 2
  return (p) => [left + (p.lon - west) * lonScale * scale, top + (north - p.lat) * scale]
}

/** 国土地理院の空中写真（地理院タイルの「全国最新写真（シームレス）」）。使うときは出典を画面に出す */
const AERIAL = 'https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto'
export const AERIAL_CREDIT = '空中写真：国土地理院'
const aerialTiles = new Map<string, HTMLImageElement>()
/** 地図を描き直す関数。空中写真のタイルが届くたびに呼ぶ */
const painters = new WeakMap<HTMLCanvasElement, () => void>()

/**
 * 道を選ぶ画面の地図。国土地理院の空中写真の上に、ふつうの道を白い点線で、おすすめの道を景色の色で描く。
 * focus を渡すと、その道を目立たせ、もう一方を薄くする。labels は出発地と目的地の名前。
 */
export function drawPreview(canvas: HTMLCanvasElement, comparison: Comparison, focus?: RouteKind, labels?: [string, string]): void {
  const ratio = window.devicePixelRatio || 1
  const width = canvas.width = canvas.clientWidth * ratio
  const height = canvas.height = canvas.clientHeight * ratio
  const normal = new RouteLine(comparison.normal)
  const scenic = new RouteLine(comparison.scenic)
  const all = [...normal.points, ...scenic.points]
  const project = projector(all, width, height, 40 * ratio)
  // 画面の端が地図のどこにあたるか（projector は経度・緯度について1次式なので、2点から戻せる）
  const north = Math.max(...all.map((p) => p.lat))
  const south = Math.min(...all.map((p) => p.lat))
  const west = Math.min(...all.map((p) => p.lon))
  const east = Math.max(...all.map((p) => p.lon))
  const [x1, y1] = project({ lat: north, lon: west })
  const [x2, y2] = project({ lat: south, lon: east })
  const lonAt = (x: number) => west + (x - x1) / (x2 - x1 || 1) * (east - west)
  const latAt = (y: number) => north + (y - y1) / (y2 - y1 || 1) * (south - north)
  const middle = (north + south) / 2 * Math.PI / 180
  const metersPerPixel = (lonAt(width) - lonAt(0)) * 111_320 * Math.cos(middle) / width
  const zoom = Math.max(10, Math.min(18, Math.ceil(Math.log2(156_543 * Math.cos(middle) / metersPerPixel))))
  const n = 2 ** zoom
  const tileX = (lon: number) => Math.floor((lon + 180) / 360 * n)
  const tileY = (lat: number) => {
    const phi = lat * Math.PI / 180
    return Math.floor((1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2 * n)
  }
  const tileLon = (x: number) => x / n * 360 - 180
  const tileLat = (y: number) => Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))) * 180 / Math.PI
  const tiles: { url: string; nw: [number, number]; se: [number, number] }[] = []
  for (let x = tileX(lonAt(0)); x <= tileX(lonAt(width)); x++) {
    for (let y = tileY(latAt(0)); y <= tileY(latAt(height)); y++) {
      tiles.push({
        url: `${AERIAL}/${zoom}/${x}/${y}.jpg`,
        nw: project({ lat: tileLat(y), lon: tileLon(x) }),
        se: project({ lat: tileLat(y + 1), lon: tileLon(x + 1) }),
      })
    }
  }
  for (const tile of tiles) {
    let image = aerialTiles.get(tile.url)
    if (!image) {
      image = new Image()
      image.src = tile.url
      aerialTiles.set(tile.url, image)
    }
    if (!image.complete) image.addEventListener('load', () => painters.get(canvas)?.(), { once: true })
  }

  const context = canvas.getContext('2d')!
  const scale = canvas.clientWidth > 600 ? 1.6 : 1
  const stroke = (line: RouteLine, from: number, to: number, color: string, lineWidth: number) => {
    context.beginPath()
    for (let i = from; i <= to; i++) {
      const [x, y] = project(line.points[i])
      if (i === from) context.moveTo(x, y)
      else context.lineTo(x, y)
    }
    context.strokeStyle = color
    context.lineWidth = lineWidth * ratio * scale
    context.stroke()
  }
  const paint = () => {
    context.fillStyle = '#1f2a24'
    context.fillRect(0, 0, width, height)
    for (const tile of tiles) {
      const image = aerialTiles.get(tile.url)!
      if (!image.complete || image.naturalWidth === 0) continue
      // 継ぎ目に隙間が出ないよう、少し重ねて描く
      context.drawImage(image, tile.nw[0], tile.nw[1], tile.se[0] - tile.nw[0] + 1, tile.se[1] - tile.nw[1] + 1)
    }
    // 道の線が写真に埋もれないよう、写真を暗くする
    context.fillStyle = 'rgba(16, 24, 20, 0.42)'
    context.fillRect(0, 0, width, height)
    context.lineJoin = context.lineCap = 'round'

    context.setLineDash(focus === 'normal' ? [] : [8 * ratio * scale, 7 * ratio * scale])
    stroke(normal, 0, normal.points.length - 1, '#f5f3ec', focus === 'normal' ? 5 : 3)
    context.setLineDash([])
    if (focus === 'normal') {
      // ふつうの道を選びかけているときは、景色のいい道を色のない薄い線にして、ふつうの道だけを見せる
      context.globalAlpha = 0.18
      stroke(scenic, 0, scenic.points.length - 1, '#9aa39d', 6)
    } else {
      stroke(scenic, 0, scenic.points.length - 1, '#acd0c0', 6)
      for (let i = 1; i < scenic.points.length; i++) {
        const here = scenic.sceneryAt((scenic.along[i - 1] + scenic.along[i]) / 2)
        if (here && here.label !== '街') stroke(scenic, i - 1, i, here.color, 6)
      }
    }
    context.globalAlpha = 1

    const dot = (p: LatLon, fill: string, label?: string) => {
      const [x, y] = project(p)
      context.beginPath()
      context.arc(x, y, 7 * ratio * scale, 0, Math.PI * 2)
      context.fillStyle = fill
      context.fill()
      context.lineWidth = 2.5 * ratio * scale
      context.strokeStyle = '#1e2a24'
      context.stroke()
      if (!label) return
      context.font = `700 ${15 * ratio * scale}px "Noto Sans JP", sans-serif`
      const right = x < width * 0.7
      context.textAlign = right ? 'left' : 'right'
      context.textBaseline = 'middle'
      const tx = x + (right ? 14 : -14) * ratio * scale
      context.lineWidth = 5 * ratio
      context.strokeStyle = 'rgba(16, 24, 20, 0.85)'
      context.strokeText(label, tx, y)
      context.fillStyle = '#f5f3ec'
      context.fillText(label, tx, y)
    }
    dot(scenic.points[0], '#ffffff', labels?.[0])
    dot(scenic.points.at(-1)!, '#eb6b40', labels?.[1])
  }
  painters.set(canvas, paint)
  paint()
}

/** 2本の道と車の位置を、小さな地図に描く。 */
export class CompareMap {
  private readonly base = document.createElement('canvas')
  private project: (p: LatLon) => [number, number] = () => [0, 0]

  constructor(private readonly canvas: HTMLCanvasElement) {}

  /** 2本の道を下絵に描く。走る方を太く、色を付ける。 */
  draw(lines: Record<RouteKind, RouteLine>, active: RouteKind): void {
    const ratio = window.devicePixelRatio || 1
    const width = this.canvas.clientWidth * ratio
    const height = this.canvas.clientHeight * ratio
    this.canvas.width = this.base.width = width
    this.canvas.height = this.base.height = height
    this.project = projector([...lines.normal.points, ...lines.scenic.points], width, height, 12 * ratio)

    const context = this.base.getContext('2d')!
    context.clearRect(0, 0, width, height)
    context.lineJoin = context.lineCap = 'round'
    const stroke = (line: RouteLine, color: string, lineWidth: number) => {
      context.beginPath()
      line.points.forEach((p, i) => {
        const [x, y] = this.project(p)
        if (i === 0) context.moveTo(x, y)
        else context.lineTo(x, y)
      })
      context.strokeStyle = color
      context.lineWidth = lineWidth * ratio
      context.stroke()
    }
    const other: RouteKind = active === 'normal' ? 'scenic' : 'normal'
    stroke(lines[other], 'rgba(255, 255, 255, 0.6)', 3)
    stroke(lines[active], active === 'scenic' ? '#3ecf7a' : '#f0c75e', 4)
    const dot = (p: LatLon, color: string) => {
      const [x, y] = this.project(p)
      context.beginPath()
      context.arc(x, y, 4 * ratio, 0, Math.PI * 2)
      context.fillStyle = color
      context.fill()
    }
    dot(lines[active].points[0], '#ffffff')
    dot(lines[active].points.at(-1)!, '#ff6b5e')
  }

  /** 下絵の上に、車の位置を描く。 */
  update(car: LatLon): void {
    const ratio = window.devicePixelRatio || 1
    const context = this.canvas.getContext('2d')!
    context.clearRect(0, 0, this.canvas.width, this.canvas.height)
    context.drawImage(this.base, 0, 0)
    const [x, y] = this.project(car)
    context.beginPath()
    context.arc(x, y, 6 * ratio, 0, Math.PI * 2)
    context.fillStyle = '#ffffff'
    context.fill()
    context.lineWidth = 2 * ratio
    context.strokeStyle = '#1b6fe0'
    context.stroke()
  }
}
