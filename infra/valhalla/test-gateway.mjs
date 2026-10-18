import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const endpoint = 'http://127.0.0.1:8003';
const token = readFileSync(new URL('./.secrets/routing.token', import.meta.url), 'utf8').trim();
const request = async (path, options = {}) => {
  for (let attempt = 0; attempt < 6; attempt++) {
    const response = await fetch(endpoint + path, { ...options, redirect: 'error', signal: AbortSignal.timeout(10000) });
    if (response.status !== 429 || attempt === 5) return response;
    await response.arrayBuffer();
    await new Promise(resolve => setTimeout(resolve, 6000));
  }
};
assert.equal((await request('/status')).status, 401);
assert.equal((await request('/search?q=test')).status, 401);
assert.equal((await request('/scenic/route', { method: 'POST' })).status, 401);
assert.equal((await request('/route-recommendations', { method: 'POST' })).status, 401);
assert.equal((await request('/route-recommendations/' + 'a'.repeat(32) + '/snapshot')).status, 401);
assert.equal((await request('/routes/route_' + 'a'.repeat(32) + '/evidence')).status, 401);
assert.equal((await request('/navigation-sessions', { method: 'POST' })).status, 401);
assert.equal((await request('/bgm/modes')).status, 401);
assert.equal((await request('/bgm/recommend', { method: 'POST' })).status, 401);
assert.equal((await request('/bgm/parties', { method: 'POST' })).status, 401);
// 同乗者のリクエスト画面だけはトークンなしで届く（知らない code なので終了扱い）
assert.equal((await request('/party/' + 'z'.repeat(22) + '/state')).status, 200);
assert.equal((await request('/party/' + 'z'.repeat(22) + '/state', { method: 'DELETE' })).status, 403);
assert.equal((await request('/status', { headers: { authorization: 'Bearer invalid' } })).status, 401);
const headers = { authorization: `Bearer ${token}` };
assert.equal((await request('/status', { headers })).status, 200);
assert.equal((await request('/route', { headers })).status, 403);
assert.equal((await request('/scenic/route', { headers })).status, 403);
assert.equal((await request('/search', { headers, method: 'POST' })).status, 403);
assert.equal((await request('/bgm/modes', { headers })).status, 200);
assert.equal((await request('/bgm/modes', { headers, method: 'DELETE' })).status, 403);
assert.equal((await request('/unknown', { headers })).status, 404);
console.log('Gateway: missing/invalid credentials rejected; valid credentials accepted; method/path restrictions passed.');
