import { bearingDegrees, distanceMeters, type LatLon } from './geo'

/** tools/fetch_roads.py が書く形。© OpenStreetMap contributors（ODbL） */
export interface RoadData {
  attribution: string
  fetched: string
  nodes: [number, number][]
  ways: { name: string; ref: string; highway: string; oneway: 0 | 1 | -1; nodes: number[] }[]
}

/** 交差点から交差点までの1本の道。 */
export interface Edge {
  id: number
  from: number
  to: number
  points: LatLon[]
  /** points[i] までの距離 */
  along: number[]
  length: number
  /** 1 は from→to だけ、-1 は to→from だけ、0 は両方へ走れる */
  oneway: 0 | 1 | -1
  name: string
  /** 道の真ん中の線から端までの幅（メートル）。この中なら左右へ自由に動ける */
  halfWidth: number
}

/** 道の上の車の位置。forward は from→to の向きに走っていること。 */
export interface RoadPosition {
  edge: Edge
  /** from からの距離 */
  offset: number
  forward: boolean
}

export class RoadNetwork {
  readonly edges: Edge[] = []
  /** 点の番号ごとの、そこにつながる道 */
  private readonly junctions = new Map<number, Edge[]>()

  constructor(data: RoadData) {
    const used = new Map<number, number>()
    for (const way of data.ways) {
      way.nodes.forEach((node, i) => {
        // 道の端と、ほかの道と共有している点で切る
        const weight = i === 0 || i === way.nodes.length - 1 ? 2 : 1
        used.set(node, (used.get(node) ?? 0) + weight)
      })
    }
    const position = (node: number): LatLon => ({ lat: data.nodes[node][0], lon: data.nodes[node][1] })
    for (const way of data.ways) {
      let start = 0
      for (let i = 1; i < way.nodes.length; i++) {
        if (i < way.nodes.length - 1 && (used.get(way.nodes[i]) ?? 0) < 2) continue
        const nodes = way.nodes.slice(start, i + 1)
        this.add(nodes, nodes.map(position), way.oneway, roadName(way), halfWidth(way.highway))
        start = i
      }
    }
  }

  private add(nodes: number[], points: LatLon[], oneway: 0 | 1 | -1, name: string, halfWidth: number): void {
    const along = [0]
    for (let i = 1; i < points.length; i++) along.push(along[i - 1] + distanceMeters(points[i - 1], points[i]))
    const length = along[along.length - 1]
    if (length < 0.5) return
    const edge: Edge = { id: this.edges.length, from: nodes[0], to: nodes[nodes.length - 1], points, along, length, oneway, name, halfWidth }
    this.edges.push(edge)
    for (const node of new Set([edge.from, edge.to])) {
      const list = this.junctions.get(node) ?? []
      list.push(edge)
      this.junctions.set(node, list)
    }
  }

  /** いちばん近い道の上の点。向きは heading に近い方を選ぶ（一方通行なら走れる向き）。 */
  snap(target: LatLon, headingDegrees: number): RoadPosition {
    const { position } = this.locate(target, headingDegrees)
    if (position.edge.oneway === 1) position.forward = true
    if (position.edge.oneway === -1) position.forward = false
    return position
  }

  /**
   * いちばん近い道の上の点と、そこまでの距離。向きは heading に近い方。
   * 自由に走っている車の今いる道を知るためのもので、一方通行は見ない（逆走していても、走っている向きを返す）。
   */
  locate(target: LatLon, headingDegrees: number): { position: RoadPosition; distance: number } {
    let best: { edge: Edge; offset: number; distance: number } | undefined
    for (const edge of this.edges) {
      for (let i = 1; i < edge.points.length; i++) {
        const [offset, distance] = nearestOnSegment(edge.points[i - 1], edge.points[i], target)
        if (!best || distance < best.distance) best = { edge, offset: edge.along[i - 1] + offset, distance }
      }
    }
    if (!best) throw new Error('道がありません')
    const along = headingAt({ edge: best.edge, offset: best.offset, forward: true })
    const forward = Math.abs(turnAngle(headingDegrees, along)) <= 90
    return { position: { edge: best.edge, offset: best.offset, forward }, distance: best.distance }
  }

  /**
   * 自由に動いた車の位置 target を、道幅の中へ収める。道の端を越えていたら端まで戻し、pushed を返す。
   * 交差点では、いちばん近い道が入れ替わるので、ハンドルを切った先の道へそのまま入っていける。
   */
  keepOnRoad(target: LatLon, headingDegrees: number): { at: LatLon; position: RoadPosition; pushed: boolean } {
    const { position, distance } = this.locate(target, headingDegrees)
    const limit = position.edge.halfWidth
    if (distance <= limit) return { at: target, position, pushed: false }
    const center = pointAt(position)
    const t = limit / distance
    const at = { lat: center.lat + (target.lat - center.lat) * t, lon: center.lon + (target.lon - center.lon) * t }
    return { at, position, pushed: true }
  }

  /**
   * distance メートル進める。交差点では steer（-1 左〜1 右）の方へ曲がる。
   * 行き止まりでは端で止まり、stopped を返す。
   */
  advance(position: RoadPosition, distance: number, steer: number): { position: RoadPosition; stopped: boolean } {
    let current = { ...position }
    let left = distance
    for (let guard = 0; guard < 100; guard++) {
      const room = current.forward ? current.edge.length - current.offset : current.offset
      if (left <= room) {
        current.offset += current.forward ? left : -left
        return { position: current, stopped: false }
      }
      left -= room
      const next = this.choose(current, steer)
      if (!next) {
        current.offset = current.forward ? current.edge.length : 0
        return { position: current, stopped: true }
      }
      current = next
    }
    return { position: current, stopped: false }
  }

