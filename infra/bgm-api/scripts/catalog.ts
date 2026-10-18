// 全曲カタログを作るスクリプト（手元で実行する）。API制限に優しく、途中で止めても続きから再開できる
//
// 使い方:
//   npm run catalog -- collect                 Deezer のプレイリストから曲を集める（catalog/sources.json）
//   npm run catalog -- annotate                Gemini で注釈する（1日の上限に当たったら止まる。再実行で続きから）
//   npm run catalog -- annotate --max-requests 1   まず1回だけ試す
//   npm run catalog -- status                  進み具合を表示する
//   npm run catalog -- export                  catalog/catalog.jsonl（PC版が起動時に取り込む。リポジトリに入れる）と
//                                              D1 に入れる SQL（catalog/out/catalog.sql）を書き出す
//
// 進み具合は catalog/work/catalog.json に保存する。キーは .dev.vars から読む

import fs from 'node:fs';
import path from 'node:path';
import { CATALOG_COLUMNS } from '../src/catalog-columns';
import { ANNOTATE_PARAMS, GeminiBillingError, GeminiRateLimitError, annotateBatch, modelVersion, type Annotation } from '../src/annotate';

// API制限に優しくするための設定（初期値）
const LIMITS = {
  deezerIntervalMs: 1000, // Deezer：1秒に1回
  geminiIntervalMs: 15_000, // Gemini：無料枠 5 RPM に対して 4 RPM
  geminiDailyRequests: 18, // Gemini：無料枠 20 RPD のうち、開発用に2回残す
  geminiRetryWaitSec: 60, // 1分あたりの上限に当たったときに待つ秒数（Retry-After が無いとき）
};

const SOURCES_PATH = 'catalog/sources.json';
const STATE_PATH = 'catalog/work/catalog.json';
const OUT_PATH = 'catalog/out/catalog.sql';
const SEED_PATH = 'catalog/catalog.jsonl';

type Group = 'jp' | 'inst' | 'western' | 'kpop';
const GROUPS: Group[] = ['jp', 'inst', 'western', 'kpop'];

type CatalogTrack = {
  track_id: string; // 'isrc:XXXX'
  isrc: string;
  deezer_id: number;
  title: string;
  artist: string;
  duration_ms: number;
  deezer_rank: number;
  group: Group;
  sources: string[]; // 集め先のプレイリスト名
  annotation?: Annotation;
  model_version?: string;
};

type State = {
  collected_at?: string;
  tracks: CatalogTrack[];
  gemini_usage: Record<string, number>; // 太平洋時間の日付 → その日に送ったリクエスト数
};

type Sources = {
  groups: Record<
    Group,
    {
      label: string;
      quota: number;
      max_per_artist: number;
      require_isrc_country?: string[]; // ISRC の国コード（先頭2文字）で絞る。邦楽に海外の曲が混ざらないように
      lang?: string; // Deezer から取得する表記の言語（洋楽は英語の原題のほうが LLM が曲を特定しやすい）
      // 必ず入れるアーティスト（Deezer のアーティストID）。人気曲の上位 artist_top 曲を、プレイリストより先に入れる
      artists?: { id: number; name: string }[];
      artist_top?: number;
      // ISRC の国コードか、指定したプレイリストに載っているアーティストのどちらかに当てはまる曲だけ採る（K-POP 用）
      include_if?: { isrc_country?: string[]; artists_from_playlists?: number[]; artist_names?: string[] };
      playlists: { id: number; name: string }[];
    }
  >;
  exclude_title_patterns: string[];
  exclude_title_patterns_vocal_groups: string[];
  min_duration_sec: number;
};

const [command, ...rest] = process.argv.slice(2);
const opts = parseOpts(rest);

async function main() {
  switch (command) {
    case 'collect':
      return collect();
    case 'annotate':
      return annotate();
    case 'status':
      return status(loadState());
    case 'export':
      return exportSql();
    default:
      console.error('usage: npm run catalog -- <collect|annotate|status|export> [--max-requests N] [--force]');
      process.exit(1);
  }
}

// ---- collect：Deezer から集める ----

