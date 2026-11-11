import { scenery, type ComparedRoute } from './compare'

/**
 * 道のレコード。旅れこのモチーフのレコードに、道を刻む。
 * 旅れこの道路台帳（docs/scenic-road-registry.md）に本登録された、名のある道だけをレコードにする。
 * - 溝：景色のいい道の景色を、外周（出発地）から内側（目的地）へ、その色でうっすら染める
 * - ラベル：道の写真をもとにした黒一色のイラストと、太い英字の名前
 * 色は旅れこのアプリと同じ（style.css の :root）。
 */
const RECORDS: Record<string, { title: string; place: string; label: string; image: string }> = {
  'hakodate-tomoe': {
    title: 'TOMOE BRIDGE',
    place: 'HAKODATE · HOKKAIDO',
    label: 'var(--sunglow)',
    // 背景と同じ写真（Ryo FUKAsawa、CC BY 2.0、Wikimedia Commons）をもとに、Canva の画像生成で黒一色のイラストにしたもの
    image: '/records/hakodate-tomoe.png',
  },
  'onuma-lake': {
    title: 'LAKE ONUMA',
    place: 'NANAE · HOKKAIDO',
    label: 'var(--mist)',
    // 東大沼キャンプ場の湖の写真（Photos of Japan、CC0、Wikimedia Commons）https://commons.wikimedia.org/wiki/File:Lake_%C5%8Cnuma_East_Camping_Ground.jpg をもとに、Canva の画像生成で黒一色のイラストにしたもの
    image: '/records/onuma-lake.png',
  },
}

/** レコードの中心と大きさ（viewBox 200×200） */
const C = 100
const DISC = 96
/** ラベルの外周と、絵の円の半径 */
const LABEL = 64
const PICTURE = 45
/** 景色を刻む溝の、外側（出発地）と内側（目的地）の半径 */
const OUTER = 92
const INNER = 68

export function roadRecord(id: string, scenic?: ComparedRoute): string | undefined {
  const record = RECORDS[id]
  if (!record) return undefined
  const total = scenic?.timeline.at(-1)?.[1] ?? 0
  const radius = (meters: number) => OUTER - (OUTER - INNER) * meters / total
  const tracks = total === 0 ? '' : scenic!.timeline
    .filter(([, , category]) => category !== 'urban' && scenery(category).label !== 'その他')
    .map(([start, end, category]) => {
      const [outer, inner] = [radius(start), radius(end)]
      return `<circle cx="${C}" cy="${C}" r="${((outer + inner) / 2).toFixed(2)}" stroke="${scenery(category).color}" stroke-width="${(outer - inner).toFixed(2)}"/>`
    }).join('')
  const grooves = Array.from({ length: Math.floor((OUTER - INNER) / 1.5) + 1 }, (_, i) =>
    `<circle cx="${C}" cy="${C}" r="${(INNER + i * 1.5).toFixed(1)}"/>`).join('')
  /** 中心から外へ開く扇形（光の反射） */
  const wedge = (from: number, to: number) => {
    const at = (deg: number) => `${(C + DISC * Math.cos(deg * Math.PI / 180)).toFixed(1)} ${(C + DISC * Math.sin(deg * Math.PI / 180)).toFixed(1)}`
    return `M${C} ${C} L${at(from)} A${DISC} ${DISC} 0 0 1 ${at(to)} Z`
  }
  // 名前を書く円弧。上は左から右へ、下は文字が逆さにならないよう左から右へ下側を回る
  const ring = (LABEL + PICTURE) / 2
  // 上の名前は円弧の外側へ文字が伸びるので、絵の縁のすぐ外を文字の下端にして、ラベルからはみ出さないようにする
  const topRing = PICTURE + 4
  return `
    <svg class="course-badge" viewBox="0 0 200 200" role="img" aria-label="${record.title}">
      <defs>
        <clipPath id="picture-${id}"><circle cx="${C}" cy="${C}" r="${PICTURE}"/></clipPath>
        <path id="top-${id}" d="M${C - topRing} ${C} A${topRing} ${topRing} 0 0 1 ${C + topRing} ${C}"/>
        <path id="bottom-${id}" d="M${C - ring} ${C} A${ring} ${ring} 0 0 0 ${C + ring} ${C}"/>
      </defs>
      <g class="disc">
        <circle cx="${C}" cy="${C}" r="${DISC}" fill="#151a17"/>
        <g fill="none" opacity="0.32">${tracks}</g>
        <g fill="none" stroke="rgba(245, 243, 236, 0.07)" stroke-width="0.5">${grooves}</g>
        <path d="${wedge(-62, -38)} ${wedge(118, 142)}" fill="rgba(245, 243, 236, 0.08)"/>
        <circle cx="${C}" cy="${C}" r="${DISC - 0.5}" fill="none" stroke="rgba(245, 243, 236, 0.18)" stroke-width="1"/>
        <circle cx="${C}" cy="${C}" r="${LABEL}" fill="${record.label}"/>
        <image href="${record.image}" x="${C - PICTURE}" y="${C - PICTURE}" width="${PICTURE * 2}" height="${PICTURE * 2}"
          preserveAspectRatio="xMidYMid slice" clip-path="url(#picture-${id})"/>
        <circle cx="${C}" cy="${C}" r="${PICTURE}" fill="none" stroke="var(--ink)" stroke-width="1.4"/>
        <g class="stamp" fill="var(--ink)" text-anchor="middle">
          <text font-size="13.5" letter-spacing="0.6"><textPath href="#top-${id}" startOffset="50%">${record.title}</textPath></text>
          <text font-size="7.5" letter-spacing="1" dominant-baseline="hanging"><textPath href="#bottom-${id}" startOffset="50%">${record.place}</textPath></text>
        </g>
        <circle cx="${C}" cy="${C}" r="2.8" fill="#151a17"/>
      </g>
    </svg>`
}
