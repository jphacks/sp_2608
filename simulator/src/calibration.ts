/**
 * ハンコンの軸の割り当て。G29 は OS や接続のモードで軸の番号と向きが変わるので、
 * 決め打ちにせず、その場で動かしてもらって覚える。
 */
export interface SteerAxis {
  index: number
  left: number
  right: number
}

export interface PedalAxis {
  index: number
  /** 踏んでいないときの値 */
  rest: number
  /** 奥まで踏んだときの値 */
  full: number
}

export interface Mapping {
  steer: SteerAxis
  throttle: PedalAxis
  brake: PedalAxis
  /** U ターンに使うボタン */
  uturnButton?: number
  /** 出発点へ戻るボタン */
  resetButton?: number
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

export function steerValue(raw: number, axis: SteerAxis): number {
  if (axis.right === axis.left) return 0
  return clamp(2 * (raw - axis.left) / (axis.right - axis.left) - 1, -1, 1)
}

/** 踏みはじめの遊びを少し取る。置いた足の重みで進まないように。 */
export function pedalValue(raw: number, axis: PedalAxis): number {
  if (axis.full === axis.rest) return 0
  const value = clamp((raw - axis.rest) / (axis.full - axis.rest), 0, 1)
  return value < 0.03 ? 0 : value
}

/** 2つの読み取りの間でいちばん大きく動いた軸。どれも動いていなければ undefined。 */
export function mostMovedAxis(before: readonly number[], after: readonly number[],
  exclude: readonly number[] = []): number | undefined {
  let best: number | undefined
  let bestDelta = 0.3
  for (let i = 0; i < Math.min(before.length, after.length); i++) {
    if (exclude.includes(i)) continue
    const delta = Math.abs(after[i] - before[i])
    if (delta > bestDelta) {
      best = i
      bestDelta = delta
    }
  }
  return best
}

/** 2つの読み取りの間で新しく押されたボタン。 */
export function newlyPressedButton(before: readonly boolean[], after: readonly boolean[]): number | undefined {
  for (let i = 0; i < after.length; i++) if (after[i] && !before[i]) return i
  return undefined
}

/** 覚えた割り当てを、ハンコンの名前ごとにしまう。 */
export function loadMapping(gamepadId: string): Mapping | undefined {
  try {
    const saved = localStorage.getItem(storageKey(gamepadId))
    return saved ? JSON.parse(saved) as Mapping : undefined
  } catch {
    return undefined
  }
}

export function saveMapping(gamepadId: string, mapping: Mapping): void {
  try {
    localStorage.setItem(storageKey(gamepadId), JSON.stringify(mapping))
  } catch {
    // しまえなくても、この画面を開いている間は使える
  }
}

const storageKey = (gamepadId: string) => `tabireco-simulator.mapping.${gamepadId}`
