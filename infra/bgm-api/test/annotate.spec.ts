import { describe, it, expect } from 'vitest';
import {
  ANNOTATE_PARAMS,
  GeminiBillingError,
  GeminiRateLimitError,
  annotateBatch,
  buildRequestBody,
  parseRateLimit,
  parseResponse,
} from '../src/annotate';

const chunk = [
  { track_id: 't0', title: '曲A', artist: 'X' },
  { track_id: 't1', title: '曲B', artist: 'Y', duration_ms: 200000 },
];

function geminiResponse(items: unknown[], extraParts: unknown[] = []) {
  return { candidates: [{ content: { parts: [...extraParts, { text: JSON.stringify({ items }) }] } }] };
}

const item = (i: number, v: Record<string, unknown> = {}) => ({
  i,
  known: true,
  energy: 0.8,
  valence: 0.6,
  acoustic: 0.2,
  spacious: 0.4,
  vocal: 0.9,
  bpm: 120,
  jp_familiarity: 0.7,
  conf: 0.9,
  tags: ['pop'],
  ...v,
});

describe('parseResponse', () => {
  it('入力の番号で曲に対応づけ、BPM を tempo に換算する', () => {
    const out = parseResponse(geminiResponse([item(1), item(0, { bpm: 60 })]), chunk);
    expect(out.map((a) => a.track_id)).toEqual(['t1', 't0']);
    expect(out[0]).toMatchObject({ title: '曲B', duration_ms: 200000, tempo: 0.5, bpm: 120, jp_familiarity: 0.7 });
    expect(out[1].tempo).toBe(0);
  });

  it('知らない曲は conf を下げ、範囲外の値は 0〜1 に収める', () => {
    const [a] = parseResponse(geminiResponse([item(0, { known: false, conf: 0.8, energy: 1.4, vocal: -1 })]), chunk);
    expect(a.conf).toBeCloseTo(0.8 * ANNOTATE_PARAMS.unknownConfFactor, 6);
    expect(a.energy).toBe(1);
    expect(a.vocal).toBe(0);
    expect(a.known).toBe(false);
  });

  it('存在しない番号・重複は無視し、返ってこなかった曲は含めない', () => {
    const out = parseResponse(geminiResponse([item(0), item(0, { energy: 0.1 }), item(5)]), chunk);
    expect(out).toHaveLength(1);
    expect(out[0].energy).toBe(0.8);
  });

  it('思考の部分（thought）は読み飛ばす', () => {
    const out = parseResponse(geminiResponse([item(0)], [{ thought: true, text: '考え中…' }]), chunk);
    expect(out).toHaveLength(1);
  });

  it('BPM が 0 なら tempo は 0.5（不明）', () => {
    const [a] = parseResponse(geminiResponse([item(0, { bpm: 0 })]), chunk);
    expect(a.bpm).toBeNull();
    expect(a.tempo).toBe(0.5);
  });
});

describe('buildRequestBody', () => {
  it('JSONスキーマと思考の深さを指定する', () => {
    const body = buildRequestBody(chunk, 'low');
    expect(body.generationConfig.thinkingConfig).toEqual({ thinkingLevel: 'low' });
    expect(body.generationConfig.responseMimeType).toBe('application/json');
    expect(JSON.parse(body.contents[0].parts[0].text)).toEqual([
      { i: 0, title: '曲A', artist: 'X' },
      { i: 1, title: '曲B', artist: 'Y' },
    ]);
  });
});

describe('parseRateLimit', () => {
  it('1日の上限（PerDay）と待つ秒数を読み取る', () => {
    const body = JSON.stringify({
      error: {
        details: [
          { '@type': 'type.googleapis.com/google.rpc.QuotaFailure', violations: [{ quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier' }] },
          { '@type': 'type.googleapis.com/google.rpc.RetryInfo', retryDelay: '42s' },
        ],
      },
    });
    const e = parseRateLimit(body, null);
    expect(e.daily).toBe(true);
    expect(e.retryAfterSec).toBe(42);
  });

  it('1分あたりの上限なら daily = false。本文が JSON でなければヘッダーの値を使う', () => {
    expect(parseRateLimit(JSON.stringify({ error: { details: [{ violations: [{ quotaId: 'PerMinute' }] }] } }), null).daily).toBe(false);
    expect(parseRateLimit('not json', '7').retryAfterSec).toBe(7);
  });
});

describe('annotateBatch', () => {
  const fakeFetch = (status: number, body: unknown) =>
    (async () => new Response(typeof body === 'string' ? body : JSON.stringify(body), { status })) as unknown as typeof fetch;

  it('成功したら注釈を返す', async () => {
    const out = await annotateBatch(chunk, { apiKey: 'k', fetchImpl: fakeFetch(200, geminiResponse([item(0), item(1)])) });
    expect(out).toHaveLength(2);
  });

  it('402 は GeminiBillingError、429 は GeminiRateLimitError', async () => {
    await expect(annotateBatch(chunk, { apiKey: 'k', fetchImpl: fakeFetch(402, '{"error":{"message":"credits depleted"}}') })).rejects.toBeInstanceOf(GeminiBillingError);
    await expect(annotateBatch(chunk, { apiKey: 'k', fetchImpl: fakeFetch(429, '{}') })).rejects.toBeInstanceOf(GeminiRateLimitError);
  });

  it('既定のモデルはバージョンを固定したもの', async () => {
    let url = '';
    const spy = (async (u: string) => {
      url = u;
      return new Response(JSON.stringify(geminiResponse([])));
    }) as unknown as typeof fetch;
    await annotateBatch(chunk, { apiKey: 'k', fetchImpl: spy });
    expect(url).toContain(`/models/${ANNOTATE_PARAMS.defaultModel}:generateContent`);
    expect(ANNOTATE_PARAMS.defaultModel).not.toContain('latest');
  });
});
