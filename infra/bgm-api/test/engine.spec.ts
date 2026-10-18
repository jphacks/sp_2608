import { describe, it, expect } from 'vitest';
import { SAMPLE, computeTarget, recommend, type PoolTrack, type RecommendInput, type TrackFeatures } from '../src/engine';
import { MODE_PARAMS } from '../src/modes';
import tracks from './fixtures/tracks.json';
import sea from '../scenario-sea.json';
import forestNight from '../scenario-forest-night.json';
import cityLively from '../scenario-city-lively.json';

// 固定データ：ローカルD1で注釈した38曲（J-POP 30曲＋インスト・シティポップなど8曲）
const fixture = tracks as TrackFeatures[];

// /recommend の開発用モードと同じく、曲をメンバーに順番に割り当てる
function poolFor(members: { id: string }[], list: TrackFeatures[] = fixture): PoolTrack[] {
  return list.map((t, i) => ({ ...t, owners: [members[i % members.length].id] }));
}

// シナリオJSONを recommend の入力にする（履歴のアーティスト名は index.ts と同じく曲IDから補う）
// シナリオは全曲に所有者がいるプールなので、playlist モードで固定する
function scenarioInput(s: any): RecommendInput {
  return {
    mode: 'playlist',
    ...s,
    pool: poolFor(s.members),
    history: (s.history ?? []).map((h: any) => ({
      ...h,
      artist: fixture.find((t) => t.track_id === h.track_id)?.artist,
    })),
  };
}

function track(id: string, v: Partial<TrackFeatures>, owners = ['a']): PoolTrack {
  return {
    track_id: id,
    title: id,
    artist: `artist-${id}`,
    energy: 0.5,
    valence: 0.5,
    tempo: 0.5,
    acoustic: 0.5,
    spacious: 0.5,
    vocal: 0.5,
    conf: 0.9,
    ...v,
    owners,
  };
}

const ONE = [{ id: 'a', name: 'A' }];

describe('computeTarget', () => {
  it('海：valence と spacious が上がり、その2軸の重みが 1.5 になる', () => {
    const p = computeTarget(sea.scene as any, 'normal');
    expect(p.target.valence).toBeGreaterThan(0.7);
    expect(p.target.spacious).toBeGreaterThan(0.9);
    expect(p.weights).toEqual({ energy: 1, valence: 1.5, tempo: 0.5, acoustic: 1, spacious: 1.5, vocal: 1 });
    expect(p.caps).toEqual({});
    expect(p.volume).toBe(0.6);
    expect(p.reasons).toEqual(['海が近い', '視界が開けている']);
  });

  it('夜の森で休息中：acoustic が上がり、energy の上限は休息中の 0.4 になる', () => {
    const p = computeTarget(forestNight.scene as any, 'resting');
    expect(p.target.acoustic).toBeGreaterThan(0.8);
    expect(p.weights.acoustic).toBe(1.5);
    // カーブ（energy ≤ 0.65）と休息中（energy ≤ 0.4）のうち厳しいほう。vocal はソフトな上限
    expect(p.caps).toEqual({ energy: 0.4 });
    expect(p.softCaps).toEqual({ vocal: 0.6 });
    expect(p.target.energy).toBeLessThanOrEqual(0.4);
    expect(p.volume).toBe(0.4);
    // 盛り上がり・休息中は、車内の理由を先頭にする
    expect(p.reasons[0]).toBe('休んでいる人がいるかもしれない');
  });

  it('市街地で盛り上がり：操作負荷の上限がかかり、vocal と音量が下がる', () => {
    const p = computeTarget(cityLively.scene as any, 'lively');
    expect(p.caps).toEqual({ energy: 0.6 });
    expect(p.softCaps).toEqual({ vocal: 0.5 });
    expect(p.target.vocal).toBeCloseTo(0.3, 3);
    expect(p.target.spacious).toBeLessThan(0.4);
    expect(p.volume).toBe(0.35); // 負荷高で 0.5、盛り上がりで −0.15
  });

  it('ターゲットは上限・ソフトな上限を超えない', () => {
    const p = computeTarget({ features: { urban: 1, sea_proximity: 1 }, load: 'high', highlight: true }, 'normal');
    expect(p.target.energy).toBeLessThanOrEqual(0.6);
    expect(p.target.vocal).toBeLessThanOrEqual(0.5);
  });

  it('名道区間：カーブの energy 上限を 0.15 緩める。操作負荷が高いときは緩めない', () => {
    expect(computeTarget({ features: {}, curvy: true, highlight: true }).caps.energy).toBeCloseTo(0.8, 6);
    expect(computeTarget({ features: {}, curvy: true }).caps.energy).toBe(0.65);
    expect(computeTarget({ features: {}, load: 'high', highlight: true }).caps.energy).toBe(0.6);
  });

  it('信頼度 q = 0 の特徴は無視する', () => {
    const p = computeTarget({ features: { sea_proximity: { v: 1, q: 0 } } });
    expect(p.target).toEqual({ energy: 0.5, valence: 0.5, tempo: 0.5, acoustic: 0.5, spacious: 0.5, vocal: 0.5 });
    expect(p.reasons).toEqual([]);
  });

  it('時間帯：夜は energy −0.1、朝は tempo +0.05', () => {
    expect(computeTarget({ features: {}, hour: 21 }).target.energy).toBeCloseTo(0.4, 6);
    expect(computeTarget({ features: {}, hour: 7 }).target.tempo).toBeCloseTo(0.55, 6);
    expect(computeTarget({ features: {}, hour: 12 }).target).toMatchObject({ energy: 0.5, tempo: 0.5 });
  });
});

