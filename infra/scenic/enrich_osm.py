import argparse
import hashlib
import json
from pathlib import Path
import sqlite3

import osmium


def attributes(tags):
    try:
        width = float(tags.get('width', ''))
        if not 1 <= width <= 50:
            width = None
    except ValueError:
        width = None
    try:
        lanes = int(tags.get('lanes', ''))
    except ValueError:
        lanes = None
    if lanes is not None and width is not None and (width < lanes * 1.5 or width > lanes * 8):
        width = None
    surface = tags.get('surface')
    paved = surface in ('asphalt', 'paved', 'concrete', 'concrete:plates', 'paving_stones')
    unpaved = surface in ('unpaved', 'gravel', 'fine_gravel', 'dirt', 'ground', 'sand', 'mud', 'compacted')
    rough = 0. if paved else .7 if unpaved else None
    if tags.get('smoothness') in ('bad', 'very_bad', 'horrible', 'very_horrible', 'impassable'):
        rough = 1.
    one = tags.get('oneway')
    oneway = -1 if one == '-1' else 1 if one in ('yes', '1', 'true') or tags.get('junction') == 'roundabout' else 0
    return {'width_m': width, 'lanes': lanes, 'surface': surface,
            'unpaved': True if unpaved else False if paved else None, 'rough': rough,
            'oneway': oneway, 'tunnel': tags.get('tunnel') not in (None, 'no') or tags.get('covered') == 'yes',
            'bridge': tags.get('bridge') not in (None, 'no')}


def enrich(source, existing, output):
    source, existing, output = Path(source), Path(existing), Path(output)
    if output.exists() or output.with_suffix(output.suffix + '.building').exists():
        raise ValueError('Choose a new output filename')
    temporary = output.with_suffix(output.suffix + '.building')
    original = sqlite3.connect(existing.resolve().as_uri() + '?mode=ro', uri=True)
    db = sqlite3.connect(temporary)
    original.backup(db)
    original.close()
    db.execute('CREATE TABLE IF NOT EXISTS way_attributes(way TEXT PRIMARY KEY, payload TEXT NOT NULL)')
    ways = {r[0] for r in db.execute('SELECT DISTINCT way FROM road')}
    class Handler(osmium.SimpleHandler):
        def way(self, way):
            if str(way.id) in ways:
                db.execute('INSERT OR REPLACE INTO way_attributes VALUES (?,?)', (str(way.id), json.dumps(attributes(way.tags))))
    Handler().apply_file(str(source))
    with source.open('rb') as stream:
        checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
    db.executemany('INSERT OR REPLACE INTO metadata VALUES (?,?)',
                  [('map_version', checksum), ('source_sha256', checksum), ('feature_version', '2.0'),
                   ('observed_at', ''), ('attribute_source', source.name)])
    db.commit()
    count = db.execute('SELECT count(*) FROM way_attributes').fetchone()[0]
    db.close()
    temporary.rename(output)
    print(f'Enriched {count} OSM ways: {output}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source')
    parser.add_argument('existing')
    parser.add_argument('output')
    args = parser.parse_args()
    enrich(args.source, args.existing, args.output)
