import { CalibrationView } from './calibrationView'
import { AERIAL_CREDIT, CompareMap, RouteLine, drawPreview, kindLabel, loadComparison, sceneryKm, sceneryStrip, type ComparedRoute, type Comparison, type RouteKind } from './compare'
import { distanceMeters, offset, type LatLon } from './geo'
import { Input } from './input'
import { LinkClient } from './link'
import { RoadNetwork, headingAt, pointAt, turnAngle, type RoadData, type RoadPosition } from './roads'
import { ROUTES, type DemoRoute } from './routes'
import { roadRecord } from './badges'
import { drawGuide } from './guide'
import { LOOK_AHEAD_METERS as PHOTO_LOOK_AHEAD, PanoramaFeed } from './panoramaFeed'
import { PanoramaRenderer, type PanoramaLayer, type View } from './panoramaRenderer'
import { StreetViewApi } from './streetviewApi'
import { nextSpeed, yawRate, type Controls } from './vehicle'

/** 写真を探す地点の間隔（panoramaFeed の SAMPLE_EVERY_METERS と同じ） */
const PHOTO_SAMPLE_METERS = 10
/** 横の視野角と、少し見下ろす角度 */
/** 画面を縦に3つに割り、正面と左右の窓を並べる。1つの窓の横の視野角（度） */
const FOV_DEGREES = 60
/** 左右の窓が向く角度（度）。窓の幅と同じにして、正面の窓と切れ目なくつなげる。外の端が真横になる */
const SIDE_DEGREES = FOV_DEGREES
/** 止まっているときにハンドルを切ると、車は動かさずに見る向きだけを変える。いっぱいに切ったときの角度（度） */
const LOOK_DEGREES = 90
const PITCH_DEGREES = -4

const params = new URLSearchParams(location.search)
const googleKey = (import.meta.env.VITE_GOOGLE_MAPS_API_KEY as string | undefined)?.trim() || undefined

const $ = <T extends HTMLElement>(selector: string) => document.querySelector<T>(selector)!
const hud = {
  root: $('#hud'),
  route: $('[data-route]'),
  kind: $('[data-kind]'),
  compare: $('[data-compare]'),
  scenery: $('[data-scenery]'),
  remaining: $('[data-remaining]'),
  phones: $('[data-phones]'),
  notice: $('[data-notice]'),
  usage: $('[data-usage]'),
  copyright: $('[data-copyright]'),
  report: $<HTMLAnchorElement>('[data-report]'),
  road: $('[data-road]'),
  junction: $('[data-junction]'),
  steer: $('[data-steer]'),
  brake: $('[data-brake]'),
  throttle: $('[data-throttle]'),
  speed: $('[data-speed]'),
}
const menu = $('#menu')

if (!googleKey) {
  $('[data-routes]').innerHTML = '<p class="warning">Google の API キーがありません。simulator/README.md の手順で .env.local を作ってから、開発サーバーを起動し直してください。</p>'
  throw new Error('Google の API キーがありません')
}

const api = await StreetViewApi.create(googleKey)
const renderer = new PanoramaRenderer($<HTMLCanvasElement>('#world canvas'))
const feed = new PanoramaFeed(api, renderer)
const guide = $<HTMLCanvasElement>('[data-guide]')
const input = new Input()
const link = new LinkClient()
const calibration = new CalibrationView(() => input.gamepad(), () => input.reloadMapping())
const networks = new Map<string, RoadNetwork>()
const comparisons = new Map<string, Comparison | undefined>()
const compareMap = new CompareMap($<HTMLCanvasElement>('[data-compare-map]'))

let route: DemoRoute | undefined
let network: RoadNetwork | undefined
/** 車のいる場所。道幅の中なら、道の真ん中の線から外れて自由に動ける */
let car: LatLon | undefined
/** 車にいちばん近い道の上の点。道の名前、分かれ道の案内、写真の先読みに使う */
let position: RoadPosition | undefined
/** 走り比べ：ふつうの道とおすすめの道のどちらを走っているか、その道の線、道のどこまで来たか */
let kind: RouteKind = 'scenic'
let lines: Record<RouteKind, RouteLine> | undefined
let along = 0
let offRoute = false
let arrived = false
let speed = 0
let heading = 0
let deadEnd = false
let running = false
let lastFrame: { view: View; a?: PanoramaLayer; b?: PanoramaLayer } | undefined

