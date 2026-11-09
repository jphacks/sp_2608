import { bearingDegrees, distanceMeters, type LatLon } from './geo'
import { TEXTURE_HEIGHT, TEXTURE_WIDTH, TILE_SIZE, type PanoramaLayer, type PanoramaRenderer } from './panoramaRenderer'
import type { PanoMeta, StreetViewApi } from './streetviewApi'

/** 写真のタイルの細かさ。3 で新しい写真の全体が 4096×2048（横 8・縦 4 枚） */
export const ZOOM = 3
/** いちばん細かい zoom。写真全体の大きさは、ここから1段粗くなるごとに半分になる */
const MAX_ZOOM = 5
/** 進む向きの左右これだけを読む。視野（75 度）＋ハンドルで見る向き（10 度）＋余裕 */
const VIEW_HALF_DEGREES = 55
/** 写真を探す地点の間隔と、先読みする距離 */
const SAMPLE_EVERY_METERS = 10
export const LOOK_AHEAD_METERS = 160
/** 道の上の地点から、この半径の写真を探す */
const SEARCH_RADIUS_METERS = 15
/** 写真を使う最短の間隔。ストリートビューは約 10m おきなので、1枚おきくらいになる（費用を半分にする） */
const MIN_SPACING_METERS = 15
/** 今の写真からこれ以上離れたら（交差点を曲がった、出発点へ戻った）、近い写真から選び直す */
const RESELECT_METERS = 45
/** 今の写真の先に、これだけの写真を先読みする（15〜20m おきなので約 70m、時速 60km で約 4 秒分） */
const PREFETCH = 4
/** 同時に持つ写真のテクスチャ（今の写真と先読みの分と、曲がったときの予備） */
const SLOTS = 1 + PREFETCH + 2
/** 写真の ID をまとめて聞く間隔。160m 先まで先読みするので、時速 60km（3 秒で 50m）でも間に合う */
const ASK_EVERY_MS = 3_000
/** 先読みした写真が、今の道なりの地点からこれ以上離れていたら外す（交差点で曲がったとき） */
const OFF_PATH_METERS = 20
/**
 * 撮った向きと道の向きの差がこれより大きい写真は使わない（反対向きに撮ったものは使う）。
 * 交差点では横道で撮った写真が一番近いことがあり、それを使うと景色が横を向いてしまう
 */
const ALIGN_DEGREES = 40
/** タイルを同時に取りに行く数 */
const PARALLEL_TILES = 16

interface Pano extends PanoMeta {
  texture?: WebGLTexture
  /** ZOOM での写真全体の大きさを、タイルの数で表したもの（古い写真は端数がある） */
  columns: number
  rows: number
  /** 水平線の上下 45 度を写す段。空の上と足もとは使わない */
  forwardRows: number[]
  /** テクスチャのうち写真が入っている割合 */
  scale: [number, number]
  /** 取りに行ったタイル、取り終えたタイル、無かったタイル。列 * 10 + 段 */
  requested: Set<number>
  loaded: Set<number>
  failed: Set<number>
  /** 1枚でも描けるタイルがある */
  visible: boolean
  /** その写真の辺りの道の向き。先読みでは、今の車の向きではなくこの向きの分を読む（カーブの先でずれないように） */
  roadHeading?: number
}

/** 写真の真ん中が heading を向いているとき、view を中心に左右 half 度を写すタイルの列。 */
export function tileColumns(panoHeading: number, view: number, half = VIEW_HALF_DEGREES, columns = 8): number[] {
  const width = 360 / columns
  const result = new Set<number>()
  const add = (angle: number) => {
    const u = (((view + angle - panoHeading) / 360 + 0.5) % 1 + 1) % 1
    result.add(Math.min(Math.ceil(columns) - 1, Math.floor(u * columns)))
  }
  // 列の幅の半分おきに調べれば、どの列も取りこぼさない。右端は刻みに乗らないことがあるので別に足す
  for (let angle = -half; angle < half; angle += width / 2) add(angle)
  add(half)
  return [...result].sort((x, y) => x - y)
}

/**
 * 写真 A から B へ進む割合（0〜1）から、B を重ねる強さ。途中の 20〜80% で、ゆっくり始まりゆっくり終わる形で入れ替える。
 * 入れ替えの始まりと終わりが急だと、そこが継ぎ目として目に付く。
 */
