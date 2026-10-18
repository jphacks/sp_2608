import { describe, it, expect } from 'vitest';
import { sceneFromScenicRoute, type ScenicBlock } from '../src/scenic';
import { computeTarget } from '../src/engine';

// 時速 60km（1km = 60秒）で走るタイムラインを作る
function timeline(blocks: [number, number, string, Partial<ScenicBlock>?][], startIso = '2026-10-17T14:00:00+09:00'): ScenicBlock[] {
  const t0 = Date.parse(startIso);
  return blocks.map(([s, e, category, extra]) => ({
    start_m: s,
    end_m: e,
    category,
    confidence: 0.3,
    arrival_time: iso(t0 + (s / 1000) * 60_000),
    ...extra,
  }));
}
function iso(ms: number) {
  // +09:00 の現地時刻で書く
  const d = new Date(ms + 9 * 3600_000);
  return d.toISOString().replace('Z', '+09:00').replace('.000', '');
}

describe('sceneFromScenicRoute', () => {
  it('到着時刻から速度を出し、次の曲の長さぶん先までを窓にする', () => {
    const w = sceneFromScenicRoute({
      position_m: 1000,
      timeline: timeline([[0, 2000, 'forest'], [2000, 10000, 'sea']]),
      next_duration_ms: 180_000, // 3分 × 時速60km = 3km
    });
    expect(w.window.speed_kmh).toBeCloseTo(60, 3);
    expect(w.window).toMatchObject({ start_m: 1000, end_m: 4000, duration_s: 180 });
    // 窓 1000〜4000m：森 1km、海 2km
    expect(w.scene.features.forest).toEqual({ v: 0.333, q: 1 });
    expect(w.scene.features.sea_proximity).toEqual({ v: 0.667, q: 1 });
  });

  it('景観エンジンの confidence（補助データの有無で決まる定数）を OSM 由来のカテゴリの信頼度に使わない', () => {
    const w = sceneFromScenicRoute({ position_m: 0, timeline: timeline([[0, 10000, 'sea']]) });
    expect(w.scene.features.sea_proximity).toMatchObject({ q: 1 });
    // 効果がそのまま効く（confidence 0.3 を掛けると 3割に弱まってしまう）
    expect(computeTarget(w.scene).target.spacious).toBeCloseTo(0.65, 3);
  });

  it('unknown は分母から外して信頼度を下げる。unclassified（トンネルなど）は分母に含める', () => {
    const w = sceneFromScenicRoute({
      position_m: 0,
      timeline: timeline([[0, 1000, 'sea'], [1000, 2000, 'unknown', { confidence: 0 }], [2000, 3000, 'unclassified'], [3000, 9000, 'sea']]),
      next_duration_ms: 240_000, // 4km
    });
    // 窓 0〜4000m：海 2km・unknown 1km・unclassified 1km → 評価済み 3km のうち海 2km
    expect(w.coverage).toBe(0.75);
    expect(w.scene.features.sea_proximity).toEqual({ v: 0.667, q: 0.75 });
    expect(w.shares.unclassified).toBe(0.333);
  });

  it('すべて unknown なら特徴量は空（景観なしと断定しない）', () => {
    const w = sceneFromScenicRoute({ position_m: 0, timeline: timeline([[0, 9000, 'unknown', { confidence: 0 }]]) });
    expect(w.scene.features).toEqual({});
    expect(w.coverage).toBe(0);
  });

  it('カテゴリ名を BGM の特徴量に対応づける（補助データ由来は confidence を使う）', () => {
    const w = sceneFromScenicRoute({
      position_m: 0,
      timeline: timeline([
        [0, 1000, 'open_nature'],
        [1000, 2000, 'inland_water', { confidence: 0.6 }],
        [2000, 3000, 'farmland', { confidence: 0.6 }],
        [3000, 9000, 'townscape', { confidence: 0.6 }],
      ]),
      next_duration_ms: 240_000,
    });
    expect(Object.keys(w.scene.features).sort()).toEqual(['nature', 'rural', 'urban', 'water']);
    expect(w.scene.features.water).toEqual({ v: 0.25, q: 0.6 });
  });

  it('名道（signature / panorama）が窓の3割以上なら highlight', () => {
    const blocks = timeline([[0, 1500, 'sea', { recommended_rank: 'signature' }], [1500, 9000, 'sea']]);
    expect(sceneFromScenicRoute({ position_m: 0, timeline: blocks, next_duration_ms: 240_000 }).scene.highlight).toBe(true);
    const few = timeline([[0, 500, 'sea', { recommended_rank: 'signature' }], [500, 9000, 'sea']]);
    expect(sceneFromScenicRoute({ position_m: 0, timeline: few, next_duration_ms: 240_000 }).scene.highlight).toBe(false);
    const minor = timeline([[0, 9000, 'sea', { recommended_rank: 'scenic' }]]);
    expect(sceneFromScenicRoute({ position_m: 0, timeline: minor }).scene.highlight).toBe(false);
  });

  it('時刻は今ではなく、窓の始まりの到着時刻の「時」（現地時刻）', () => {
    const w = sceneFromScenicRoute({
      position_m: 5000,
      timeline: timeline([[0, 4000, 'forest'], [4000, 9000, 'forest']], '2026-10-17T18:58:00+09:00'),
    });
    expect(w.scene.hour).toBe(19); // 4km 地点に 19:02 到着
  });

  it('海の見通し（確認済み）と開放感は、データがあるときだけ使う', () => {
    const w = sceneFromScenicRoute({
      position_m: 0,
      timeline: timeline([[0, 2000, 'sea', { visibility_verified: true, openness: 0.8 }], [2000, 9000, 'sea', { openness: null }]]),
      next_duration_ms: 240_000,
    });
    expect(w.scene.features.sea_visibility).toEqual({ v: 0.5, q: 1 });
    expect(w.scene.features.openness).toEqual({ v: 0.8, q: 0.5 });
    const none = sceneFromScenicRoute({ position_m: 0, timeline: timeline([[0, 9000, 'sea']]) });
    expect(none.scene.features.sea_visibility).toBeUndefined();
    expect(none.scene.features.openness).toBeUndefined();
  });

  it('次の曲の間に reveal（海が見え始める地点）があれば何秒後かを返す', () => {
    const w = sceneFromScenicRoute({
      position_m: 1000,
      timeline: timeline([[0, 2000, 'forest'], [2000, 9000, 'forest']]),
      reveals: [{ start_m: 2500 }, { start_m: 8000 }],
      next_duration_ms: 180_000,
    });
    expect(w.reveal).toEqual({ start_m: 2500, eta_s: 90 });
  });

  it('最後のブロックの中では、直前の2地点の到着時刻から速度を出す', () => {
    const w = sceneFromScenicRoute({
      position_m: 12000,
      timeline: timeline([[0, 4000, 'forest'], [4000, 20000, 'sea']]),
      next_duration_ms: 180_000,
    });
    expect(w.window.speed_kmh).toBeCloseTo(60, 3);
    expect(w.window.end_m).toBe(15000);
  });

  it('到着時刻が無ければ時速40kmとみなす', () => {
    const w = sceneFromScenicRoute({ position_m: 0, timeline: [{ start_m: 0, end_m: 9000, category: 'sea' }], next_duration_ms: 90_000 });
    expect(w.window.speed_kmh).toBe(40);
    expect(w.window.end_m).toBe(1000);
    expect(w.scene.hour).toBeUndefined();
  });
  it('湖・川・谷は水辺、市街地は街として、長さを足し合わせて特徴にする', () => {
    const w = sceneFromScenicRoute({
      position_m: 0,
      timeline: timeline([[0, 1000, 'river'], [1000, 1500, 'lake'], [1500, 2000, 'valley'], [2000, 3000, 'urban']]),
      next_duration_ms: 180_000,
    });
    expect(w.scene.features.water).toEqual({ v: 0.667, q: 1 });
    expect(w.scene.features.urban).toEqual({ v: 0.333, q: 1 });
    expect(w.shares).toMatchObject({ river: 0.333, lake: 0.167, valley: 0.167, urban: 0.333 });
  });
});