describe('recommend：3つのシナリオ（seed = 1 の結果を固定）', () => {
  it('海：上限なし。選んだ曲のあとに、点数の高い順で明るく開放的な曲が並ぶ', () => {
    const r = recommend(scenarioInput(sea));
    expect(r.cap_mode).toBe('none');
    // 先頭は選んだ曲、そのあとは点数の高い順の次点（再生できなかったときに使う）
    expect(r.candidates[0].title).toBe(r.chosen?.title);
    const best = ['幾億光年', 'きらり', 'タイムパラドックス', 'マリーゴールド', 'Plastic Love', 'Pretender'];
    expect(r.candidates.slice(1).map((c) => c.title)).toEqual(best.filter((t) => t !== r.chosen?.title).slice(0, 4));
    expect(r.chosen?.message).toMatch(/^海が近いので、.*開放感のある曲を流します$/);
  });

  it('夜の森で休息中：energy ≤ 0.4 を満たす曲だけが候補になる', () => {
    const r = recommend(scenarioInput(forestNight));
    expect(r.cap_mode).toBe('absolute');
    expect(r.fallback).toBeNull();
    expect(r.chosen?.title).toBe('Merry Christmas Mr. Lawrence');
    // 38曲のプールでは energy ≤ 0.4 の曲が2曲しかない（全曲カタログで解消する想定）
    expect(r.candidates.map((c) => c.title)).toEqual(['Merry Christmas Mr. Lawrence', '糸']);
    expect(r.candidates.every((c) => c.over_cap === 0)).toBe(true);
  });

  it('市街地で盛り上がり：歌のある曲も候補に残り、vocal のはみ出しは減点される。履歴の曲は出さない', () => {
    const r = recommend(scenarioInput(cityLively));
    expect(r.cap_mode).toBe('absolute');
    expect(r.chosen?.title).toBe('丸ノ内サディスティック');
    const titles = r.candidates.map((c) => c.title);
    expect(titles).not.toContain('アイドル');
    expect(titles).not.toContain('唱');
    const top = r.candidates[0];
    expect(top.over_soft).toBeCloseTo(0.35, 3); // vocal 0.85 − 0.5
    expect(top.score).toBeCloseTo(-top.distance - 0.3 * top.over_soft, 2);
  });

  it('同じシードなら結果が同じ。シードを変えると、最高点に近い曲の中で選ばれる曲が変わる', () => {
    const input = scenarioInput(sea);
    expect(recommend({ ...input, seed: 42 })).toEqual(recommend({ ...input, seed: 42 }));
    const picks = new Set<string>();
    for (let seed = 1; seed <= 30; seed++) {
      const r = recommend({ ...input, seed });
      picks.add(r.chosen!.title);
      const best = Math.max(...r.candidates.map((c) => c.score));
      expect(r.candidates[0].score).toBeGreaterThanOrEqual(best - SAMPLE.scoreWindow - 0.001);
    }
    expect(picks.size).toBeGreaterThan(2);
  });

  it('くじ引きの確率は、対象の曲全体で合計 1。候補にはその一部だけが載る', () => {
    for (const s of [sea, forestNight, cityLively]) {
      const r = recommend(scenarioInput(s));
      const sum = r.candidates.reduce((total, c) => total + c.prob, 0);
      expect(sum).toBeGreaterThan(0);
      expect(sum).toBeLessThanOrEqual(1.001);
      expect(r.sample_size).toBeLessThanOrEqual(SAMPLE.maxTracks);
      expect(r.candidates[0].prob).toBeGreaterThan(0);
    }
  });
});