async function collect() {
  const state = loadState();
  if (state.tracks.length > 0 && !opts.force) {
    console.error(`すでに ${state.tracks.length} 曲集めてあります。集め直すときは --force（注釈済みの結果は引き継ぐ）`);
    process.exit(1);
  }
  const sources = JSON.parse(fs.readFileSync(SOURCES_PATH, 'utf8')) as Sources;
  const exclude = new RegExp(sources.exclude_title_patterns.join('|'), 'i');
  const excludeVocal = new RegExp(sources.exclude_title_patterns_vocal_groups.join('|'), 'i');
  const previous = new Map(state.tracks.map((t) => [t.track_id, t]));

  const picked: CatalogTrack[] = [];
  const seenIsrc = new Set<string>();
  const seenName = new Set<string>();
  const skipped: Record<string, number> = {};
  const skip = (why: string) => {
    skipped[why] = (skipped[why] ?? 0) + 1;
  };

  // 前回集めた曲の ISRC（Deezer の曲ID → ISRC）。人気曲の一覧には ISRC が無いので、問い合わせを減らすために使う
  const isrcByDeezerId = new Map(state.tracks.map((t) => [t.deezer_id, t.isrc]));

  for (const group of GROUPS) {
    const g = sources.groups[group];
    if (!g) continue;
    const perArtist = new Map<string, number>();
    const byId = new Map<string, CatalogTrack>();
    let count = 0;

    // 1曲を候補として検討し、採ったら true を返す
    const consider = (t: any, sourceName: string, explicitArtist: boolean, allowArtists: Set<string> | null): boolean => {
      const isrc: string | undefined = t.isrc;
      const title: string = t.title_short || t.title;
      const artist: string = t.artist?.name ?? '';
      const nameKey = `${norm(title)}|${norm(artist)}`;
      if (!isrc) return skip('ISRCなし'), false;
      const country = isrc.slice(0, 2).toUpperCase();
      // 指定したアーティストの曲は、本人と確認済みなので国コードでは絞らない
      if (!explicitArtist && g.require_isrc_country && !g.require_isrc_country.includes(country)) {
        return skip('ISRCの国コードが対象外'), false;
      }
      if (g.include_if && !(g.include_if.isrc_country ?? []).includes(country) && !allowArtists?.has(norm(artist))) {
        return skip('K-POPの条件に当てはまらない'), false;
      }
      // 同じ曲が別のプレイリストにもあれば、集め先だけ足す
      const dup = byId.get(`isrc:${isrc}`);
      if (dup) {
        if (!dup.sources.includes(sourceName)) dup.sources.push(sourceName);
        return false;
      }
      if (seenIsrc.has(isrc) || seenName.has(nameKey)) return skip('重複'), false;
      const fullTitle = `${t.title} ${t.title_version ?? ''}`;
      if (exclude.test(fullTitle)) return skip('カラオケ・ライブ・リミックスなど'), false;
      if (group !== 'inst' && excludeVocal.test(fullTitle)) return skip('歌ものグループのインスト版'), false;
      if ((t.duration ?? 0) < sources.min_duration_sec) return skip('短すぎる'), false;
      const a = norm(artist);
      if ((perArtist.get(a) ?? 0) >= g.max_per_artist) return skip('アーティストごとの上限'), false;

      const track_id = `isrc:${isrc}`;
      const prev = previous.get(track_id);
      const ct: CatalogTrack = {
        track_id,
        isrc,
        deezer_id: t.id,
        title,
        artist,
        duration_ms: (t.duration ?? 0) * 1000,
        deezer_rank: t.rank ?? 0,
        group,
        sources: [sourceName],
        ...(prev?.annotation ? { annotation: prev.annotation, model_version: prev.model_version } : {}),
      };
      picked.push(ct);
      byId.set(track_id, ct);
      seenIsrc.add(isrc);
      seenName.add(nameKey);
      perArtist.set(a, (perArtist.get(a) ?? 0) + 1);
      count++;
      return true;
    };

    // プレイリストの曲を取る
    const lists: { id: number; name: string; tracks: any[] }[] = [];
    for (const pl of g.playlists) {
      const tracks = await deezerPlaylistTracks(pl.id, g.lang);
      console.log(`  [${group}] ${pl.name}: ${tracks.length}曲`);
      lists.push({ id: pl.id, name: pl.name, tracks });
    }
    // include_if のアーティスト（例：Japanese K-Pop に載っているアーティスト）
    let allowArtists: Set<string> | null = null;
    if (g.include_if) {
      allowArtists = new Set((g.include_if.artist_names ?? []).map(norm));
      for (const id of g.include_if.artists_from_playlists ?? []) {
        const tracks = lists.find((l) => l.id === id)?.tracks ?? (await deezerPlaylistTracks(id, g.lang));
        for (const t of tracks) allowArtists.add(norm(t.artist?.name ?? ''));
      }
    }

    // 1. 指定したアーティストの人気曲を先に入れる
    for (const a of g.artists ?? []) {
      const top = await deezerArtistTop(a.id, g.artist_top ?? 10, g.lang);
      let n = 0;
      for (const t of top) {
        if (count >= g.quota) break;
        t.isrc = isrcByDeezerId.get(t.id) ?? (await deezerTrack(t.id, g.lang))?.isrc;
        if (consider(t, `artist:${a.name}`, true, allowArtists)) n++;
      }
      console.log(`  [${group}] ${a.name}（人気曲）: ${n}曲`);
    }

    // 2. 残りをプレイリストから1曲ずつ順番に取る（ラウンドロビン）
    for (let i = 0; count < g.quota && lists.some((l) => i < l.tracks.length); i++) {
      for (const l of lists) {
        if (count >= g.quota) break;
        const t = l.tracks[i];
        if (t) consider(t, l.name, false, allowArtists);
      }
    }
    console.log(`[${group}] ${count} / ${g.quota}曲`);
  }

  state.tracks = picked;
  state.collected_at = new Date().toISOString();
  saveState(state);
  console.log('除外した曲:', skipped);
  status(state);
}

