import argparse
import datetime
import hashlib
import sqlite3
from pathlib import Path

import osmium

from places import normalize


def build(source, output):
    output = Path(output)
    temporary = output.with_suffix(output.suffix + '.building')
    if output.exists() or temporary.exists():
        raise ValueError('Choose a new output filename; existing data will not be overwritten')
    output.parent.mkdir(parents=True, exist_ok=True)
    with Path(source).open('rb') as stream:
        source_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
    db = sqlite3.connect(temporary)
    db.executescript('''
        CREATE TABLE places(id TEXT PRIMARY KEY, name TEXT NOT NULL, label TEXT NOT NULL,
                            lat REAL NOT NULL, lon REAL NOT NULL, normalized_name TEXT NOT NULL,
                            terms TEXT NOT NULL, priority INTEGER NOT NULL);
        CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
    ''')

    def insert(kind, entity, lat, lon):
        tags = dict(entity.tags)
        name = tags.get('name:ja') or tags.get('name')
        if not name or tags.get('access') == 'private':
            return
        if not any(k in tags for k in ('place', 'amenity', 'tourism', 'shop', 'leisure', 'railway', 'natural', 'historic', 'building')):
            return
        address = ''.join(tags.get(k, '') for k in ('addr:province', 'addr:city', 'addr:suburb', 'addr:quarter', 'addr:neighbourhood', 'addr:street', 'addr:housenumber'))
        category = next((tags[k] for k in ('place', 'amenity', 'tourism', 'railway', 'shop', 'leisure', 'natural', 'historic') if k in tags), '')
        priority = 3 if 'place' in tags or tags.get('railway') == 'station' else 2 if 'tourism' in tags else 1
        aliases = ' '.join(v for k, v in tags.items() if k in ('name', 'name:ja', 'name:en', 'name:ja_kana', 'name:ja_rm', 'alt_name', 'short_name', 'official_name'))
        label = ' · '.join(p for p in (name, address, category) if p)
        db.execute('INSERT OR IGNORE INTO places VALUES (?,?,?,?,?,?,?,?)',
                   (f'{kind}:{entity.id}', name, label, lat, lon, normalize(name), normalize(aliases + ' ' + address), priority))

    class Handler(osmium.SimpleHandler):
        def node(self, node):
            if (node.tags.get('name') or node.tags.get('name:ja')) and node.location.valid():
                insert('node', node, node.location.lat, node.location.lon)

        def way(self, way):
            if not (way.tags.get('name') or way.tags.get('name:ja')):
                return
            if not any(k in way.tags for k in ('place', 'amenity', 'tourism', 'shop', 'leisure', 'railway', 'natural', 'historic', 'building')):
                return
            points = [(n.lat, n.lon) for n in way.nodes if n.location.valid()]
            if points:
                insert('way', way, sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points))

    try:
        Handler().apply_file(str(source), locations=True, idx='flex_mem')
        db.executemany('INSERT INTO metadata VALUES (?,?)', [('source', Path(source).name),
            ('source_sha256', source_hash),
            ('built_at', datetime.datetime.now(datetime.timezone.utc).isoformat()),
            ('attribution', '© OpenStreetMap contributors'), ('license', 'ODbL-1.0')])
        count = db.execute('SELECT count(*) FROM places').fetchone()[0]
        db.commit()
    finally:
        db.close()
    temporary.rename(output)
    print(f'Indexed {count} named places: {output}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source')
    parser.add_argument('output')
    args = parser.parse_args()
    build(args.source, args.output)
