// 同乗者のリクエスト。車の端末が案内ごとに party を作り、QR コードで同乗者に /party/{code} を開いてもらう。
// 同乗者はログインせずに曲を検索してリクエストし、車の端末が数秒ごとに受け取って流す。
//
// /parties/...（作る・受け取る・閉じる）は車の端末だけが呼ぶので、他の API と同じく合言葉で守る。
// /party/...（ページ・検索・リクエスト）は同乗者のブラウザから呼ぶので合言葉を求めない。
// 代わりに推測できない code を知っている人だけが使え、案内が終わるか期限が来たら使えなくなる。

import { Hono } from 'hono';
import type { SqlDatabase } from './db';
import { PARTY_PAGE_CSS, PARTY_PAGE_JS, partyPageHtml } from './party-page';
import { SpotifyRateLimitError, getAccessToken, getTrack, searchTracks, type SpotifyTrack } from './spotify';

export type PartyBindings = {
  bgm_db: SqlDatabase;
  SPOTIFY_CLIENT_ID: string;
  SPOTIFY_CLIENT_SECRET: string;
};

export const PARTY_PARAMS = {
  ttlMs: 12 * 60 * 60 * 1000, // 閉じ忘れても、この時間で使えなくなる
  keepClosedMs: 24 * 60 * 60 * 1000, // 終わった party とリクエストを消すまでの時間
  searchLimit: 10,
  searchCacheMs: 10 * 60 * 1000,
  searchCacheSize: 300,
  perGuestPerMinute: 3, // 1人が続けて送れる数
  perPartyPer10Minutes: 40,
  maxQueueShown: 30,
  maxText: 200,
  maxName: 20,
};

const CODE = /^[A-Za-z0-9_-]{22}$/;
const GUEST_ID = /^[A-Za-z0-9_-]{8,64}$/;
const TRACK_URI = /^spotify:track:([A-Za-z0-9]{22})$/;

type PartyRow = { code: string; expires_at: number; closed_at: number | null; state_json: string | null; state_at: number | null };

// 同じ言葉の検索で Spotify の利用枠を使い切らないよう、しばらく覚えておく。照合済みの曲の情報も残す。
const searchCache = new Map<string, { at: number; tracks: SpotifyTrack[] }>();
const knownTracks = new Map<string, SpotifyTrack>();

export function clearPartyCaches() {
  searchCache.clear();
  knownTracks.clear();
}