async function deezerArtistTop(id: number, limit: number, lang?: string): Promise<any[]> {
  const res = await politeFetch(
    `https://api.deezer.com/artist/${id}/top?limit=${limit}`,
    LIMITS.deezerIntervalMs,
    lang ? { 'Accept-Language': lang } : undefined,
  );
  const data: any = await res.json();
  if (data.error) throw new Error(`Deezer artist ${id}: ${JSON.stringify(data.error)}`);
  return data.data ?? [];
}

async function deezerTrack(id: number, lang?: string): Promise<any | null> {
  const res = await politeFetch(
    `https://api.deezer.com/track/${id}`,
    LIMITS.deezerIntervalMs,
    lang ? { 'Accept-Language': lang } : undefined,
  );
  const data: any = await res.json();
  return data.error ? null : data;
}

async function deezerPlaylistTracks(id: number, lang?: string): Promise<any[]> {
  const out: any[] = [];
  for (let index = 0; ; index += 100) {
    const res = await politeFetch(
      `https://api.deezer.com/playlist/${id}/tracks?limit=100&index=${index}`,
      LIMITS.deezerIntervalMs,
      lang ? { 'Accept-Language': lang } : undefined,
    );
    const data: any = await res.json();
    if (data.error) throw new Error(`Deezer playlist ${id}: ${JSON.stringify(data.error)}`);
    out.push(...(data.data ?? []));
    if (!data.next || (data.data ?? []).length === 0) break;
  }
  return out;
}

// ---- annotate：Gemini で注釈する ----

