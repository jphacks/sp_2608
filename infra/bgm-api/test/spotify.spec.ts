import { describe, it, expect, beforeEach } from 'vitest';
import { SpotifyRateLimitError, clearTokenCache, getAccessToken, searchByIsrc } from '../src/spotify';

describe('getAccessToken', () => {
  beforeEach(() => clearTokenCache());

  it('有効期限までは取り直さない', async () => {
    let calls = 0;
    const f = (async () => {
      calls++;
      return new Response(JSON.stringify({ access_token: `tok${calls}`, expires_in: 3600 }));
    }) as unknown as typeof fetch;
    expect(await getAccessToken('id', 'secret', f, 0)).toBe('tok1');
    expect(await getAccessToken('id', 'secret', f, 1000 * 3000)).toBe('tok1');
    // 期限の60秒前を過ぎたら取り直す
    expect(await getAccessToken('id', 'secret', f, 1000 * 3550)).toBe('tok2');
    expect(calls).toBe(2);
  });
});

describe('searchByIsrc', () => {
  it('ISRC で検索して曲IDを返す。無ければ null', async () => {
    let url = '';
    const found = (async (u: string) => {
      url = u;
      return new Response(JSON.stringify({ tracks: { items: [{ id: 'abc' }] } }));
    }) as unknown as typeof fetch;
    expect(await searchByIsrc('JPR652000112', 'tok', found)).toBe('abc');
    expect(decodeURIComponent(url)).toContain('q=isrc:JPR652000112');
    expect(url).toContain('limit=1');
    const none = (async () => new Response(JSON.stringify({ tracks: { items: [] } }))) as unknown as typeof fetch;
    expect(await searchByIsrc('X', 'tok', none)).toBeNull();
  });

  it('429 は待つ秒数と QUOTA_EXCEEDED かどうかを返す', async () => {
    const limited = (async () =>
      new Response(JSON.stringify({ status: 429, reason: 'QUOTA_EXCEEDED' }), { status: 429, headers: { 'retry-after': '12' } })) as unknown as typeof fetch;
    const e = await searchByIsrc('X', 'tok', limited).catch((x) => x);
    expect(e).toBeInstanceOf(SpotifyRateLimitError);
    expect(e.retryAfterSec).toBe(12);
    expect(e.quotaExceeded).toBe(true);
  });
});
