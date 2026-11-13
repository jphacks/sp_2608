"""Export the published registry and its unresolved entries for review."""

import argparse
from collections import Counter
from contextlib import closing
import json
import math
from pathlib import Path
import sqlite3

from recommended_roads import RANKS


def read_registry(path):
    with closing(sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)) as db:
        metadata = dict(db.execute('SELECT key,value FROM metadata'))
        sources = {ident: json.loads(raw) for ident, raw in db.execute('SELECT id,payload FROM source')}
        records = [json.loads(raw) for raw, in db.execute('SELECT payload FROM recommendation ORDER BY id')]
        parts = {}
        for ident, raw in db.execute('SELECT recommendation_id,payload FROM road_part ORDER BY recommendation_id,sequence'):
            parts.setdefault(ident, []).append(json.loads(raw))
    return metadata, sources, records, parts


def markdown(path):
    metadata, sources, records, parts = read_registry(path)
    counts = Counter((r['status'], r['rank']) for r in records)
    lines = ['# 北海道 景観道路台帳', '',
             '推薦DBから生成した道路台帳。更新は原稿とDBへ反映し、再生成します。', '',
             f'- 選定基準: `{metadata["selection_policy"]}` / 形状検査: `{metadata["geometry_policy"]}`',
             f'- DB版: `{metadata["version"]}`',
             f'- 地図版: `{metadata["map_version"]}`',
             f'- DB生成時刻（UTC）: {metadata["built_at"]}', '',
             '## 件数', '', '| 区分 | 本登録 | 保留 |', '| --- | ---: | ---: |']
    for rank, label in RANKS.items():
        lines.append(f'| {label["label"]} | {counts["active", rank]} | {counts["pending", rank]} |')
    lines.extend(['', '件数は推薦道路・認定区間のID数です。物理的な道路の重複を除いた路線数ではありません。', '',
                  '保留は本登録ではなく、探索時の推薦ランク・推薦加点・マーカーの対象外です。',
                  '地理特徴からの未登録候補は別の地理カタログから探索され、この台帳の件数には含めません。', '',
                  '## 道路一覧', '', '| ID | 道路・区間名 | 区分 | 状態 | 照合延長 | 接続成分 |',
                  '| --- | --- | --- | --- | ---: | ---: |'])
    for r in records:
        summary = r['geometry_summary']
        lines.append(f'| `{r["id"]}` | {r["name"]} | {RANKS[r["rank"]]["label"]} | '
                     f'{"本登録" if r["status"] == "active" else "保留"} | '
                     f'{summary["matched_meters"] / 1000:.2f} km | {summary["component_count"]} |')
    lines.extend(['', '照合延長はOSM道路サンプルの合計で、上下線や枝道を含み得ます。',
                  '認定区間は公開線形に従い、複数の接続成分を持つ場合もそのまま保持します。',
                  '端点は形状の座標（緯度, 経度）で、観光資料が示す正式な地名・測量座標ではありません。', '',
                  '## 道路ごとの範囲・根拠', ''])
    for r in records:
        lines.extend([f'### {r["name"]}（{r["id"]}）', '',
                      f'- 状態: {"本登録" if r["status"] == "active" else "保留（未登録）"}',
                      f'- 区分: {RANKS[r["rank"]]["label"]} / 重み {RANKS[r["rank"]]["weight"]}'])
        descriptions = set()

        def describe(label, value):
            if value and value not in descriptions:
                lines.append(f'- {label}: {value}')
                descriptions.add(value)

        describe('対象範囲', r.get('extent', {}).get('description', r.get('scope_note', '未確認')))
        describe('注意', r.get('scope_note'))
        for issue in r.get('publication_issues', []):
            lines.append('- 保留理由: ' + issue)
        review = r.get('research_review')
        if review:
            lines.append(f'- 調査: {review["method"]} / {review["checked_on"]}')
            for key, label in (('selection_reason', '選定理由'), ('section_reason', '区間の決め方'),
                               ('duplicate_check', '重複確認'), ('cautions', '調査時の注意')):
                describe(label, review[key])
            for finding in review['findings']:
                source = sources[finding['source_id']]
                lines.append(f'- 本文確認: [{finding["locator"]}]({source["source_url"]}) — {finding["fact"]}')
        for ident in r.get('road_introduction_source_ids', []):
            source = sources[ident]
            lines.append(f'- 道路紹介: [{source["publisher"]}]({source["source_url"]})（`{ident}`）')
            if source.get('review_note'):
                lines.append('  - 確認内容: ' + source['review_note'])
        for index, part in enumerate(parts.get(r['id'], []), 1):
            a, b = part['coordinates'][0], part['coordinates'][-1]
            lines.append(f'- 形状部分 {index}: ({a[1]:.7f}, {a[0]:.7f}) → ({b[1]:.7f}, {b[0]:.7f}) / '
                         f'{part["meters"] / 1000:.2f} km')
        lines.append('')
    return '\n'.join(lines).rstrip() + '\n'


