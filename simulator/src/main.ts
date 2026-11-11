import { CalibrationView } from './calibrationView'
import { RouteLine, loadComparison, type RouteKind } from './compare'
import { CompareHud } from './compareHud'
import { installDebugHooks, type Frame } from './debugHooks'
import { distanceMeters, offset, type LatLon } from './geo'
import { drawGuide } from './guide'
import { Input } from './input'
import { LinkClient } from './link'
import { Menu } from './menu'
import { LOOK_AHEAD_METERS as PHOTO_LOOK_AHEAD, PanoramaFeed } from './panoramaFeed'
import { PanoramaRenderer } from './panoramaRenderer'
import { RoadNetwork, headingAt, pointAt, turnAngle, type RoadData, type RoadPosition } from './roads'
import { ROUTES, type DemoRoute } from './routes'
import { StreetViewApi } from './streetviewApi'
import { startUsageLog } from './usageLog'
import { nextSpeed, yawRate, type Controls } from './vehicle'

/** 写真を探す地点の間隔（panoramaFeed の SAMPLE_EVERY_METERS と同じ） */
const PHOTO_SAMPLE_METERS = 10
/** 正面と左右の3つの窓の、1つの横の視野角。左右の窓はこの角度だけ横を向く */
const FOV_DEGREES = 60
/** 止まってハンドルを切ったときに見回せる角度 */
const LOOK_DEGREES = 90
const PITCH_DEGREES = -4
/** 見どころとして飛ぶ景色。川は道から見えない所が多いので入れない */
const HIGHLIGHTS = ['sea', 'lake', 'forest', 'valley', 'open_nature', 'farmland']

const params = new URLSearchParams(location.search)
const googleKey = (import.meta.env.VITE_GOOGLE_MAPS_API_KEY as string | undefined)?.trim() || undefined

