// 推薦ロジック本体。DBやHTTPに依存しない純粋関数だけで書く（後で端末側に移植できるように）

import { DEFAULT_MODE, MODE_PARAMS, type ModeId } from './modes';

export const AXES = ['energy', 'valence', 'tempo', 'acoustic', 'spacious', 'vocal'] as const;
export type Axis = (typeof AXES)[number];
export type Vec = Record<Axis, number>;

export const FEATURE_KEYS = [
  'sea_proximity',
  'sea_visibility',
  'openness',
  'forest',
  'nature',
  'rural',
  'urban',
  'water',
] as const;
export type FeatureKey = (typeof FEATURE_KEYS)[number];

// v: 特徴量 0〜1、q: 信頼度 0〜1（省略時 1、不明なら 0）
export type FeatureValue = { v: number; q?: number } | number;

export type Scene = {
  features: Partial<Record<FeatureKey, FeatureValue>>;
  load?: 'normal' | 'high'; // 操作負荷
  curvy?: boolean; // 曲率が高い・急勾配
  highlight?: boolean; // 名道区間
  hour?: number; // 0〜23
};

export type CabinState = 'lively' | 'normal' | 'quiet' | 'resting';

// 歌詞の題材。カタログの曲は Gemini が歌詞の内容から付けたもの（catalog/themes.jsonl）
export const THEMES = [
  'sea', 'summer', 'night', 'city', 'drive_travel', 'nature', 'sky_wind', 'river_lake', 'hometown', 'morning', 'rain',
] as const;
export type Theme = (typeof THEMES)[number];

export type TrackFeatures = Vec & {
  track_id: string;
  title: string;
  artist: string;
  conf: number;
  duration_ms?: number | null;
  popularity?: number; // 知名度 0〜1。無い曲は 0（知名度の加点なし）として扱う
  // 安全のための上限（caps）の判定にだけ使う energy。カタログの曲は LLM と試聴の音のうち高いほう。無ければ energy
  energy_cap?: number;
  // 歌詞の題材 → 確かさ（0〜1）。曲調の近さとは別枠で、場面に合う題材の曲を加点する
  themes?: Partial<Record<Theme, number>>;
};
// owners が空（または無い）曲は、所有者に関する項がすべて 0 になる（全曲カタログの曲など）
export type PoolTrack = TrackFeatures & { owners?: string[] };
export type HistoryEntry = { track_id: string; artist?: string; owner_id?: string };
export type Member = { id: string; name: string };

export type RecommendInput = {
  mode?: ModeId; // 省略時は DEFAULT_MODE
  pool: PoolTrack[];
  scene: Scene;
  cabin?: CabinState;
  members: Member[];
  history?: HistoryEntry[]; // このドライブで流した曲（古い順）
  recent_track_ids?: string[]; // 前回までのドライブで流した曲
  top_k?: number;
  seed?: number;
  // 前の曲を選んだときのターゲット（前回の応答の plan.target）。曲調が急に飛ばないよう、差を抑える
  previous_target?: Partial<Vec>;
};

// 景観特徴が各軸を動かす量（設計書の初期値）
const EFFECTS: Record<FeatureKey, Partial<Vec>> = {
  // 海は「明るく爽快な曲」を狙う。spacious は注釈上 energy と強く逆向き（順位相関 −0.86）なので、
  // 海で spacious を上げると落ち着いた曲に寄ってしまう。開放感は openness に任せ、海では energy と valence を上げる
  sea_proximity: { energy: 0.2, valence: 0.2, tempo: 0.05, acoustic: -0.05 },
  sea_visibility: { energy: 0.1, valence: 0.15, spacious: 0.05 },
  openness: { valence: 0.05, spacious: 0.25 },
  forest: { energy: -0.1, tempo: -0.05, acoustic: 0.25, spacious: -0.05, vocal: -0.05 },
  nature: { energy: -0.05, acoustic: 0.15, spacious: 0.05 },
  rural: { valence: 0.1, tempo: -0.05, acoustic: 0.1, spacious: 0.05 },
  urban: { energy: 0.1, tempo: 0.05, acoustic: -0.15, spacious: -0.2 },
  water: { energy: -0.1, tempo: -0.05, acoustic: 0.05, spacious: 0.2, vocal: -0.05 },
};