/**
 * 継ぎ目の測定（開発用）。今の位置で、仮の円筒の半径ごとに写真 A だけと B だけの違いを測る。
 * 半径を数字で比べて決めるために使った（panoramaRenderer.ts の PROXY_RADIUS）。
 */
;(window as unknown as Record<string, unknown>).__usage = () => ({ ...api.usage, ...feed.stats })
;(window as unknown as Record<string, unknown>).__feed = () => car ? feed.debug(car) : undefined
;(window as unknown as Record<string, unknown>).__seam = (settings: { radius: number }[]) => {
  if (!lastFrame?.a || !lastFrame.b) return undefined
  const saved = { radius: renderer.radius }
  const result = settings.map((setting) => {
    Object.assign(renderer, setting)
    return renderer.seam(lastFrame!.view, lastFrame!.a!, lastFrame!.b!)
  })
  Object.assign(renderer, saved)
  return result
}

/** 道を選ぶ画面で出している道（ROUTES の番号）。← → で切り替える */
let course = 0

/** 「万代町 → 函館駅（ともえ大橋）」を、出発地・目的地・通る所に分ける。 */
function splitName(name: string): { from: string; to: string; via?: string } {
  const match = name.match(/^(.+?) → (.+?)(?:（(.+)）)?$/)
  return match ? { from: match[1], to: match[2], via: match[3] } : { from: '', to: name }
}

/** 道を選ぶ画面。1つの目的地を大きく出し、空中写真の地図の上で2本の道を比べる。← → で目的地を変える。 */
async function renderMenu(): Promise<void> {
  await Promise.all(ROUTES.map(async (r) => comparisons.has(r.id) || comparisons.set(r.id, await loadComparison(r.id))))
  const r = ROUTES[course]
  const comparison = comparisons.get(r.id)
  const { from, to, via } = splitName(r.name)
  const option = (optionKind: RouteKind, compared: ComparedRoute, number: number) => {
    const parts = sceneryKm(compared).slice(0, 3).map((part) => `<span class="chip"><i style="background: ${part.color}"></i>${part.label} ${part.km.toFixed(1)}km</span>`).join('')
    const demo = r.demo?.[optionKind]
    const range = demo && demoRange(demo, compared.timeline.at(-1)?.[1] ?? compared.km * 1000)
    return `
      <button type="button" class="option ${optionKind}" data-route-id="${r.id}" data-kind="${optionKind}">
        <span class="key">${number}</span>
        <span class="option-name">${optionKind === 'scenic' ? '景色のいい道' : 'ふつうの道'}</span>
        <span class="option-figures"><b>${compared.minutes}</b>分<small>${compared.km.toFixed(1)}km</small></span>
        <span class="strip" style="background: ${sceneryStrip(compared)}">${range ? `<span class="demo-range" style="left: ${range.left}; width: ${range.width}"></span>` : ''}</span>
        <span class="chips">${parts || '<span class="chip"><i style="background: #8c8c8c"></i>街なか</span>'}</span>
      </button>`
  }
  $('[data-routes]').innerHTML = `
    ${r.photo ? `<div class="course-photo" style="background-image: url('${r.photo.url}')"></div>` : ''}
    <div class="course">
      <figure class="course-map">
        ${comparison ? `<canvas data-preview="${r.id}"></canvas>` : ''}
        <figcaption>${AERIAL_CREDIT}</figcaption>
      </figure>
      <div class="course-info">
        <div class="course-from">${from}から</div>
        <div class="course-title">
          <h1 class="course-to">${to}</h1>
          ${roadRecord(r.id, comparison?.scenic) ?? (via ? `<span class="course-via">${via}</span>` : '')}
        </div>
        <div class="course-options">
          ${comparison
            ? option('normal', comparison.normal, 1) + option('scenic', comparison.scenic, 2)
            : `<button type="button" class="option normal" data-route-id="${r.id}" data-kind="normal"><span class="key">1</span><span class="option-name">この道を走る</span></button>`}
        </div>
      </div>
    </div>
    <nav class="course-tabs">
      ${ROUTES.map((each, i) => `<button type="button" data-course="${i}"${i === course ? ' class="current"' : ''}>${splitName(each.name).to}</button>`).join('')}
      <span class="course-hint">← → 目的地　1 2 道を選ぶ</span>
    </nav>
    ${r.photo ? `<div class="course-credit">${r.photo.credit}</div>` : ''}`
  const canvas = document.querySelector<HTMLCanvasElement>('[data-preview]')
  const paintMap = (focus?: RouteKind) => {
    if (canvas && comparison) drawPreview(canvas, comparison, focus, [from, to])
  }
  paintMap()
  // 地図の地名の書体が読み込まれる前に描くと、別の書体になる
  void document.fonts.ready.then(() => paintMap())
  for (const button of document.querySelectorAll<HTMLButtonElement>('[data-route-id]')) {
    button.addEventListener('click', () => choose(button.dataset.routeId!, button.dataset.kind as RouteKind))
    // 選びかけている道を、地図の上で目立たせる
    for (const type of ['mouseenter', 'focus']) button.addEventListener(type, () => paintMap(button.dataset.kind as RouteKind))
    for (const type of ['mouseleave', 'blur']) button.addEventListener(type, () => paintMap())
  }
  for (const tab of document.querySelectorAll<HTMLButtonElement>('[data-course]')) {
    tab.addEventListener('click', () => showCourse(Number(tab.dataset.course)))
  }
  renderWheelStatus()
}

