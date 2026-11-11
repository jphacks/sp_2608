import { describe, expect, it } from 'vitest'
import { mostMovedAxis, newlyPressedButton, pedalValue, steerValue } from '../src/calibration'

describe('steerValue', () => {
  it('左いっぱいが -1、右いっぱいが 1、まん中が 0', () => {
    const axis = { index: 0, left: -1, right: 1 }
    expect(steerValue(-1, axis)).toBe(-1)
    expect(steerValue(1, axis)).toBe(1)
    expect(steerValue(0, axis)).toBe(0)
  })

  it('軸の向きが逆でも、左が -1 になる', () => {
    const axis = { index: 0, left: 1, right: -1 }
    expect(steerValue(1, axis)).toBe(-1)
    expect(steerValue(-0.5, axis)).toBeCloseTo(0.5)
  })
})

describe('pedalValue', () => {
  it('踏んでいないとき 1、奥まで踏むと -1 になるペダルでも 0〜1 に直す', () => {
    const axis = { index: 2, rest: 1, full: -1 }
    expect(pedalValue(1, axis)).toBe(0)
    expect(pedalValue(-1, axis)).toBe(1)
    expect(pedalValue(0, axis)).toBeCloseTo(0.5)
  })

  it('踏みはじめの遊びでは進まない', () => {
    expect(pedalValue(0.02, { index: 1, rest: 0, full: 1 })).toBe(0)
  })
})

describe('mostMovedAxis', () => {
  it('いちばん動いた軸を選び、割り当て済みの軸は外す', () => {
    const rest = [0, 1, 1, 0]
    const now = [0.9, 1, -1, 0]
    expect(mostMovedAxis(rest, now)).toBe(2)
    expect(mostMovedAxis(rest, now, [2])).toBe(0)
  })

  it('どれも少ししか動いていなければ選ばない', () => {
    expect(mostMovedAxis([0, 0], [0.1, -0.2])).toBeUndefined()
  })
})

describe('newlyPressedButton', () => {
  it('新しく押されたボタンだけを拾う', () => {
    expect(newlyPressedButton([true, false, false], [true, false, true])).toBe(2)
    expect(newlyPressedButton([true, false], [true, false])).toBeUndefined()
  })
})
