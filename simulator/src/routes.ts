import type { LatLon } from './geo'

/**
 * デモで走る道。出発点と向きは、旅れこの経路サーバー（Valhalla）で出発地から目的地まで引いた経路の、
 * 最初の点と最初の15メートルの向き（2026-11-05 に取得）。旅れこの目的地の検索には destination の名前を入れる。
 * 名前と位置は、旅れこの検索でその場所そのものが1件目に出る書き方と、その1件目の位置（2026-11-06 に確認）。
 */
export interface DemoRoute {
  id: string
  name: string
  start: LatLon
  headingDegrees: number
  destination: { name: string; position: LatLon }
  /** 最短ルートの距離（キロメートル） */
  km: number
  note: string
}

export const ROUTES: readonly DemoRoute[] = [
  // 短い道（5〜15分で走り比べられる）。出発点と向きは、走り比べのデータ（public/compare）の道の始まりを使う
  {
    id: 'hakodate-tomoe',
    name: '万代町 → 函館駅（ともえ大橋）',
    start: { lat: 41.79004, lon: 140.728635 },
    headingDegrees: 194.0,
    destination: { name: '函館駅', position: { lat: 41.773436, lon: 140.726639 } },
    km: 2.3,
    note: '約3kmの短い道。おすすめの道は、函館港の上を渡るともえ大橋を通る',
  },
  {
    id: 'onuma-lake',
    name: '大沼公園駅 → 東大沼',
    start: { lat: 41.967686, lon: 140.668872 },
    headingDegrees: 197.3,
    destination: { name: '東大沼', position: { lat: 42.0007, lon: 140.7118 } },
    km: 6.5,
    note: '大沼の湖畔。おすすめの道は湖を回る道道338号を通る',
  },
  {
    id: 'wakkanai-noshappu',
    name: '稚内駅 → ノシャップ岬',
    start: { lat: 45.417307, lon: 141.676764 },
    headingDegrees: 249.0,
    destination: { name: 'ノシャップ岬', position: { lat: 45.448782, lon: 141.644754 } },
    km: 4.5,
    note: 'おすすめの道は、稚内港の海沿いを通る',
  },
  {
    id: 'rumoi-ogon',
    name: '留萌駅 → 黄金岬',
    start: { lat: 43.939985, lon: 141.653791 },
    headingDegrees: 258.5,
    destination: { name: '黄金岬', position: { lat: 43.947591, lon: 141.630582 } },
    km: 2.9,
    note: 'おすすめの道は、日本海の海沿いを通る',
  },
]
