// Gemini による曲の注釈（6軸・信頼度・日本での認知度・タグ）。
// Worker の /annotate と、カタログ作成スクリプト（scripts/catalog.ts）の両方から使う。
// fetch 以外の実行環境（Workers・D1・Node）には依存しない

export const ANNOTATE_PARAMS = {
  // 注釈の基準を揃えるため、"-latest" のような別名ではなくバージョンを固定する
  defaultModel: 'gemini-3.8-flash',
  // 思考の深さ。曲の知識を引き出す作業なので深い推論は要らず、トークンと待ち時間を抑える
  thinkingLevel: 'low',
  promptVersion: 'annot-v2',
  batchSize: 80, // 1リクエストで注釈する曲数（無料枠は1日20リクエストなので大きめにする）
  unknownConfFactor: 0.5, // LLM が「知らない」と答えた曲の conf に掛ける倍率
};

export type AnnotateInput = {
  track_id: string;
  title: string;
  artist: string;
  duration_ms?: number | null;
};

export type Annotation = {
  track_id: string;
  title: string;
  artist: string;
  duration_ms: number | null;
  energy: number;
  valence: number;
  tempo: number;
  acoustic: number;
  spacious: number;
  vocal: number;
  conf: number;
  jp_familiarity: number; // 日本の一般の人の認知度 0〜1
  known: boolean;
  tags: string[];
  bpm: number | null;
};

// 推定の基準を揃えるための曲（アンカー）。値は annot-v1 の注釈結果をもとに決めた初期値
const ANCHORS = [
  { title: '怪獣の花唄', artist: 'Vaundy', energy: 0.82, valence: 0.65, acoustic: 0.25, spacious: 0.45, vocal: 0.85, bpm: 150, jp_familiarity: 0.85 },
  { title: '糸', artist: '中島みゆき', energy: 0.35, valence: 0.48, acoustic: 0.75, spacious: 0.65, vocal: 0.92, bpm: 72, jp_familiarity: 0.95 },
  { title: 'Plastic Love', artist: '竹内まりや', energy: 0.72, valence: 0.65, acoustic: 0.3, spacious: 0.5, vocal: 0.85, bpm: 104, jp_familiarity: 0.6 },
  { title: 'Merry Christmas Mr. Lawrence', artist: '坂本龍一', energy: 0.4, valence: 0.45, acoustic: 0.85, spacious: 0.75, vocal: 0, bpm: 105, jp_familiarity: 0.75 },
  { title: '唱', artist: 'Ado', energy: 0.94, valence: 0.72, acoustic: 0.08, spacious: 0.25, vocal: 0.95, bpm: 135, jp_familiarity: 0.7 },
];

export const SYSTEM_PROMPT = `あなたは音楽キュレーターです。
入力は曲のリスト [{"i": 番号, "title": 曲名, "artist": アーティスト名}] です。
各曲について、次の値を推定してください。

- energy (0.0〜1.0): 0.2=静かなバラードやアンビエント / 0.5=ミディアム / 0.8=アップテンポで激しい
- valence (0.0〜1.0): 0.2=切ない・暗い / 0.5=どちらでもない / 0.8=明るく楽しい
- acoustic (0.0〜1.0): 0.2=電子音・打ち込みが中心 / 0.5=混在 / 0.8=生楽器・弾き語りが中心
- spacious (0.0〜1.0): 0.2=音が詰まった密な音像 / 0.5=標準 / 0.8=余白や残響が多く広がりがある
- vocal (0.0〜1.0): 0.0=インスト / 0.5=ボーカルは控えめ / 0.9=ボーカルが前面に出ている
- bpm (整数): 推定BPM。分からなければ 0
- jp_familiarity (0.0〜1.0): 日本の一般の人（20〜50代）が曲を聴いてすぐ分かる度合い。0.1=ほぼ知られていない / 0.5=音楽好きやファンなら知っている / 0.9=誰でも知っている国民的な曲
- known (真偽値): その曲を知っていれば true。知らなければ false にし、アーティストの作風から推定する
- conf (0.0〜1.0): 推定の自信度
- tags: ジャンル・ムード・季節を表す英小文字の短いタグを最大5個

基準となる曲の値（これに合わせて尺度を揃えること）:
${ANCHORS.map((a) => JSON.stringify(a)).join('\n')}

ルール:
- 入力の i をそのまま返し、すべての曲を返すこと
- 値はなるべく 0.0〜1.0 の幅を広く使い、すべて 0.5 付近に寄せないこと`;

export const RESPONSE_SCHEMA = {
  type: 'OBJECT',
  properties: {
    items: {
      type: 'ARRAY',
      items: {
        type: 'OBJECT',
        properties: {
          i: { type: 'INTEGER' },
          known: { type: 'BOOLEAN' },
          energy: { type: 'NUMBER' },
          valence: { type: 'NUMBER' },
          acoustic: { type: 'NUMBER' },
          spacious: { type: 'NUMBER' },
          vocal: { type: 'NUMBER' },
          bpm: { type: 'INTEGER' },
          jp_familiarity: { type: 'NUMBER' },
          conf: { type: 'NUMBER' },
          tags: { type: 'ARRAY', items: { type: 'STRING' } },
        },
        required: ['i', 'known', 'energy', 'valence', 'acoustic', 'spacious', 'vocal', 'bpm', 'jp_familiarity', 'conf', 'tags'],
      },
    },
  },
  required: ['items'],
};