export function blend(a: LatLon, b: LatLon, car: LatLon): number {
  const scale = Math.cos(a.lat * Math.PI / 180)
  const abx = (b.lon - a.lon) * scale
  const aby = b.lat - a.lat
  const acx = (car.lon - a.lon) * scale
  const acy = car.lat - a.lat
  const length = abx * abx + aby * aby
  if (length === 0) return 1
  const progress = (acx * abx + acy * aby) / length
  const t = Math.max(0, Math.min(1, (progress - 0.2) / 0.6))
  return t * t * (3 - 2 * t)
}

/** 地点 p が、car から heading の向きに何メートル前にあるか（後ろなら負）。 */
export function aheadMeters(car: LatLon, heading: number, p: LatLon): number {
  const north = distanceMeters(car, { lat: p.lat, lon: car.lon }) * Math.sign(p.lat - car.lat)
  const east = distanceMeters(car, { lat: car.lat, lon: p.lon }) * Math.sign(p.lon - car.lon)
  const radians = heading * Math.PI / 180
  return north * Math.cos(radians) + east * Math.sin(radians)
}

/**
 * 走る先の写真を順に用意する。道の上の少し先までの地点で写真の ID を探し、
 * 今の写真 A と次の写真 B を決め、進む向きの分だけタイルを読み込む。
 */
export class PanoramaFeed {
  /** 地点ごとの写真の ID（null は写真がない） */
  private readonly ids = new Map<string, string | null>()
  private readonly asking = new Set<string>()
  private readonly panos = new Map<string, Pano | 'loading'>()
  private readonly free: WebGLTexture[] = []
  /** 読み込み待ちのタイル。priority が小さい（車に近い写真の）ものから取りに行く */
  private readonly tileQueue: { priority: number; job: () => Promise<void> }[] = []
  private tilesInFlight = 0
  private a: Pano | undefined
  /** 先読みしている写真。道なりの近い順。先頭が次の写真 B */
  private next: Pano[] = []
  private lastAsk = -Infinity
  /** 今いる辺りに写真が見つからない */
  gap = false
  /** 開発用の数え上げ（写真を選び直した回数、全方向を読んだ回数、入れ替えた回数、テクスチャを取り上げた回数） */
  readonly stats = { reselect: 0, around: 0, advance: 0, evict: 0 }

  constructor(private readonly api: StreetViewApi, private readonly renderer: PanoramaRenderer) {
    for (let i = 0; i < SLOTS; i++) this.free.push(renderer.createTexture())
  }

  /** 出発点へ戻ったとき。前の道の写真を引きずらない。 */
  reset(): void {
    this.a = undefined
    this.next = []
    // 出発点の写真の ID は、待たずにすぐ聞く
    this.lastAsk = -Infinity
  }

  /** 今の写真が描ける状態か（出発直後の読み込み中の表示に使う）。 */
  get ready(): boolean {
    return this.a?.visible === true
  }

  get attribution(): { copyright: string; reportProblemLink: string } | undefined {
    return this.a ? { copyright: this.a.copyright, reportProblemLink: this.a.reportProblemLink } : undefined
  }

  /**
   * 毎フレーム呼ぶ。path は車の位置から道なりに先へ並べた地点（SAMPLE_EVERY_METERS おき）。
   * 描く2枚の写真と、B を重ねる強さを返す。
   */
  update(car: LatLon, heading: number, path: LatLon[], now: number): { a?: PanoramaLayer; b?: PanoramaLayer; mixB: number } {
    // ID を聞く回数も数えられるので、毎フレームではなくまとめて聞く
    if (now - this.lastAsk >= ASK_EVERY_MS) {
      this.lastAsk = now
      this.askIds(path)
    }
    const sequence = this.sequence(path)
    this.gap = sequence.length === 0 && path.every((p) => this.ids.get(key(p)) === null)
    const ahead = (p: Pano) => aheadMeters(car, heading, p.at)
    // 交差点で曲がって、先読みしていた写真が今の道から外れたか
    const onPath = (p: Pano) => path.some((q) => distanceMeters(q, p.at) < OFF_PATH_METERS)
    const after = (from: Pano, skip: (Pano | undefined)[]) => sequence.find((p) => !skip.includes(p) &&
      ahead(p) > ahead(from) && distanceMeters(from.at, p.at) >= MIN_SPACING_METERS)

    // 次の写真を読み終えていないうちは入れ替えない。読みかけで替えると、読めた所と読めていない所の境目が出る
    // 次の写真を読み終えていないうちは入れ替えない。読みかけで替えると、読めた所と読めていない所の境目が出る
    const b = this.next[0]
    if (b && ahead(b) < 0 && this.loadedFor(b, b.roadHeading ?? heading)) {
      this.stats.advance += 1
      this.a = b
      this.next.shift()
    }
    if (!this.a || distanceMeters(car, this.a.at) > RESELECT_METERS) {
      this.stats.reselect += 1
      // 車より後ろ（または真横）の写真を選ぶ。前の写真だと、車とその写真の間の路面が写真の後ろ側になり、
      // 前方しか読まないタイルでは欠ける
      const behind = sequence.filter((p) => ahead(p) <= 2)
      this.a = nearest(behind.length > 0 ? behind : sequence, car)
      this.next = []
    }
    // 一度決めた先読みは、道から外れない限り替えない。替えるたびに読み込みし直しになる
    const off = this.next.findIndex((p) => !onPath(p))
    if (off >= 0) this.next = this.next.slice(0, off)
    while (this.a && this.next.length < PREFETCH) {
      const following = after(this.next.at(-1) ?? this.a, [this.a, ...this.next])
      if (!following) break
      this.next.push(following)
    }

    const order = [this.a, ...this.next].filter((p): p is Pano => p !== undefined)
    const keep = new Set(order)
    order.forEach((pano, priority) => {
      // 今の写真が車より前にあるときだけ、全方向（足もとまで）を読む。車と写真の間の路面が写真の後ろ側になるため
      const around = pano === this.a && aheadMeters(car, heading, pano.at) > 2
      if (around && pano.requested.size < 20) this.stats.around += 1
      this.loadTiles(pano, pano === this.a ? heading : pano.roadHeading ?? heading, keep, around, priority)
    })

    const next = this.next[0]
    return {
      a: this.a && layer(this.a, car),
      b: next && layer(next, car),
      mixB: this.a && next && this.loadedFor(next, next.roadHeading ?? heading) ? blend(this.a.at, next.at, car) : 0,
    }
  }

