import type { LatLon } from './geo'
import type { PanoramaFeed } from './panoramaFeed'
import type { PanoramaLayer, PanoramaRenderer, View } from './panoramaRenderer'
import type { StreetViewApi } from './streetviewApi'

export interface Frame {
  view: View
  a?: PanoramaLayer
  b?: PanoramaLayer
}

/** 開発者ツールから呼ぶ測定（__usage・__feed・__seam） */
export function installDebugHooks(api: StreetViewApi, feed: PanoramaFeed, renderer: PanoramaRenderer, car: () => LatLon | undefined, frame: () => Frame | undefined): void {
  const hooks = window as unknown as Record<string, unknown>
  hooks.__usage = () => ({ ...api.usage, ...feed.stats })
  hooks.__feed = () => {
    const at = car()
    return at ? feed.debug(at) : undefined
  }
  // 仮の円筒の半径ごとの、写真の継ぎ目の目立ちやすさ
  hooks.__seam = (settings: { radius: number }[]) => {
    const last = frame()
    if (!last?.a || !last.b) return undefined
    const saved = renderer.radius
    const result = settings.map(({ radius }) => {
      renderer.radius = radius
      return renderer.seam(last.view, last.a!, last.b!)
    })
    renderer.radius = saved
    return result
  }
}