describe('recommend：上限（安全制約）', () => {
  const scene = { features: {}, load: 'high' as const }; // energy ≤ 0.6（上限）、vocal ≤ 0.5（ソフト）

  it('上限を満たす曲が1曲でもあれば、それ以外は候補に入れない（5曲に足りなくても補わない）', () => {
    const pool = [
      track('ok1', { energy: 0.4 }),
      track('ok2', { energy: 0.5 }),
      // 距離では近いが上限を超える曲
      track('loud1', { energy: 0.61 }),
      track('loud2', { energy: 0.7 }),
      track('loud3', { energy: 0.8 }),
    ];
    for (let seed = 1; seed <= 20; seed++) {
      const r = recommend({ pool, scene, members: ONE, seed });
      expect(r.cap_mode).toBe('absolute');
      expect(r.candidates.map((c) => c.title).sort()).toEqual(['ok1', 'ok2']);
      expect(r.chosen!.track_id).toMatch(/^ok/);
    }
  });

  it('上限を満たす曲が1曲もないときだけ、はみ出しが小さい順に補い、はみ出し分を減点する', () => {
    const pool = [
      track('over-small', { energy: 0.65 }), // 0.05
      track('over-mid', { energy: 0.8 }), // 0.2
      track('over-big', { energy: 1 }), // 0.4
    ];
    const r = recommend({ mode: 'playlist', pool, scene, members: ONE, seed: 1 });
    expect(r.cap_mode).toBe('nearest');
    const byId = Object.fromEntries(r.candidates.map((c) => [c.title, c]));
    expect(byId['over-small'].over_cap).toBeCloseTo(0.05, 3);
    expect(byId['over-mid'].over_cap).toBeCloseTo(0.2, 3);
    expect(byId['over-big'].over_cap).toBeCloseTo(0.4, 3);
    // λ_cap = 1.0：スコア = −距離 − はみ出し
    for (const c of r.candidates) expect(c.score).toBeCloseTo(-c.distance - c.over_cap, 2);
  });

  it('vocal はソフトな上限：超える曲も候補に残り、はみ出し × 0.3 だけ減点する', () => {
    const pool = [track('inst', { vocal: 0 }), track('vocal', { vocal: 0.9 })];
    const r = recommend({ pool, scene, members: ONE, seed: 1 });
    const byId = Object.fromEntries(r.candidates.map((c) => [c.title, c]));
    expect(Object.keys(byId).sort()).toEqual(['inst', 'vocal']);
    expect(byId.vocal.over_soft).toBeCloseTo(0.4, 3);
    expect(byId.vocal.over_cap).toBe(0);
    expect(byId.inst.over_soft).toBe(0);
  });

  it('上限がない場面では cap_mode = none で、はみ出しは 0', () => {
    const r = recommend({ pool: poolFor(ONE), scene: { features: {} }, members: ONE, seed: 1 });
    expect(r.cap_mode).toBe('none');
    expect(r.candidates.every((c) => c.over_cap === 0 && c.over_soft === 0)).toBe(true);
  });
});