  /** 開発用。今の写真と先読みの様子 */
  debug(car: LatLon): object {
    const describe = (p: Pano) => ({ id: p.id.slice(0, 6), m: Math.round(distanceMeters(car, p.at)), tex: !!p.texture, req: p.requested.size, ok: p.loaded.size })
    return { a: this.a && describe(this.a), next: this.next.map(describe), queue: this.tileQueue.length, inFlight: this.tilesInFlight }
  }

  /** 今の向きで見る分のタイルを、全部読み終えたか。 */
  private loadedFor(pano: Pano, heading: number): boolean {
    const done = (tile: number) => pano.loaded.has(tile) || pano.failed.has(tile)
    return tileColumns(pano.heading, heading, VIEW_HALF_DEGREES, pano.columns)
      .every((column) => pano.forwardRows.every((row) => done(column * 10 + row)))
  }

  /** まだ聞いていない地点の写真の ID を、まとめて1回で聞く。 */
  private askIds(path: LatLon[]): void {
    const unknown = path.filter((p) => !this.ids.has(key(p)) && !this.asking.has(key(p))).slice(0, 100)
    if (unknown.length === 0) return
    for (const p of unknown) this.asking.add(key(p))
    this.api.panoIds(unknown, SEARCH_RADIUS_METERS)
      .then((ids) => unknown.forEach((p, i) => this.ids.set(key(p), ids[i] ?? null)))
      .catch(() => { /* 次のフレームで聞き直す */ })
      .finally(() => unknown.forEach((p) => this.asking.delete(key(p))))
  }

  /** 道なりの順に並べた写真（情報を取り終え、道の向きに沿って撮ったものだけ）。情報がまだなら取りに行く。 */
  private sequence(path: LatLon[]): Pano[] {
    const result: Pano[] = []
    for (const [i, p] of path.entries()) {
      const id = this.ids.get(key(p))
      if (!id) continue
      const pano = this.panos.get(id)
      if (pano === undefined) {
        this.panos.set(id, 'loading')
        this.api.metadata(id)
          .then((meta) => this.panos.set(id, { ...meta, ...grid(meta), requested: new Set(), loaded: new Set(), failed: new Set(), visible: false }))
          .catch(() => this.panos.delete(id))
        continue
      }
      if (pano === 'loading' || result.includes(pano)) continue
      const along = bearingDegrees(path[Math.max(0, i - 1)], path[Math.min(path.length - 1, Math.max(1, i))])
      if (!aligned(pano.heading, along)) continue
      pano.roadHeading = along
      result.push(pano)
    }
    return result
  }

