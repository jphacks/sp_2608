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
  /** 目的地の英語の名前。道を選ぶ画面に出す */
  en: string
  /**
   * デモで走る区間。道の始まりからの道のり（メートル）の [始まり, 終わり]。特に見てほしい所だけを走る。
   * 無ければ道の始まりから目的地まで
   */
  demo?: { normal: [number, number]; scenic: [number, number] }
  /** 道を選ぶ画面の背景の写真。使ってよい条件（ライセンス）がはっきりしたものだけ。credit は画面に出す出典 */
  photo?: { url: string; credit: string }
}

export const ROUTES: readonly DemoRoute[] = [
  // 短い道（5〜15分で走り比べられる）。出発点と向きは、走り比べのデータ（public/compare）の道の始まりを使う
  {
    id: 'hakodate-tomoe',
    en: 'HAKODATE STATION',
    name: '万代町 → 函館駅（ともえ大橋）',
    start: { lat: 41.79004, lon: 140.728635 },
    headingDegrees: 194.0,
    destination: { name: '函館駅', position: { lat: 41.773436, lon: 140.726639 } },
    km: 2.3,
    note: '約3kmの短い道。おすすめの道は、函館港の上を渡るともえ大橋を通る',
    // おすすめの道は、橋の上り口から海の上を渡りきるまで。ふつうの道は、同じ辺りの国道5号
    demo: { normal: [500, 1900], scenic: [500, 1950] },
    // https://commons.wikimedia.org/wiki/File:%E5%87%BD%E9%A4%A8%E6%B9%BE%E5%B2%B8%E5%A4%A7%E6%A9%8B.jpg
    photo: { url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dd/%E5%87%BD%E9%A4%A8%E6%B9%BE%E5%B2%B8%E5%A4%A7%E6%A9%8B.jpg/1920px-%E5%87%BD%E9%A4%A8%E6%B9%BE%E5%B2%B8%E5%A4%A7%E6%A9%8B.jpg', credit: '写真：Ryo FUKAsawa（CC BY 2.0、Wikimedia Commons）' },
  },
  {
    id: 'onuma-lake',
    en: 'HIGASHI-ONUMA',
    name: '大沼公園駅 → 東大沼',
    start: { lat: 41.967686, lon: 140.668872 },
    headingDegrees: 197.3,
    destination: { name: '東大沼', position: { lat: 42.0007, lon: 140.7118 } },
    km: 6.5,
    note: '大沼の湖畔。おすすめの道は湖を回る道道338号を通る',
    // おすすめの道は、湖の区間がいちばん長く続く所。ふつうの道は、林の中を抜ける所
    demo: { normal: [4300, 5700], scenic: [3100, 4700] },
    // https://commons.wikimedia.org/wiki/File:Onuma_Park_Nanae_Hokkaido_Japan01s5.jpg
    photo: { url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f5/Onuma_Park_Nanae_Hokkaido_Japan01s5.jpg/1920px-Onuma_Park_Nanae_Hokkaido_Japan01s5.jpg', credit: '写真：663highland（CC BY 2.5、Wikimedia Commons）' },
  },
  {
    id: 'wakkanai-noshappu',
    en: 'CAPE NOSHAPPU',
    name: '稚内駅 → ノシャップ岬',
    start: { lat: 45.417307, lon: 141.676764 },
    headingDegrees: 249.0,
    destination: { name: 'ノシャップ岬', position: { lat: 45.448782, lon: 141.644754 } },
    km: 4.5,
    note: 'おすすめの道は、稚内港の海沿いを通る',
    // https://commons.wikimedia.org/wiki/File:The_lighthouse_at_Cape_Noshappu,_Wakkanai,_Japan.jpg
    photo: { url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ed/The_lighthouse_at_Cape_Noshappu%2C_Wakkanai%2C_Japan.jpg/1920px-The_lighthouse_at_Cape_Noshappu%2C_Wakkanai%2C_Japan.jpg', credit: '写真：OKJaguar（CC BY 4.0、Wikimedia Commons）' },
  },
  {
    id: 'rumoi-ogon',
    en: 'CAPE OGON',
    name: '留萌駅 → 黄金岬',
    start: { lat: 43.939985, lon: 141.653791 },
    headingDegrees: 258.5,
    destination: { name: '黄金岬', position: { lat: 43.947591, lon: 141.630582 } },
    km: 2.9,
    note: 'おすすめの道は、日本海の海沿いを通る',
    // https://commons.wikimedia.org/wiki/File:%E9%BB%84%E9%87%91%E5%B2%AC_-_panoramio.jpg
    photo: { url: 'https://upload.wikimedia.org/wikipedia/commons/b/be/%E9%BB%84%E9%87%91%E5%B2%AC_-_panoramio.jpg', credit: '写真：Yobito KAYANUMA（CC BY-SA 3.0、Wikimedia Commons）' },
  },
]
