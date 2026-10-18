import { describe, it, expect } from 'vitest';
import { recommend, type HistoryEntry, type PoolTrack } from '../src/engine';
import { DEFAULT_MODE, MODE_IDS, MODE_LABELS, MODE_PARAMS, isModeId, type ModeId } from '../src/modes';
import { segmentRoute, type RoutePoint } from '../src/segment';
import sampleRoute from '../routes/sample-coast.json';

const members = [
  { id: 'a', name: 'A' },
  { id: 'b', name: 'B' },
  { id: 'c', name: 'C' },
];

function track(id: string, v: Partial<PoolTrack> = {}): PoolTrack {
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
    owners: [],
    ...v,
  };
}

// 特徴量が全曲同じプール。スコアの差は補正項だけになる
const flatPool = [
  track('mine', { owners: ['a'], artist: 'X' }),
  track('same-artist', { artist: 'x ' }), // 表記ゆれ（大文字・空白）は同じアーティストとみなす
  track('other', {}),
  track('famous', { popularity: 1 }),
];
const flatScene = { features: {} };

function scores(mode: ModeId, opts: { cabin?: 'quiet'; history?: HistoryEntry[] } = {}) {
  const r = recommend({ mode, pool: flatPool, scene: flatScene, members, seed: 1, ...opts });
  return { r, s: Object.fromEntries(r.candidates.map((c) => [c.title, c])) };
}

describe('モードの定義', () => {
  it('すべてのモードに表示名・短縮形・補足と定数がある', () => {
    for (const id of MODE_IDS) {
      expect(MODE_LABELS[id].label).not.toBe('');
      expect(MODE_LABELS[id].shortLabel).not.toBe('');
      expect(MODE_LABELS[id].description).not.toBe('');
      expect(MODE_PARAMS[id]).toBeDefined();
    }
    expect(MODE_LABELS.blend.shortLabel).toBe('すべての曲から＋好み');
    expect(isModeId('blend')).toBe(true);
    expect(isModeId('all')).toBe(false);
    expect(DEFAULT_MODE).toBe('blend');
  });

  it('知名度の加点は blend > discover > playlist = 0 の順', () => {
    expect(MODE_PARAMS.blend.popularity).toBeGreaterThan(MODE_PARAMS.discover.popularity);
    expect(MODE_PARAMS.discover.popularity).toBeGreaterThan(0);
    expect(MODE_PARAMS.playlist.popularity).toBe(0);
  });
});

describe('discover：すべての曲から', () => {
  it('所有者の情報を完全に無視する（偏り・共有・プレイリストの加点がすべて 0、名前も出さない）', () => {
    const { r, s } = scores('discover', { history: [{ track_id: 'h', owner_id: 'a' }] });
    expect(r.candidates).toHaveLength(4);
    for (const c of r.candidates) {
      expect(c.owners).toEqual([]);
      expect(c.owner_bias).toBe(0);
      expect(c.shared).toBe(0);
      expect(c.pref).toBeNull();
    }
    expect(s.mine.score).toBeCloseTo(s.other.score, 6);
    expect(r.chosen?.owner_id).toBeNull();
  });

  it('知名度は弱めに加点する', () => {
    const { s } = scores('discover');
    expect(s.famous.score - s.other.score).toBeCloseTo(MODE_PARAMS.discover.popularity, 6);
  });
});

describe('blend：すべての曲から（好みを反映）', () => {
  it('リストにある曲を大きく、同じアーティストの曲を少し加点する', () => {
    const { s } = scores('blend');
    expect(s.mine.pref).toBe('track');
    expect(s['same-artist'].pref).toBe('artist');
    expect(s.other.pref).toBeNull();
    expect(s.mine.score - s.other.score).toBeCloseTo(MODE_PARAMS.blend.prefTrack, 6);
    expect(s['same-artist'].score - s.other.score).toBeCloseTo(MODE_PARAMS.blend.prefArtist, 6);
    expect(MODE_PARAMS.blend.prefTrack).toBeGreaterThan(MODE_PARAMS.blend.prefArtist);
  });

  it('知名度は標準の強さで加点し、静かなときは強める', () => {
    expect(scores('blend').s.famous.score - scores('blend').s.other.score).toBeCloseTo(
      MODE_PARAMS.blend.popularity,
      6,
    );
    const q = scores('blend', { cabin: 'quiet' }).s;
    expect(q.famous.score - q.other.score).toBeCloseTo(MODE_PARAMS.blend.popularity * 2.5, 6);
  });

  it('所有者の偏りの減点も効く', () => {
    const history = ['h1', 'h2', 'h3'].map((id) => ({ track_id: id, owner_id: 'a' }));
    const { s } = scores('blend', { history });
    expect(s.mine.owner_bias).toBeCloseTo(1 - 1 / 3, 3);
    expect(s.mine.score - s.other.score).toBeCloseTo(
      MODE_PARAMS.blend.prefTrack - MODE_PARAMS.blend.owner * (2 / 3),
      2,
    );
  });

  it('リストにある曲を選んだときは名前を出し、同じアーティストの曲ならそう伝える', () => {
    const pool = [track('mine', { owners: ['b'] })];
    expect(recommend({ mode: 'blend', pool, scene: flatScene, members, seed: 1 }).chosen?.message).toBe(
      'Bさんのリストから今の雰囲気に合う曲を流します',
    );
    const pool2 = [track('liked', { owners: ['b'], artist: 'Y' }), track('cousin', { artist: 'Y' })];
    const r = recommend({ mode: 'blend', pool: pool2, scene: flatScene, members, history: [{ track_id: 'liked' }], seed: 1 });
    expect(r.chosen?.message).toBe('みんなのリストにあるアーティストの、今の雰囲気に合う曲を流します');
  });
});