// 景観 → 合う歌詞の題材（値は結び付きの強さ）。曲調が遠くても、題材が合う曲（遅いテンポの海の歌など）を候補に入れるため
const THEME_LINKS: Record<FeatureKey, Partial<Record<Theme, number>>> = {
  sea_proximity: { sea: 1, summer: 0.5 },
  sea_visibility: { sea: 1 },
  openness: { sky_wind: 1 },
  forest: { nature: 1 },
  nature: { nature: 1, sky_wind: 0.5 },
  rural: { hometown: 1, nature: 0.5 },
  urban: { city: 1 },
  water: { river_lake: 1 },
};
// 車で走っているので、ドライブ・旅の歌はいつも少しだけ合う
const THEME_ALWAYS: Partial<Record<Theme, number>> = { drive_travel: 0.5 };
// 題材が合う曲の加点（題材の一致度 1 あたり）。知名度の加点（blend で最大 0.1）と同じくらいにする
const THEME_BONUS = 0.05;
// 題材の枠。4曲ごとの2曲目（history の件数を4で割った余りが turn）は、場面の題材を歌った曲（一致度 minMatch 以上）から、
// 狙いから maxDistance 以内なら曲調が遠くても選ぶ。曲調の近さより題材を優先する枠なので、温度 tau は通常より高い
// distanceRelief：この枠では曲調の遠さの減点をこの割合だけ戻す（0.5 なら距離の効きが半分）
export const THEME_SLOT = { turn: 1, minMatch: 0.5, maxDistance: 0.6, tau: 0.2, distanceRelief: 0.5 };
const THEME_LABEL: Record<Theme, string> = {
  sea: '海', summer: '夏', night: '夜', city: '街', drive_travel: 'ドライブや旅', nature: '自然', sky_wind: '空や風',
  river_lake: '川や湖', hometown: '故郷や帰り道', morning: '朝', rain: '雨',
};

const FEATURE_LABEL: Record<FeatureKey, string> = {
  sea_proximity: '海が近い',
  sea_visibility: '海が見えてきた',
  openness: '視界が開けている',
  forest: '森の中を走っている',
  nature: '自然が多い',
  rural: '田園が広がっている',
  urban: '街なかを走っている',
  water: '湖や川沿いを走っている',
};

const CABIN_LABEL: Record<CabinState, string | null> = {
  lively: '会話が盛り上がっている',
  normal: null,
  quiet: '車内が静かになってきた',
  resting: '休んでいる人がいるかもしれない',
};

// モードによらない係数。soft: ソフトな上限（vocal）のはみ出し1あたりの減点
// モードで変わる係数（知名度・共有・所有者の偏り・プレイリストの加点）は modes.ts の MODE_PARAMS
const LAMBDA = { rep: 0.3, artist: 0.15, conf: 0.2, cap: 1.0, soft: 0.3 };
const FAM_BOOST = 2.5; // 静か・名道区間で「みんな知ってる曲」の加点を強める倍率
// くじ引きの対象。同じ景色で毎回同じ数曲にならないよう、最高点から scoreWindow 以内の曲を最大 maxTracks 曲まで広げる。
// 点数が高いほど選ばれやすいことは変えない（tau が温度）。返す candidates（再生できなかったときの次点）とは別。
// 30曲・0.1 では、どの場面でもこの範囲に入らない曲が多かった（2026-11 のカタログで、通常の枠に入るのは670曲）。
// 60曲・0.12 で923曲に増え、選ばれる曲の狙いからの平均距離は 0.218 → 0.226 にとどまる。
// 0.15 まで広げると、blend で好みの加点がない曲の割合が5曲に1曲の狙いを超えた（test/modes.spec.ts）
export const SAMPLE = { maxTracks: 60, scoreWindow: 0.12, tau: 0.06 };
const MIN_RANGE = 0.2; // プール内の幅がこれ未満の軸は重みを下げる
// 枠の周期。history の件数をこれで割った余りが THEME_SLOT.turn の回が題材の枠、それ以外は通常の枠。
// 景観に合う曲を流すことに振り切るので、景観と関係なく曲を選ぶ枠（全曲に順番を回す探索の枠、有名曲の枠）は置かない。
// そのため、どの場面の狙いからも遠い曲調の曲（とても激しい曲など）は流れない
const SLOT_CYCLE = 4;