function showCourse(index: number): void {
  course = (index + ROUTES.length) % ROUTES.length
  void renderMenu()
}

/** 景色の帯の上で、デモで走る区間が占める位置。 */
function demoRange([from, to]: [number, number], length: number): { left: string; width: string } {
  return { left: `${(from / length * 100).toFixed(2)}%`, width: `${((to - from) / length * 100).toFixed(2)}%` }
}

function renderWheelStatus(): void {
  const pad = input.gamepad()
  // read を呼ばないと割り当てを読み込まないので、ここで1回読む
  input.read(0)
  $('[data-wheel]').textContent = !pad
    ? 'ハンコンが見えません。ハンコンのボタンを1回押すと出ます。キーボードの矢印キーでも走れます。'
    : input.hasMapping
      ? `ハンコン：${pad.id}（調整済み）`
      : `ハンコン：${pad.id}。まだ調整していません。C キーで調整してください。`
}

function choose(id: string, chosenKind: RouteKind): void {
  const chosen = ROUTES.find((r) => r.id === id)
  if (!chosen) return
  menu.hidden = true
  void spawn(chosen, chosenKind)
}

async function loadNetwork(id: string): Promise<RoadNetwork> {
  const cached = networks.get(id)
  if (cached) return cached
  const data = await (await fetch(`/roads/${id}.json`)).json() as RoadData
  const loaded = new RoadNetwork(data)
  networks.set(id, loaded)
  return loaded
}

async function spawn(next: DemoRoute, nextKind: RouteKind): Promise<void> {
  running = false
  route = next
  kind = nextKind
  hud.root.hidden = false
  hud.route.textContent = next.name
  network = await loadNetwork(next.id)
  if (!comparisons.has(next.id)) comparisons.set(next.id, await loadComparison(next.id))
  const comparison = comparisons.get(next.id)
  lines = comparison && { normal: new RouteLine(comparison.normal), scenic: new RouteLine(comparison.scenic) }
  renderCompare(comparison)
  // 走り比べでは、選んだ道の始まりから、その道の向きで出る
  const line = lines?.[kind]
  // ?at=12000&look=-50 で、道の始まりから 12km の地点に、道の向きから左へ 50 度向いて止まる（景色を1か所ずつ確かめる用）。
  // このときは先の写真を読まない
  const at = line && params.has('at') ? Number(params.get('at')) : undefined
  feed.prefetch = at === undefined ? feed.prefetch : 0
  // 道の始まりが駅前の広場など、道路のデータにない道のことがある。そのまま近い道へ乗せると、反対向きの車線に乗ることがある。
  // データにある道の上で、その道を走れる向きが選んだ道の向きと合う所まで進めて、そこから出る
  // デモで走る区間が決まっている道は、その始まりから出る
  const start = at ?? next.demo?.[kind][0] ?? 0
  let from = start
  if (line && at === undefined) {
    const fits = (meters: number) => {
      const snapped = network!.snap(line.pointAt(meters), line.headingAt(meters + 10))
      return distanceMeters(pointAt(snapped), line.pointAt(meters)) <= 8
        && Math.abs(turnAngle(headingAt(snapped), line.headingAt(meters + 10))) < 45
    }
    while (from < start + 600 && !fits(from)) from += 10
  }
  position = line
    ? network.snap(line.pointAt(from), line.headingAt(from + 10))
    : network.snap(next.start, next.headingDegrees)
  along = 0
  offRoute = false
  arrived = false
  speed = 0
  deadEnd = false
  car = pointAt(position)
  heading = (headingAt(position) + (at === undefined ? 0 : Number(params.get('look') ?? 0)) + 360) % 360
  feed.reset()
  // スマホの旅れこに、この道の目的地で案内を始めさせる（デモ中にスマホで検索して打たなくてよいように）
  void link.start(car, heading, next.destination)
  running = true
}

