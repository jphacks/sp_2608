import { describe, it, expect } from 'vitest';
import { segmentRoute, type RoutePoint } from '../src/segment';
import sampleRoute from '../routes/sample-coast.json';

// n 点ぶん（200m間隔）の同じ点を作る
function repeat(n: number, p: Omit<RoutePoint, 'km'>): RoutePoint[] {
  return Array.from({ length: n }, () => structuredClone(p));
}

describe('segmentRoute', () => {
  it('景観が一様なら1区間になる。ETA は距離と速度から計算する', () => {
    const segs = segmentRoute(repeat(30, { features: { forest: 0.9 }, speed_kmh: 36 }));
    expect(segs).toHaveLength(1);
    expect(segs[0]).toMatchObject({ start_km: 0, end_km: 6, start_s: 0, end_s: 600 });
    expect(segs[0].features.forest).toEqual({ v: 0.9, q: 1 });
  });

  it('景観が大きく変わる地点で区切る', () => {
    const points = [
      ...repeat(20, { features: { urban: 0.9 }, speed_kmh: 30 }), // 4km、8分
      ...repeat(20, { features: { sea_proximity: 0.9 }, speed_kmh: 30 }),
    ];
    const segs = segmentRoute(points);
    expect(segs.map((s) => [s.start_km, s.end_km])).toEqual([
      [0, 4],
      [4, 8],
    ]);
  });

  it('3分に満たない区間は、特徴が近いほうの隣に統合する', () => {
    const points = [
      ...repeat(20, { features: { urban: 0.9 }, speed_kmh: 40 }),
      ...repeat(5, { features: { rural: 0.6, urban: 0.3 }, speed_kmh: 60 }), // 1km、1分
      ...repeat(20, { features: { rural: 0.8 }, speed_kmh: 40 }),
    ];
    const segs = segmentRoute(points);
    expect(segs).toHaveLength(2);
    // 短い区間は田園側（特徴が近いほう）に入る
    expect(segs[1].start_km).toBe(4);
    for (const s of segs) expect(s.end_s - s.start_s).toBeGreaterThanOrEqual(180);
  });

  it('一瞬だけの変化では区切らない', () => {
    const points = repeat(40, { features: { forest: 0.9, openness: 0.1 }, speed_kmh: 40 });
    points[20].features.openness = 0.8;
    expect(segmentRoute(points)).toHaveLength(1);
  });

  it('q = 0（不明）の点では区切らず、平均にも入れない。その分 q が下がる', () => {
    const points = repeat(20, { features: { sea_proximity: 0.8 }, speed_kmh: 30 });
    points[5].features.sea_proximity = { v: 0, q: 0 };
    points[6].features.sea_proximity = { v: 0, q: 0 };
    const segs = segmentRoute(points);
    expect(segs).toHaveLength(1);
    expect(segs[0].features.sea_proximity).toEqual({ v: 0.8, q: 0.9 });
  });

  it('運転条件は区間内で最も厳しいものを採る', () => {
    const points = repeat(20, { features: { urban: 0.8 }, speed_kmh: 30 });
    points[10].load = 'high'; // 1点だけ（短いので統合される）
    const segs = segmentRoute(points);
    expect(segs).toHaveLength(1);
    expect(segs[0].load).toBe('high');
    expect(segs[0].scene.load).toBe('high');
  });

  it('名道は区間の半分以上が名道のときだけ highlight にする', () => {
    const points = repeat(20, { features: { sea_proximity: 0.9 }, speed_kmh: 60 }); // 4分
    for (let i = 0; i < 12; i++) points[i].highlight = true;
    const segs = segmentRoute(points);
    expect(segs).toHaveLength(1);
    expect(segs[0].highlight).toBe(true);
  });

  it('確認済みの海の可視性が立ち上がる地点を reveal として記録する', () => {
    const points = repeat(30, { features: { sea_proximity: 0.9 }, speed_kmh: 30 });
    for (let i = 15; i < 30; i++) points[i].features.sea_visibility = { v: 0.9, q: 0.8 };
    // 未確認（q が低い）の可視性は reveal にしない
    points[5].features.sea_visibility = { v: 0.9, q: 0.3 };
    const segs = segmentRoute(points);
    const reveals = segs.map((s) => s.reveal_km).filter((k) => k != null);
    expect(reveals).toEqual([3]);
  });

  it('出発時刻を渡すと、区間の中間時点の hour を入れる', () => {
    const points = repeat(30, { features: { forest: 0.9 }, speed_kmh: 6 }); // 6km、60分
    expect(segmentRoute(points, { start_hour: 18.75 })[0].scene.hour).toBe(19);
    expect(segmentRoute(points)[0].scene.hour).toBeUndefined();
  });

  it('空のルートは空の配列', () => {
    expect(segmentRoute([])).toEqual([]);
  });

  it('サンプルルート：区間の境目が景観の切り替わりと一致する', () => {
    const segs = segmentRoute(sampleRoute.points as RoutePoint[], { start_hour: sampleRoute.start_hour });
    expect(segs.map((s) => [s.start_km, s.end_km])).toEqual([
      [0, 2],
      [2, 5],
      [5, 12],
      [12, 18],
      [18, 21],
      [21, 28],
      [28, 30],
    ]);
    expect(segs[3]).toMatchObject({ curvy: true, load: 'normal' });
    expect(segs[5]).toMatchObject({ highlight: true, reveal_km: 21 });
    expect(segs[6].load).toBe('high');
  });
});