// 上限を満たす曲の中で一番近い曲でも、ターゲットとの距離がこれを超えたら、
// 無理に曲調を寄せず、直前の曲調に近い曲を選んで音量だけを下げる
const FALLBACK = { maxDistance: 0.35, volumeDrop: 0.1 };
// playlist モードでリストの曲が上限を1曲も満たさないとき、カタログから補うインスト曲の vocal の上限
const INST_MAX_VOCAL = 0.2;
const MIN_VOLUME = 0.2;
// 前の曲のターゲットからの変化の上限（軸ごと）。景観が急に変わっても曲調が飛びすぎないようにする
const MAX_TARGET_STEP = 0.25;

export type Plan = {
  target: Vec;
  weights: Vec;
  caps: Partial<Vec>; // 上限（安全制約）。超える曲は、満たす曲が1曲もないときしか候補にしない
  softCaps: Partial<Vec>; // ソフトな上限。超えた分だけ減点する
  volume: number;
  famBoost: number; // 知名度・共有の加点に掛ける倍率
  themes: Partial<Record<Theme, number>>; // 今の場面に合う歌詞の題材（0〜1）
  themeReasons: Partial<Record<Theme, string>>; // その題材を選んだ理由（「視界が開けている」など。理由の一言に使う）
  reasons: string[];
};

