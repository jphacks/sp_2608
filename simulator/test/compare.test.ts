import { describe, expect, it } from 'vitest'
import { RouteLine, sceneryKm, sceneryStrip, type ComparedRoute } from '../src/compare'
import { distanceMeters, offset } from '../src/geo'

/** 北へ 1km 進み、東へ 1km 進む道。前半が森、後半が湖。 */
const start = { lat: 43, lon: 142 }
const corner = offset(start, 1000, 0)
const end = offset(corner, 0, 1000)
const shape: [number, number][] = []
for (let m = 0; m <= 1000; m += 10) shape.push([offset(start, m, 0).lat, offset(start, m, 0).lon])
for (let m = 10; m <= 1000; m += 10) shape.push([offset(corner, 0, m).lat, offset(corner, 0, m).lon])
const route: ComparedRoute = {
  source: 'tabireco', role: '', km: 2, minutes: 3, shape,
  timeline: [[0, 1000, 'forest'], [1000, 1400, 'urban'], [1400, 2000, 'lake']], reasons: [],
}

describe('RouteLine', () => {
  it('道の長さと、始まりの向きが分かる', () => {
    const line = new RouteLine(route)
    expect(line.length).toBeCloseTo(2000, -1)
    expect(line.startHeading).toBeCloseTo(0, 0)
    expect(distanceMeters(line.pointAt(2000), end)).toBeLessThan(1)
  })

  it('車が道のどこまで来たかと、道からの離れ具合が分かる', () => {
    const line = new RouteLine(route)
    const at = line.locate(offset(start, 400, 3))
    expect(at.along).toBeCloseTo(400, -1)
    expect(at.distance).toBeCloseTo(3, 0)
    // 角を曲がった先
    expect(line.locate(offset(corner, 0, 500)).along).toBeCloseTo(1500, -1)
  })

  it('角の手前と先で、道の向きが変わる', () => {
    const line = new RouteLine(route)
    expect(line.headingAt(980)).toBeCloseTo(0, 0)
    expect(line.headingAt(1025)).toBeCloseTo(90, 0)
  })

  it('今いる所の景色が分かる', () => {
    const line = new RouteLine(route)
    expect(line.sceneryAt(500)?.label).toBe('森')
    expect(line.sceneryAt(1700)?.label).toBe('湖')
  })
})

describe('景色の内訳', () => {
  it('街を除いて、長い順に並べる', () => {
    expect(sceneryKm(route).map((s) => [s.label, s.km])).toEqual([['森', 1], ['湖', 0.6]])
  })

  it('景色のデータが無い道は、帯を出さない', () => {
    expect(sceneryStrip({ ...route, timeline: [] })).toBe('')
    expect(sceneryStrip(route)).toContain('50.00%')
  })
})
