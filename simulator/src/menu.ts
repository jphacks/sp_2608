import { roadRecord } from './badges'
import { AERIAL_CREDIT, drawPreview, loadComparison, rangeOnStrip, sceneryKm, sceneryStrip, type ComparedRoute, type RouteKind } from './compare'
import { ROUTES, type DemoRoute } from './routes'

/** 「万代町 → 函館駅（ともえ大橋）」を、出発地・目的地・通る所に分ける */
function splitName(name: string): { from: string; to: string; via?: string } {
  const match = name.match(/^(.+?) → (.+?)(?:（(.+)）)?$/)
  return match ? { from: match[1], to: match[2], via: match[3] } : { from: '', to: name }
}

/** 道を選ぶ画面。目的地を1つずつ大きく出し、空中写真の地図で2本の道を比べる */
export class Menu {
  private course = 0

  constructor(
    private readonly root: HTMLElement,
    private readonly list: HTMLElement,
    private readonly status: HTMLElement,
    private readonly onChoose: (route: DemoRoute, kind: RouteKind) => void,
    private readonly wheelStatus: () => string,
  ) {}

  get isOpen(): boolean {
    return !this.root.hidden
  }

  open(route?: DemoRoute): void {
    this.root.hidden = false
    this.show(route ? ROUTES.indexOf(route) : this.course)
  }

  close(): void {
    this.root.hidden = true
  }

  step(delta: number): void {
    this.show(this.course + delta)
  }

  /** 数字キーで、n 番目の道を選ぶ */
  pick(n: number): void {
    this.list.querySelectorAll<HTMLButtonElement>('[data-kind]')[n - 1]?.click()
  }

  refreshStatus(): void {
    this.status.textContent = this.wheelStatus()
  }

  show(index: number): void {
    this.course = (index + ROUTES.length) % ROUTES.length
    void this.render()
  }

  private async render(): Promise<void> {
    const route = ROUTES[this.course]
    const comparison = await loadComparison(route.id)
    const { from, to, via } = splitName(route.name)
    this.list.innerHTML = `
      ${route.photo ? `<div class="course-photo" style="background-image: url('${route.photo.url}')"></div>` : ''}
      <div class="course">
        <figure class="course-map">
          ${comparison ? '<canvas data-preview></canvas>' : ''}
          <figcaption>${AERIAL_CREDIT}　道路：© OpenStreetMap contributors</figcaption>
        </figure>
        <div class="course-info">
          <div class="course-head">
            <div class="course-route">
              <div class="course-from">${from}から</div>
              <h1 class="course-to">${to}</h1>
              <div class="course-en">${route.en}</div>
            </div>
            ${roadRecord(route.id, comparison?.scenic) ?? (via ? `<span class="course-via">${via}</span>` : '')}
          </div>
          <div class="course-options">
            ${comparison
              ? option(route, 'normal', comparison.normal, 1) + option(route, 'scenic', comparison.scenic, 2)
              : '<button type="button" class="option normal" data-kind="normal"><span class="key">1</span><span class="option-name">この道を走る</span></button>'}
          </div>
        </div>
      </div>
      <nav class="course-tabs">
        ${ROUTES.map((each, i) => `<button type="button" data-course="${i}"${i === this.course ? ' class="current"' : ''}>${splitName(each.name).to}</button>`).join('')}
      </nav>
      ${route.photo ? `<div class="course-credit">${route.photo.credit}</div>` : ''}
      ${this.arrow(-1)}${this.arrow(1)}`

    const canvas = this.list.querySelector<HTMLCanvasElement>('[data-preview]')
    const paintMap = (focus?: RouteKind) => {
      if (canvas && comparison) drawPreview(canvas, comparison, focus, [from, to])
    }
    paintMap()
    // 地名の書体が読み込まれる前に描くと、別の書体になる
    void document.fonts.ready.then(() => paintMap())

    for (const button of this.list.querySelectorAll<HTMLButtonElement>('[data-kind]')) {
      const kind = button.dataset.kind as RouteKind
      button.addEventListener('click', () => this.onChoose(route, kind))
      for (const type of ['mouseenter', 'focus']) button.addEventListener(type, () => paintMap(kind))
      for (const type of ['mouseleave', 'blur']) button.addEventListener(type, () => paintMap())
    }
    for (const tab of this.list.querySelectorAll<HTMLButtonElement>('[data-course]')) {
      tab.addEventListener('click', () => this.show(Number(tab.dataset.course)))
    }
    for (const button of this.list.querySelectorAll<HTMLButtonElement>('[data-step]')) {
      button.addEventListener('click', () => this.step(Number(button.dataset.step)))
    }
    this.refreshStatus()
  }

  /** 隣の目的地へ移る矢印 */
  private arrow(step: -1 | 1): string {
    const neighbor = splitName(ROUTES[(this.course + step + ROUTES.length) % ROUTES.length].name).to
    return `<button type="button" class="course-arrow ${step < 0 ? 'previous' : 'next'}" data-step="${step}" aria-label="${neighbor}へ">
      <svg viewBox="0 0 12 24" aria-hidden="true"><path d="${step < 0 ? 'M10 2 L2 12 L10 22' : 'M2 2 L10 12 L2 22'}"/></svg>
      <span>${neighbor}</span>
    </button>`
  }
}

function option(route: DemoRoute, kind: RouteKind, compared: ComparedRoute, number: number): string {
  const parts = sceneryKm(compared).slice(0, 3)
    .map((part) => `<span class="chip"><i style="background: ${part.color}"></i>${part.label} ${part.km.toFixed(1)}km</span>`).join('')
  const demo = route.demo?.[kind]
  const range = demo && rangeOnStrip(demo, compared.timeline.at(-1)?.[1] ?? compared.km * 1000)
  return `
    <button type="button" class="option ${kind}" data-kind="${kind}">
      <span class="key">${number}</span>
      <span class="option-name">${kind === 'scenic' ? '景色のいい道' : 'ふつうの道'}</span>
      <span class="option-figures"><b>${compared.minutes}</b>分<small>${compared.km.toFixed(1)}km</small></span>
      <span class="strip" style="background: ${sceneryStrip(compared)}">${range ? `<span class="demo-range" style="left: ${range.left}; width: ${range.width}"></span>` : ''}</span>
      <span class="chips">${parts || '<span class="chip"><i style="background: #8c8c8c"></i>街なか</span>'}</span>
    </button>`
}
