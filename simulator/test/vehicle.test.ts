import { describe, expect, it } from 'vitest'
import { bearingDegrees, distanceMeters, offset } from '../src/geo'
import { CAR, nextSpeed, type Controls } from '../src/vehicle'

const idle: Controls = { steer: 0, throttle: 0, brake: 0 }

function run(speed: number, controls: Controls, seconds: number, dt = 1 / 60): number {
  let current = speed
  for (let t = 0; t < seconds; t += dt) current = nextSpeed(current, controls, dt)
  return current
}

describe('geo', () => {
  it('offset で進んだ距離と向きが、測り直しても同じになる', () => {
    const start = { lat: 41.773436, lon: 140.726639 }
    const moved = offset(start, 300, 400)
    expect(distanceMeters(start, moved)).toBeCloseTo(500, 0)
    expect(bearingDegrees(start, moved)).toBeCloseTo(53.13, 0)
  })
})

describe('nextSpeed', () => {
  it('アクセルを踏むと速くなり、最高速を超えない', () => {
    const after = run(0, { ...idle, throttle: 1 }, 90)
    expect(after).toBeGreaterThan(CAR.topSpeed * 0.95)
    expect(after).toBeLessThanOrEqual(CAR.topSpeed)
  })

  it('ブレーキで止まり、後ろへは動かない', () => {
    expect(run(20, { ...idle, brake: 1 }, 5)).toBe(0)
  })

  it('アクセルを離すとゆっくり落ちる', () => {
    const after = run(20, idle, 5)
    expect(after).toBeGreaterThan(17)
    expect(after).toBeLessThan(20)
  })
})