// 429 のとき。daily = 1日あたりの上限に当たった（その日はもう呼ばない）
export class GeminiRateLimitError extends Error {
  constructor(
    message: string,
    readonly retryAfterSec: number | null,
    readonly daily: boolean,
  ) {
    super(message);
    this.name = 'GeminiRateLimitError';
  }
}

// 402：前払いのクレジットが無いなど、請求の問題でリクエストが断られた（待っても解決しない）
export class GeminiBillingError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'GeminiBillingError';
  }
}

export type AnnotateOptions = {
  apiKey: string;
  model?: string;
  thinkingLevel?: string;
  fetchImpl?: typeof fetch;
};

export function modelVersion(model: string): string {
  return `${model}/${ANNOTATE_PARAMS.promptVersion}`;
}

export function buildRequestBody(chunk: AnnotateInput[], thinkingLevel = ANNOTATE_PARAMS.thinkingLevel) {
  const list = chunk.map((t, i) => ({ i, title: t.title, artist: t.artist }));
  return {
    systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
    contents: [{ role: 'user', parts: [{ text: JSON.stringify(list) }] }],
    generationConfig: {
      responseMimeType: 'application/json',
      responseSchema: RESPONSE_SCHEMA,
      temperature: 0.2,
      thinkingConfig: { thinkingLevel },
    },
  };
}

export async function annotateBatch(chunk: AnnotateInput[], opts: AnnotateOptions): Promise<Annotation[]> {
  const model = opts.model || ANNOTATE_PARAMS.defaultModel;
  const doFetch = opts.fetchImpl ?? fetch;
  const res = await doFetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': opts.apiKey },
    body: JSON.stringify(buildRequestBody(chunk, opts.thinkingLevel)),
  });

  if (res.status === 429) {
    const body = await res.text();
    throw parseRateLimit(body, res.headers.get('retry-after'));
  }
  if (res.status === 402) {
    throw new GeminiBillingError(`Gemini 402: ${(await res.text()).slice(0, 300)}`);
  }
  if (!res.ok) {
    throw new Error(`Gemini ${res.status}: ${(await res.text()).slice(0, 300)}`);
  }
  return parseResponse(await res.json(), chunk);
}

// Gemini の応答 → 注釈。返ってこなかった曲は含めない（呼び出し側で次回に回す）
export function parseResponse(data: any, chunk: AnnotateInput[]): Annotation[] {
  const text: string = data?.candidates?.[0]?.content?.parts?.filter((p: any) => !p.thought).map((p: any) => p.text ?? '').join('') ?? '';
  const parsed = JSON.parse(text) as { items?: any[] };
  const out: Annotation[] = [];
  const seen = new Set<number>();
  for (const item of parsed.items ?? []) {
    const t = chunk[item.i];
    if (!t || seen.has(item.i)) continue;
    seen.add(item.i);
    const bpm = typeof item.bpm === 'number' && item.bpm > 0 ? Math.round(item.bpm) : null;
    const known = item.known === true;
    out.push({
      track_id: t.track_id,
      title: t.title,
      artist: t.artist,
      duration_ms: t.duration_ms ?? null,
      energy: clamp(item.energy),
      valence: clamp(item.valence),
      acoustic: clamp(item.acoustic),
      spacious: clamp(item.spacious),
      vocal: clamp(item.vocal),
      tempo: bpm ? clamp((bpm - 60) / 120) : 0.5,
      // 知らない曲は自信度を下げる
      conf: clamp(item.conf) * (known ? 1 : ANNOTATE_PARAMS.unknownConfFactor),
      jp_familiarity: clamp(item.jp_familiarity),
      known,
      tags: Array.isArray(item.tags) ? item.tags.slice(0, 5).map(String) : [],
      bpm,
    });
  }
  return out;
}

// 429 の本文から、待つ秒数と「1日の上限かどうか」を読み取る
export function parseRateLimit(body: string, retryAfterHeader: string | null): GeminiRateLimitError {
  let retry: number | null = retryAfterHeader ? Number(retryAfterHeader) || null : null;
  let daily = false;
  try {
    const details: any[] = JSON.parse(body)?.error?.details ?? [];
    for (const d of details) {
      if (typeof d.retryDelay === 'string') retry = parseFloat(d.retryDelay) || retry;
      for (const v of d.violations ?? []) {
        if (/PerDay/i.test(String(v.quotaId ?? ''))) daily = true;
      }
    }
  } catch {
    // 本文が JSON でなければ、ヘッダーの値だけ使う
  }
  return new GeminiRateLimitError(`Gemini 429${daily ? ' (daily quota)' : ''}: ${body.slice(0, 200)}`, retry, daily);
}

function clamp(n: unknown): number {
  const v = Number(n);
  if (!Number.isFinite(v)) return 0.5;
  return Math.min(1, Math.max(0, v));
}