/** 走り比べの枠（小さな地図と、2本の道の景色の帯）を、選んだ道に合わせて描く。 */
function renderCompare(comparison: Comparison | undefined): void {
  hud.compare.hidden = !comparison || !lines
  hud.kind.textContent = comparison ? kindLabel(kind, comparison[kind]) : ''
  hud.kind.className = `kind-badge ${kind}`
  if (!comparison || !lines) return
  for (const each of ['normal', 'scenic'] as const) {
    const row = $(`[data-compare-row="${each}"]`)
    row.classList.toggle('active', each === kind)
    row.querySelector('.figures')!.textContent = `${each === 'scenic' ? 'おすすめ' : 'ふつう'}　${comparison[each].km.toFixed(1)}km・約${comparison[each].minutes}分`
    row.querySelector<HTMLElement>('.strip')!.style.background = sceneryStrip(comparison[each])
    const range = row.querySelector<HTMLElement>('.demo-range')!
    const demo = route?.demo?.[each]
    range.hidden = !demo
    if (demo) Object.assign(range.style, demoRange(demo, lines[each].length))
  }
  compareMap.draw(lines, kind)
}

/** 見る向きの、車の向きからのずれ（度） */
let look = 0

function drive(dt: number, now: number): void {
  if (!network || !car) return
  const controls = input.read(dt)
  speed = nextSpeed(speed, controls, dt)
  // ハンドルで車の向きが変わり、向いている方へ進む
  heading = (heading + yawRate(speed, controls.steer) * dt + 360) % 360
  const radians = heading * Math.PI / 180
  const kept = network.keepOnRoad(offset(car, Math.cos(radians) * speed * dt, Math.sin(radians) * speed * dt), heading, position)
  car = kept.at
  position = kept.position
  if (kept.pushed) {
    // 道の端に当たった。端に沿って滑らせ、向きを道なりへ戻す。端へ突っ込む角度が深いほど速さを落とす
    const along = turnAngle(heading, headingAt(position))
    speed = Math.max(0, speed - speed * Math.abs(Math.sin(along * Math.PI / 180)) * 4 * dt)
    heading = (heading + along * Math.min(1, dt * 2) + 360) % 360
  }
  deadEnd = kept.pushed && network.advance(position, 1, 0).stopped

  const here = car
  const line = lines?.[kind]
  if (line) {
    const at = line.locate(car)
    along = at.along
    offRoute = at.distance > 40
    // デモで走る区間の終わりも、到着として扱う
    arrived = line.length - along < 40 || (!params.has('at') && along >= (route?.demo?.[kind][1] ?? Infinity))
    compareMap.update(car)
  }
  // 道なりの先の地点。この辺りの写真を先に読んでおく
  const path = [here]
  for (let meters = PHOTO_SAMPLE_METERS; meters <= PHOTO_LOOK_AHEAD; meters += PHOTO_SAMPLE_METERS) {
    const next = network.advance(position, meters, controls.steer)
    path.push(pointAt(next.position))
    if (next.stopped) break
  }
  // 止まってアクセルを離している間は、ハンドルで辺りを見回せる。走り出すと正面へ戻る
  const lookTarget = speed < 0.5 && controls.throttle === 0 ? controls.steer * LOOK_DEGREES : 0
  look += (lookTarget - look) * Math.min(1, dt * 6)
  const { a, b, mixB } = feed.update(here, heading + look, path, now, position.edge.bridge)
  const view = { heading: heading + look, pitch: PITCH_DEGREES, fov: FOV_DEGREES }
  for (const index of [0, 1, 2]) {
    renderer.render({ ...view, heading: view.heading + (index - 1) * SIDE_DEGREES }, a, b, mixB, { index, count: 3 })
  }
  if (line) drawGuide(guide, line, along, here, view, SIDE_DEGREES, 3, kind === 'scenic' ? 'rgba(62, 207, 122, 0.75)' : 'rgba(240, 199, 94, 0.75)')
  lastFrame = { view, a, b }
  link.update(now, here, heading, speed)
  renderHud(controls, here)
}