describe('playlist：みんなのプレイリストから', () => {
  it('誰かのリストにある曲だけを候補にし、知名度は使わない', () => {
    const { r } = scores('playlist');
    expect(r.candidates.map((c) => c.title)).toEqual(['mine']);
    const pool = [track('p1', { owners: ['a'] }), track('p2', { owners: ['a'], popularity: 1 })];
    const r2 = recommend({ mode: 'playlist', pool, scene: flatScene, members, seed: 1 });
    const s = Object.fromEntries(r2.candidates.map((c) => [c.title, c]));
    expect(s.p1.score).toBeCloseTo(s.p2.score, 6);
  });

  describe('リストの曲が上限を1曲も満たさないとき', () => {
    const scene = { features: {}, load: 'high' as const }; // energy ≤ 0.6
    const owned = [track('loud1', { owners: ['a'], energy: 0.9 }), track('loud2', { owners: ['b'], energy: 0.8 })];

    it('カタログのインスト曲（上限を満たし、vocal ≤ 0.2）から補う', () => {
      const pool = [
        ...owned,
        track('inst', { energy: 0.4, vocal: 0 }),
        track('calm-vocal', { energy: 0.4, vocal: 0.8 }), // 歌ものは補わない
        track('loud-inst', { energy: 0.9, vocal: 0 }), // 上限を超えるインストも補わない
      ];
      const r = recommend({ mode: 'playlist', pool, scene, members, seed: 1 });
      expect(r.cap_mode).toBe('catalog_fill');
      expect(r.candidates.map((c) => c.title)).toEqual(['inst']);
      expect(r.chosen?.owner_id).toBeNull();
      expect(r.chosen?.message).toBe('運転に集中しやすいインスト曲を流します');
    });

    it('補えるインスト曲もなければ、今までどおり、はみ出しが小さい順に補う', () => {
      const r = recommend({ mode: 'playlist', pool: [...owned, track('pop', { energy: 0.4, vocal: 0.8 })], scene, members, seed: 1 });
      expect(r.cap_mode).toBe('nearest');
      expect(r.candidates.map((c) => c.title)).toEqual(['loud2', 'loud1']);
    });

    it('リストに上限を満たす曲が1曲でもあれば、カタログからは補わない', () => {
      const pool = [...owned, track('ok', { owners: ['c'], energy: 0.5 }), track('inst', { energy: 0.4, vocal: 0 })];
      const r = recommend({ mode: 'playlist', pool, scene, members, seed: 1 });
      expect(r.cap_mode).toBe('absolute');
      expect(r.candidates.map((c) => c.title)).toEqual(['ok']);
    });
  });

  it('リストの曲が1曲もなければ chosen = null', () => {
    const r = recommend({ mode: 'playlist', pool: [track('x')], scene: flatScene, members, seed: 1 });
    expect(r.chosen).toBeNull();
  });
});

describe('blend：加点を受けていない曲の割合（係数の調整）', () => {
  // 疑似カタログ：600曲・300アーティスト。12% が誰かのリストにある曲、12% がインスト
  function catalog(seed: number): PoolTrack[] {
    const r = rng(seed);
    return Array.from({ length: 600 }, (_, i) => {
      const inst = r() < 0.12;
      const owned = r() < 0.12;
      return track(`t${i}`, {
        artist: `ar${Math.floor(r() * 300)}`,
        energy: r(),
        valence: r(),
        tempo: r(),
        acoustic: r(),
        spacious: r(),
        vocal: inst ? 0 : 0.75 + r() * 0.2,
        popularity: r(),
        owners: owned ? [members[Math.floor(r() * 3)].id] : [],
      });
    });
  }

  it('サンプルルートを30回走って、加点なしの曲がおよそ5曲に1曲選ばれる', () => {
    const segs = segmentRoute(sampleRoute.points as RoutePoint[], { start_hour: sampleRoute.start_hour });
    const cabins = ['lively', 'normal', 'quiet', 'normal', 'resting', 'normal', 'lively'] as const;
    let total = 0;
    let none = 0;
    for (let d = 0; d < 30; d++) {
      const pool = catalog(1000 + d);
      const history: HistoryEntry[] = [];
      segs.forEach((seg, si) => {
        for (let k = 0; k < 2; k++) {
          const r = recommend({
            mode: 'blend',
            pool,
            scene: seg.scene,
            cabin: cabins[si],
            members,
            history,
            seed: d * 100 + si * 2 + k,
          });
          const c = r.candidates.find((x) => x.title === r.chosen!.title)!;
          total++;
          if (c.pref === null) none++;
          history.push({ track_id: r.chosen!.track_id, artist: r.chosen!.artist, owner_id: r.chosen!.owner_id ?? undefined });
        }
      });
    }
    const ratio = none / total;
    expect(ratio).toBeGreaterThan(0.12);
    expect(ratio).toBeLessThan(0.3);
  });
});

function rng(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
