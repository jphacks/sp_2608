import { Hono } from 'hono';
import type { SqlDatabase } from './db';
import { recommend, type PoolTrack, type Scene } from './engine';
import { sceneFromScenicRoute, type ScenicRoute } from './scenic';
import { routeTimeline } from './route-source';
import { DEFAULT_MODE, MODE_IDS, MODE_LABELS } from './modes';
import { SPOTIFY_PARAMS, SpotifyRateLimitError, getAccessToken, searchByIsrc } from './spotify';
import { ANNOTATE_PARAMS, GeminiRateLimitError, annotateBatch, modelVersion, type Annotation } from './annotate';
import { partyRoutes } from './party';
import { InvalidRecommendation, parseRecommendation, type RecommendationRequest } from './recommend-input';

type Bindings = {
  bgm_db: SqlDatabase; // Workers では D1、PC では sqlite.ts
  GEMINI_API_KEY: string;
  GEMINI_MODEL?: string;
  GEMINI_THINKING_LEVEL?: string;
  SPOTIFY_CLIENT_ID: string;
  SPOTIFY_CLIENT_SECRET: string;
  APP_TOKEN?: string; // アプリ用の合言葉。本番は wrangler secret で設定する
  SCENIC_INTERNAL_URL?: string; // 景観エンジンの内部 URL（PC 版のみ）。route.route_id からタイムラインを取る
};

type TrackInput = {
  track_id?: string;
  title: string;
  artist: string;
  duration_ms?: number;
};

// 使うモデルは wrangler.jsonc の vars で GEMINI_MODEL、思考の深さは GEMINI_THINKING_LEVEL で差し替えられる
const MAX_TRACKS = 500; // 1リクエストあたりの上限

const app = new Hono<{ Bindings: Bindings }>();

// APP_TOKEN が設定されている環境（本番）では、/ と /modes 以外に「Authorization: Bearer <APP_TOKEN>」を求める。
// /annotate は Gemini のクレジットを、/link/spotify は Spotify の利用枠を使うので、外から呼ばれないようにする。
// /party/ 以下は同乗者のブラウザから開くリクエスト画面。合言葉の代わりに推測できない code で守る（party.ts）
const PUBLIC_PATHS = new Set(['/', '/modes']);
app.use('*', async (c, next) => {
  const token = c.env.APP_TOKEN;
  const path = new URL(c.req.url).pathname;
  if (!token || PUBLIC_PATHS.has(path) || path.startsWith('/party/')) return next();
  if (!safeEqual(c.req.header('authorization') ?? '', `Bearer ${token}`)) {
    return c.json({ error: 'unauthorized' }, 401);
  }
  return next();
});

app.get('/', (c) => c.text('bgm-api ok'));

// 同乗者のリクエスト（車の端末が作る party と、同乗者のリクエスト画面）
app.route('/', partyRoutes());

// 画面に出すモードの一覧（表示名は modes.ts の MODE_LABELS で変える）
app.get('/modes', (c) =>
  c.json({ default: DEFAULT_MODE, modes: MODE_IDS.map((id) => ({ id, ...MODE_LABELS[id] })) }),
);

// 曲のリストを受け取り、6軸の特徴量を返す（キャッシュがあればそれを使う）
app.post('/annotate', async (c) => {
  const body = await c.req
    .json<{ tracks: TrackInput[]; force?: boolean }>()
    .catch(() => null);

  if (!body || !Array.isArray(body.tracks) || body.tracks.length === 0) {
    return c.json({ error: 'tracks is required' }, 400);
  }
  if (body.tracks.length > MAX_TRACKS) {
    return c.json({ error: `max ${MAX_TRACKS} tracks per request` }, 400);
  }
  if ((body.force !== undefined && typeof body.force !== 'boolean') || body.tracks.some((track) =>
    !track || typeof track.title !== 'string' || !track.title.trim() || track.title.length > 500 ||
    typeof track.artist !== 'string' || !track.artist.trim() || track.artist.length > 500 ||
    (track.track_id !== undefined && (typeof track.track_id !== 'string' || !track.track_id.trim() || track.track_id.length > 500)) ||
    (track.duration_ms !== undefined && (!Number.isFinite(track.duration_ms) || track.duration_ms <= 0 || track.duration_ms > 3_600_000)))) {
    return c.json({ error: 'invalid tracks or force' }, 400);
  }

  const tracks = dedupe(
    body.tracks
      .filter((t) => t && t.title && t.artist)
      .map((t) => ({ ...t, track_id: t.track_id ?? makeId(t.title, t.artist) })),
  );

  const cached = body.force
    ? new Map<string, unknown>()
    : await loadCached(c.env.bgm_db, tracks.map((t) => t.track_id));
  const missing = tracks.filter((t) => !cached.has(t.track_id));
  const model = c.env.GEMINI_MODEL || ANNOTATE_PARAMS.defaultModel;

  const fresh: Annotation[] = [];
  const errors: string[] = [];
  for (const chunk of chunks(missing, ANNOTATE_PARAMS.batchSize)) {
    try {
      fresh.push(
        ...(await annotateBatch(chunk, {
          apiKey: c.env.GEMINI_API_KEY,
          model,
          thinkingLevel: c.env.GEMINI_THINKING_LEVEL,
        })),
      );
    } catch (e) {
      errors.push(String(e));
      // レート制限に当たったら、残りの曲は送らない（次のリクエストで続きから注釈する）
      if (e instanceof GeminiRateLimitError) break;
    }
  }

  if (fresh.length > 0) {
    await saveFeatures(c.env.bgm_db, fresh, model);
  }

  const freshMap = new Map(fresh.map((f) => [f.track_id, f]));
  const result = tracks.map(
    (t) =>
      cached.get(t.track_id) ??
      freshMap.get(t.track_id) ?? {
        track_id: t.track_id,
        title: t.title,
        artist: t.artist,
        error: 'not annotated',
      },
  );

  return c.json({
    model,
    cached: cached.size,
    annotated: fresh.length,
    errors,
    tracks: result,
  });
});