const $ = <T extends HTMLElement>(selector: string) => document.querySelector<T>(selector)!
const hud = {
  root: $('#hud'),
  route: $('[data-route]'),
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
const compareHud = new CompareHud($('[data-compare]'), $('[data-kind]'), $('[data-scenery]'))
const menu = new Menu($('#menu'), $('[data-routes]'), $('[data-wheel]'), (route, kind) => {
  menu.close()
  void spawn(route, kind)
}, wheelStatus)
const networks = new Map<string, RoadNetwork>()

let route: DemoRoute | undefined
let network: RoadNetwork | undefined
/** 車のいる場所。道幅の中なら、道の真ん中の線から外れて動ける */
let car: LatLon | undefined
/** 車にいちばん近い道の上の点 */
let position: RoadPosition | undefined
let kind: RouteKind = 'scenic'
let lines: Record<RouteKind, RouteLine> | undefined
/** 走っている道の、始まりからの道のり */
let along = 0
let offRoute = false
let arrived = false
let speed = 0
let heading = 0
/** 見る向きの、車の向きからのずれ */
let look = 0
let deadEnd = false
let running = false
let lastFrame: Frame | undefined

installDebugHooks(api, feed, renderer, () => car, () => lastFrame)
startUsageLog(api.usage, () => ({ route: route?.id, kind }))

function wheelStatus(): string {
  const pad = input.gamepad()
  // read を呼ばないと割り当てを読み込まない
  input.read(0)
  return !pad ? 'hancon: none' : input.hasMapping ? 'hancon: connected' : 'hancon: not calibrated'
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
  const comparison = await loadComparison(next.id)
  lines = comparison && { normal: new RouteLine(comparison.normal), scenic: new RouteLine(comparison.scenic) }
  compareHud.show(next, comparison, lines, kind)
  const line = lines?.[kind]
  // ?at=12000&look=-50 で、その地点に止まって景色を確かめる。先の写真は読まない
  const at = line && params.has('at') ? Number(params.get('at')) : undefined
  feed.prefetch = at === undefined ? feed.prefetch : 0
  const start = at ?? next.demo?.[kind][0] ?? 0
  let from = start
  if (line && at === undefined) {
    // 道路データに無い駅前などから出ると反対の車線に乗るので、道の向きと合う所まで進めて出る
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
  // スマホの旅れこに、この道の目的地で案内を始めさせる
  void link.start(car, heading, next.destination)
  running = true
}

function drive(dt: number, now: number): void {
  if (!network || !car) return
  const controls = input.read(dt)
  speed = nextSpeed(speed, controls, dt)
  heading = (heading + yawRate(speed, controls.steer) * dt + 360) % 360
  const radians = heading * Math.PI / 180
  const kept = network.keepOnRoad(offset(car, Math.cos(radians) * speed * dt, Math.sin(radians) * speed * dt), heading, position)
  car = kept.at
  position = kept.position
  if (kept.pushed) {
    // 道の端に当たったら、端に沿って滑らせ、深く突っ込むほど速さを落とす
    const along = turnAngle(heading, headingAt(position))
    speed = Math.max(0, speed - speed * Math.abs(Math.sin(along * Math.PI / 180)) * 4 * dt)
    heading = (heading + along * Math.min(1, dt * 2) + 360) % 360
  }
  deadEnd = kept.pushed && network.advance(position, 1, 0).stopped

  const line = lines?.[kind]
  if (line) {
    const at = line.locate(car)
    along = at.along
    offRoute = at.distance > 40
    // デモで走る区間の終わりも、着いたことにする
    arrived = line.length - along < 40 || (!params.has('at') && along >= (route?.demo?.[kind][1] ?? Infinity))
    compareHud.update(car, line, along, kind)
  }
  // 道なりの先の写真を先に読む
  const path = [car]
  for (let meters = PHOTO_SAMPLE_METERS; meters <= PHOTO_LOOK_AHEAD; meters += PHOTO_SAMPLE_METERS) {
    const next = network.advance(position, meters, controls.steer)
    path.push(pointAt(next.position))
    if (next.stopped) break
  }
  // 止まってアクセルを離している間は、ハンドルで見回せる
  const lookTarget = speed < 0.5 && controls.throttle === 0 ? controls.steer * LOOK_DEGREES : 0
  look += (lookTarget - look) * Math.min(1, dt * 6)
  const { a, b, mixB } = feed.update(car, heading + look, path, now, position.edge.bridge)
  const view = { heading: heading + look, pitch: PITCH_DEGREES, fov: FOV_DEGREES }
  for (const index of [0, 1, 2]) {
    renderer.render({ ...view, heading: view.heading + (index - 1) * FOV_DEGREES }, a, b, mixB, { index, count: 3 })
  }
  if (line) drawGuide(guide, line, along, car, view, FOV_DEGREES, 3, kind === 'scenic' ? 'rgba(62, 207, 122, 0.75)' : 'rgba(240, 199, 94, 0.75)')
  lastFrame = { view, a, b }
  link.update(now, car, heading, speed)
  renderHud(controls, car)
}

function renderHud(controls: Controls, here: LatLon): void {
  if (!route || !network || !position) return
  hud.speed.textContent = Math.round(speed * 3.6).toString()
  // G29 はハンドルが左右に 450 度ずつ回る
  hud.steer.style.transform = `rotate(${controls.steer * 450}deg)`
  hud.throttle.style.height = `${controls.throttle * 100}%`
  hud.brake.style.height = `${controls.brake * 100}%`
  const line = lines?.[kind]
  hud.remaining.textContent = line
    ? `目的地まで ${((line.length - along) / 1000).toFixed(1)} km`
    : `目的地まで直線で ${(distanceMeters(here, route.destination.position) / 1000).toFixed(1)} km`
  hud.road.textContent = position.edge.name || '名前のない道'
  hud.junction.textContent = junctionText(controls.steer, line)
  hud.phones.innerHTML = link.phones === undefined
    ? '<span class="ng">中継に届きません（npm run dev で開いていますか）</span>'
    : link.phones > 0
      ? `<span class="ok">スマホ ${link.phones}台に位置を送っています</span>`
      : '<span class="ng">スマホがつながっていません</span>'
  hud.notice.textContent = api.limited ? 'Google の今日の上限に達しました（17時ごろ戻ります）' : !feed.ready ? '景色を読み込んでいます…' : feed.gap ? 'この辺りは写真がありません' : ''
  hud.usage.textContent = `読み込んだ写真のタイル ${api.usage.tiles.toLocaleString()} 枚`
  const credit = feed.attribution
  hud.copyright.textContent = credit?.copyright ?? ''
  hud.report.hidden = !credit?.reportProblemLink
  if (credit?.reportProblemLink) hud.report.href = credit.reportProblemLink
}

function junctionText(steer: number, line: RouteLine | undefined): string {
  if (arrived) return `${line && line.length - along >= 40 ? '見どころはここまでです' : '目的地に着きました'}。T キーで、もう一方の道を走り比べられます`
  if (deadEnd) return '行き止まりです。U キー（または U ターンのボタン）で向きを変えます'
  if (line && offRoute) return '選んだ道から外れています。左下の地図の線へ戻ってください'
  const junction = network!.nextJunction(position!, steer)
  if (!junction) return 'しばらく分かれ道はありません'
  // 走り比べでは、選んだ道がどちらへ曲がるかも出す
  const turn = line && turnAngle(line.headingAt(along + junction.distance - 20), line.headingAt(along + junction.distance + 25))
  return `${Math.round(junction.distance)}m 先の分かれ道${turn === undefined ? '' : `：${direction(turn)}`}`
}

function direction(turn: number): string {
  if (turn < -30) return '← 左へ'
  if (turn > 30) return '右へ →'
  return '↑ まっすぐ'
}

/** 次の見どころ（300m 以上続く景色）の始まりへ移る */
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

/** その場で反対を向く。道幅の中では切り返しが難しいため */
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
    if (menu.isOpen) menu.close()
    else menu.open(route)
  } else if (event.code === 'KeyC') {
    calibration.open()
  } else if (menu.isOpen) {
    if (event.code === 'ArrowLeft' || event.code === 'ArrowRight') menu.step(event.code === 'ArrowRight' ? 1 : -1)
    else if (/^Digit[1-9]$/.test(event.code)) menu.pick(Number(event.code.slice(5)))
  } else if (event.code === 'KeyU') {
    uturn()
  } else if (event.code === 'Backspace' && route) {
    void spawn(route, kind)
  } else if (event.code === 'KeyJ') {
    jumpToHighlight()
  } else if (event.code === 'KeyT' && route && lines) {
    void spawn(route, kind === 'scenic' ? 'normal' : 'scenic')
  }
})
window.addEventListener('gamepadconnected', () => {
  // 初めてのハンコンは、その場で調整画面を開く（G29 は OS ごとに軸の番号と向きが変わる）
  input.read(0)
  if (!input.hasMapping && !calibration.isOpen) calibration.open()
  else if (menu.isOpen) menu.refreshStatus()
})
window.addEventListener('pagehide', () => link.stop())

menu.open()
// ?route=onuma-lake&kind=normal で、選ぶ画面を飛ばして走り出す
const initial = ROUTES.find((r) => r.id === params.get('route'))
if (initial) {
  menu.close()
  void spawn(initial, params.get('kind') === 'normal' ? 'normal' : 'scenic')
}

let last = performance.now()
function frame(now: number): void {
  const dt = Math.min(0.1, (now - last) / 1000)
  last = now
  if (running && !menu.isOpen && !calibration.isOpen) drive(dt, now)
  requestAnimationFrame(frame)
}
requestAnimationFrame(frame)
