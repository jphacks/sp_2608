// 実カタログ（catalog/catalog.jsonl）で、景観によって選ばれる曲が変わることを確かめる。
// engine.spec.ts は小さな疑似データでロジックを確かめる。こちらは「実際の曲の並びで、狙いどおりの方向に選曲が動くか」を見る。
// 比べるのは、選ばれた曲の各軸の「カタログ内の順位」（0〜1）の平均。エンジンは軸を順位に直して比べるので、生の値ではなく順位で見る。
// しきい値は、2026-11 のカタログ（2,347曲）で測った差の半分〜3分の2くらいにしてある。カタログの入れ替えで少し動いても落ちない。
import { describe, expect, it } from 'vitest';
import {
  AXES,
  EXPLORE,
  FEATURE_KEYS,
  computeTarget,
  rankDistance,
  rankPool,
  recommend,
  type Axis,
  type CabinState,
  type PoolTrack,
  type Scene,
} from '../src/engine';
import type { ModeId } from '../src/modes';
import catalogText from '../catalog/catalog.jsonl?raw';

const pool: PoolTrack[] = catalogText
  .trim()
  .split('\n')
  .map((l: string) => ({ ...JSON.parse(l), owners: [] }));

// 各曲の、カタログ全体での順位（0〜1）
const rank = new Map<string, Record<Axis, number>>();
for (const a of AXES) {
  [...pool]
    .sort((x, y) => x[a] - y[a])
    .forEach((t, i) => {
      if (!rank.has(t.track_id)) rank.set(t.track_id, {} as Record<Axis, number>);
      rank.get(t.track_id)![a] = i / (pool.length - 1);
    });
}

const SEEDS = 100;
const SCENES: Record<string, Scene> = {
  none: { features: {}, hour: 14 },
  sea: { features: { sea_proximity: 1, sea_visibility: 0.8, openness: 0.8 }, hour: 14 },
  forest: { features: { forest: 1 }, hour: 14 },
  rural: { features: { rural: 1 }, hour: 14 },
  urban: { features: { urban: 1 }, hour: 14 },
  water: { features: { water: 1 }, hour: 14 },
};

type Picks = { ids: Set<string>; mean: Record<Axis, number> };
function simulate(mode: ModeId, scene: Scene): Picks {
  const chosen: string[] = [];
  for (let seed = 1; seed <= SEEDS; seed++) {
    const r = recommend({ mode, pool, scene, members: [{ id: 'a', name: 'A' }], seed });
    chosen.push(r.chosen!.track_id);
  }
  const mean = Object.fromEntries(
    AXES.map((a) => [a, chosen.reduce((s, id) => s + rank.get(id)![a], 0) / chosen.length]),
  ) as Record<Axis, number>;
  return { ids: new Set(chosen), mean };
}

function jaccard(a: Set<string>, b: Set<string>): number {
  const inter = [...a].filter((x) => b.has(x)).length;
  return inter / (a.size + b.size - inter);
}

describe.each<ModeId>(['discover', 'blend'])('実カタログで景観ごとに選曲が変わる（%s）', (mode) => {
  const r = Object.fromEntries(Object.entries(SCENES).map(([k, s]) => [k, simulate(mode, s)])) as Record<
    keyof typeof SCENES,
    Picks
  >;
  // 景観なしからの差
  const diff = (scene: keyof typeof SCENES, a: Axis) => r[scene].mean[a] - r.none.mean[a];

  it('海沿い：明るく、勢いのある曲に寄る', () => {
    expect(diff('sea', 'valence')).toBeGreaterThan(0.2);
    expect(diff('sea', 'energy')).toBeGreaterThan(0.1);
  });

  it('森林：生音寄りで、落ち着いた曲に寄る', () => {
    expect(diff('forest', 'acoustic')).toBeGreaterThan(0.1);
    expect(diff('forest', 'energy')).toBeLessThan(-0.08);
  });

  it('市街地：電子音寄りで、開放感の少ない、勢いのある曲に寄る', () => {
    expect(diff('urban', 'acoustic')).toBeLessThan(-0.1);
    expect(diff('urban', 'spacious')).toBeLessThan(-0.1);
    expect(diff('urban', 'energy')).toBeGreaterThan(0.08);
  });

  it('湖・川沿い：開放感のある曲に寄る', () => {
    expect(diff('water', 'spacious')).toBeGreaterThan(0.08);
  });

  it('田園：少し明るい曲に寄る', () => {
    expect(diff('rural', 'valence')).toBeGreaterThan(0.03);
  });

  it('対照的な景観どうしでは、選ばれる曲がほとんど重ならない', () => {
    expect(jaccard(r.sea.ids, r.urban.ids)).toBeLessThan(0.1);
    expect(jaccard(r.sea.ids, r.forest.ids)).toBeLessThan(0.1);
    expect(jaccard(r.forest.ids, r.urban.ids)).toBeLessThan(0.1);
  });
});

describe('実カタログで、どの曲にも流れる場面がある', () => {
  // 景観8種の有無（256通り）× 時間帯 × 車内 × 運転負荷。名道区間・車内が静かなときは探索の枠を使わないので除く。
  // 曲ごとに「上限を満たし、狙いから EXPLORE.maxDistance 以内になる場面」が1つでもあれば、探索の枠で選ばれる確率が 0 でない
  it('全曲が、少なくとも1つの場面で探索の枠の対象になる', () => {
    const plans = new Map<string, ReturnType<typeof computeTarget>>();
    for (let m = 0; m < 1 << FEATURE_KEYS.length; m++) {
      const features = Object.fromEntries(FEATURE_KEYS.filter((_, i) => m & (1 << i)).map((k) => [k, 1]));
      for (const hour of [7, 14])
        for (const cabin of ['normal', 'lively', 'resting'] as CabinState[])
          for (const load of ['normal', 'high'] as const) {
            const p = computeTarget({ features, hour, load }, cabin);
            plans.set(JSON.stringify([p.target, p.weights, p.caps]), p);
          }
    }
    const unreached = new Set(pool.map((t) => t.track_id));
    // 順位は重みにしかよらないので、重みごとに1回だけ作る
    const ranked = new Map<string, ReturnType<typeof rankPool>>();
    for (const p of plans.values()) {
      const wk = JSON.stringify(p.weights);
      if (!ranked.has(wk)) ranked.set(wk, rankPool(pool, p.weights));
      const { rel, effW } = ranked.get(wk)!;
      const caps = Object.entries(p.caps) as [Axis, number][];
      for (const t of pool) {
        if (!unreached.has(t.track_id)) continue;
        if (caps.some(([a, c]) => t[a] > c)) continue;
        if (rankDistance(p.target, rel.get(t.track_id)!, effW) <= EXPLORE.maxDistance) unreached.delete(t.track_id);
      }
    }
    const names = pool.filter((t) => unreached.has(t.track_id)).map((t) => `${t.title} / ${t.artist}`);
    expect(names).toEqual([]);
  });
});