// 景観・車内状態・候補プールから次の1曲を選ぶ
app.post('/recommend', async (c) => {
  let body: RecommendationRequest;
  try {
    body = parseRecommendation(await c.req.json<unknown>().catch(() => null));
  } catch (error) {
    if (error instanceof InvalidRecommendation) return c.json({ error: error.message }, 400);
    throw error;
  }
  const members: { id: string; name: string }[] = body.members;

  // pool：同乗者のプレイリストの曲と所有者。カタログにある曲は 'isrc:XXXX' で送ると、カタログの同じ曲に所有者が付く
  let poolSpec: { track_id: string; owners?: string[] }[] = body.pool ?? [];
  const feats = new Map<string, any>();

  if (!Array.isArray(body.pool)) {
    // 開発用：pool を省略すると、カタログ以外のDBの曲をメンバーに順番に割り当てて、プレイリストの代わりにする
    const { results } = await c.env.bgm_db
      .prepare('SELECT * FROM track_features WHERE catalog_group IS NULL')
      .all<any>();
    poolSpec = (results ?? []).map((r, i) => ({ track_id: r.track_id, owners: [members[i % members.length].id] }));
    for (const r of results ?? []) feats.set(r.track_id, rowToFeatures(r));
  } else {
    for (const [id, f] of await loadCached(c.env.bgm_db, poolSpec.map((p) => p.track_id))) feats.set(id, f);
  }

  // 全曲カタログを候補に足す（所有者なし）。include_catalog: false で外せる
  const owners = new Map<string, Set<string>>();
  for (const p of poolSpec) {
    const set = owners.get(p.track_id) ?? new Set<string>();
    for (const o of p.owners ?? []) set.add(o);
    owners.set(p.track_id, set);
  }
  if (body.include_catalog !== false) {
    const { results } = await c.env.bgm_db
      .prepare('SELECT * FROM track_features WHERE catalog_group IS NOT NULL')
      .all<any>();
    for (const r of results ?? []) {
      if (!feats.has(r.track_id)) feats.set(r.track_id, rowToFeatures(r));
      if (!owners.has(r.track_id)) owners.set(r.track_id, new Set());
    }
  }

  const pool: PoolTrack[] = [...owners]
    .filter(([id]) => feats.has(id))
    .map(([id, o]) => ({ ...feats.get(id), owners: [...o] }));
  const missing = [...owners.keys()].filter((id) => !feats.has(id));

  const history = (body.history ?? []).map((h) => ({
    ...h,
    artist: h.artist ?? feats.get(h.track_id)?.artist,
  }));

  // 景観エンジンのタイムラインがあれば、次の曲が流れる区間の景観を計算する。scene で送られた値はそれより優先する
  let scene: Scene = body.scene ?? { features: {} };
  let routeWindow = null;
  let routeStatus: 'given' | 'resolved' | 'unavailable' | null = null;
  let route: ScenicRoute | null = null;
  if (body.route && Array.isArray(body.route.timeline)) {
    route = body.route as ScenicRoute;
    routeStatus = 'given';
  } else if (body.route?.route_id) {
    // 取れなくても選曲は止めない。景観なし（scene の値だけ）で選ぶ
    const resolved = await routeTimeline(c.env.bgm_db, c.env.SCENIC_INTERNAL_URL, body.route.route_id);
    routeStatus = resolved ? 'resolved' : 'unavailable';
    if (resolved) route = { ...body.route, ...resolved } as ScenicRoute;
  }
  if (route) {
    const w = sceneFromScenicRoute(route);
    const given: Partial<Scene> = body.scene ?? {};
    scene = { ...w.scene, ...given, features: { ...w.scene.features, ...(given.features ?? {}) } };
    routeWindow = { window: w.window, coverage: w.coverage, shares: w.shares, reveal: w.reveal, scene };
  }

  const result = recommend({ ...body, scene, pool, history });
  return c.json({ pool_size: pool.length, missing, route_window: routeWindow, route_status: routeStatus, ...result });
});