// 景観＋車内状態 → 音楽ターゲット
export function computeTarget(scene: Scene, cabin: CabinState = 'normal'): Plan {
  const t: Vec = { energy: 0.5, valence: 0.5, tempo: 0.5, acoustic: 0.5, spacious: 0.5, vocal: 0.5 };
  const w: Vec = { energy: 1, valence: 1, tempo: 0.5, acoustic: 1, spacious: 1, vocal: 1 };
  const caps: Partial<Vec> = {};
  const softCaps: Partial<Vec> = {};
  let volume = 0.6;
  let famBoost = 1;

  const contribs: { key: FeatureKey; strength: number }[] = [];
  const themes: Partial<Record<Theme, number>> = { ...THEME_ALWAYS };
  const themeReasons: Partial<Record<Theme, string>> = {};
  for (const key of FEATURE_KEYS) {
    const raw = scene.features?.[key];
    if (raw == null) continue;
    const v = typeof raw === 'number' ? raw : raw.v;
    const q = typeof raw === 'number' ? 1 : (raw.q ?? 1);
    const s = clamp01(v) * clamp01(q);
    if (s === 0) continue;
    for (const a of AXES) t[a] += s * (EFFECTS[key][a] ?? 0);
    for (const [th, k] of Object.entries(THEME_LINKS[key]) as [Theme, number][]) {
      if (s * k > (themes[th] ?? 0)) {
        themes[th] = s * k;
        themeReasons[th] = FEATURE_LABEL[key];
      }
    }
    contribs.push({ key, strength: s });
  }
  contribs.sort((x, y) => y.strength - x.strength);

  // 一番強い特徴が動かす上位2軸の重みを上げる
  const top = contribs[0];
  if (top && top.strength >= 0.3) {
    const eff = EFFECTS[top.key];
    const axes = AXES.filter((a) => (eff[a] ?? 0) !== 0)
      .sort((a, b) => Math.abs(eff[b] ?? 0) - Math.abs(eff[a] ?? 0))
      .slice(0, 2);
    for (const a of axes) w[a] = a === 'tempo' ? 0.75 : 1.5;
  }

  // 夜は energy を下げない。運転中に眠気を誘う方向へ寄せたくないため（夜は休息中・車内が静かのほうで扱う）
  if (scene.hour != null && scene.hour >= 4 && scene.hour < 9) {
    t.tempo += 0.05;
    themes.morning = 1;
    themeReasons.morning = '朝の時間帯な';
  }
  // 夜は曲調を変えず、夜を歌った曲を加点するだけにする
  if (scene.hour != null && (scene.hour >= 19 || scene.hour < 4)) {
    themes.night = 1;
    themeReasons.night = '夜になった';
  }

  if (scene.highlight) {
    t.energy += 0.1;
    famBoost = FAM_BOOST;
  }
  if (scene.load === 'high') {
    capMin(caps, 'energy', 0.6);
    // vocal は上限にすると、J-POP中心の場ではインスト曲しか残らないので、減点だけにする
    capMin(softCaps, 'vocal', 0.5);
    volume = Math.min(volume, 0.5);
  }
  if (scene.curvy) {
    capMin(caps, 'energy', 0.65);
    capMin(softCaps, 'vocal', 0.6);
  }
  // 名道区間では、操作負荷が高くなければ energy の上限を緩める
  if (scene.highlight && scene.load !== 'high' && caps.energy != null) {
    caps.energy = Math.min(1, caps.energy + 0.15);
  }

  switch (cabin) {
    case 'lively':
      t.vocal -= 0.2;
      volume -= 0.15;
      break;
    case 'quiet':
      t.valence += 0.05;
      famBoost = FAM_BOOST;
      break;
    case 'resting':
      t.energy -= 0.2;
      t.tempo -= 0.1;
      t.vocal -= 0.2;
      capMin(caps, 'energy', 0.4);
      volume -= 0.2;
      break;
  }

  for (const a of AXES) {
    t[a] = clamp01(t[a]);
    const cap = Math.min(caps[a] ?? 1, softCaps[a] ?? 1);
    t[a] = Math.min(t[a], cap);
  }

  const cabinReason = CABIN_LABEL[cabin];
  const sceneReasons = contribs.slice(0, 2).map((c) => FEATURE_LABEL[c.key]);
  const reasons =
    cabin === 'lively' || cabin === 'resting'
      ? [cabinReason!, ...sceneReasons]
      : [...sceneReasons, ...(cabinReason ? [cabinReason] : [])];

  return {
    target: roundVec(t),
    weights: w,
    caps,
    softCaps,
    volume: round(Math.min(1, Math.max(MIN_VOLUME, volume))),
    famBoost,
    themes,
    themeReasons,
    reasons,
  };
}