def simplify(line, tolerance_meters):
    """Douglas-Peucker で線を間引く。端点は残す。距離は線の緯度で縮めた平面で測る（数 km の道なら十分正確）。"""
    if tolerance_meters <= 0 or len(line) <= 2:
        return line
    scale = math.cos(math.radians(sum(p[1] for p in line) / len(line)))

    def xy(p):
        return p[0] * 111_320 * scale, p[1] * 110_574

    points = [xy(p) for p in line]
    keep = [False] * len(line)
    keep[0] = keep[-1] = True
    stack = [(0, len(line) - 1)]
    while stack:
        first, last = stack.pop()
        (ax, ay), (bx, by) = points[first], points[last]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        farthest, index = 0.0, None
        for i in range(first + 1, last):
            px, py = points[i]
            gap = (abs(dy * px - dx * py + bx * ay - by * ax) / length) if length else math.hypot(px - ax, py - ay)
            if gap > farthest:
                farthest, index = gap, i
        if index is not None and farthest > tolerance_meters:
            keep[index] = True
            stack += [(first, index), (index, last)]
    return [p for p, k in zip(line, keep) if k]


def geojson(path, ids=None, simplify_meters=0.0, precision=None):
    """台帳の道の形。ids を渡すとその道だけにし、アプリに入れる用に線を間引いて桁を落とせる。"""
    metadata, _, records, parts = read_registry(path)
    if ids is not None:
        missing = set(ids) - {r['id'] for r in records}
        if missing:
            raise SystemExit('Unknown road ids: ' + ', '.join(sorted(missing)))

    def line(coordinates):
        simplified = simplify(coordinates, simplify_meters)
        return [[round(lon, precision), round(lat, precision)] for lon, lat in simplified] if precision else simplified

    features = []
    for r in records:
        if not parts.get(r['id']) or (ids is not None and r['id'] not in ids):
            continue
        features.append({'type': 'Feature', 'properties': {
            'id': r['id'], 'name': r['name'], 'status': r['status'],
            'proposed_rank': r['rank'], 'publication_issues': r['publication_issues']},
            'geometry': {'type': 'MultiLineString', 'coordinates': [line(p['coordinates']) for p in parts[r['id']]]}})
    return {'type': 'FeatureCollection', 'metadata': metadata, 'features': features}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('registry')
    parser.add_argument('--format', choices=('markdown', 'geojson'), default='markdown')
    parser.add_argument('--ids', help='geojson: comma-separated road ids to export (e.g. the road records in the app)')
    parser.add_argument('--simplify-meters', type=float, default=0.0, help='geojson: Douglas-Peucker tolerance')
    parser.add_argument('--precision', type=int, help='geojson: decimal places of coordinates')
    args = parser.parse_args()
    ids = set(args.ids.split(',')) if args.ids else None
    print(markdown(args.registry) if args.format == 'markdown' else
          json.dumps(geojson(args.registry, ids, args.simplify_meters, args.precision), ensure_ascii=False, separators=(',', ':')),
          end='' if args.format == 'markdown' else '\n')