// 選ばれた曲の Spotify の曲IDを返す。照合済みなら D1 から返し、未照合の曲だけ ISRC で検索する
// body: { track_ids: ['isrc:XXXX', ...] }（1回 SPOTIFY_PARAMS.maxPerRequest 曲まで）
app.post('/link/spotify', async (c) => {
  const body = await c.req.json<{ track_ids?: string[] }>().catch(() => null);
  if (!Array.isArray(body?.track_ids) || body.track_ids.some((id) => typeof id !== 'string' || !id.trim() || id.length > 500)) {
    return c.json({ error: 'track_ids must be an array of non-empty strings' }, 400);
  }
  const ids = [...new Set(body?.track_ids ?? [])];
  if (ids.length === 0) return c.json({ error: 'track_ids is required' }, 400);
  if (ids.length > SPOTIFY_PARAMS.maxPerRequest) {
    return c.json({ error: `max ${SPOTIFY_PARAMS.maxPerRequest} track_ids per request` }, 400);
  }

  const placeholders = ids.map(() => '?').join(',');
  const { results } = await c.env.bgm_db
    .prepare(`SELECT track_id, service_track_id FROM track_link WHERE service = 'spotify' AND track_id IN (${placeholders})`)
    .bind(...ids)
    .all<{ track_id: string; service_track_id: string | null }>();
  const links = new Map<string, string | null>((results ?? []).map((r) => [r.track_id, r.service_track_id]));

  // ISRC が分かる曲だけ検索する（'isrc:XXXX' 形式、またはDBの isrc 列）
  const todo = ids.filter((id) => !links.has(id));
  const isrcOf = new Map<string, string>();
  for (const id of todo) if (id.startsWith('isrc:')) isrcOf.set(id, id.slice(5));
  const unknownIsrc = todo.filter((id) => !isrcOf.has(id));
  if (unknownIsrc.length > 0) {
    const ph = unknownIsrc.map(() => '?').join(',');
    const { results: rows } = await c.env.bgm_db
      .prepare(`SELECT track_id, isrc FROM track_features WHERE track_id IN (${ph}) AND isrc IS NOT NULL`)
      .bind(...unknownIsrc)
      .all<{ track_id: string; isrc: string }>();
    for (const r of rows ?? []) isrcOf.set(r.track_id, r.isrc);
  }

  let retryAfter: number | null = null;
  const fresh: { track_id: string; id: string | null }[] = [];
  if (isrcOf.size > 0) {
    // 資格情報が無いと Spotify に問い合わせられない。PC 版では infra/valhalla/.secrets/bgm.env に書く
    if (!c.env.SPOTIFY_CLIENT_ID || !c.env.SPOTIFY_CLIENT_SECRET) {
      return c.json({ error: 'spotify_not_configured' }, 503);
    }
    const token = await getAccessToken(c.env.SPOTIFY_CLIENT_ID, c.env.SPOTIFY_CLIENT_SECRET);
    for (const [trackId, isrc] of isrcOf) {
      try {
        fresh.push({ track_id: trackId, id: await searchByIsrc(isrc, token) });
      } catch (e) {
        // レート制限のときは結果を保存せず、待つ秒数だけ返す（残りの曲も問い合わせない）
        if (e instanceof SpotifyRateLimitError) {
          retryAfter = e.retryAfterSec ?? 30;
          break;
        }
        throw e;
      }
    }
  }
  if (fresh.length > 0) {
    // 見つからなかった曲も NULL で保存し、同じ曲を何度も検索しないようにする
    const stmt = c.env.bgm_db.prepare(
      `INSERT INTO track_link (track_id, service, service_track_id, matched_at) VALUES (?, 'spotify', ?, ?)
       ON CONFLICT(track_id, service) DO UPDATE SET service_track_id = excluded.service_track_id, matched_at = excluded.matched_at`,
    );
    const now = Date.now();
    await c.env.bgm_db.batch(fresh.map((f) => stmt.bind(f.track_id, f.id, now)));
    for (const f of fresh) links.set(f.track_id, f.id);
  }

  return c.json({
    links: ids.map((id) => ({
      track_id: id,
      // not_found：Spotify に無い曲（次の候補に回す）。pending：レート制限でまだ照合できていない。no_isrc：ISRC が分からず照合できない
      status: links.has(id) ? (links.get(id) ? 'found' : 'not_found') : isrcOf.has(id) ? 'pending' : 'no_isrc',
      spotify_id: links.get(id) ?? null,
      spotify_uri: links.get(id) ? `spotify:track:${links.get(id)}` : null,
    })),
    retry_after: retryAfter,
  });
});

