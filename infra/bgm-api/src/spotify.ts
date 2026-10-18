// Spotify の曲IDの照合（ISRC で検索）。再生する直前に、選ばれた曲だけを照合する。
// fetch 以外の実行環境には依存しない（fetchImpl を差し替えてテストできる）

export const SPOTIFY_PARAMS = {
  market: 'JP',
  maxPerRequest: 5, // /link/spotify で一度に照合する曲数（次の曲の候補だけを照合する前提）
  tokenMarginSec: 60, // アクセストークンの期限の何秒前に取り直すか
};

export class SpotifyRateLimitError extends Error {
  constructor(readonly retryAfterSec: number | null, readonly quotaExceeded: boolean) {
    super(`Spotify 429${quotaExceeded ? ' (QUOTA_EXCEEDED)' : ''}`);
    this.name = 'SpotifyRateLimitError';
  }
}

type Token = { value: string; expiresAt: number };
let cachedToken: Token | null = null; // Worker のインスタンスが生きている間は使い回す

export async function getAccessToken(
  clientId: string,
  clientSecret: string,
  fetchImpl: typeof fetch = fetch,
  now = Date.now(),
): Promise<string> {
  if (cachedToken && cachedToken.expiresAt > now) return cachedToken.value;
  const res = await fetchImpl('https://accounts.spotify.com/api/token', {
    method: 'POST',
    headers: {
      Authorization: `Basic ${btoa(`${clientId}:${clientSecret}`)}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: 'grant_type=client_credentials',
  });
  if (!res.ok) throw new Error(`Spotify token ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const data: any = await res.json();
  cachedToken = {
    value: data.access_token,
    expiresAt: now + (Number(data.expires_in) - SPOTIFY_PARAMS.tokenMarginSec) * 1000,
  };
  return cachedToken.value;
}

export function clearTokenCache() {
  cachedToken = null;
}

// ISRC → Spotify の曲ID。見つからなければ null
export async function searchByIsrc(isrc: string, token: string, fetchImpl: typeof fetch = fetch): Promise<string | null> {
  const q = new URLSearchParams({ q: `isrc:${isrc}`, type: 'track', market: SPOTIFY_PARAMS.market, limit: '1' });
  const res = await fetchImpl(`https://api.spotify.com/v1/search?${q}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (res.status === 429) {
    const body = await res.text();
    throw new SpotifyRateLimitError(Number(res.headers.get('retry-after')) || null, body.includes('QUOTA_EXCEEDED'));
  }
  if (!res.ok) throw new Error(`Spotify search ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const data: any = await res.json();
  return data?.tracks?.items?.[0]?.id ?? null;
}

/** 同乗者のリクエスト画面で使う曲の情報。 */
export type SpotifyTrack = { uri: string; title: string; artist: string; duration_ms: number | null; image_url: string | null };

// 曲名・アーティストで検索する（同乗者のリクエスト画面）。再生できない曲は外す
export async function searchTracks(query: string, token: string, limit = 10, fetchImpl: typeof fetch = fetch): Promise<SpotifyTrack[]> {
  const q = new URLSearchParams({ q: query, type: 'track', market: SPOTIFY_PARAMS.market, limit: String(limit) });
  const data: any = await spotifyGet(`https://api.spotify.com/v1/search?${q}`, token, fetchImpl);
  return (data?.tracks?.items ?? []).map(toTrack).filter((t: SpotifyTrack | null): t is SpotifyTrack => t != null);
}

// 1曲の情報。リクエストされた曲が実在し、この市場で再生できるかを確かめる。無ければ null
export async function getTrack(id: string, token: string, fetchImpl: typeof fetch = fetch): Promise<SpotifyTrack | null> {
  const q = new URLSearchParams({ market: SPOTIFY_PARAMS.market });
  const data = await spotifyGet(`https://api.spotify.com/v1/tracks/${encodeURIComponent(id)}?${q}`, token, fetchImpl, true);
  return data ? toTrack(data) : null;
}

async function spotifyGet(url: string, token: string, fetchImpl: typeof fetch, allowNotFound = false): Promise<any> {
  const res = await fetchImpl(url, { headers: { Authorization: `Bearer ${token}` } });
  if (res.status === 429) {
    const body = await res.text();
    throw new SpotifyRateLimitError(Number(res.headers.get('retry-after')) || null, body.includes('QUOTA_EXCEEDED'));
  }
  if (allowNotFound && (res.status === 404 || res.status === 400)) return null;
  if (!res.ok) throw new Error(`Spotify ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return res.json();
}

function toTrack(item: any): SpotifyTrack | null {
  if (!item || typeof item.id !== 'string' || !/^[A-Za-z0-9]{22}$/.test(item.id)) return null;
  if (item.is_playable === false || item.is_local) return null;
  const images: any[] = Array.isArray(item.album?.images) ? item.album.images : [];
  // 一覧に出すので、64px 以上で一番小さい画像を使う
  const image = images.filter((i) => typeof i?.url === 'string' && (i.width ?? 0) >= 64).sort((a, b) => (a.width ?? 0) - (b.width ?? 0))[0] ?? images[0];
  return {
    uri: `spotify:track:${item.id}`,
    title: String(item.name ?? ''),
    artist: (Array.isArray(item.artists) ? item.artists : []).map((a: any) => a?.name).filter(Boolean).join(' / '),
    duration_ms: Number.isFinite(item.duration_ms) ? item.duration_ms : null,
    image_url: typeof image?.url === 'string' && image.url.startsWith('https://i.scdn.co/') ? image.url : null,
  };
}