export function partyRoutes(now: () => number = Date.now) {
  const app = new Hono<{ Bindings: PartyBindings }>();

  // ---- 車の端末 ----

  app.post('/parties', async (c) => {
    const db = c.env.bgm_db;
    const at = now();
    // 古い party を片付けてから作る
    const old = at - PARTY_PARAMS.keepClosedMs;
    await db.batch([
      db.prepare('DELETE FROM party_request WHERE code IN (SELECT code FROM party WHERE expires_at < ? OR closed_at < ?)').bind(old, old),
      db.prepare('DELETE FROM party WHERE expires_at < ? OR closed_at < ?').bind(old, old),
    ]);
    const code = newCode();
    const expiresAt = at + PARTY_PARAMS.ttlMs;
    await db.batch([db.prepare('INSERT INTO party (code, created_at, expires_at) VALUES (?, ?, ?)').bind(code, at, expiresAt)]);
    return c.json({ code, expires_at: expiresAt }, 201);
  });

  // 今の曲と予約の列を受け取り、まだ渡していないリクエストを返す
  app.post('/parties/:code/sync', async (c) => {
    const code = c.req.param('code');
    const party = await findParty(c.env.bgm_db, code);
    if (!party) return c.json({ error: 'not_found' }, 404);
    const body = await c.req.json<any>().catch(() => null);
    if (!body || typeof body !== 'object') return c.json({ error: 'body is required' }, 400);
    const after = Number.isInteger(body.after) && body.after >= 0 ? body.after : 0;
    const db = c.env.bgm_db;
    const state = carState(body);
    if (state) {
      await db.batch([db.prepare('UPDATE party SET state_json = ?, state_at = ? WHERE code = ?').bind(JSON.stringify(state), now(), code)]);
    }
    const { results } = await db
      .prepare(`SELECT id, guest_name, uri, title, artist, duration_ms, image_url, mode, created_at
                FROM party_request WHERE code = ? AND id > ? ORDER BY id LIMIT 50`)
      .bind(code, after)
      .all<any>();
    return c.json({ open: isOpen(party, now()), requests: results ?? [] });
  });

  app.post('/parties/:code/close', async (c) => {
    const code = c.req.param('code');
    if (!CODE.test(code)) return c.json({ error: 'not_found' }, 404);
    await c.env.bgm_db.batch([
      c.env.bgm_db.prepare('UPDATE party SET closed_at = ? WHERE code = ? AND closed_at IS NULL').bind(now(), code),
    ]);
    return c.json({ closed: true });
  });

  // ---- 同乗者のブラウザ ----

  app.get('/party/app.js', (c) => asset(c, PARTY_PAGE_JS, 'text/javascript; charset=utf-8'));
  app.get('/party/app.css', (c) => asset(c, PARTY_PAGE_CSS, 'text/css; charset=utf-8'));

  app.get('/party/:code', (c) => {
    const res = c.html(partyPageHtml());
    secure(res.headers);
    return res;
  });

  app.get('/party/:code/state', async (c) => {
    const party = await findParty(c.env.bgm_db, c.req.param('code'));
    if (!party || !isOpen(party, now())) return guestJson(c, { open: false });
    const state = party.state_json ? JSON.parse(party.state_json) : {};
    return guestJson(c, { open: true, now_playing: state.now_playing ?? null, queue: state.queue ?? [], updated_at: party.state_at });
  });

  app.get('/party/:code/search', async (c) => {
    const party = await findParty(c.env.bgm_db, c.req.param('code'));
    if (!party || !isOpen(party, now())) return guestJson(c, { error: 'closed' }, 410);
    const query = (c.req.query('q') ?? '').normalize('NFKC').replace(/\s+/g, ' ').trim();
    if (!query || query.length > 100) return guestJson(c, { error: 'q is required (1-100 chars)' }, 400);
    const key = query.toLowerCase();
    const cached = searchCache.get(key);
    if (cached && now() - cached.at < PARTY_PARAMS.searchCacheMs) return guestJson(c, { tracks: cached.tracks });
    if (!c.env.SPOTIFY_CLIENT_ID || !c.env.SPOTIFY_CLIENT_SECRET) return guestJson(c, { error: 'spotify_not_configured' }, 503);
    try {
      const token = await getAccessToken(c.env.SPOTIFY_CLIENT_ID, c.env.SPOTIFY_CLIENT_SECRET);
      const tracks = await searchTracks(query, token, PARTY_PARAMS.searchLimit);
      remember(key, tracks, now());
      return guestJson(c, { tracks });
    } catch (e) {
      if (e instanceof SpotifyRateLimitError) return guestJson(c, { error: 'busy', retry_after: e.retryAfterSec ?? 30 }, 503);
      return guestJson(c, { error: 'search_failed' }, 502);
    }
  });

  app.post('/party/:code/requests', async (c) => {
    const code = c.req.param('code');
    const db = c.env.bgm_db;
    const party = await findParty(db, code);
    const at = now();
    if (!party || !isOpen(party, at)) return guestJson(c, { error: 'closed' }, 410);
    const body = await c.req.json<any>().catch(() => null);
    const guestId = typeof body?.guest_id === 'string' && GUEST_ID.test(body.guest_id) ? body.guest_id : null;
    const name = cleanText(body?.name, PARTY_PARAMS.maxName);
    const match = typeof body?.uri === 'string' ? TRACK_URI.exec(body.uri) : null;
    const mode = body?.mode === 'now' || body?.mode === 'next' ? body.mode : null;
    if (!guestId || !name || !match || !mode) return guestJson(c, { error: 'guest_id, name, uri and mode (now|next) are required' }, 400);

    const { results: counts } = await db
      .prepare(`SELECT
                  SUM(CASE WHEN guest_id = ? AND created_at > ? THEN 1 ELSE 0 END) AS guest,
                  SUM(CASE WHEN created_at > ? THEN 1 ELSE 0 END) AS party
                FROM party_request WHERE code = ?`)
      .bind(guestId, at - 60_000, at - 600_000, code)
      .all<{ guest: number | null; party: number | null }>();
    if ((counts?.[0]?.guest ?? 0) >= PARTY_PARAMS.perGuestPerMinute || (counts?.[0]?.party ?? 0) >= PARTY_PARAMS.perPartyPer10Minutes) {
      return guestJson(c, { error: 'too_many' }, 429);
    }

    // 曲名などはブラウザから受け取らず、サーバーが Spotify で確かめた値を使う
    let track = knownTracks.get(body.uri) ?? null;
    if (!track) {
      if (!c.env.SPOTIFY_CLIENT_ID || !c.env.SPOTIFY_CLIENT_SECRET) return guestJson(c, { error: 'spotify_not_configured' }, 503);
      try {
        const token = await getAccessToken(c.env.SPOTIFY_CLIENT_ID, c.env.SPOTIFY_CLIENT_SECRET);
        track = await getTrack(match[1], token);
      } catch (e) {
        if (e instanceof SpotifyRateLimitError) return guestJson(c, { error: 'busy', retry_after: e.retryAfterSec ?? 30 }, 503);
        return guestJson(c, { error: 'lookup_failed' }, 502);
      }
      if (!track) return guestJson(c, { error: 'unknown_track' }, 404);
      knownTracks.set(track.uri, track);
    }
    const { results } = await db
      .prepare(`INSERT INTO party_request (code, guest_id, guest_name, uri, title, artist, duration_ms, image_url, mode, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`)
      .bind(code, guestId, name, track.uri, track.title, track.artist, track.duration_ms, track.image_url, mode, at)
      .all<{ id: number }>();
    return guestJson(c, { id: results?.[0]?.id ?? null, title: track.title, artist: track.artist, mode }, 201);
  });

  return app;
}

function newCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function findParty(db: SqlDatabase, code: string): Promise<PartyRow | null> {
  if (!CODE.test(code)) return null;
  const { results } = await db
    .prepare('SELECT code, expires_at, closed_at, state_json, state_at FROM party WHERE code = ?')
    .bind(code)
    .all<PartyRow>();
  return results?.[0] ?? null;
}

function isOpen(party: PartyRow, at: number) {
  return party.closed_at == null && party.expires_at > at;
}

// 車の端末から受け取る表示用の値。同乗者のページにそのまま出すので、形と長さを決めて保存する
function carState(body: any): { now_playing: unknown; queue: unknown[] } | null {
  if (!('now_playing' in body) && !('queue' in body)) return null;
  const item = (value: any) => {
    const title = cleanText(value?.title, PARTY_PARAMS.maxText);
    if (!title) return null;
    return {
      title,
      artist: cleanText(value?.artist, PARTY_PARAMS.maxText) ?? '',
      by: cleanText(value?.by, PARTY_PARAMS.maxName),
      kind: value?.kind === 'request' ? 'request' : 'bgm',
    };
  };
  const queue = (Array.isArray(body.queue) ? body.queue : []).slice(0, PARTY_PARAMS.maxQueueShown).map(item).filter(Boolean);
  return { now_playing: body.now_playing ? item(body.now_playing) : null, queue };
}

function cleanText(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  // 制御文字を除き、前後の空白を落とす
  const text = value.normalize('NFKC').replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, max);
  return text || null;
}

function remember(key: string, tracks: SpotifyTrack[], at: number) {
  if (searchCache.size >= PARTY_PARAMS.searchCacheSize) searchCache.delete(searchCache.keys().next().value!);
  searchCache.set(key, { at, tracks });
  for (const t of tracks) {
    if (knownTracks.size >= PARTY_PARAMS.searchCacheSize * PARTY_PARAMS.searchLimit) knownTracks.delete(knownTracks.keys().next().value!);
    knownTracks.set(t.uri, t);
  }
}

function secure(headers: Headers) {
  headers.set(
    'Content-Security-Policy',
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src https://i.scdn.co; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  );
  headers.set('X-Content-Type-Options', 'nosniff');
  headers.set('Referrer-Policy', 'no-referrer');
  headers.set('Cache-Control', 'no-store');
}

function guestJson(c: any, body: unknown, status = 200) {
  const res = c.json(body, status);
  secure(res.headers);
  return res;
}

function asset(c: any, text: string, type: string) {
  const res = c.body(text, 200, { 'Content-Type': type });
  secure(res.headers);
  res.headers.set('Cache-Control', 'public, max-age=300');
  return res;
}
