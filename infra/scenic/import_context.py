"""OSM driving context and POI inventory, separate from reviewed scenic roads."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import sqlite3

from catalog import Catalog
from recommendation.models import identity

WAY_TAGS = ('access', 'motor_vehicle', 'motorcar', 'access:conditional', 'motor_vehicle:conditional',
            'seasonal', 'winter_service', 'snowplowing', 'incline', 'maxspeed', 'lit', 'ford', 'toll')
POI_TAGS = ('name', 'opening_hours', 'access', 'motor_vehicle', 'wheelchair', 'toilets', 'drinking_water',
            'fee', 'parking', 'capacity', 'entrance', 'ele', 'website', 'operator')
AMENITIES = {'parking', 'parking_entrance', 'toilets', 'fuel', 'charging_station', 'drinking_water',
             'restaurant', 'cafe', 'fast_food'}
TOURISM = {'viewpoint', 'picnic_site', 'camp_site', 'information', 'museum', 'attraction'}
NATURAL = {'peak', 'saddle', 'beach', 'waterfall', 'cliff', 'hot_spring', 'spring', 'cave_entrance'}


def poi_kind(tags):
    for key, kinds in [('highway', {'services', 'rest_area'}), ('amenity', AMENITIES),
                       ('tourism', TOURISM), ('natural', NATURAL), ('waterway', {'waterfall'})]:
        if tags.get(key) in kinds:
            return tags[key]
    return None


def incline(tags):
    value = tags.get('incline', '')
    try:
        result = float(value[:-1]) / 100 if value.endswith('%') else None
    except (TypeError, ValueError):
        return None
    return result if result is not None and math.isfinite(result) and abs(result) <= .5 else None


def build(pbf, catalog_path, output):
    import osmium
    output = Path(output)
    temporary = output.with_suffix(output.suffix + '.building')
    if output.exists() or temporary.exists():
        raise ValueError('Choose a new output filename')
    catalog = Catalog(catalog_path)
    try:
        with Path(pbf).open('rb') as stream:
            checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
        if checksum != catalog.source.get('source_sha256'):
            raise ValueError('OSM source differs from the road catalog')
        ways = {str(row[0]) for row in catalog.db.execute('SELECT DISTINCT way FROM road')}
        output.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(temporary)
        counts = {'way_context': 0, 'poi': 0, 'by_kind': {}}
        try:
            db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);'
                             'CREATE TABLE way_context(way_id TEXT PRIMARY KEY,payload TEXT NOT NULL);'
                             'CREATE TABLE poi(id TEXT PRIMARY KEY,kind TEXT,lat REAL,lon REAL,payload TEXT NOT NULL);'
                             'CREATE INDEX poi_kind ON poi(kind);'
                             'CREATE VIRTUAL TABLE poi_bounds USING rtree(id,min_lon,max_lon,min_lat,max_lat);')

            def add_poi(kind, identifier, tags, lat, lon):
                if not catalog.contains((lat, lon)):
                    return
                attributes = {key: tags[key] for key in POI_TAGS if tags.get(key)}
                attributes.update(navigation_verified=False, opening_hours_verified=False,
                                  recommendation_status='unregistered_candidate')
                cursor = db.execute('INSERT OR IGNORE INTO poi VALUES (?,?,?,?,?)',
                                    (identifier, kind, lat, lon, json.dumps(attributes, ensure_ascii=False)))
                if cursor.rowcount:
                    db.execute('INSERT INTO poi_bounds VALUES (?,?,?,?,?)', (cursor.lastrowid, lon, lon, lat, lat))
                    counts['poi'] += 1
                    counts['by_kind'][kind] = counts['by_kind'].get(kind, 0) + 1

            class Handler(osmium.SimpleHandler):
                def node(self, node):
                    kind = poi_kind(node.tags)
                    if kind and node.location.valid():
                        add_poi(kind, f'n{node.id}', node.tags, node.lat, node.lon)

                def way(self, way):
                    if str(way.id) in ways:
                        attributes = {key: way.tags[key] for key in WAY_TAGS if way.tags.get(key)}
                        grade = incline(way.tags)
                        if grade is not None:
                            attributes['osm_grade'] = grade
                        if attributes:
                            db.execute('INSERT INTO way_context VALUES (?,?)', (str(way.id), json.dumps(attributes)))
                            counts['way_context'] += 1
                    kind = poi_kind(way.tags)
                    if kind and len(way.nodes) and all(node.location.valid() for node in way.nodes):
                        add_poi(kind, f'w{way.id}', way.tags,
                                sum(node.lat for node in way.nodes) / len(way.nodes),
                                sum(node.lon for node in way.nodes) / len(way.nodes))

            print('Collecting OSM road context and unverified POI inventory...', flush=True)
            Handler().apply_file(str(pbf), locations=True, idx='flex_mem')
            metadata = {'schema': '1', 'catalog_signature': identity(catalog.source),
                        'version': identity(['context-1', checksum, counts]), 'counts': json.dumps(counts),
                        'built_at': datetime.now(timezone.utc).isoformat(), 'source_sha256': checksum,
                        'license': 'ODbL-1.0', 'source_url': 'https://www.openstreetmap.org/copyright',
                        'method': 'OSM nodes/ways; way representative coordinates are not verified entrances; relations not included'}
            db.executemany('INSERT INTO metadata VALUES (?,?)', metadata.items())
            db.commit()
        finally:
            db.close()
        temporary.rename(output)
        print(json.dumps(metadata, ensure_ascii=False), flush=True)
    finally:
        catalog.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('pbf')
    parser.add_argument('catalog')
    parser.add_argument('output')
    args = parser.parse_args()
    build(args.pbf, args.catalog, args.output)
