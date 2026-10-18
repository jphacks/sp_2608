// 区間ごとの選曲を一覧で出すシミュレーション。走る前に机上で「この区間でこの曲は合っているか」を確認する
//
// 使い方:
//   npm run simulate -- routes/sample-coast.json
//   npm run simulate -- routes/sample-coast.json --pool test/fixtures/tracks.json --seed 3 --format md -v
//   npm run simulate -- routes/sample-coast.json --mode blend --owned-ratio 0.3
//
// --mode: discover / blend / playlist（省略時は DEFAULT_MODE）
// --owned-ratio: owners が無い曲のうち、メンバーのリストにある曲とみなす割合（既定 1 = 全曲）。
//   blend で「加点を受けていない曲がどれくらい選ばれるか」を見るときに使う
//
// ルートファイル:
//   { start_hour?, members, cabin_events?: [{km, state}], seed?, points: RoutePoint[] }
// プールファイル:
//   TrackFeatures の配列。owners が無い曲は、--owned-ratio の割合だけメンバーに順番に割り当てる

import fs from 'node:fs';
import { AXES, recommend, type CabinState, type HistoryEntry, type Member, type PoolTrack } from '../src/engine';
import { DEFAULT_MODE, MODE_LABELS, isModeId, type ModeId } from '../src/modes';
import { segmentRoute, type RoutePoint } from '../src/segment';

type RouteFile = {
  name?: string;
  start_hour?: number;
  members: Member[];
  cabin_events?: { km: number; state: CabinState }[];
  seed?: number;
  points: RoutePoint[];
};

const FEATURE_SHORT: Record<string, string> = {
  sea_proximity: '海近',
  sea_visibility: '海見',
  openness: '開放',
  forest: '森',
  nature: '自然',
  rural: '田園',
  urban: '街',
  water: '水辺',
};

const args = parseArgs(process.argv.slice(2));
if (!args.route) {
  console.error(
    'usage: npm run simulate -- <route.json> [--mode discover|blend|playlist] [--pool tracks.json] [--owned-ratio R] [--seed N] [--format text|md|json] [-v]',
  );
  process.exit(1);
}
if (!isModeId(args.mode)) {
  console.error(`unknown mode: ${args.mode}`);
  process.exit(1);
}
const mode: ModeId = args.mode;

const route = JSON.parse(fs.readFileSync(args.route, 'utf8')) as RouteFile;
const rawPool = JSON.parse(fs.readFileSync(args.pool, 'utf8')) as (Omit<PoolTrack, 'owners'> & { owners?: string[] })[];
const members = route.members;
let ownedCount = 0;
const pool: PoolTrack[] = rawPool.map((t, i) => {
  if (t.owners) return { ...t, owners: t.owners };
  // 割合 R のとき、i 曲目までに floor(i×R) 曲がリストにあるよう、均等に散らす
  const owned = Math.floor((i + 1) * args.ownedRatio) > Math.floor(i * args.ownedRatio);
  return { ...t, owners: owned ? [members[ownedCount++ % members.length].id] : [] };
});
const baseSeed = args.seed ?? route.seed ?? 1;

const segments = segmentRoute(route.points, { start_hour: route.start_hour });
const history: HistoryEntry[] = [];

const rows = segments.map((seg) => {
  const cabin = cabinAt(route.cabin_events ?? [], seg.start_km);
  const res = recommend({ mode, pool, scene: seg.scene, cabin, members, history, seed: baseSeed + seg.idx });
  if (res.chosen) {
    history.push({ track_id: res.chosen.track_id, artist: res.chosen.artist, owner_id: res.chosen.owner_id ?? undefined });
  }
  return { seg, cabin, res };
});

if (args.format === 'json') {
  console.log(JSON.stringify(rows, null, 2));
  process.exit(0);
}

const header = ['#', 'km', '時刻', '分', '主な景観', '条件', '車内', 'ターゲット E/V/T/A/S/Vo', '上限', '音量', '選曲', '持ち主', '加点', '確率', '理由'];
const table = rows.map(({ seg, cabin, res }) => {
  const chosen = res.chosen;
  const pick = chosen ? res.candidates.find((c) => c.title === chosen.title) : undefined;
  return [
    String(seg.idx),
    `${seg.start_km.toFixed(1)}–${seg.end_km.toFixed(1)}`,
    clock(route.start_hour, seg.start_s),
    ((seg.end_s - seg.start_s) / 60).toFixed(1),
    topFeatures(seg.features),
    [
      seg.load === 'high' ? '負荷高' : '',
      seg.curvy ? 'カーブ' : '',
      seg.highlight ? '名道' : '',
      seg.reveal_km != null ? `reveal@${seg.reveal_km}` : '',
    ]
      .filter(Boolean)
      .join(' '),
    cabin,
    AXES.map((a) => res.plan.target[a].toFixed(2)).join(' '),
    Object.entries(res.plan.caps)
      .map(([a, v]) => `${short(a)}≤${v}`)
      .join(' ') +
      (res.cap_mode === 'nearest' ? ' (不足)' : res.cap_mode === 'catalog_fill' ? ' (インスト補充)' : '') +
      (res.fallback ? ` →直前寄せ(d=${res.fallback.min_distance})` : ''),
    res.volume.toFixed(2),
    chosen ? `${chosen.title} / ${chosen.artist}` : '（候補なし）',
    chosen?.owner_id ? (members.find((m) => m.id === chosen.owner_id)?.name ?? chosen.owner_id) : '',
    pick?.pref === 'track' ? '曲' : pick?.pref === 'artist' ? 'アーティスト' : '',
    pick ? pick.prob.toFixed(2) : '',
    chosen?.message ?? '',
  ];
});