function renderHud(controls: Controls, here: { lat: number; lon: number }): void {
  if (!route || !network || !position) return
  hud.speed.textContent = Math.round(speed * 3.6).toString()
  // G29 はハンドルが 900 度（左右に 450 度ずつ）回る
  hud.steer.style.transform = `rotate(${controls.steer * 450}deg)`
  hud.throttle.style.height = `${controls.throttle * 100}%`
  hud.brake.style.height = `${controls.brake * 100}%`
  const line = lines?.[kind]
  hud.remaining.textContent = line
    ? `目的地まで ${((line.length - along) / 1000).toFixed(1)} km`
    : `目的地まで直線で ${(distanceMeters(here, route.destination.position) / 1000).toFixed(1)} km`
  hud.road.textContent = position.edge.name || '名前のない道'

  const junction = network.nextJunction(position, controls.steer)
  // 走り比べでは、次の分かれ道で、選んだ道がどちらへ曲がるかを知らせる
  const turn = line && junction
    ? turnAngle(line.headingAt(along + junction.distance - 20), line.headingAt(along + junction.distance + 25))
    : undefined
  hud.junction.textContent = arrived
    ? `${line && line.length - along >= 40 ? '見どころはここまでです' : '目的地に着きました'}。T キーで、もう一方の道を走り比べられます`
    : deadEnd
      ? '行き止まりです。U キー（または U ターンのボタン）で向きを変えます'
      : line && offRoute
        ? '選んだ道から外れています。左下の地図の線へ戻ってください'
        : junction
          ? `${Math.round(junction.distance)}m 先の分かれ道${turn === undefined ? '' : `：${direction(turn)}`}`
          : 'しばらく分かれ道はありません'
  if (line) {
    const now = line.sceneryAt(along)
    hud.scenery.innerHTML = now ? `いまの景色：<span class="dot" style="background: ${now.color}"></span>${now.label}` : ''
    $<HTMLElement>(`[data-compare-row="${kind}"] .marker`).style.left = `${(along / line.length * 100).toFixed(2)}%`
  }

  hud.phones.innerHTML = link.phones === undefined
    ? '<span class="ng">中継に届きません（npm run dev で開いていますか）</span>'
    : link.phones > 0
      ? `<span class="ok">スマホ ${link.phones}台に位置を送っています</span>`
      : '<span class="ng">スマホがつながっていません</span>'
  hud.notice.textContent = api.limited ? 'Google の今日の上限に達しました（17時ごろ戻ります）' : !feed.ready ? '景色を読み込んでいます…' : feed.gap ? 'この辺りは写真がありません' : ''
  // Street View のタイルは月10万枚まで無料、その先は1,000枚で約2ドル（2026-11 の料金表）
  hud.usage.textContent = `読み込んだ写真のタイル ${api.usage.tiles.toLocaleString()} 枚`
  const credit = feed.attribution
  hud.copyright.textContent = credit?.copyright ?? ''
  hud.report.hidden = !credit?.reportProblemLink
  if (credit?.reportProblemLink) hud.report.href = credit.reportProblemLink
}

function direction(turn: number): string {
  if (turn < -30) return '← 左へ'
  if (turn > 30) return '右へ →'
  return '↑ まっすぐ'
}

