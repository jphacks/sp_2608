import { readFile } from 'node:fs/promises'
import { describe, expect, it } from 'vitest'
import { distanceMeters, offset } from '../src/geo'
import { RoadNetwork, headingAt, pointAt, turnAngle, turnAround, type RoadData } from '../src/roads'
import { ROUTES } from '../src/routes'

/**
 * 南から北へ来て、交差点 C で東西に分かれる十字路。北への道も続く。
 * 東の道は一方通行で、E から C へ（西向きに）だけ走れる。
 *
 *            N
 *            |
 *     W ---- C ←--- E
 *            |
 *            S
 */
const C = { lat: 43, lon: 142 }
const at = (north: number, east: number) => offset(C, north, east)
const points = [at(-200, 0), C, at(200, 0), at(0, -200), at(0, 200)]
const data: RoadData = {
  attribution: '',
  fetched: '',
  nodes: points.map((p) => [p.lat, p.lon]),
  ways: [
    { name: '南北の道', ref: '', highway: 'tertiary', oneway: 0, nodes: [0, 1, 2] },
    { name: '西の道', ref: '', highway: 'residential', oneway: 0, nodes: [1, 3] },
    // E→C の向きにだけ走れる（C から東へは入れない）
    { name: '東の道', ref: '', highway: 'residential', oneway: 1, nodes: [4, 1] },
  ],
}

describe('RoadNetwork', () => {
  const network = new RoadNetwork(data)

  it('道を交差点で切り分ける', () => {
    // 南北の道は C で2本に分かれ、西と東を足して4本
    expect(network.edges).toHaveLength(4)
  })

  it('出発点を一番近い道へ乗せ、向きを合わせる', () => {
    const start = network.snap(at(-150, 5), 0)
    expect(start.edge.name).toBe('南北の道')
    expect(distanceMeters(pointAt(start), at(-150, 0))).toBeLessThan(1)
    expect(headingAt(start)).toBeCloseTo(0, 0)
  })

  it('ハンドルがまっすぐなら、交差点をまっすぐ抜ける', () => {
    const start = network.snap(at(-150, 0), 0)
    const { position } = network.advance(start, 200, 0)
    expect(position.edge.name).toBe('南北の道')
    expect(distanceMeters(pointAt(position), at(50, 0))).toBeLessThan(1)
  })

  it('左に切ると西の道へ曲がる', () => {
    const start = network.snap(at(-150, 0), 0)
    const { position } = network.advance(start, 200, -1)
    expect(position.edge.name).toBe('西の道')
    expect(distanceMeters(pointAt(position), at(0, -50))).toBeLessThan(1)
    expect(headingAt(position)).toBeCloseTo(270, 0)
  })

  it('一方通行の逆には入らない。右に切っても東の道へは曲がらない', () => {
    const start = network.snap(at(-150, 0), 0)
    const { position } = network.advance(start, 200, 1)
    expect(position.edge.name).not.toBe('東の道')
  })

  it('次の分かれ道までの距離と、今のハンドルで選ばれる向きを教える', () => {
    const start = network.snap(at(-150, 0), 0)
    const left = network.nextJunction(start, -1)!
    expect(left.distance).toBeCloseTo(150, 0)
    expect(left.turn).toBeCloseTo(-90, 0)
    // 一方通行の東の道は数えない（北・西の2本）
    expect(left.branches).toBe(2)
  })

  it('行き止まりでは端で止まり、U ターンすると戻れる', () => {
    const start = network.snap(at(150, 0), 0)
    const end = network.advance(start, 100, 0)
    expect(end.stopped).toBe(true)
    expect(distanceMeters(pointAt(end.position), at(200, 0))).toBeLessThan(1)
    const back = turnAround(end.position)!
    expect(headingAt(back)).toBeCloseTo(180, 0)
    expect(network.advance(back, 50, 0).stopped).toBe(false)
  })

  it('一方通行の道では U ターンできない', () => {
    const east = network.snap(at(0, 150), 270)
    expect(east.edge.name).toBe('東の道')
    expect(turnAround(east)).toBeUndefined()
  })
})

describe('turnAngle', () => {
  it('右回りを正、左回りを負にし、近い回り方で測る', () => {
    expect(turnAngle(350, 10)).toBe(20)
    expect(turnAngle(10, 350)).toBe(-20)
    expect(turnAngle(0, 180)).toBe(-180)
  })
})

describe('デモの4本の道（OSM から取った実データ）', () => {
  for (const route of ROUTES) {
    it(`${route.name}：出発点が道の上にあり、道をたどって目的地の近くまで行ける`, async () => {
      const data = JSON.parse(await readFile(new URL(`../public/roads/${route.id}.json`, import.meta.url), 'utf8')) as RoadData
      const network = new RoadNetwork(data)
      // 駅前の広場など、取っていない種類の道から出る道もあるので 50m まで許す
      const start = network.snap(route.start, route.headingDegrees)
      expect(distanceMeters(pointAt(start), route.start)).toBeLessThan(50)

      // 一方通行を守って、出発点から入れる交差点を全部たどる
      const arrive = (edge: (typeof network.edges)[number], forward: boolean) => forward ? edge.to : edge.from
      const seen = new Set<number>([arrive(start.edge, start.forward)])
      const queue = [...seen]
      while (queue.length > 0) {
        const node = queue.pop()!
        for (const edge of network.edges) {
          for (const forward of [true, false]) {
            if ((forward ? edge.from : edge.to) !== node) continue
            if ((edge.oneway === 1 && !forward) || (edge.oneway === -1 && forward)) continue
            const next = arrive(edge, forward)
            if (!seen.has(next)) { seen.add(next); queue.push(next) }
          }
        }
      }
      const goal = network.snap(route.destination.position, 0)
      expect(seen.has(goal.edge.from) || seen.has(goal.edge.to)).toBe(true)
    })
  }
})

describe('道幅の中を自由に走る', () => {
  const network = new RoadNetwork(data)

  it('道幅の中なら、真ん中の線から外れた位置をそのまま使う', () => {
    // 南北の道（tertiary、端まで 3.5m）の、真ん中から東へ 2m
    const target = at(-100, 2)
    const kept = network.keepOnRoad(target, 0)
    expect(kept.pushed).toBe(false)
    expect(kept.at).toEqual(target)
    expect(kept.position.edge.name).toBe('南北の道')
    expect(kept.position.forward).toBe(true)
  })

  it('道の端を越えたら、端まで戻す', () => {
    const kept = network.keepOnRoad(at(-100, 10), 0)
    expect(kept.pushed).toBe(true)
    expect(distanceMeters(kept.at, at(-100, 0))).toBeCloseTo(3.5, 1)
    // 道に沿った位置は変えない
    expect(distanceMeters(kept.at, at(-100, 3.5))).toBeLessThan(0.1)
  })

  it('交差点で横へ向かうと、横の道へ入れる', () => {
    // C から西へ 30m。南北の道からは外れているが、西の道の上
    const kept = network.keepOnRoad(at(1, -30), 270)
    expect(kept.pushed).toBe(false)
    expect(kept.position.edge.name).toBe('西の道')
  })

  it('一方通行を逆に走っていても、走っている向きを返す', () => {
    // 東の道は西向きにだけ走れる。東へ向かって走る
    const { position } = network.locate(at(0, 100), 90)
    expect(position.edge.name).toBe('東の道')
    expect(headingAt(position)).toBeCloseTo(90, 0)
  })
})

