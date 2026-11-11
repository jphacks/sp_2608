import { loadMapping, pedalValue, steerValue, type Mapping } from './calibration'
import type { Controls } from './vehicle'

/** ハンコン（ゲームパッドとして見える）とキーボードの操作をまとめて読む。 */
export class Input {
  private readonly keys = new Set<string>()
  private keyboardSteer = 0
  private buttonsBefore: boolean[] = []
  private mappingFor: string | undefined
  private mapping: Mapping | undefined
  onUturn: () => void = () => {}
  onReset: () => void = () => {}

  constructor() {
    window.addEventListener('keydown', (event) => {
      if (event.repeat) return
      this.keys.add(event.code)
    })
    window.addEventListener('keyup', (event) => this.keys.delete(event.code))
    // 別の画面へ移ると keyup が来ず、踏みっぱなしになる
    window.addEventListener('blur', () => this.keys.clear())
  }

  /** つながっているハンコン。Chrome はボタンを1回押すまで見せない。 */
  gamepad(): Gamepad | undefined {
    const pads = navigator.getGamepads().filter((pad): pad is Gamepad => pad !== null && pad.connected)
    // ハンドルとペダルで軸が3本以上あるものを優先する
    return pads.find((pad) => pad.axes.length >= 3) ?? pads[0]
  }

  /** 覚えた割り当てを読み直す（調整のあとに呼ぶ）。 */
  reloadMapping(): void {
    this.mappingFor = undefined
  }

  get hasMapping(): boolean {
    return this.mapping !== undefined
  }

  read(dt: number): Controls {
    const pad = this.gamepad()
    if (pad && this.mappingFor !== pad.id) {
      this.mappingFor = pad.id
      this.mapping = loadMapping(pad.id)
    }
    if (!pad) this.mapping = undefined

    const keyboard = this.readKeyboard(dt)
    if (!pad || !this.mapping) return keyboard

    const mapping = this.mapping
    const buttons = pad.buttons.map((button) => button.pressed)
    const pressed = (index: number | undefined) =>
      index !== undefined && buttons[index] === true && this.buttonsBefore[index] !== true
    if (pressed(mapping.uturnButton)) this.onUturn()
    if (pressed(mapping.resetButton)) this.onReset()
    this.buttonsBefore = buttons

    const wheel: Controls = {
      steer: steerValue(pad.axes[mapping.steer.index] ?? 0, mapping.steer),
      throttle: pedalValue(pad.axes[mapping.throttle.index] ?? mapping.throttle.rest, mapping.throttle),
      brake: pedalValue(pad.axes[mapping.brake.index] ?? mapping.brake.rest, mapping.brake),
    }
    // キーボードを触っている間はそちらを使う（ハンコンが手元にないときの確認用）
    const typing = keyboard.throttle > 0 || keyboard.brake > 0 || keyboard.steer !== 0
    return typing ? keyboard : wheel
  }

  private readKeyboard(dt: number): Controls {
    const held = (...codes: string[]) => codes.some((code) => this.keys.has(code))
    const target = (held('ArrowLeft', 'KeyA') ? -1 : 0) + (held('ArrowRight', 'KeyD') ? 1 : 0)
    // 押している間は少しずつ切り、離すと戻す。いきなり切ると曲がりすぎる
    const rate = target === 0 ? 2.5 : 1.2
    const delta = target === 0 ? -this.keyboardSteer : target - this.keyboardSteer
    this.keyboardSteer += Math.sign(delta) * Math.min(Math.abs(delta), rate * dt)
    return {
      steer: this.keyboardSteer,
      throttle: held('ArrowUp', 'KeyW') ? 1 : 0,
      brake: held('ArrowDown', 'KeyS', 'Space') ? 1 : 0,
    }
  }
}