export function recommend(input: RecommendInput) {
  const cabin = input.cabin ?? 'normal';
  const history = input.history ?? [];
  const members = input.members;
  const N = Math.max(1, members.length);
  const plan = smoothTarget(computeTarget(input.scene, cabin), input.previous_target);
  const mode = input.mode ?? DEFAULT_MODE;
  const M = MODE_PARAMS[mode];

  // discover では所有者の情報を完全に無視する
  const ownersOf = (t: PoolTrack) => (M.useOwners ? (t.owners ?? []) : []);
  // blend の加点に使う「誰かのリストにある曲のアーティスト」
  const likedArtists = new Set(
    input.pool.filter((t) => ownersOf(t).length > 0).map((t) => normArtist(t.artist)),
  );

  // このドライブで流した曲は候補から外す。playlist モードでは誰かのリストにある曲だけにする
  const played = new Set(history.map((h) => h.track_id));
  const recent = new Set(input.recent_track_ids ?? []);
  const available = input.pool.filter((t) => !played.has(t.track_id));
  const capKeys = Object.keys(plan.caps) as Axis[];
  const capValue = (t: PoolTrack, a: Axis) => (a === 'energy' ? (t.energy_cap ?? t.energy) : t[a]);
  const overOf = (t: PoolTrack) => capKeys.reduce((s, a) => s + Math.max(0, capValue(t, a) - plan.caps[a]!), 0);
  let pool = available.filter((t) => M.pool === 'all' || ownersOf(t).length > 0);

  // playlist モードで、リストの曲が上限を1曲も満たさないときは、カタログのインスト曲から補う
  // （運転負荷が高い区間で、上限を超える曲を流さないため。pool にはカタログの曲も含めて渡す前提）
  let catalogFill = false;
  if (M.pool === 'owned' && capKeys.length > 0 && !pool.some((t) => overOf(t) === 0)) {
    const fill = available.filter(
      (t) => ownersOf(t).length === 0 && overOf(t) === 0 && t.vocal <= INST_MAX_VOCAL,
    );
    if (fill.length > 0) {
      pool = fill;
      catalogFill = true;
    }
  }
  if (pool.length === 0) {
    return { mode, plan, volume: plan.volume, chosen: null, candidates: [], note: 'no tracks left in pool' };
  }

  const { rel, effW, ranges } = rankPool(pool, plan.weights);

  // 上限（安全制約）：上限を満たす曲が1曲でもあれば、その中からだけ選ぶ。
  // 1曲もないときだけ、はみ出しが小さい順に補う（無音よりはまし、という扱い）
  const requestedK = input.top_k ?? 5;
  let eligible = pool;
  // catalog_fill：playlist モードで、カタログのインスト曲から補った
  let capMode: 'absolute' | 'nearest' | 'catalog_fill' | 'none' = 'none';
  const overCap = new Map<string, number>();
  if (capKeys.length > 0) {
    for (const t of pool) overCap.set(t.track_id, overOf(t));
    const ok = pool.filter((t) => overCap.get(t.track_id)! === 0);
    if (ok.length > 0) {
      eligible = ok;
      capMode = catalogFill ? 'catalog_fill' : 'absolute';
    } else {
      eligible = [...pool]
        .sort((x, y) => overCap.get(x.track_id)! - overCap.get(y.track_id)!)
        .slice(0, requestedK);
      capMode = 'nearest';
    }
  }
  const topK = Math.min(requestedK, eligible.length);
  const softKeys = Object.keys(plan.softCaps) as Axis[];
  const softOver = (t: PoolTrack) =>
    softKeys.reduce((s, a) => s + Math.max(0, t[a] - plan.softCaps[a]!), 0);

  const last10 = history.slice(-10);
  const share = (o: string) =>
    last10.length ? last10.filter((h) => h.owner_id === o).length / last10.length : 0;
  const recentArtists = new Set(history.slice(-3).map((h) => h.artist).filter(Boolean));
  const distTo = (ref: Vec, t: PoolTrack) => rankDistance(ref, rel.get(t.track_id)!, effW);

  // ターゲットに近い曲が上限の範囲に無いときは、直前の曲調を基準にする
  const minTargetDistance = Math.min(...eligible.map((t) => distTo(plan.target, t)));
  let ref = plan.target;
  let fallback: { min_distance: number; ref: 'previous' | 'target' } | null = null;
  if (minTargetDistance > FALLBACK.maxDistance) {
    const last = history[history.length - 1];
    const prev = last && input.pool.find((t) => t.track_id === last.track_id);
    if (prev) {
      // 直前の曲は候補から外れているので、今のプールの中での順位に換算する
      ref = Object.fromEntries(AXES.map((a) => [a, rankIn(pool.map((t) => t[a]), prev[a])])) as Vec;
    }
    fallback = { min_distance: round(minTargetDistance), ref: prev ? 'previous' : 'target' };
  }
  const volume = fallback ? round(Math.max(MIN_VOLUME, plan.volume - FALLBACK.volumeDrop)) : plan.volume;

  const scored = eligible
    .map((t) => {
      const owners = ownersOf(t);
      const d = distTo(ref, t);
      const R = recent.has(t.track_id) ? 1 : 0;
      const A = recentArtists.has(t.artist) ? 1 : 0;
      const O = owners.length ? Math.min(...owners.map((o) => Math.max(0, share(o) - 1 / N))) : 0;
      const F = N > 1 && owners.length > 0 ? (owners.length - 1) / (N - 1) : 0;
      const C = Math.max(0, 0.6 - t.conf);
      const S = softOver(t);
      const P = clamp01(t.popularity ?? 0);
      const th = themeMatch(plan.themes, t.themes);
      // プレイリストの加点：リストにある曲を大きく、同じアーティストの曲を少し（重ねがけはしない）
      const pref: 'track' | 'artist' | null =
        owners.length > 0 ? 'track' : likedArtists.has(normArtist(t.artist)) ? 'artist' : null;
      const prefBonus = pref === 'track' ? M.prefTrack : pref === 'artist' ? M.prefArtist : 0;
      const score =
        -d -
        LAMBDA.rep * R -
        LAMBDA.artist * A -
        M.owner * O +
        M.shared * plan.famBoost * F +
        M.popularity * plan.famBoost * P +
        prefBonus +
        THEME_BONUS * th.value -
        LAMBDA.conf * C -
        LAMBDA.cap * (overCap.get(t.track_id) ?? 0) -
        LAMBDA.soft * S;
      return {
        track: t,
        score,
        exact_distance: d,
        breakdown: {
          distance: round(d),
          over_cap: round(overCap.get(t.track_id) ?? 0),
          over_soft: round(S),
          recent: R,
          same_artist: A,
          owner_bias: round(O),
          shared: round(F),
          popularity: round(P),
          theme: th.theme,
          theme_match: round(th.value),
          pref,
          conf_penalty: round(C),
        },
      };
    })
    .sort((a, b) => b.score - a.score);

  const maxScore = scored[0].score;
  // くじ引きの枠。4曲ごとに、2曲目は題材の枠（THEME_SLOT）、ほかは通常の枠。
  // どの枠も、上限を満たし直近3曲と同じアーティストでない曲だけ。対象が1曲も無ければ通常の枠に戻す
  const turn = history.length % SLOT_CYCLE;
  const special = !fallback && capMode !== 'nearest' && !catalogFill;
  const okBase = (s: (typeof scored)[number]) => s.breakdown.same_artist === 0 && s.breakdown.over_cap === 0;
  const hasSceneTheme = Object.entries(plan.themes).some(([th, w]) => th !== 'drive_travel' && w >= THEME_SLOT.minMatch);
  let slot: 'score' | 'theme' = 'score';
  let sample = scored.filter((s) => s.score >= maxScore - SAMPLE.scoreWindow).slice(0, SAMPLE.maxTracks);
  let tau = SAMPLE.tau;
  let relief = 0;
  if (special && turn === THEME_SLOT.turn && hasSceneTheme) {
    // 題材の枠：今の場面の題材（海・街など）を歌った曲から選ぶ。曲調は緩く（maxDistance 以内）しか見ないので、
    // テンポの遅い海の歌のように、通常の枠では点数で負ける曲にも出番がある
    const pool = scored.filter(
      (s) =>
        okBase(s) &&
        s.breakdown.theme != null &&
        s.breakdown.theme !== 'drive_travel' &&
        s.breakdown.theme_match >= THEME_SLOT.minMatch &&
        s.exact_distance <= THEME_SLOT.maxDistance,
    );
    if (pool.length > 0) [slot, sample, tau, relief] = ['theme', pool, THEME_SLOT.tau, THEME_SLOT.distanceRelief];
  }
  // どの枠でも、点数（狙いへの近さ・知名度・好みや題材の加点など）が高いほど選ばれやすい。ただし対象の曲はどれも 0 にならない
  const slotScore = (s: (typeof scored)[number]) => s.score + relief * s.exact_distance;
  const maxInSample = Math.max(...sample.map(slotScore));
  const weights = sample.map((s) => Math.exp((slotScore(s) - maxInSample) / tau));
  const total = weights.reduce((s, p) => s + p, 0);
  const probOf = new Map(sample.map((s, i) => [s.track.track_id, weights[i] / total]));
  const rnd = mulberry32(input.seed ?? Date.now())();
  let acc = 0;
  let pickIndex = sample.length - 1;
  for (let i = 0; i < sample.length; i++) {
    acc += weights[i] / total;
    if (rnd < acc) {
      pickIndex = i;
      break;
    }
  }
  const chosen = sample[pickIndex];
  // 再生できなかったときの次点：選んだ曲のあとに、点数の高い順で topK 曲まで
  const top = [chosen, ...scored.filter((s) => s !== chosen)].slice(0, topK);

  // 演出：直近の再生が少ない所有者の名前を出す。所有者がいない曲は名前を出さない
  const owner = [...ownersOf(chosen.track)].sort((a, b) => share(a) - share(b))[0] ?? null;
  const ownerName = owner == null ? null : (members.find((m) => m.id === owner)?.name ?? owner);
  // 題材が合ったから選ばれた曲は、そう伝える（ドライブ・旅はいつも少し加点しているので除く）。
  // 夜・朝の題材は景色ではなく時間帯の理由なので、理由の前置きも時間帯にする
  const theme = chosen.breakdown.theme;
  const themeHit =
    !catalogFill &&
    !fallback &&
    theme != null &&
    theme !== 'drive_travel' &&
    chosen.breakdown.theme_match >= 0.5;
  // 題材が理由のときは、その題材を選んだ景観・時間帯を理由にする（「視界が開けているので、空や風を歌った曲」）
  const why = (themeHit ? plan.themeReasons[theme!] : undefined) ?? plan.reasons[0];
  const from = ownerName
    ? `${ownerName}さんのリストから`
    : chosen.breakdown.pref === 'artist'
      ? 'みんなのリストにあるアーティストの、'
      : '';
  const message =
    `${why ? `${why}ので、` : ''}` +
    (catalogFill
      ? '運転に集中しやすいインスト曲を流します'
      : fallback
        ? `音量を少し下げて、${from}今の流れに近い曲を流します`
        : themeHit
          ? `${from}${THEME_LABEL[theme!]}を歌った曲を流します`
            : `${from}${moodWord(plan.target)}曲を流します`);

  return {
    mode,
    plan,
    volume,
    pool_ranges: ranges,
    effective_weights: roundVec(effW),
    cap_mode: capMode,
    fallback,
    chosen: {
      track_id: chosen.track.track_id,
      title: chosen.track.title,
      artist: chosen.track.artist,
      duration_ms: chosen.track.duration_ms ?? null,
      owner_id: owner,
      message,
    },
    sample_size: sample.length,
    slot, // どの枠で選んだか（score：通常 / theme：題材の枠）

    candidates: top.map((s) => ({
      track_id: s.track.track_id,
      title: s.track.title,
      artist: s.track.artist,
      owners: ownersOf(s.track),
      score: round(s.score),
      prob: round(probOf.get(s.track.track_id) ?? 0),
      ...s.breakdown,
    })),
  };
}