  private loadTiles(pano: Pano, heading: number, keep: Set<Pano>, around: boolean, priority: number): void {
    if (!pano.texture) {
      pano.texture = this.takeTexture(keep)
      if (!pano.texture) return
    }
    const texture = pano.texture
    const columns = around ? [...Array(Math.ceil(pano.columns)).keys()] : tileColumns(pano.heading, heading, VIEW_HALF_DEGREES, pano.columns)
    for (const column of columns) {
      for (const row of around ? [...Array(pano.rows).keys()] : pano.forwardRows) {
        const tile = column * 10 + row
        if (pano.requested.has(tile) || pano.failed.has(tile)) continue
        pano.requested.add(tile)
        this.tileQueue.push({ priority, job: async () => {
          // 読み込みを待つ間に、テクスチャを別の写真に回していたら書き込まない
          if (pano.texture !== texture) return
          try {
            const image = await this.api.tile(pano.id, ZOOM, column, row)
            if (pano.texture !== texture) return
            this.renderer.putTile(texture, column, row, image)
            pano.loaded.add(tile)
            pano.visible = true
          } catch {
            // 取れなかったタイルは取り直さない（無いタイルを取り続けて回数だけ増えるのを防ぐ）
            pano.failed.add(tile)
          }
        } })
      }
    }
    this.pump()
  }

  /** 空いているテクスチャ。なければ、今使っていない写真から取り上げる。 */
  private takeTexture(keep: Set<Pano>): WebGLTexture | undefined {
    const free = this.free.pop()
    if (free) return free
    for (const pano of this.panos.values()) {
      if (pano === 'loading' || !pano.texture || keep.has(pano)) continue
      const texture = pano.texture
      this.stats.evict += 1
      pano.texture = undefined
      pano.requested.clear()
      pano.loaded.clear()
      pano.failed.clear()
      pano.visible = false
      this.renderer.clearTexture(texture)
      return texture
    }
    return undefined
  }

  private pump(): void {
    while (this.tilesInFlight < PARALLEL_TILES && this.tileQueue.length > 0) {
      let first = 0
      for (let i = 1; i < this.tileQueue.length; i++) {
        if (this.tileQueue[i].priority < this.tileQueue[first].priority) first = i
      }
      const { job } = this.tileQueue.splice(first, 1)[0]
      this.tilesInFlight += 1
      void job().finally(() => {
        this.tilesInFlight -= 1
        this.pump()
      })
    }
  }
}

/**
 * ZOOM での写真のタイルの並び。新しい写真は 16384×8192 で横 8・縦 4 枚。
 * 古い写真（2011 年ごろ）は 13312×6656 などと小さく、横 6.5・縦 3.25 枚になる。
 */
export function grid(meta: { imageWidth: number; imageHeight: number }):
  { columns: number; rows: number; forwardRows: number[]; scale: [number, number] } {
  const shrink = 2 ** (Math.max(MAX_ZOOM, Math.ceil(Math.log2(meta.imageWidth / TILE_SIZE))) - ZOOM)
  const width = meta.imageWidth / shrink
  const height = meta.imageHeight / shrink
  const top = Math.floor(height * 0.25 / TILE_SIZE)
  const bottom = Math.floor((height * 0.75 - 1) / TILE_SIZE)
  return {
    columns: width / TILE_SIZE,
    rows: Math.ceil(height / TILE_SIZE),
    forwardRows: Array.from({ length: bottom - top + 1 }, (_, i) => top + i),
    scale: [width / TEXTURE_WIDTH, height / TEXTURE_HEIGHT],
  }
}

/** 写真を撮った向きが、道の向き（同じ向きか反対向き）に沿っているか。 */
export function aligned(panoHeading: number, roadHeading: number): boolean {
  const difference = Math.abs(((panoHeading - roadHeading + 540) % 360) - 180)
  return difference < ALIGN_DEGREES || difference > 180 - ALIGN_DEGREES
}

/** 地点を約 10m の升目にまとめる。同じ升目では写真の ID を聞き直さない */
function key(p: LatLon): string {
  return `${p.lat.toFixed(4)},${p.lon.toFixed(4)}`
}

function nearest(panos: Pano[], car: LatLon): Pano | undefined {
  let best: Pano | undefined
  for (const pano of panos) {
    if (!best || distanceMeters(car, pano.at) < distanceMeters(car, best.at)) best = pano
  }
  return best
}

function layer(pano: Pano, car: LatLon): PanoramaLayer | undefined {
  if (!pano.texture) return undefined
  const north = distanceMeters(pano.at, { lat: car.lat, lon: pano.at.lon }) * Math.sign(car.lat - pano.at.lat)
  const east = distanceMeters(pano.at, { lat: pano.at.lat, lon: car.lon }) * Math.sign(car.lon - pano.at.lon)
  return { texture: pano.texture, offsetEast: east, offsetNorth: north, heading: pano.heading, scale: pano.scale }
}