if (args.format === 'md') {
  console.log([header, header.map(() => '---'), ...table].map((r) => `| ${r.map(mdEscape).join(' | ')} |`).join('\n'));
} else {
  if (route.name) console.log(route.name);
  console.log(`モード: ${mode}（${MODE_LABELS[mode].label}）  プール: ${pool.length}曲（うちリストにある曲 ${pool.filter((t) => t.owners?.length).length}曲）\n`);
  printTable(header, table);
  // 加点の内訳（blend の係数調整用）
  const prefs = rows.map(({ res }) => res.candidates.find((c) => c.title === res.chosen?.title)?.pref);
  const count = (v: string | null) => prefs.filter((p) => p === v).length;
  console.log(`\n加点の内訳: 曲 ${count('track')} / アーティスト ${count('artist')} / なし ${count(null)}（${rows.length}区間）`);
}

if (args.verbose && args.format !== 'md') {
  console.log('\n候補の上位（区間ごと）');
  for (const { seg, res } of rows) {
    console.log(`\n#${seg.idx}  ${seg.start_km.toFixed(1)}–${seg.end_km.toFixed(1)}km  cap_mode=${res.cap_mode ?? '-'}  fallback=${res.fallback ? res.fallback.ref : '-'}`);
    printTable(
      ['曲', 'スコア', '確率', '距離', '上限超過', 'vocal超過', '同アーティスト', '偏り', '共有'],
      res.candidates.map((c) => [
        `${c.title} / ${c.artist}`,
        c.score.toFixed(3),
        c.prob.toFixed(2),
        c.distance.toFixed(3),
        c.over_cap.toFixed(2),
        c.over_soft.toFixed(2),
        String(c.same_artist),
        c.owner_bias.toFixed(2),
        c.shared.toFixed(2),
      ]),
    );
  }
}

// ---- 補助 ----

function parseArgs(argv: string[]) {
  const out = {
    route: '',
    mode: DEFAULT_MODE as string,
    ownedRatio: 1,
    pool: 'test/fixtures/tracks.json',
    seed: undefined as number | undefined,
    format: 'text' as 'text' | 'md' | 'json',
    verbose: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--pool') out.pool = argv[++i];
    else if (a === '--mode') out.mode = argv[++i];
    else if (a === '--owned-ratio') out.ownedRatio = Number(argv[++i]);
    else if (a === '--seed') out.seed = Number(argv[++i]);
    else if (a === '--format') out.format = argv[++i] as typeof out.format;
    else if (a === '--md') out.format = 'md';
    else if (a === '--json') out.format = 'json';
    else if (a === '-v' || a === '--verbose') out.verbose = true;
    else if (!a.startsWith('-')) out.route = a;
  }
  return out;
}

// km 時点の車内状態（その地点までの最後のイベント）
function cabinAt(events: { km: number; state: CabinState }[], km: number): CabinState {
  let state: CabinState = 'normal';
  for (const e of [...events].sort((a, b) => a.km - b.km)) if (e.km <= km) state = e.state;
  return state;
}

// 強い順に上位3つの特徴（v×q）
function topFeatures(f: Record<string, { v: number; q: number } | undefined>): string {
  return Object.entries(f)
    .map(([k, x]) => [k, x ? x.v * x.q : 0] as const)
    .filter(([, s]) => s >= 0.1)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([k, s]) => `${FEATURE_SHORT[k] ?? k}${s.toFixed(1)}`)
    .join(' ');
}

function short(axis: string): string {
  return { energy: 'E', valence: 'V', tempo: 'T', acoustic: 'A', spacious: 'S', vocal: 'Vo' }[axis] ?? axis;
}

function clock(startHour: number | undefined, sec: number): string {
  if (startHour == null) return `+${Math.round(sec / 60)}m`;
  const m = Math.round(startHour * 60 + sec / 60) % (24 * 60);
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

function mdEscape(s: string): string {
  return s.replace(/\|/g, '\\|');
}

// 全角文字を幅2として数え、列をそろえて表示する
function printTable(header: string[], rows: string[][]) {
  const widths = header.map((h, i) => Math.max(width(h), ...rows.map((r) => width(r[i] ?? ''))));
  const line = (r: string[]) => r.map((c, i) => c + ' '.repeat(widths[i] - width(c))).join('  ');
  console.log(line(header));
  console.log(widths.map((w) => '-'.repeat(w)).join('  '));
  for (const r of rows) console.log(line(r));
}

function width(s: string): number {
  let w = 0;
  for (const ch of s) {
    const cp = ch.codePointAt(0)!;
    w += cp >= 0x1100 && !(cp >= 0x2010 && cp <= 0x2027) && !(cp >= 0x2190 && cp <= 0x22ff) ? 2 : 1;
  }
  return w;
}