// 前の曲のターゲットから軸ごとに MAX_TARGET_STEP までしか動かさない。上限（caps）はそのあとにもう一度かける
function smoothTarget(plan: Plan, prev?: Partial<Vec>): Plan & { smoothed: Axis[] } {
  const smoothed: Axis[] = [];
  if (!prev) return { ...plan, smoothed };
  const target = { ...plan.target };
  for (const a of AXES) {
    const p = prev[a];
    if (p == null || !Number.isFinite(p)) continue;
    const limited = Math.min(p + MAX_TARGET_STEP, Math.max(p - MAX_TARGET_STEP, target[a]));
    if (limited !== target[a]) smoothed.push(a);
    target[a] = Math.min(limited, plan.caps[a] ?? 1, plan.softCaps[a] ?? 1);
  }
  return { ...plan, target: roundVec(target), smoothed };
}

// 場面に合う題材と曲の題材の一致度。一番よく合う題材1つだけを数える（重ねがけしない）
function themeMatch(want: Partial<Record<Theme, number>>, has: Partial<Record<Theme, number>> | undefined) {
  let best: { theme: Theme | null; value: number } = { theme: null, value: 0 };
  if (!has) return best;
  for (const [th, w] of Object.entries(want) as [Theme, number][]) {
    const v = w * (has[th] ?? 0);
    if (v > best.value) best = { theme: th, value: v };
  }
  return best;
}

