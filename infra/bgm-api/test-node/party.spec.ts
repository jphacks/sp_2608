// 同乗者のリクエスト（PC 版の SQLite で確かめる）。
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import app from '../src/index';
import { PARTY_PARAMS, clearPartyCaches } from '../src/party';
import { clearTokenCache } from '../src/spotify';
import { SqliteDatabase } from '../src/sqlite';

const migrations = new URL('../migrations', import.meta.url).pathname;
const ID = 'a'.repeat(22);
const OTHER = 'b'.repeat(22);

let directory: string;
let db: SqliteDatabase;
let spotifyCalls: string[];

const env = () => ({ bgm_db: db, GEMINI_API_KEY: '', SPOTIFY_CLIENT_ID: 'client', SPOTIFY_CLIENT_SECRET: 'secret' });

function spotifyItem(id: string, name: string) {
  return { id, name, duration_ms: 200000, artists: [{ name: 'Artist' }], album: { images: [{ url: 'https://i.scdn.co/image/x', width: 64 }] } };
}

beforeEach(() => {
  directory = mkdtempSync(join(tmpdir(), 'bgm-party-'));
  db = new SqliteDatabase(join(directory, 'bgm.sqlite'));
  db.migrate(migrations);
  clearPartyCaches();
  clearTokenCache();
  spotifyCalls = [];
  vi.stubGlobal('fetch', async (input: string) => {
    const url = String(input);
    spotifyCalls.push(url);
    if (url.startsWith('https://accounts.spotify.com/')) return Response.json({ access_token: 't', expires_in: 3600 });
    if (url.startsWith('https://api.spotify.com/v1/search')) return Response.json({ tracks: { items: [spotifyItem(ID, '夏の終わり')] } });
    if (url.startsWith(`https://api.spotify.com/v1/tracks/${OTHER}`)) return Response.json(spotifyItem(OTHER, '本当の曲名'));
    return new Response('not found', { status: 404 });
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  db.close();
  rmSync(directory, { recursive: true, force: true });
});

async function call(path: string, init: { method?: string; body?: unknown } = {}) {
  const res = await app.fetch(
    new Request(`http://bgm.test${path}`, {
      method: init.method ?? 'GET',
      headers: init.body ? { 'content-type': 'application/json' } : undefined,
      body: init.body ? JSON.stringify(init.body) : undefined,
    }),
    env(),
  );
  const text = await res.text();
  let json: any = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text, headers: res.headers };
}

async function createParty(): Promise<string> {
  const res = await call('/parties', { method: 'POST' });
  expect(res.status).toBe(201);
  expect(res.json.code).toMatch(/^[A-Za-z0-9_-]{22}$/);
  return res.json.code;
}

const guest = (extra: Record<string, unknown> = {}) => ({ guest_id: 'guest-0001', name: 'たろう', uri: `spotify:track:${ID}`, mode: 'next', ...extra });

describe('同乗者のリクエスト', () => {
  it('検索した曲を予約すると、車の端末が受け取れる。曲名はサーバーが確かめた値を使う', async () => {
    const code = await createParty();
    const search = await call(`/party/${code}/search?q=${encodeURIComponent('夏の終わり')}`);
    expect(search.status).toBe(200);
    expect(search.json.tracks[0]).toMatchObject({ uri: `spotify:track:${ID}`, title: '夏の終わり', artist: 'Artist' });

    const sent = await call(`/party/${code}/requests`, { method: 'POST', body: guest({ title: '偽の曲名', mode: 'now' }) });
    expect(sent.status).toBe(201);

    const sync = await call(`/parties/${code}/sync`, { method: 'POST', body: { after: 0 } });
    expect(sync.json.open).toBe(true);
    expect(sync.json.requests).toHaveLength(1);
    expect(sync.json.requests[0]).toMatchObject({ guest_name: 'たろう', title: '夏の終わり', mode: 'now', uri: `spotify:track:${ID}` });
    // 受け取った分は after で除ける
    const again = await call(`/parties/${code}/sync`, { method: 'POST', body: { after: sync.json.requests[0].id } });
    expect(again.json.requests).toHaveLength(0);
  });

  it('検索していない曲は Spotify で1曲ずつ確かめる。無い曲は断る', async () => {
    const code = await createParty();
    const ok = await call(`/party/${code}/requests`, { method: 'POST', body: guest({ uri: `spotify:track:${OTHER}` }) });
    expect(ok.status).toBe(201);
    expect(ok.json.title).toBe('本当の曲名');
    const missing = await call(`/party/${code}/requests`, { method: 'POST', body: guest({ guest_id: 'guest-0002', uri: `spotify:track:${'c'.repeat(22)}` }) });
    expect(missing.status).toBe(404);
  });

  it('同じ言葉の検索は Spotify に問い合わせ直さない', async () => {
    const code = await createParty();
    await call(`/party/${code}/search?q=abc`);
    await call(`/party/${code}/search?q=ABC`);
    expect(spotifyCalls.filter((u) => u.includes('/v1/search'))).toHaveLength(1);
  });

  it('名前や曲の形が正しくないリクエストは受け付けない', async () => {
    const code = await createParty();
    expect((await call(`/party/${code}/requests`, { method: 'POST', body: guest({ name: '  ' }) })).status).toBe(400);
    expect((await call(`/party/${code}/requests`, { method: 'POST', body: guest({ uri: 'spotify:album:x' }) })).status).toBe(400);
    expect((await call(`/party/${code}/requests`, { method: 'POST', body: guest({ mode: 'later' }) })).status).toBe(400);
    expect((await call(`/party/${code}/requests`, { method: 'POST', body: guest({ guest_id: 'x' }) })).status).toBe(400);
  });

  it('1人が続けて送れる数には上限がある', async () => {
    const code = await createParty();
    await call(`/party/${code}/search?q=x`);
    for (let i = 0; i < PARTY_PARAMS.perGuestPerMinute; i++) {
      expect((await call(`/party/${code}/requests`, { method: 'POST', body: guest() })).status).toBe(201);
    }
    expect((await call(`/party/${code}/requests`, { method: 'POST', body: guest() })).status).toBe(429);
    // 別の人は送れる
    expect((await call(`/party/${code}/requests`, { method: 'POST', body: guest({ guest_id: 'guest-0002' }) })).status).toBe(201);
  });

  it('車の端末が送った今の曲と予約の列を、同乗者の画面に出す', async () => {
    const code = await createParty();
    await call(`/parties/${code}/sync`, {
      method: 'POST',
      body: {
        after: 0,
        now_playing: { title: '海の曲', artist: 'A', kind: 'bgm' },
        queue: [{ title: '次の曲', artist: 'B', by: 'たろう', kind: 'request', extra: 'x' }, { artist: 'no title' }],
      },
    });
    const state = await call(`/party/${code}/state`);
    expect(state.json).toMatchObject({
      open: true,
      now_playing: { title: '海の曲', artist: 'A', kind: 'bgm' },
      queue: [{ title: '次の曲', artist: 'B', by: 'たろう', kind: 'request' }],
    });
    expect(state.json.queue[0].extra).toBeUndefined();
  });

  it('閉じた party と知らない code では、何もできない', async () => {
    const code = await createParty();
    await call(`/parties/${code}/close`, { method: 'POST' });
    expect((await call(`/party/${code}/state`)).json).toEqual({ open: false });
    expect((await call(`/party/${code}/search?q=x`)).status).toBe(410);
    expect((await call(`/party/${code}/requests`, { method: 'POST', body: guest() })).status).toBe(410);
    expect((await call(`/parties/${code}/sync`, { method: 'POST', body: {} })).json.open).toBe(false);
    expect((await call(`/party/${'z'.repeat(22)}/state`)).json).toEqual({ open: false });
    expect((await call(`/parties/${'z'.repeat(22)}/sync`, { method: 'POST', body: {} })).status).toBe(404);
  });

  it('リクエスト画面は外部のスクリプトを読み込まず、CSP を付ける', async () => {
    const code = await createParty();
    const page = await call(`/party/${code}`);
    expect(page.status).toBe(200);
    expect(page.headers.get('content-security-policy')).toContain("script-src 'self'");
    expect(page.text).toContain('<script src="app.js" defer></script>');
    expect(page.text).not.toMatch(/<script>(?!<)/);
    const js = await call('/party/app.js');
    expect(js.headers.get('content-type')).toContain('javascript');
    // 曲名は textContent で入れる（innerHTML を使わない）
    expect(js.text).not.toContain('innerHTML');
  });

  it('APP_TOKEN があっても、同乗者の画面だけは合言葉なしで開ける', async () => {
    const code = await createParty();
    const withToken = { ...env(), APP_TOKEN: 'secret' };
    const page = await app.fetch(new Request(`http://bgm.test/party/${code}/state`), withToken);
    expect(page.status).toBe(200);
    const create = await app.fetch(new Request('http://bgm.test/parties', { method: 'POST' }), withToken);
    expect(create.status).toBe(401);
  });
});