  /** 交差点の先で選ぶ道。曲がれる道がなければ undefined。 */
  choose(position: RoadPosition, steer: number): RoadPosition | undefined {
    const node = position.forward ? position.edge.to : position.edge.from
    const arriving = headingAt({ ...position, offset: position.forward ? position.edge.length : 0 })
    // 左いっぱいで 120 度左、右いっぱいで 120 度右の道を探す。まっすぐなら、いちばんまっすぐな道
    const wanted = Math.max(-1, Math.min(1, steer)) * 120
    let best: { position: RoadPosition; score: number } | undefined
    for (const option of this.options(position, node)) {
      const score = Math.abs(turnAngle(arriving, headingAt(option)) - wanted)
      if (!best || score < best.score) best = { position: option, score }
    }
    return best?.position
  }

  /** 次に曲がる道を選べる交差点（道が3本以上）までの距離と、今のハンドルで選ばれる道。 */
  nextJunction(position: RoadPosition, steer: number, lookMeters = 400):
    { distance: number; choice: RoadPosition; turn: number; branches: number } | undefined {
    let current = position
    let distance = current.forward ? current.edge.length - current.offset : current.offset
    for (let guard = 0; guard < 50 && distance < lookMeters; guard++) {
      const node = current.forward ? current.edge.to : current.edge.from
      const options = this.options(current, node)
      if (options.length === 0) return undefined
      if (options.length >= 2) {
        const choice = this.choose(current, steer)!
        const arriving = headingAt({ ...current, offset: current.forward ? current.edge.length : 0 })
        return { distance, choice, turn: turnAngle(arriving, headingAt(choice)), branches: options.length }
      }
      current = options[0]
      distance += current.edge.length
    }
    return undefined
  }

  /** 交差点 node から入れる道。来た道を戻る（U ターン）は外す。 */
  private options(position: RoadPosition, node: number): RoadPosition[] {
    const result: RoadPosition[] = []
    for (const edge of this.junctions.get(node) ?? []) {
      for (const forward of [true, false]) {
        if ((forward ? edge.from : edge.to) !== node) continue
        if (edge.oneway === 1 && !forward) continue
        if (edge.oneway === -1 && forward) continue
        if (edge === position.edge && forward !== position.forward) continue
        result.push({ edge, offset: forward ? 0 : edge.length, forward })
      }
    }
    return result
  }
}

/** 道の上の地点。 */
export function pointAt(position: RoadPosition): LatLon {
  const { edge, offset } = position
  const i = segmentIndex(edge, offset)
  const t = (offset - edge.along[i - 1]) / Math.max(1e-9, edge.along[i] - edge.along[i - 1])
  const a = edge.points[i - 1]
  const b = edge.points[i]
  return { lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t }
}

/** 走っている向き（北を 0 とした時計回りの角度）。 */
export function headingAt(position: RoadPosition): number {
  const { edge, offset, forward } = position
  const i = segmentIndex(edge, offset)
  const a = edge.points[i - 1]
  const b = edge.points[i]
  return forward ? bearingDegrees(a, b) : bearingDegrees(b, a)
}

/** U ターンした位置。一方通行で逆に走れないなら undefined。 */
export function turnAround(position: RoadPosition): RoadPosition | undefined {
  const forward = !position.forward
  if (position.edge.oneway === 1 && !forward) return undefined
  if (position.edge.oneway === -1 && forward) return undefined
  return { ...position, forward }
}

/** from から to へ向きを変えるときの角度（-180〜180、右が正）。 */
export function turnAngle(from: number, to: number): number {
  return ((to - from + 540) % 360) - 180
}

function segmentIndex(edge: Edge, offset: number): number {
  let i = 1
  while (i < edge.along.length - 1 && edge.along[i] < offset) i++
  return i
}

/** 線分 a-b のうち p にいちばん近い点の、a からの距離と、p までの距離。 */
function nearestOnSegment(a: LatLon, b: LatLon, p: LatLon): [number, number] {
  const scale = Math.cos(a.lat * Math.PI / 180)
  const bx = (b.lon - a.lon) * scale
  const by = b.lat - a.lat
  const px = (p.lon - a.lon) * scale
  const py = p.lat - a.lat
  const lengthSquared = bx * bx + by * by
  const t = lengthSquared === 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / lengthSquared))
  const point = { lat: a.lat + by * t, lon: a.lon + (b.lon - a.lon) * t }
  return [distanceMeters(a, point), distanceMeters(point, p)]
}

/** 「国道5号（大沼国道）」の形。名前が番号と同じなら番号だけ。 */
function roadName(way: RoadData['ways'][number]): string {
  if (!way.ref) return way.name
  const number = label(way.highway, way.ref)
  return way.name && way.name !== number ? `${number}（${way.name}）` : number
}

/** OSM のデータに車線の数を入れていないので、道の種類で幅を決める（片側1車線で約 3.5m、路肩を少し足す）。 */
function halfWidth(highway: string): number {
  if (/^(motorway|trunk)/.test(highway)) return 5.5
  if (/^(primary|secondary)/.test(highway)) return 4.5
  return 3.5
}

/** 日本の OSM では、一般国道を trunk、道道（主要地方道と一般道道）を primary・secondary にしている。 */
function label(highway: string, ref: string): string {
  if (highway.startsWith('motorway')) return ref
  if (highway.startsWith('trunk')) return `国道${ref}号`
  return `道道${ref}号`
}