function moodWord(t: Vec): string {
  if (t.energy >= 0.65 && t.valence >= 0.6) return '明るくアップテンポな';
  if (t.energy <= 0.4) return '落ち着いた';
  if (t.spacious >= 0.65) return '開放感のある';
  if (t.acoustic >= 0.65) return '生音が心地いい';
  if (t.valence >= 0.65) return '爽やかな';
  return '今の雰囲気に合う';
}

// 各軸をプール内の順位（0〜1）に変換する。ターゲットは「プール内のどのあたりか」として解釈する。
// 似た値ばかりの軸は、順位の差が誇張されるので重みを下げる（effW）
export function rankPool(pool: TrackFeatures[], weights: Vec) {
  const rel = new Map<string, Vec>(pool.map((t) => [t.track_id, {} as Vec]));
  const effW: Vec = { ...weights };
  const ranges = {} as Vec;
  for (const a of AXES) {
    const vals = pool.map((t) => t[a]);
    const p = percentiles(vals);
    pool.forEach((t, i) => (rel.get(t.track_id)![a] = p[i]));
    const range = Math.max(...vals) - Math.min(...vals);
    ranges[a] = round(range);
    if (range < MIN_RANGE) effW[a] *= range / MIN_RANGE;
  }
  return { rel, effW, ranges };
}