async function loadCached(db: SqlDatabase, ids: string[]): Promise<Map<string, unknown>> {
  const map = new Map<string, unknown>();
  // D1のバインド変数の上限に収まるよう分割して問い合わせる
  for (const part of chunks(ids, 90)) {
    if (part.length === 0) continue;
    const placeholders = part.map(() => '?').join(',');
    const { results } = await db
      .prepare(`SELECT * FROM track_features WHERE track_id IN (${placeholders})`)
      .bind(...part)
      .all<any>();
    for (const r of results ?? []) map.set(r.track_id, rowToFeatures(r));
  }
  return map;
}

// D1 の行 → 推薦に使う曲の特徴量
function rowToFeatures(r: any) {
  return {
    track_id: r.track_id,
    title: r.title,
    artist: r.artist,
    duration_ms: r.duration_ms,
    energy: r.energy,
    valence: r.valence,
    tempo: r.tempo,
    acoustic: r.acoustic,
    spacious: r.spacious,
    vocal: r.vocal,
    conf: r.conf,
    // 知名度：カタログの曲は計算済みの popularity、それ以外は LLM の認知度で代用する
    popularity: r.popularity ?? r.jp_familiarity ?? undefined,
    jp_familiarity: r.jp_familiarity,
    isrc: r.isrc,
    catalog_group: r.catalog_group,
    tags: safeParse(r.tags_json, []),
    bpm: safeParse(r.source_json, {} as any)?.bpm ?? null,
    cached: true,
  };
}

async function saveFeatures(db: SqlDatabase, rows: Annotation[], model: string) {
  const now = Date.now();
  const stmt = db.prepare(
    `INSERT INTO track_features
       (track_id, title, artist, duration_ms, energy, valence, tempo, acoustic, spacious, vocal,
        conf, jp_familiarity, tags_json, source_json, model_version, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(track_id) DO UPDATE SET
       title = excluded.title, artist = excluded.artist, duration_ms = excluded.duration_ms,
       energy = excluded.energy, valence = excluded.valence, tempo = excluded.tempo,
       acoustic = excluded.acoustic, spacious = excluded.spacious, vocal = excluded.vocal,
       conf = excluded.conf, jp_familiarity = excluded.jp_familiarity, tags_json = excluded.tags_json, source_json = excluded.source_json,
       model_version = excluded.model_version, updated_at = excluded.updated_at`,
  );
  await db.batch(
    rows.map((r) =>
      stmt.bind(
        r.track_id,
        r.title,
        r.artist,
        r.duration_ms,
        r.energy,
        r.valence,
        r.tempo,
        r.acoustic,
        r.spacious,
        r.vocal,
        r.conf,
        r.jp_familiarity,
        JSON.stringify(r.tags),
        JSON.stringify({ axes: 'llm', bpm: r.bpm, bpm_source: r.bpm ? 'llm' : null, known: r.known }),
        modelVersion(model),
        now,
      ),
    ),
  );
}

// Spotify連携までの仮ID：曲名とアーティスト名を正規化してつなげる
function makeId(title: string, artist: string): string {
  const norm = (s: string) => s.normalize('NFKC').toLowerCase().replace(/\s+/g, ' ').trim();
  return `local:${norm(title)}|${norm(artist)}`;
}

function dedupe<T extends { track_id: string }>(items: T[]): T[] {
  const seen = new Set<string>();
  return items.filter((t) => (seen.has(t.track_id) ? false : (seen.add(t.track_id), true)));
}

function chunks<T>(arr: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size));
  return out;
}

// 比較にかかる時間から一致した長さが推測されないよう、最後まで比べる
function safeEqual(a: string, b: string): boolean {
  let diff = a.length ^ b.length;
  for (let i = 0; i < Math.max(a.length, b.length); i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function safeParse<T>(s: string | null, fallback: T): T {
  if (!s) return fallback;
  try {
    return JSON.parse(s) as T;
  } catch {
    return fallback;
  }
}

export default app;
