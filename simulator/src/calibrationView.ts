import { mostMovedAxis, newlyPressedButton, saveMapping, type Mapping } from './calibration'

type Step = 'rest' | 'left' | 'right' | 'throttle' | 'brake' | 'uturn' | 'reset'

const PROMPTS: Record<Step, string> = {
  rest: 'アクセルとブレーキを一度ずつ踏んで離し、ハンドルをまっすぐにして、手と足を離してください。',
  left: 'ハンドルを左いっぱいまで回して、そのまま押さえてください。',
  right: 'ハンドルを右いっぱいまで回して、そのまま押さえてください。',
  throttle: 'アクセルを奥まで踏んで、そのまま押さえてください。',
  brake: 'ブレーキを奥まで踏んで、そのまま押さえてください。',
  uturn: 'U ターンに使うボタンを押してください。使わないなら Enter。',
  reset: '出発点へ戻るボタンを押してください。使わないなら Enter。',
}

const ORDER: Step[] = ['rest', 'left', 'right', 'throttle', 'brake', 'uturn', 'reset']

/**
 * ハンコンの軸を覚える画面。G29 は OS（Mac・Windows）や接続のモードで軸の番号と向きが変わるので、
 * その場で動かしてもらい、いちばん動いた軸を割り当てる。
 */
export class CalibrationView {
  private step = 0
  private rest: number[] = []
  private restButtons: boolean[] = []
  private draft: Partial<Mapping> = {}
  private frame = 0
  private readonly element: HTMLElement
  private readonly prompt: HTMLElement
  private readonly message: HTMLElement
  private readonly axes: HTMLElement

  constructor(
    private readonly gamepad: () => Gamepad | undefined,
    private readonly onClose: (saved: boolean) => void,
  ) {
    this.element = document.getElementById('calibration')!
    this.prompt = this.element.querySelector('[data-prompt]')!
    this.message = this.element.querySelector('[data-message]')!
    this.axes = this.element.querySelector('[data-axes]')!
    this.element.querySelector('[data-next]')!.addEventListener('click', () => this.next())
    this.element.querySelector('[data-cancel]')!.addEventListener('click', () => this.close(false))
  }

  get isOpen(): boolean {
    return !this.element.hidden
  }

  open(): void {
    this.step = 0
    this.draft = {}
    this.element.hidden = false
    this.show()
    const poll = () => {
      this.render()
      if (this.isOpen) this.frame = requestAnimationFrame(poll)
    }
    poll()
  }

  /** Enter キーか「次へ」で今の姿勢を読み取る。 */
  next(): void {
    const pad = this.gamepad()
    if (!pad) {
      this.message.textContent = 'ハンコンが見えません。ハンコンのボタンを1回押してください。'
      return
    }
    const now = [...pad.axes]
    const name = ORDER[this.step]

    if (name === 'rest') {
      this.rest = now
      this.restButtons = pad.buttons.map((button) => button.pressed)
    } else if (name === 'left') {
      const index = mostMovedAxis(this.rest, now)
      if (index === undefined) return this.retry()
      this.draft.steer = { index, left: now[index], right: now[index] }
    } else if (name === 'right') {
      const steer = this.draft.steer!
      if (Math.abs(now[steer.index] - steer.left) < 0.3) return this.retry()
      steer.right = now[steer.index]
    } else if (name === 'throttle' || name === 'brake') {
      // ハンドルの軸は外す。アクセルの軸は外さない：ペダルを1本の軸にまとめる設定（Windows の G HUB など）では、
      // アクセルで片側へ、ブレーキで反対側へ動く。同じ軸でも、アクセルと反対へ動いていればブレーキとして覚える
      const index = mostMovedAxis(this.rest, now, [this.draft.steer!.index])
      if (index === undefined) return this.retry()
      const throttle = this.draft.throttle
      if (name === 'brake' && throttle?.index === index &&
        Math.sign(now[index] - this.rest[index]) === Math.sign(throttle.full - throttle.rest)) return this.retry()
      this.draft[name] = { index, rest: this.rest[index], full: now[index] }
    }
    // ボタンの段で Enter を押したら「使わない」
    this.advance()
  }

  private advance(): void {
    this.step += 1
    if (this.step < ORDER.length) return this.show()
    const pad = this.gamepad()
    if (pad) saveMapping(pad.id, this.draft as Mapping)
    this.close(true)
  }

  private retry(): void {
    this.message.textContent = '動きが読み取れませんでした。いっぱいまで動かして、押さえたまま Enter を押してください。'
  }

  private show(): void {
    const name = ORDER[this.step]
    this.prompt.textContent = `${this.step + 1} / ${ORDER.length}　${PROMPTS[name]}`
    this.message.textContent = ''
  }

  private render(): void {
    const pad = this.gamepad()
    if (!pad) {
      this.axes.textContent = 'ハンコンが見えません。ハンコンのボタンを1回押してください（Chrome は押すまで見せません）。'
      return
    }
    const name = ORDER[this.step]
    if (name === 'uturn' || name === 'reset') {
      const pressed = newlyPressedButton(this.restButtons, pad.buttons.map((button) => button.pressed))
      if (pressed !== undefined) {
        this.draft[name === 'uturn' ? 'uturnButton' : 'resetButton'] = pressed
        // 同じボタンを次の段でも拾わないよう、押された状態を覚え直す
        this.restButtons = pad.buttons.map((button) => button.pressed)
        this.advance()
        return
      }
    }
    this.axes.innerHTML = `<div class="pad-name">${escapeHtml(pad.id)}</div>` + pad.axes
      .map((value, index) => `<div class="axis"><span>軸 ${index}</span><meter min="-1" max="1" value="${value}"></meter><code>${value.toFixed(2)}</code></div>`)
      .join('')
  }

  private close(saved: boolean): void {
    cancelAnimationFrame(this.frame)
    this.element.hidden = true
    this.onClose(saved)
  }
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
}