// ターゲット（順位）と曲（順位）の重みつきの距離
export function rankDistance(ref: Vec, r: Vec, w: Vec): number {
  const wSum = AXES.reduce((s, a) => s + w[a], 0) || 1;
  return Math.sqrt(AXES.reduce((s, a) => s + w[a] * (ref[a] - r[a]) ** 2, 0) / wSum);
}

function percentiles(values: number[]): number[] {
  const n = values.length;
  if (n <= 1) return values.map(() => 0.5);
  const idx = values.map((v, i) => [v, i] as const).sort((a, b) => a[0] - b[0]);
  const out = new Array<number>(n);
  let i = 0;
  while (i < n) {
    let j = i;
    while (j + 1 < n && idx[j + 1][0] === idx[i][0]) j++;
    const p = (i + j) / 2 / (n - 1); // 同じ値は平均順位
    for (let k = i; k <= j; k++) out[idx[k][1]] = p;
    i = j + 1;
  }
  return out;
}

// x がプールの値の中でどの順位にあたるか（percentiles と同じ尺度、0〜1）
function rankIn(values: number[], x: number): number {
  const n = values.length;
  if (n <= 1) return 0.5;
  const below = values.filter((v) => v < x).length;
  const equal = values.filter((v) => v === x).length;
  return Math.min(1, Math.max(0, (below + (equal - 1) / 2) / (n - 1)));
}

function normArtist(s: string): string {
  return s.normalize('NFKC').toLowerCase().replace(/\s+/g, ' ').trim();
}

function capMin(caps: Partial<Vec>, a: Axis, v: number) {
  caps[a] = Math.min(caps[a] ?? 1, v);
}

function clamp01(n: unknown): number {
  const v = Number(n);
  if (!Number.isFinite(v)) return 0;
  return Math.min(1, Math.max(0, v));
}

function round(n: number): number {
  return Math.round(n * 1000) / 1000;
}

function roundVec(v: Vec): Vec {
  return Object.fromEntries(AXES.map((a) => [a, round(v[a])])) as Vec;
}

// テストで結果を再現できるよう、シード付きの乱数を使う
function mulberry32(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
