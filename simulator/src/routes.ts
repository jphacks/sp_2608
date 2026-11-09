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
  {
    id: 'hakodate-onuma',
    name: '函館駅 → 大沼公園',
    start: { lat: 41.773436, lon: 140.726639 },
    headingDegrees: 116.0,
    destination: { name: '大沼公園', position: { lat: 41.9808, lon: 140.6701 } },
    km: 27.0,
    note: 'ピッチで比べている道。街なかから峠を越えて湖へ',
  },
  {
    id: 'sapporo-jozankei',
    name: '札幌 石山 → 定山渓',
    start: { lat: 42.943609, lon: 141.317415 },
    headingDegrees: 43.9,
    destination: { name: '定山渓温泉', position: { lat: 42.97, lon: 141.1664 } },
    km: 16.7,
    note: '札幌の郊外から山あいの温泉街へ。豊平川に沿う',
  },
  {
    id: 'biei-aoiike',
    name: '美瑛駅 → 青い池',
    start: { lat: 43.588398, lon: 142.466396 },
    headingDegrees: 157.9,
    destination: { name: '青い池', position: { lat: 43.4936, lon: 142.6143 } },
    km: 16.6,
    note: '丘と畑の中の道',
  },
  {
    id: 'ogon-road',
    name: '黄金道路（広尾 → えりも岬）',
    start: { lat: 42.286832, lon: 143.316841 },
    headingDegrees: 150.1,
    destination: { name: '襟裳岬', position: { lat: 41.9244, lon: 143.2494 } },
    km: 44.9,
    note: '海沿いの崖の道。ストアの見出し画像と同じ道',
  },
]