describe('recommend：ターゲットに近い曲が無いとき（直前の曲調に寄せる）', () => {
  // energy と acoustic が一緒に上がる曲ばかりのプール。
  // 夜の森で休息中のターゲット（energy 低・acoustic 高）には、どの曲も遠い
  const pool = [0.1, 0.15, 0.2, 0.25, 0.3, 0.35].map((x, i) => track(`t${i}`, { energy: x, acoustic: x }));
  const base = { pool, scene: forestNight.scene as any, cabin: 'resting' as const, members: ONE, seed: 1 };

  it('距離が 0.35 を超えたら、直前の曲に近い曲を選び、音量を 0.1 下げる', () => {
    const r = recommend({ ...base, history: [{ track_id: 't0' }] });
    expect(r.fallback).toMatchObject({ ref: 'previous' });
    expect(r.fallback!.min_distance).toBeGreaterThan(0.35);
    expect(r.candidates[0].title).toBe('t1'); // 直前の t0 に一番近い
    expect(r.chosen?.title).toBe('t1');
    expect(r.volume).toBeCloseTo(r.plan.volume - 0.1, 6);
    expect(r.chosen?.message).toContain('音量を少し下げて');
  });

  it('直前の曲が無ければ、ターゲットを基準にしたまま音量だけ下げる', () => {
    const r = recommend({ ...base, history: [] });
    expect(r.fallback).toMatchObject({ ref: 'target' });
    expect(r.volume).toBeCloseTo(r.plan.volume - 0.1, 6);
  });

  it('近い曲があれば fallback しない', () => {
    const r = recommend(scenarioInput(sea));
    expect(r.fallback).toBeNull();
    expect(r.volume).toBe(r.plan.volume);
  });
});

describe('recommend：スコアの補正項', () => {
  const members = [
    { id: 'a', name: 'A' },
    { id: 'b', name: 'B' },
  ];
  const scene = { features: {} };

  it('このドライブで流した曲は候補から外す。全部流したら chosen = null', () => {
    const pool = [track('x', {}), track('y', {})];
    const r = recommend({ pool, scene, members, history: [{ track_id: 'x' }], seed: 1 });
    expect(r.candidates.map((c) => c.title)).toEqual(['y']);
    const empty = recommend({ pool, scene, members, history: [{ track_id: 'x' }, { track_id: 'y' }], seed: 1 });
    expect(empty.chosen).toBeNull();
  });

  it('直近3曲と同じアーティスト・前回のドライブで流した曲・低信頼度を減点する', () => {
    const pool = [
      track('base', {}),
      { ...track('same', {}), artist: 'X' },
      track('recent', {}),
      track('lowconf', { conf: 0.3 }),
    ];
    const r = recommend({
      pool,
      scene,
      members: ONE,
      history: [{ track_id: 'played', artist: 'X' }],
      recent_track_ids: ['recent'],
      seed: 1,
    });
    const s = Object.fromEntries(r.candidates.map((c) => [c.title, c]));
    // 全曲が同じ特徴量なので距離は等しく、差は補正項だけ
    expect(s.base.score - s.same.score).toBeCloseTo(0.15, 3);
    expect(s.base.score - s.recent.score).toBeCloseTo(0.3, 3);
    expect(s.base.score - s.lowconf.score).toBeCloseTo(0.2 * 0.3, 3);
    expect(r.chosen?.title).toBe('base');
  });

  it('再生が多い所有者の曲は減点し、複数人のリストにある曲は加点する', () => {
    const pool = [track('of-a', {}, ['a']), track('of-b', {}, ['b']), track('shared', {}, ['a', 'b'])];
    const history = [
      { track_id: 'h1', owner_id: 'a' },
      { track_id: 'h2', owner_id: 'a' },
      { track_id: 'h3', owner_id: 'a' },
      { track_id: 'h4', owner_id: 'b' },
    ];
    const r = recommend({ pool, scene, members, history, seed: 1 });
    const s = Object.fromEntries(r.candidates.map((c) => [c.title, c]));
    expect(s['of-a'].owner_bias).toBeCloseTo(0.25, 3); // 3/4 − 1/2
    expect(s['of-b'].owner_bias).toBe(0);
    expect(s['shared'].shared).toBe(1);
    expect(r.candidates[0].title).toBe('shared');
    // 演出の一言には、直近の再生が少ない所有者の名前を出す
    if (r.chosen?.title === 'shared') expect(r.chosen.owner_id).toBe('b');
  });
});