async function annotate() {
  loadEnv();
  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) throw new Error('.dev.vars に GEMINI_API_KEY がありません');
  const model = process.env.GEMINI_MODEL || ANNOTATE_PARAMS.defaultModel;
  const thinkingLevel = process.env.GEMINI_THINKING_LEVEL || ANNOTATE_PARAMS.thinkingLevel;
  const version = modelVersion(model);

  const state = loadState();
  const day = pacificDate();
  const dailyCap = opts.maxRequests ?? LIMITS.geminiDailyRequests;
  const usedToday = () => state.gemini_usage[day] ?? 0;

  // 同じモデル・同じプロンプトの版で注釈済みの曲は送らない
  const pending = () => state.tracks.filter((t) => t.model_version !== version);
  console.log(`モデル: ${model}（思考: ${thinkingLevel}）  未注釈: ${pending().length}曲  今日の使用: ${usedToday()}回（上限 ${dailyCap}回、太平洋時間 ${day}）`);

  let sent = 0;
  while (pending().length > 0) {
    if (opts.maxRequests != null ? sent >= opts.maxRequests : usedToday() >= dailyCap) {
      console.log('今日の上限に達したので終了します。日本時間16時（夏時間以外は17時）以降に再実行すると続きから再開します');
      break;
    }
    const chunk = pending().slice(0, ANNOTATE_PARAMS.batchSize);
    sent++;
    try {
      const results = await annotateBatch(chunk, { apiKey, model, thinkingLevel });
      // 回数は、応答が返ってきたときだけ数える（402 や 429 で断られたリクエストは数えない）
      state.gemini_usage[day] = usedToday() + 1;
      const byId = new Map(results.map((r) => [r.track_id, r]));
      for (const t of chunk) {
        const r = byId.get(t.track_id);
        if (r) {
          t.annotation = r;
          t.model_version = version;
        }
      }
      saveState(state);
      // 返ってこなかった曲は未注釈のまま残り、次のリクエストに回る
      console.log(`  ${results.length} / ${chunk.length}曲を注釈（残り ${pending().length}曲）`);
      if (results.length === 0) {
        console.log('1曲も返ってこなかったので、いったん終了します');
        break;
      }
    } catch (e) {
      if (e instanceof GeminiBillingError) {
        console.log(
          'Gemini が請求の問題（前払いのクレジット不足など）でリクエストを断りました。\n' +
            'https://ai.studio/projects でプロジェクトの請求を確認してから、もう一度実行してください。\n' +
            String(e),
        );
        break;
      }
      if (e instanceof GeminiRateLimitError) {
        if (e.daily) {
          console.log('Gemini の1日の上限に当たりました。日本時間16時（夏時間以外は17時）以降に再実行してください');
          break;
        }
        const wait = e.retryAfterSec ?? LIMITS.geminiRetryWaitSec;
        console.log(`  1分あたりの上限に当たったので ${wait}秒待ちます`);
        await sleep(wait * 1000);
        continue;
      }
      console.error(String(e));
      break;
    }
    if (pending().length > 0) await sleep(LIMITS.geminiIntervalMs);
  }
  status(state);
}

// ---- status / export ----

function status(state: State) {
  const version = modelVersion(process.env.GEMINI_MODEL || ANNOTATE_PARAMS.defaultModel);
  const rows = GROUPS.map((g) => {
    const ts = state.tracks.filter((t) => t.group === g);
    return `${g}: ${ts.length}曲（注釈済み ${ts.filter((t) => t.model_version === version).length}）`;
  });
  console.log(`カタログ ${state.tracks.length}曲  ${rows.join('  ')}`);
}

