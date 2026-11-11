import { CompareMap, kindLabel, rangeOnStrip, sceneryStrip, type Comparison, type RouteKind, type RouteLine } from './compare'
import type { LatLon } from './geo'
import type { DemoRoute } from './routes'

/** 走っている間の走り比べの枠：道の種類の札、2本の道の地図、景色の帯と今いる所 */
export class CompareHud {
  private readonly map: CompareMap

  constructor(private readonly root: HTMLElement, private readonly badge: HTMLElement, private readonly scenery: HTMLElement) {
    this.map = new CompareMap(root.querySelector<HTMLCanvasElement>('[data-compare-map]')!)
  }

  show(route: DemoRoute, comparison: Comparison | undefined, lines: Record<RouteKind, RouteLine> | undefined, kind: RouteKind): void {
    this.root.hidden = !comparison || !lines
    this.badge.textContent = comparison ? kindLabel(kind, comparison[kind]) : ''
    this.badge.className = `kind-badge ${kind}`
    if (!comparison || !lines) return
    for (const each of ['normal', 'scenic'] as const) {
      const row = this.row(each)
      row.classList.toggle('active', each === kind)
      row.querySelector('.figures')!.textContent = `${each === 'scenic' ? 'おすすめ' : 'ふつう'}　${comparison[each].km.toFixed(1)}km・約${comparison[each].minutes}分`
      row.querySelector<HTMLElement>('.strip')!.style.background = sceneryStrip(comparison[each])
      const range = row.querySelector<HTMLElement>('.demo-range')!
      const demo = route.demo?.[each]
      range.hidden = !demo
      if (demo) Object.assign(range.style, rangeOnStrip(demo, lines[each].length))
    }
    this.map.draw(lines, kind)
  }

  update(car: LatLon, line: RouteLine, along: number, kind: RouteKind): void {
    this.map.update(car)
    const now = line.sceneryAt(along)
    this.scenery.innerHTML = now ? `いまの景色：<span class="dot" style="background: ${now.color}"></span>${now.label}` : ''
    this.row(kind).querySelector<HTMLElement>('.marker')!.style.left = `${(along / line.length * 100).toFixed(2)}%`
  }

  private row(kind: RouteKind): HTMLElement {
    return this.root.querySelector<HTMLElement>(`[data-compare-row="${kind}"]`)!
  }
}
