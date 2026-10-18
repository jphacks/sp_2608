"""Bind reviewed source facts to named OSM road samples; never infer official extents."""

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3

from catalog import Catalog
from recommended_roads import RANKS, normalize
from recommendation.models import identity
from road_geometry import published_sections, match_lines, trace_straight_road
from road_selection import POLICY, validate_selection
from registry_geometry import describe_geometry, publication_issues


def build(pbf, catalog_path, manifest_path, output, official_sections=None, model_courses=None, require_complete=False,
          require_official_complete=False):
    import osmium

    manifest = json.loads(Path(manifest_path).read_text())
    with Path(pbf).open('rb') as stream:
        checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
    records = manifest['roads']
    sections = published_sections(official_sections) if official_sections else {}
    if official_sections:
        if set(sections) != {f'selected-{i:02d}' for i in range(1, 16)} or any(not lines for lines in sections.values()):
            raise ValueError('Published archive must contain all 15 selected road centerlines')
        with Path(official_sections).open('rb') as stream:
            section_checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
        manifest['sources'].append({'source_id': 'sapporo-selected-2026', 'publisher': 'シーニックバイウェイ北海道推進協議会 / 札幌市',
            'source_url': 'https://ckan.pf-sapporo.jp/dataset/scenicbyway/resource/fee75228-c8ff-43b6-9c53-b67a7389b2ad',
            'license': 'CC-BY-4.0', 'version': section_checksum, 'acquired_at': datetime.now(timezone.utc).isoformat()})
    if model_courses:
        with Path(model_courses).open('rb') as stream:
            model_checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
        manifest['sources'].append({'source_id': 'sapporo-models-2026', 'publisher': 'シーニックバイウェイ北海道推進協議会 / 札幌市',
            'source_url': 'https://ckan.pf-sapporo.jp/dataset/scenicbyway/resource/30ca6984-bc52-49b5-8971-e9260d9dbaf5',
            'license': 'CC-BY-4.0', 'version': model_checksum, 'acquired_at': datetime.now(timezone.utc).isoformat()})
    sources = {s['source_id']: s for s in manifest['sources']}
    if len(sources) != len(manifest['sources']) or len({r['id'] for r in records}) != len(records):
        raise ValueError('Duplicate source or recommendation ID')
    for record in records:
        if record['rank'] not in RANKS or not record['source_ids'] or not set(record['source_ids']) <= sources.keys():
            raise ValueError('Invalid recommendation provenance')
        publishers = {sources[s]['publisher'] for s in record['source_ids']}
        if record['rank'] == 'signature' and record.get('designation') != 'selected':
            raise ValueError('Only selected official sections may use signature rank')
        if record['rank'] == 'panorama' and len(publishers) < 2:
            raise ValueError('Panorama rank requires two independent publishers')
    validate_selection(manifest, sources)
    output = Path(output)
    temporary = output.with_suffix(output.suffix + '.building')
    if output.exists() or temporary.exists():
        raise ValueError('Choose a new output filename; existing data is preserved')
    output.parent.mkdir(parents=True, exist_ok=True)
    aliases = defaultdict(list)
    for record in records:
        for name in record.get('osm_names', []):
            aliases[normalize(name)].append(record['id'])
    ways, way_refs = defaultdict(set), {}
    records_by_ref = defaultdict(list)
    for record in records:
        for ref in record.get('osm_refs', []):
            records_by_ref[ref].append(record['id'])

    class Handler(osmium.SimpleHandler):
        def way(self, way):
            if not way.tags.get('highway'):
                return
            way_refs[str(way.id)] = way.tags.get('ref', '').split(';')
            for ref in way_refs[str(way.id)]:
                for ident in records_by_ref.get(ref, []):
                    ways[ident].add(str(way.id))
            names = {normalize(name) for key in ('name', 'name:ja', 'alt_name', 'loc_name', 'official_name', 'bridge:name')
                     for name in way.tags.get(key, '').split(';') if name}
            for name in names:
                for ident in aliases.get(name, []):
                    ways[ident].add(str(way.id))

    Handler().apply_file(str(pbf))
    catalog = Catalog(catalog_path)
    if catalog.source.get('source_sha256') and catalog.source['source_sha256'] != checksum:
        catalog.close()
        raise ValueError('OSM extract differs from the scenic catalog source')
    db = sqlite3.connect(temporary)
    db.executescript('''
        CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
        CREATE TABLE source(id TEXT PRIMARY KEY,payload TEXT NOT NULL);
        CREATE TABLE recommendation(id TEXT PRIMARY KEY,status TEXT NOT NULL,payload TEXT NOT NULL);
        CREATE TABLE membership(road_id INTEGER NOT NULL,recommendation_id TEXT NOT NULL,
            PRIMARY KEY(road_id,recommendation_id));
        CREATE INDEX membership_recommendation ON membership(recommendation_id,road_id);
        CREATE TABLE road_part(recommendation_id TEXT NOT NULL,sequence INTEGER NOT NULL,payload TEXT NOT NULL,
            PRIMARY KEY(recommendation_id,sequence));
    ''')
    records_by_id = {r['id']: r for r in records}
    records_by_way = defaultdict(list)
    for ident, ids in ways.items():
        for way in ids:
            records_by_way[way].append(ident)
    matches, lengths, road_rows = defaultdict(list), defaultdict(float), {}
    for row in catalog.db.execute('SELECT id,way,lat1,lon1,lat2,lon2,length FROM road ORDER BY id'):
        for ident in records_by_way.get(row['way'], []):
            record = records_by_id[ident]
            bounds = record.get('bounds', catalog.bounds)
            west, south, east, north = bounds
            if all(south <= row[lat] <= north and west <= row[lon] <= east for lat, lon in [('lat1', 'lon1'), ('lat2', 'lon2')]):
                matches[ident].append(row['id'])
                lengths[ident] += row['length']
                road_rows[row['id']] = dict(row)
    national_refs = {1: ['453'], 2: ['230'], 3: ['276'], 5: ['334'], 6: ['238'],
                     8: ['243'], 10: ['273'], 11: ['230'], 13: ['243'], 14: ['228'], 15: ['123']}
    for ident, lines in sorted(sections.items()):
        ids, report = match_lines(catalog, lines, way_refs, national_refs.get(int(ident[-2:]), ()))
        print(json.dumps({'id': ident, **report}, ensure_ascii=False), flush=True)
        record = records_by_id[ident]
        record.update(source_ids=list(dict.fromkeys(record['source_ids'] + ['sapporo-selected-2026'])),
                      review_required=report['coverage'] < .9, geometry_match=report,
                      scope_note=f'札幌市公開の認定区間線形をOSM道路へ照合（線形対応率{report["coverage"]:.1%}）。対応した車道部分のみ。通行規制・現地景観は別途確認。')
        matches[ident], lengths[ident] = ids, report['matched_meters']
    if sections and not records_by_id['selected-11'].get('review_required', True):
        records_by_id.pop('selected-11-muine', None)
    for record in records_by_id.values():
        if record.get('straight_trace'):
            if 'sapporo-models-2026' not in sources:
                record['review_required'] = True
                continue
            trace = record['straight_trace']
            ids, report = trace_straight_road(catalog, tuple(reversed(trace['origin'])), trace['heading'], trace['meters'], record['bounds'])
            record.update(review_required=report['coverage'] < .95, geometry_match=report,
                          source_ids=list(dict.fromkeys(record['source_ids'] + ['sapporo-models-2026'])))
            matches[record['id']], lengths[record['id']] = ids, report.get('matched_meters', 0)
            print(json.dumps({'id': record['id'], **report}), flush=True)
    try:
        pending = []
        for source in sources.values():
            db.execute('INSERT INTO source VALUES (?,?)', (source['source_id'], json.dumps(source, ensure_ascii=False)))
        for ident, record in sorted(records_by_id.items()):
            ids = matches[ident]
            rows = [road_rows.get(road_id) or dict(catalog.db.execute('SELECT * FROM road WHERE id=?', (road_id,)).fetchone())
                    for road_id in ids]
            geometry = describe_geometry(rows)
            issues = publication_issues(record, geometry, official=ident in sections)
            status = 'pending' if issues else 'active'
            if status == 'pending':
                pending.append(ident)
            item = {**record, 'status': status, 'mapped_meters': round(lengths[ident] if status == 'active' else 0),
                    'publication_issues': issues,
                    'geometry_summary': {key: value for key, value in geometry.items() if key != 'parts'},
                    'scope': ('published_centerline' if ident in sections
                              else 'derived_straight_osm_segments' if record.get('straight_trace') else 'named_osm_segments'),
                    'weight': RANKS[record['rank']]['weight']}
            db.execute('INSERT INTO recommendation VALUES (?,?,?)', (ident, status, json.dumps(item, ensure_ascii=False)))
            for index, part in enumerate(geometry['parts']):
                db.execute('INSERT INTO road_part VALUES (?,?,?)', (ident, index, json.dumps(part)))
            if status == 'active':
                db.executemany('INSERT INTO membership VALUES (?,?)', ((road_id, ident) for road_id in ids))
        if require_complete and pending:
            raise ValueError('Unresolved recommendation sections: ' + ', '.join(pending))
        if require_official_complete and (len(sections) != 15 or any(ident in pending for ident in sections)):
            raise ValueError('All 15 official sections must be resolved')
        version = hashlib.sha256((json.dumps(records_by_id, sort_keys=True) + json.dumps(RANKS, sort_keys=True) + str(sorted(matches.items()))).encode()).hexdigest()[:32]
        db.executemany('INSERT INTO metadata VALUES (?,?)', [('schema', '1'), ('selection_policy', POLICY),
            ('geometry_policy', 'connected-v1'), ('version', version),
            ('map_version', catalog.source.get('map_version') or identity(catalog.source)), ('built_at', datetime.now(timezone.utc).isoformat()),
            ('catalog_signature', identity(catalog.source)),
            ('attribution', '道路形状: © OpenStreetMap contributors / ODbL-1.0。推薦情報: 各出典をもとに旅れこが編集。')])
        db.commit()
        print(json.dumps({'records': len(records_by_id), 'status': dict(db.execute('SELECT status,count(*) FROM recommendation GROUP BY status')),
                          'memberships': db.execute('SELECT count(*) FROM membership').fetchone()[0]}, ensure_ascii=False), flush=True)
    finally:
        db.close()
        catalog.close()
    temporary.rename(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('pbf')
    parser.add_argument('catalog')
    parser.add_argument('manifest')
    parser.add_argument('output')
    parser.add_argument('--official-sections')
    parser.add_argument('--model-courses')
    parser.add_argument('--require-complete', action='store_true')
    parser.add_argument('--require-official-complete', action='store_true')
    args = parser.parse_args()
    build(args.pbf, args.catalog, args.manifest, args.output, args.official_sections, args.model_courses,
          args.require_complete, args.require_official_complete)