function exportSql() {
  const state = loadState();
  const done = state.tracks.filter((t) => t.annotation);
  if (done.length === 0) {
    console.error('注釈済みの曲がありません');
    process.exit(1);
  }
  // 知名度 = LLM の日本での認知度と、Deezer rank のグループ内順位の平均
  const rankPct = new Map<string, number>();
  for (const g of GROUPS) {
    const ts = done.filter((t) => t.group === g).sort((a, b) => a.deezer_rank - b.deezer_rank);
    ts.forEach((t, i) => rankPct.set(t.track_id, ts.length > 1 ? i / (ts.length - 1) : 0.5));
  }
  const now = Date.now();
  const seed: Record<string, unknown>[] = [];
  const lines = done.map((t) => {
    const a = t.annotation!;
    const popularity = round((a.jp_familiarity + rankPct.get(t.track_id)!) / 2);
    const source = { axes: 'llm', bpm: a.bpm, bpm_source: a.bpm ? 'llm' : null, known: a.known, deezer_id: t.deezer_id, sources: t.sources };
    const values = [
      t.track_id, a.title, a.artist, t.duration_ms, a.energy, a.valence, a.tempo, a.acoustic, a.spacious, a.vocal,
      a.conf, JSON.stringify(a.tags), JSON.stringify(source), t.model_version, now,
      t.isrc, a.jp_familiarity, t.deezer_rank, popularity, t.group,
    ];
    seed.push(Object.fromEntries(CATALOG_COLUMNS.map((column, i) => [column, values[i] ?? null])));
    return `INSERT INTO track_features (track_id, title, artist, duration_ms, energy, valence, tempo, acoustic, spacious, vocal, conf, tags_json, source_json, model_version, updated_at, isrc, jp_familiarity, deezer_rank, popularity, catalog_group) VALUES (${values.map(sqlValue).join(', ')}) ON CONFLICT(track_id) DO UPDATE SET title = excluded.title, artist = excluded.artist, duration_ms = excluded.duration_ms, energy = excluded.energy, valence = excluded.valence, tempo = excluded.tempo, acoustic = excluded.acoustic, spacious = excluded.spacious, vocal = excluded.vocal, conf = excluded.conf, tags_json = excluded.tags_json, source_json = excluded.source_json, model_version = excluded.model_version, updated_at = excluded.updated_at, isrc = excluded.isrc, jp_familiarity = excluded.jp_familiarity, deezer_rank = excluded.deezer_rank, popularity = excluded.popularity, catalog_group = excluded.catalog_group;`;
  });
  fs.mkdirSync(path.dirname(OUT_PATH), { recursive: true });
  fs.writeFileSync(OUT_PATH, lines.join('\n') + '\n');
  // 差分を読みやすくするため、track_id の順に1行1曲で書く。
  seed.sort((a, b) => String(a.track_id).localeCompare(String(b.track_id)));
  fs.writeFileSync(SEED_PATH, seed.map((row) => JSON.stringify(row)).join('\n') + '\n');
  console.log(`${done.length}曲を ${SEED_PATH} と ${OUT_PATH} に書き出しました`);
  console.log(`ローカル: npx wrangler d1 execute bgm-db --local --file ${OUT_PATH}`);
  console.log(`本番:     npx wrangler d1 migrations apply bgm-db --remote したあと、npx wrangler d1 execute bgm-db --remote --file ${OUT_PATH}`);
}

// ---- 補助 ----

function loadState(): State {
  if (!fs.existsSync(STATE_PATH)) return { tracks: [], gemini_usage: {} };
  return JSON.parse(fs.readFileSync(STATE_PATH, 'utf8')) as State;
}

// 書き込み途中で止まっても壊れないよう、一時ファイルに書いてから置き換える
function saveState(state: State) {
  fs.mkdirSync(path.dirname(STATE_PATH), { recursive: true });
  const tmp = `${STATE_PATH}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(state, null, 1));
  fs.renameSync(tmp, STATE_PATH);
}

let lastFetch = 0;
async function politeFetch(url: string, intervalMs: number, headers?: Record<string, string>): Promise<Response> {
  for (let attempt = 0; ; attempt++) {
    const wait = lastFetch + intervalMs - Date.now();
    if (wait > 0) await sleep(wait);
    lastFetch = Date.now();
    const res = await fetch(url, { headers });
    if (res.status === 429 && attempt < 3) {
      const sec = Number(res.headers.get('retry-after')) || 5 * (attempt + 1);
      console.log(`  429：${sec}秒待ちます`);
      await sleep(sec * 1000);
      continue;
    }
    return res;
  }
}

function loadEnv() {
  if (fs.existsSync('.dev.vars')) process.loadEnvFile('.dev.vars');
}

// Gemini の1日の上限は太平洋時間の0時にリセットされる
function pacificDate(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Los_Angeles' }).format(new Date());
}

function parseOpts(argv: string[]) {
  const out = { maxRequests: undefined as number | undefined, force: false };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--max-requests') out.maxRequests = Number(argv[++i]);
    else if (argv[i] === '--force') out.force = true;
  }
  return out;
}

function norm(s: string): string {
  return s.normalize('NFKC').toLowerCase().replace(/\s+/g, ' ').trim();
}

function sqlValue(v: unknown): string {
  if (v == null) return 'NULL';
  if (typeof v === 'number') return Number.isFinite(v) ? String(v) : 'NULL';
  return `'${String(v).replace(/'/g, "''")}'`;
}

function round(n: number): number {
  return Math.round(n * 1000) / 1000;
}

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

// 定数・関数の定義がすべて済んでから実行する
main().catch((e) => {
  console.error(e);
  process.exit(1);
});
