import { describe, expect, it } from 'vitest'
import { offset } from '../src/geo'
import { aheadMeters, aligned, blend, grid, tileColumns } from '../src/panoramaFeed'

describe('tileColumns', () => {
  it('写真の真ん中と同じ向きを見るなら、真ん中の列（8列のうち 2〜5）を読む', () => {
    // 1列は 45 度。真ん中（u=0.5）は列 4 の左端
    expect(tileColumns(0, 0, 55)).toEqual([2, 3, 4, 5])
  })

  it('写真の真後ろを見るなら、左右の端の列をまたいで読む', () => {
    expect(tileColumns(0, 180, 30)).toEqual([0, 7])
  })

  it('写真の向きがずれていても、見る方角に合わせて列を選ぶ', () => {
    expect(tileColumns(90, 90, 20)).toEqual(tileColumns(0, 0, 20))
  })
})

describe('blend', () => {
  const a = { lat: 43, lon: 142 }
  const b = offset(a, 20, 0)

  it('A の近くでは A だけ、B の近くでは B だけを出し、中ほどで入れ替える', () => {
    expect(blend(a, b, offset(a, 2, 0))).toBe(0)
    expect(blend(a, b, offset(a, 10, 0))).toBeCloseTo(0.5, 1)
    expect(blend(a, b, offset(a, 18, 0))).toBe(1)
  })

  it('道の横へのずれは進み具合に入れない', () => {
    expect(blend(a, b, offset(a, 10, 3))).toBeCloseTo(0.5, 1)
  })
})

describe('aheadMeters', () => {
  it('進む向きの前は正、後ろは負', () => {
    const car = { lat: 43, lon: 142 }
    expect(aheadMeters(car, 0, offset(car, 30, 0))).toBeCloseTo(30, 0)
    expect(aheadMeters(car, 0, offset(car, -30, 0))).toBeCloseTo(-30, 0)
    expect(aheadMeters(car, 90, offset(car, 0, 30))).toBeCloseTo(30, 0)
  })
})

describe('aligned', () => {
  it('道と同じ向き・反対向きに撮った写真は使い、横道の写真は使わない', () => {
    expect(aligned(10, 0)).toBe(true)
    expect(aligned(185, 0)).toBe(true)
    expect(aligned(90, 0)).toBe(false)
    expect(aligned(355, 20)).toBe(true)
  })
})

describe('grid', () => {
  it('新しい写真（16384×8192）は zoom 3 で横 8・縦 4 枚、水平線の上下は 2 段目と 3 段目', () => {
    expect(grid({ imageWidth: 16384, imageHeight: 8192 })).toEqual({ columns: 8, rows: 4, forwardRows: [1, 2], scale: [1, 1] })
  })

  it('古い写真（13312×6656、2011 年の美瑛）は横 6.5 枚で、7 枚目（x=7）を取りに行かない', () => {
    const old = grid({ imageWidth: 13312, imageHeight: 6656 })
    expect(old.columns).toBe(6.5)
    expect(old.rows).toBe(4)
    expect(old.forwardRows).toEqual([0, 1, 2])
    expect(old.scale).toEqual([0.8125, 0.8125])
    for (let view = 0; view < 360; view += 15) {
      expect(Math.max(...tileColumns(0, view, 55, old.columns))).toBeLessThanOrEqual(6)
    }
  })
})