describe('recommend：所有者がいない曲（全曲カタログ）', () => {
  const members = [
    { id: 'a', name: 'A' },
    { id: 'b', name: 'B' },
    { id: 'c', name: 'C' },
  ];

  it('所有者に関する項は 0 になり、演出の一言に名前を出さない', () => {
    const pool = fixture.map(({ ...t }) => t as PoolTrack); // owners なし
    const r = recommend({
      pool,
      scene: sea.scene as any,
      members,
      history: [{ track_id: 'x', owner_id: 'a' }],
      seed: 1,
    });
    expect(r.candidates.every((c) => c.owner_bias === 0 && c.shared === 0)).toBe(true);
    expect(r.chosen?.owner_id).toBeNull();
    expect(r.chosen?.message).toBe('海が近いので、開放感のある曲を流します');
  });

  it('owners が空配列でも同じ扱い（複数人のリストにある曲の項がマイナスにならない）', () => {
    const pool = [track('none', {}, []), track('one', {}, ['a'])];
    const r = recommend({ mode: 'blend', pool, scene: { features: {} }, members, seed: 1 });
    const byId = Object.fromEntries(r.candidates.map((c) => [c.title, c]));
    expect(byId.none.shared).toBe(0);
    expect(byId.one.shared).toBe(0);
    // 差はプレイリストの加点だけ
    expect(byId.one.score - byId.none.score).toBeCloseTo(MODE_PARAMS.blend.prefTrack, 6);
  });
});

describe('recommend：プール内の順位への変換', () => {
  it('J-POPだけのプールでは vocal の幅が狭いので重みが下がる', () => {
    const jpop = fixture.slice(0, 30);
    const r = recommend({ pool: poolFor(ONE, jpop), scene: { features: {} }, members: ONE, seed: 1 });
    expect(r.pool_ranges!.vocal).toBeCloseTo(0.13, 3);
    expect(r.effective_weights!.vocal).toBeCloseTo(0.65, 3);
    expect(r.effective_weights!.energy).toBe(1);
  });
});

describe('recommend：曲調の急変を抑える（previous_target）', () => {
  it('前の曲のターゲットから軸ごとに 0.25 までしか動かさない', () => {
    const scene = { features: { sea_proximity: 1, openness: 1 } }; // spacious が大きく上がる場面
    const free = recommend({ pool: poolFor(ONE), scene, members: ONE, seed: 1 });
    expect(free.plan.target.spacious).toBeGreaterThan(0.8);
    const prev = { energy: 0.5, valence: 0.5, tempo: 0.5, acoustic: 0.5, spacious: 0.4, vocal: 0.5 };
    const r = recommend({ pool: poolFor(ONE), scene, members: ONE, seed: 1, previous_target: prev });
    expect(r.plan.target.spacious).toBeCloseTo(0.65, 6);
    expect(r.plan.smoothed).toContain('spacious');
  });

  it('上限（安全制約）は、抑えたあとにもう一度かける', () => {
    const prev = { energy: 0.9 };
    const r = recommend({ pool: poolFor(ONE), scene: { features: {}, load: 'high' }, members: ONE, seed: 1, previous_target: prev });
    expect(r.plan.target.energy).toBeLessThanOrEqual(0.6);
  });
});

