import type { RouteLine } from './compare'
import type { LatLon } from './geo'
import { CAMERA_HEIGHT, type View } from './panoramaRenderer'

/** 路面に引く線の幅と、先まで引く長さ（メートル） */
const WIDTH_METERS = 0.5
const AHEAD_METERS = 200
const STEP_METERS = 3

/**
 * 走る道の線を、路面の上に引く。景色と同じカメラ（panoramaRenderer.ts の ray の逆）で、地面の点を画面の点へ直す。
 * 画面は横に panes 等分で、左から index 番目の窓は view.heading から (index − 真ん中) × side 度だけ横を向く。
 */
export function drawGuide(canvas: HTMLCanvasElement, line: RouteLine, along: number, car: LatLon, view: View, side: number, panes: number, color: string): void {
  if (canvas.width !== canvas.clientWidth || canvas.height !== canvas.clientHeight) {
    canvas.width = canvas.clientWidth
    canvas.height = canvas.clientHeight
  }
  const context = canvas.getContext('2d')!
  context.clearRect(0, 0, canvas.width, canvas.height)
  const radians = Math.PI / 180
  const scale = Math.cos(car.lat * radians) * 111_320
  // 線の左右の端（車から東へ・北へ、メートル）
  const edges: { left: [number, number]; right: [number, number] }[] = []
  for (let meters = along + STEP_METERS; meters <= Math.min(line.length, along + AHEAD_METERS); meters += STEP_METERS) {
    const p = line.pointAt(meters)
    const heading = line.headingAt(meters) * radians
    const east = (p.lon - car.lon) * scale
    const north = (p.lat - car.lat) * 111_320
    // 道の向きに直角な向き（右手）
    const [rx, ry] = [Math.cos(heading) * WIDTH_METERS / 2, -Math.sin(heading) * WIDTH_METERS / 2]
    edges.push({ left: [east - rx, north - ry], right: [east + rx, north + ry] })
  }
  const paneWidth = canvas.width / panes
  const tanHalfFov = Math.tan(view.fov * radians / 2)
  const aspect = paneWidth / canvas.height
  const [cp, sp] = [Math.cos(view.pitch * radians), Math.sin(view.pitch * radians)]
  context.fillStyle = color
  for (let index = 0; index < panes; index++) {
    const yaw = (view.heading + (index - (panes - 1) / 2) * side) * radians
    const [cy, sy] = [Math.cos(yaw), Math.sin(yaw)]
    /** 地面の点を、この窓の画面の点へ。カメラの後ろと、すぐ足もとは undefined */
    const project = ([east, north]: [number, number]): [number, number] | undefined => {
      const x = east * cy - north * sy
      const forward = east * sy + north * cy
      const y = -CAMERA_HEIGHT * cp - forward * sp
      const z = -CAMERA_HEIGHT * sp + forward * cp
      if (z < 1) return undefined
      return [paneWidth * index + (x / z / tanHalfFov + 1) / 2 * paneWidth, (1 - y / z * aspect / tanHalfFov) / 2 * canvas.height]
    }
    context.save()
    context.beginPath()
    context.rect(paneWidth * index, 0, paneWidth, canvas.height)
    context.clip()
    for (let i = 1; i < edges.length; i++) {
      const corners = [project(edges[i - 1].left), project(edges[i].left), project(edges[i].right), project(edges[i - 1].right)]
      if (corners.some((corner) => !corner)) continue
      context.beginPath()
      corners.forEach((corner, n) => (n === 0 ? context.moveTo(corner![0], corner![1]) : context.lineTo(corner![0], corner![1])))
      context.fill()
    }
    context.restore()
  }
}
