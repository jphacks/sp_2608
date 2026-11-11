import { scenery, type ComparedRoute } from './compare'

/**
 * 道のレコード。旅れこのモチーフのレコードに、道を刻む。
 * 旅れこの道路台帳（docs/scenic-road-registry.md）に本登録された、名のある道だけをレコードにする。
 * - 溝：景色のいい道の景色を、外周（出発地）から内側（目的地）へ、その色の輪で刻む
 * - ラベル：道の絵と名前。絵の無い道は名前だけ
 * - 後ろのジャケット：道ごとの色
 * 色は旅れこのアプリと同じ（style.css の :root）。
 */
const RECORDS: Record<string, { title: string; caption: string; sleeve: string; art?: string }> = {
  'hakodate-tomoe': {
    title: 'ともえ大橋',
    caption: '函館湾岸',
    sleeve: 'var(--sunset)',
    // 奥に函館山、手前の海の上へ橋が弧を描いて延びる（200×200 の中に描き、ラベルの大きさに縮める）
    art: `
      <rect width="200" height="200" fill="var(--paper)"/>
      <circle cx="150" cy="48" r="15" fill="var(--sunset)"/>
      <path d="M0 104 C16 78 44 50 76 46 C100 44 112 58 128 70 C142 80 160 92 200 100 V200 H0 Z" fill="var(--mountains)"/>
      <path d="M0 102 H200 V200 H0 Z" fill="var(--sky)"/>
      <g fill="var(--paper)">
        <rect x="56" y="131" width="7" height="40"/><rect x="90" y="120" width="7" height="50"/>
        <rect x="124" y="107" width="6" height="60"/><rect x="152" y="96" width="5" height="70"/>
      </g>
      <path d="M-4 146 Q86 132 204 70 L202 58 Q84 112 -4 124 Z" fill="var(--paper)" stroke="var(--mountains)" stroke-width="3" stroke-linejoin="round"/>
      <path d="M-2 135 Q86 122 203 64" fill="none" stroke="var(--mountains)" stroke-width="3" stroke-dasharray="9 7"/>`,
  },
  'onuma-lake': { title: '大沼湖畔', caption: '道道338号', sleeve: 'var(--sky)' },
}

/** レコードの中心と大きさ（viewBox 220×220） */
const CX = 124
const CY = 120
const DISC = 92
const LABEL = 46
/** 景色を刻む溝の、外側（出発地）と内側（目的地）の半径 */
const OUTER = 88
const INNER = 51

export function roadRecord(id: string, scenic?: ComparedRoute): string | undefined {
  const record = RECORDS[id]
  if (!record) return undefined
  const total = scenic?.timeline.at(-1)?.[1] ?? 0
  const radius = (meters: number) => OUTER - (OUTER - INNER) * meters / total
  const rings = total === 0 ? '' : scenic!.timeline
    .filter(([, , category]) => category !== 'urban' && scenery(category).label !== 'その他')
    .map(([start, end, category]) => {
      const [outer, inner] = [radius(start), radius(end)]
      return `<circle cx="${CX}" cy="${CY}" r="${((outer + inner) / 2).toFixed(2)}" fill="none" stroke="${scenery(category).color}" stroke-width="${(outer - inner).toFixed(2)}" opacity="0.45"/>`
    }).join('')
  const grooves = Array.from({ length: Math.floor((OUTER - INNER) / 3.5) + 1 }, (_, i) =>
    `<circle cx="${CX}" cy="${CY}" r="${(INNER + i * 3.5).toFixed(1)}"/>`).join('')
  const arc = (r: number, from: number, to: number) => {
    const point = (deg: number) => [CX + r * Math.cos(deg * Math.PI / 180), CY + r * Math.sin(deg * Math.PI / 180)].map((v) => v.toFixed(1)).join(' ')
    return `M${point(from)} A${r} ${r} 0 0 1 ${point(to)}`
  }
  const size = LABEL * 2 / 200
  return `
    <svg class="course-badge" viewBox="0 0 220 220" role="img" aria-label="${record.title}">
      <defs><clipPath id="label-${id}"><circle cx="${CX}" cy="${CY}" r="${LABEL}"/></clipPath></defs>
      <rect x="4" y="4" width="160" height="160" fill="${record.sleeve}"/>
      <circle cx="${CX}" cy="${CY}" r="${DISC}" fill="var(--ink)"/>
      ${rings}
      <g fill="none" stroke="rgba(245, 243, 236, 0.1)" stroke-width="0.7">${grooves}</g>
      <path d="${arc(76, 200, 240)} ${arc(70, 20, 60)}" fill="none" stroke="rgba(245, 243, 236, 0.35)" stroke-width="2.5" stroke-linecap="round"/>
      <circle cx="${CX}" cy="${CY}" r="${LABEL}" fill="var(--paper)"/>
      ${record.art ? `<g clip-path="url(#label-${id})"><g transform="translate(${CX - LABEL} ${CY - LABEL}) scale(${size})">${record.art}</g></g>` : ''}
      <rect x="${CX - LABEL}" y="${CY + 8}" width="${LABEL * 2}" height="22" fill="var(--mountains)" clip-path="url(#label-${id})"/>
      <text x="${CX}" y="${CY + 24}" text-anchor="middle" fill="var(--paper)" font-size="${record.title.length > 5 ? 12 : 14}" font-weight="900">${record.title}</text>
      <text x="${CX}" y="${CY + 39}" text-anchor="middle" fill="var(--mountains)" font-size="7.5" font-weight="700" letter-spacing="2">${record.caption}</text>
      <circle cx="${CX}" cy="${CY}" r="3.5" fill="var(--ink)"/>
    </svg>`
}