/** 見どころの景色。「川」は、ストリートビューと見比べると道から見えない所が多かったので入れない */
const HIGHLIGHTS = ['sea', 'lake', 'forest', 'valley', 'open_nature', 'farmland']

/** 走り比べ：今いる所より先の、次の見どころ（300m 以上続く景色）の始まりへ移る。長い道を全部走らずに見せるため。 */
function jumpToHighlight(): void {
  const line = lines?.[kind]
  if (!line || !network) return
  const next = line.route.timeline.find(([start, end, category]) =>
    start > along + 50 && end - start >= 300 && HIGHLIGHTS.includes(category))
  if (!next) return
  position = network.snap(line.pointAt(next[0]), line.headingAt(next[0] + 10))
  car = pointAt(position)
  heading = headingAt(position)
  speed = 0
  deadEnd = false
  feed.reset()
}

/** その場で反対を向く。道幅の中では切り返しが難しいので残している。 */
function uturn(): void {
  if (!car || speed > 2) return
  deadEnd = false
  heading = (heading + 180) % 360
}

input.onUturn = uturn
input.onReset = () => {
  if (route) void spawn(route, kind)
}

window.addEventListener('keydown', (event) => {
  if (calibration.isOpen) {
    if (event.code === 'Enter') calibration.next()
    return
  }
  if (event.code === 'KeyM') {
    menu.hidden = !menu.hidden
    if (!menu.hidden) showCourse(route ? ROUTES.indexOf(route) : course)
  } else if (!menu.hidden && (event.code === 'ArrowLeft' || event.code === 'ArrowRight')) {
    showCourse(course + (event.code === 'ArrowRight' ? 1 : -1))
  } else if (event.code === 'KeyC') {
    calibration.open()
  } else if (event.code === 'KeyU') {
    uturn()
  } else if (event.code === 'Backspace' && route) {
    void spawn(route, kind)
  } else if (event.code === 'KeyJ' && menu.hidden) {
    jumpToHighlight()
  } else if (event.code === 'KeyT' && route && menu.hidden && lines) {
    // 同じ出発点から、もう一方の道を走る
    void spawn(route, kind === 'scenic' ? 'normal' : 'scenic')
  } else if (!menu.hidden && /^Digit[1-9]$/.test(event.code)) {
    document.querySelectorAll<HTMLButtonElement>('[data-route-id]')[Number(event.code.slice(5)) - 1]?.click()
  }
})
window.addEventListener('gamepadconnected', () => {
  // 初めてつないだハンコンは、その場で調整画面を開く。G29 は Mac と Windows で軸の番号と向きが変わるので決め打ちにしない
  input.read(0)
  if (!input.hasMapping && !calibration.isOpen) calibration.open()
  else if (!menu.hidden) renderWheelStatus()
})
window.addEventListener('pagehide', () => link.stop())

// ストリートビューを実際に何枚読んだかを、開発サーバーのファイル（logs/streetview-usage.jsonl）に残す。
// 前に送ってから増えた分だけを、30 秒おきと、画面を閉じるときに送る
let logged = { ...api.usage }
function logUsage(): void {
  const now = { ...api.usage }
  const added = Object.fromEntries(Object.entries(now).map(([name, value]) => [name, value - logged[name as keyof typeof now]]))
  if (Object.values(added).every((value) => value === 0)) return
  logged = now
  navigator.sendBeacon('/usage-log', JSON.stringify({ route: route?.id, kind, ...added }))
}
setInterval(logUsage, 30_000)
window.addEventListener('pagehide', logUsage)

void renderMenu()
// ?route=onuma-lake&kind=normal のように道を指定すると、選ぶ画面を飛ばして走り出す（kind を省くとおすすめの道）
const initialRoute = params.get('route')
if (initialRoute) choose(initialRoute, params.get('kind') === 'normal' ? 'normal' : 'scenic')

let last = performance.now()
function frame(now: number): void {
  const dt = Math.min(0.1, (now - last) / 1000)
  last = now
  if (running && menu.hidden && !calibration.isOpen) drive(dt, now)
  requestAnimationFrame(frame)
}
requestAnimationFrame(frame)
