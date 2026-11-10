import { CalibrationView } from './calibrationView'
import { distanceMeters, offset, type LatLon } from './geo'
import { Input } from './input'
import { LinkClient } from './link'
import { RoadNetwork, headingAt, pointAt, turnAngle, type RoadData, type RoadPosition } from './roads'
import { ROUTES, type DemoRoute } from './routes'
import { LOOK_AHEAD_METERS as PHOTO_LOOK_AHEAD, PanoramaFeed } from './panoramaFeed'
import { PanoramaRenderer, type PanoramaLayer, type View } from './panoramaRenderer'
import { StreetViewApi } from './streetviewApi'
import { nextSpeed, yawRate, type Controls } from './vehicle'

/** 写真を探す地点の間隔（panoramaFeed の SAMPLE_EVERY_METERS と同じ） */
const PHOTO_SAMPLE_METERS = 10
/** 横の視野角と、少し見下ろす角度 */
const FOV_DEGREES = 75
const PITCH_DEGREES = -4

const params = new URLSearchParams(location.search)
const googleKey = (import.meta.env.VITE_GOOGLE_MAPS_API_KEY as string | undefined)?.trim() || undefined

const $ = <T extends HTMLElement>(selector: string) => document.querySelector<T>(selector)!
const hud = {
  root: $('#hud'),
  route: $('[data-route]'),
  destination: $('[data-destination]'),
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
const input = new Input()
const link = new LinkClient()
const calibration = new CalibrationView(() => input.gamepad(), () => input.reloadMapping())
const networks = new Map<string, RoadNetwork>()

let route: DemoRoute | undefined
let network: RoadNetwork | undefined
/** 車のいる場所。道幅の中なら、道の真ん中の線から外れて自由に動ける */
let car: LatLon | undefined
/** 車にいちばん近い道の上の点。道の名前、分かれ道の案内、写真の先読みに使う */
let position: RoadPosition | undefined
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

function renderMenu(): void {
  $('[data-routes]').innerHTML = ROUTES.map((r, i) => `
    <button type="button" data-route-id="${r.id}">
      <div class="name">${i + 1}. ${r.name}（約${r.km.toFixed(0)}km）</div>
      <div class="note">${r.note}。旅れこの目的地：${r.destination.name}</div>
    </button>`).join('')
  for (const button of document.querySelectorAll<HTMLButtonElement>('[data-route-id]')) {
    button.addEventListener('click', () => choose(button.dataset.routeId!))
  }
  renderWheelStatus()
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

function choose(id: string): void {
  const chosen = ROUTES.find((r) => r.id === id)
  if (!chosen) return
  menu.hidden = true
  void spawn(chosen)
}

async function loadNetwork(id: string): Promise<RoadNetwork> {
  const cached = networks.get(id)
  if (cached) return cached
  const data = await (await fetch(`/roads/${id}.json`)).json() as RoadData
  const loaded = new RoadNetwork(data)
  networks.set(id, loaded)
  return loaded
}

async function spawn(next: DemoRoute): Promise<void> {
  running = false
  route = next
  hud.root.hidden = false
  hud.route.textContent = next.name
  hud.destination.textContent = next.destination.name
  network = await loadNetwork(next.id)
  position = network.snap(next.start, next.headingDegrees)
  speed = 0
  deadEnd = false
  car = pointAt(position)
  heading = headingAt(position)
  feed.reset()
  // スマホの旅れこに、この道の目的地で案内を始めさせる（デモ中にスマホで検索して打たなくてよいように）
  void link.start(car, heading, next.destination)
  running = true
}

function drive(dt: number, now: number): void {
  if (!network || !car) return
  const controls = input.read(dt)
  speed = nextSpeed(speed, controls, dt)
  // ハンドルで車の向きが変わり、向いている方へ進む
  heading = (heading + yawRate(speed, controls.steer) * dt + 360) % 360
  const radians = heading * Math.PI / 180
  const kept = network.keepOnRoad(offset(car, Math.cos(radians) * speed * dt, Math.sin(radians) * speed * dt), heading)
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
  // 道なりの先の地点。この辺りの写真を先に読んでおく
  const path = [here]
  for (let meters = PHOTO_SAMPLE_METERS; meters <= PHOTO_LOOK_AHEAD; meters += PHOTO_SAMPLE_METERS) {
    const next = network.advance(position, meters, controls.steer)
    path.push(pointAt(next.position))
    if (next.stopped) break
  }
  const { a, b, mixB } = feed.update(here, heading, path, now)
  const view = { heading, pitch: PITCH_DEGREES, fov: FOV_DEGREES }
  renderer.render(view, a, b, mixB)
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
  const km = distanceMeters(here, route.destination.position) / 1000
  hud.remaining.textContent = `目的地まで直線で ${km.toFixed(1)} km`
  hud.road.textContent = position.edge.name || '名前のない道'

  const junction = network.nextJunction(position, controls.steer)
  hud.junction.textContent = deadEnd
    ? '行き止まりです。U キー（または U ターンのボタン）で向きを変えます'
    : junction
      ? `${Math.round(junction.distance)}m 先に分かれ道`
      : 'しばらく分かれ道はありません'

  hud.phones.innerHTML = link.phones === undefined
    ? '<span class="ng">中継に届きません（npm run dev で開いていますか）</span>'
    : link.phones > 0
      ? `<span class="ok">スマホ ${link.phones}台に位置を送っています</span>`
      : '<span class="ng">スマホがつながっていません</span>'
  hud.notice.textContent = !feed.ready ? '景色を読み込んでいます…' : feed.gap ? 'この辺りは写真がありません' : ''
  // Street View のタイルは月10万枚まで無料、その先は1,000枚で約2ドル（2026-11 の料金表）
  hud.usage.textContent = `読み込んだ写真のタイル ${api.usage.tiles.toLocaleString()} 枚`
  const credit = feed.attribution
  hud.copyright.textContent = credit?.copyright ?? ''
  hud.report.hidden = !credit?.reportProblemLink
  if (credit?.reportProblemLink) hud.report.href = credit.reportProblemLink
}

/** その場で反対を向く。道幅の中では切り返しが難しいので残している。 */
function uturn(): void {
  if (!car || speed > 2) return
  deadEnd = false
  heading = (heading + 180) % 360
}

input.onUturn = uturn
input.onReset = () => {
  if (route) void spawn(route)
}

window.addEventListener('keydown', (event) => {
  if (calibration.isOpen) {
    if (event.code === 'Enter') calibration.next()
    return
  }
  if (event.code === 'KeyM') {
    menu.hidden = !menu.hidden
    if (!menu.hidden) renderWheelStatus()
  } else if (event.code === 'KeyC') {
    calibration.open()
  } else if (event.code === 'KeyU') {
    uturn()
  } else if (event.code === 'Backspace' && route) {
    void spawn(route)
  } else if (!menu.hidden && /^Digit[1-9]$/.test(event.code)) {
    const chosen = ROUTES[Number(event.code.slice(5)) - 1]
    if (chosen) choose(chosen.id)
  }
})
window.addEventListener('gamepadconnected', () => {
  // 初めてつないだハンコンは、その場で調整画面を開く。G29 は Mac と Windows で軸の番号と向きが変わるので決め打ちにしない
  input.read(0)
  if (!input.hasMapping && !calibration.isOpen) calibration.open()
  else if (!menu.hidden) renderWheelStatus()
})
window.addEventListener('pagehide', () => link.stop())

renderMenu()
// ?route=hakodate-onuma のように道を指定すると、選ぶ画面を飛ばして走り出す
const initialRoute = params.get('route')
if (initialRoute) choose(initialRoute)

let last = performance.now()
function frame(now: number): void {
  const dt = Math.min(0.1, (now - last) / 1000)
  last = now
  if (running && menu.hidden && !calibration.isOpen) drive(dt, now)
  requestAnimationFrame(frame)
}
requestAnimationFrame(frame)
