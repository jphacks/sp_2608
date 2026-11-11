import { bearingDegrees, distanceMeters, type LatLon } from './geo'

/** tools/fetch_roads.py が書く形。© OpenStreetMap contributors（ODbL） */
export interface RoadData {
  attribution: string
  fetched: string
  nodes: [number, number][]
  ways: { name: string; ref: string; highway: string; oneway: 0 | 1 | -1; nodes: number[]; bridge?: 1 }[]
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
  /** 橋の上の道 */
  bridge: boolean
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
  /** 升目ごとの、そこを通る線分（道と、points の番号）。近い道を毎フレーム探すので、全部の道を調べずに済むように */
  private readonly cells = new Map<string, [Edge, number][]>()

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
        this.add(nodes, nodes.map(position), way.oneway, roadName(way), halfWidth(way.highway), way.bridge === 1)
        start = i
      }
    }
  }

  private add(nodes: number[], points: LatLon[], oneway: 0 | 1 | -1, name: string, halfWidth: number, bridge: boolean): void {
    const along = [0]
    for (let i = 1; i < points.length; i++) along.push(along[i - 1] + distanceMeters(points[i - 1], points[i]))
    const length = along[along.length - 1]
    if (length < 0.5) return
    const edge: Edge = { id: this.edges.length, from: nodes[0], to: nodes[nodes.length - 1], points, along, length, oneway, name, halfWidth, bridge }
    this.edges.push(edge)
    for (let i = 1; i < points.length; i++) {
      const [a, b] = [points[i - 1], points[i]]
      for (let row = cellRow(Math.min(a.lat, b.lat)); row <= cellRow(Math.max(a.lat, b.lat)); row++) {
        for (let column = cellColumn(Math.min(a.lon, b.lon)); column <= cellColumn(Math.max(a.lon, b.lon)); column++) {
          const key = `${row},${column}`
          const list = this.cells.get(key)
          if (list) list.push([edge, i])
          else this.cells.set(key, [[edge, i]])
        }
      }
    }
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
  locate(target: LatLon, headingDegrees: number, among?: Iterable<Edge>): { position: RoadPosition; distance: number } {
    let best: { edge: Edge; offset: number; distance: number } | undefined
    const check = (edge: Edge, i: number) => {
      const [offset, distance] = nearestOnSegment(edge.points[i - 1], edge.points[i], target)
      if (!best || distance < best.distance) best = { edge, offset: edge.along[i - 1] + offset, distance }
    }
    if (among) {
      for (const edge of among) for (let i = 1; i < edge.points.length; i++) check(edge, i)
    } else {
      // まず、まわりの升目（3×3）の線分だけを調べる。升目1つ分より近い道が見つかれば、それがいちばん近い
      const [row, column] = [cellRow(target.lat), cellColumn(target.lon)]
      for (let r = row - 1; r <= row + 1; r++) {
        for (let c = column - 1; c <= column + 1; c++) {
          for (const [edge, i] of this.cells.get(`${r},${c}`) ?? []) check(edge, i)
        }
      }
      if (!best || best.distance > CELL_METERS) {
        for (const edge of this.edges) for (let i = 1; i < edge.points.length; i++) check(edge, i)
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
   * from（直前にいた道）を渡すと、その道と、その道の両端でつながる道だけから選び、脇道へ勝手に移らない。
   * 橋の下をくぐる道のように、地図の上で重なるだけでつながっていない道へは移らない。
   */
  keepOnRoad(target: LatLon, headingDegrees: number, from?: RoadPosition): { at: LatLon; position: RoadPosition; pushed: boolean } {
    const among = from && new Set([from.edge, ...this.junctions.get(from.edge.from) ?? [], ...this.junctions.get(from.edge.to) ?? []])
    if (from && among) {
      // 幅の中に車がいる道。今の道の幅の中にいる間は、今の道のまま（脇道の入り口を通っても、そちらへ移らない）。
      // 今の道を出たら、車の向きにいちばん沿う道へ移る
      const inside = [...among].map((edge) => this.locate(target, headingDegrees, [edge]))
        .filter((found) => found.distance <= found.position.edge.halfWidth)
      const off = (found: { position: RoadPosition }) => Math.abs(turnAngle(headingDegrees, headingAt(found.position)))
      const chosen = inside.find((found) => found.position.edge === from.edge)
        ?? inside.sort((a, b) => off(a) - off(b))[0]
      if (chosen) return { at: target, position: chosen.position, pushed: false }
    }
    const { position, distance } = this.locate(target, headingDegrees, among)
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

/** 升目の大きさ。緯度 0.002 度は約 220m、経度 0.003 度は北海道で約 240m */
const CELL_METERS = 200
const cellRow = (lat: number) => Math.floor(lat / 0.002)
const cellColumn = (lon: number) => Math.floor(lon / 0.003)

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
