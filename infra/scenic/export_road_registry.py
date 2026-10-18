"""Export the published registry and its unresolved entries for review."""

import argparse
from collections import Counter
from contextlib import closing
import json
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


def geojson(path):
    metadata, _, records, parts = read_registry(path)
    features = []
    for r in records:
        if not parts.get(r['id']):
            continue
        features.append({'type': 'Feature', 'properties': {
            'id': r['id'], 'name': r['name'], 'status': r['status'],
            'proposed_rank': r['rank'], 'publication_issues': r['publication_issues']},
            'geometry': {'type': 'MultiLineString', 'coordinates': [p['coordinates'] for p in parts[r['id']]]}})
    return {'type': 'FeatureCollection', 'metadata': metadata, 'features': features}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('registry')
    parser.add_argument('--format', choices=('markdown', 'geojson'), default='markdown')
    args = parser.parse_args()
    print(markdown(args.registry) if args.format == 'markdown' else
          json.dumps(geojson(args.registry), ensure_ascii=False, separators=(',', ':')),
          end='' if args.format == 'markdown' else '\n')
