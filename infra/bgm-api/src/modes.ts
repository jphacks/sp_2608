// 選曲モードの定義。推薦ロジックは1本で、モードは「候補の範囲」と「重みの定数セット」だけで切り替える。
// DB・HTTPに依存しない（後で端末側に移植できるように）

export const MODE_IDS = ['discover', 'blend', 'playlist'] as const;
export type ModeId = (typeof MODE_IDS)[number];
// v1 の本命は全曲から選ぶ形。mode を省略したときは blend にする
export const DEFAULT_MODE: ModeId = 'blend';

// 画面に出す文言。識別子とは切り離して、ここだけ直せば表示を変えられるようにする
export const MODE_LABELS: Record<ModeId, { label: string; shortLabel: string; description: string }> = {
  discover: {
    label: 'すべての曲から',
    shortLabel: 'すべての曲から',
    description: '景色と車内の雰囲気だけで選びます',
  },
  blend: {
    label: 'すべての曲から（好みを反映）',
    shortLabel: 'すべての曲から＋好み', // 画面の幅が足りないとき用
    description: 'みんなのプレイリストに近い曲を優先します',
  },
  playlist: {
    label: 'みんなのプレイリストから',
    shortLabel: 'みんなのプレイリストから',
    description: '同乗者のプレイリストの曲だけを流します',
  },
};

export type ModeParams = {
  // 候補の範囲。owned は、誰かのプレイリストにある曲だけ
  pool: 'all' | 'owned';
  // false なら所有者の情報を完全に無視する（所有者の偏り・共有・プレイリストの加点がすべて 0）
  useOwners: boolean;
  // 以下はスコアの係数。popularity と shared には、静か・名道区間で computeTarget の famBoost を掛ける
  popularity: number; // 知名度（0〜1）1あたりの加点
  shared: number; // 複数人のリストにある曲の加点
  owner: number; // 所有者の偏りの減点
  prefTrack: number; // 誰かのリストにある曲の加点（blend）
  prefArtist: number; // リストにある曲と同じアーティストの曲の加点（blend）
};

// 初期値。blend の prefTrack・prefArtist は、加点を受けていない曲が5曲に1曲くらい選ばれるよう調整した値
// （疑似カタログ：600曲・300アーティスト・リストにある曲12%で約20%。test/modes.spec.ts で確認している。
//   実カタログができたら、npm run simulate の集計で調整し直す）
export const MODE_PARAMS: Record<ModeId, ModeParams> = {
  discover: { pool: 'all', useOwners: false, popularity: 0.05, shared: 0, owner: 0, prefTrack: 0, prefArtist: 0 },
  blend: { pool: 'all', useOwners: true, popularity: 0.1, shared: 0.1, owner: 0.4, prefTrack: 0.1, prefArtist: 0.04 },
  playlist: { pool: 'owned', useOwners: true, popularity: 0, shared: 0.1, owner: 0.4, prefTrack: 0, prefArtist: 0 },
};

export function isModeId(x: unknown): x is ModeId {
  return typeof x === 'string' && (MODE_IDS as readonly string[]).includes(x);
}
