"""Build optional water/valley proximity features without changing road IDs."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import sqlite3

from catalog import Catalog
from recommendation.models import identity


def build(pbf, catalog_path, output):
    import numpy as np
    import osmium
    import shapely
    from shapely.geometry import LineString, Point
    from shapely.ops import transform, nearest_points

    output = Path(output)
    temporary = output.with_suffix(output.suffix + '.building')
    if output.exists() or temporary.exists():
        raise ValueError('Choose a new output filename')
    catalog = Catalog(catalog_path)
    with Path(pbf).open('rb') as stream:
        checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
    if catalog.source.get('source_sha256') != checksum:
        catalog.close()
        raise ValueError('Landscape OSM source differs from the road catalog')
    latitude = (catalog.bounds[1] + catalog.bounds[3]) / 2
    sx, sy = 111195 * math.cos(math.radians(latitude)), 111195
    layers = {kind: [] for kind in ('sea', 'lake', 'river', 'water', 'valley', 'farmland')}
    factory = osmium.geom.WKBFactory()

    def project(x, y, z=None):
        return np.asarray(x) * sx, np.asarray(y) * sy

    def add(kind, geometry):
        if geometry.is_empty or not geometry.is_valid:
            return
        layers[kind].append(transform(project, geometry))

    class Handler(osmium.SimpleHandler):
        def node(self, node):
            if node.tags.get('natural') in ('valley', 'gorge') or node.tags.get('geological') == 'gorge':
                if node.location.valid():
                    add('valley', Point(node.lon, node.lat))

        def way(self, way):
            kinds = []
            if way.tags.get('natural') == 'coastline':
                kinds.append('sea')
            if way.tags.get('waterway') in ('river', 'canal', 'stream'):
                kinds.append('river')
            if way.tags.get('natural') in ('valley', 'gorge') or way.tags.get('geological') == 'gorge':
                kinds.append('valley')
            if kinds and len(way.nodes) >= 2 and all(n.location.valid() for n in way.nodes):
                geometry = LineString([(n.lon, n.lat) for n in way.nodes])
                for kind in kinds:
                    add(kind, geometry)

        def area(self, area):
            water = area.tags.get('water')
            kind = ('farmland' if area.tags.get('landuse') in ('farmland', 'orchard', 'vineyard', 'meadow') else
                    'river' if water in ('river', 'canal') or area.tags.get('waterway') == 'riverbank' else
                    'lake' if water in ('lake', 'reservoir', 'pond') or area.tags.get('landuse') == 'reservoir' else
                    'water' if area.tags.get('natural') == 'water' else None)
            if kind:
                try:
                    add(kind, shapely.from_wkb(factory.create_multipolygon(area)))
                except (RuntimeError, ValueError, shapely.errors.GEOSException):
                    return

    print('Reading OSM water, coastlines and tagged valleys...', flush=True)
    try:
        Handler().apply_file(str(pbf), locations=True, idx='flex_mem')
        trees = {kind: shapely.STRtree(items) for kind, items in layers.items() if items}
        output.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(temporary)
        try:
            db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);'
                             'CREATE TABLE road_landscape(road_id INTEGER PRIMARY KEY,payload TEXT NOT NULL);')
            cursor = catalog.db.execute('SELECT id,lat1,lon1,lat2,lon2 FROM road ORDER BY id')
            scanned, stored = 0, 0
            while rows := cursor.fetchmany(4096):
                coords = np.array([((r['lon1'] + r['lon2']) / 2 * sx, (r['lat1'] + r['lat2']) / 2 * sy) for r in rows])
                points = shapely.points(coords)
                features = [{} for _ in rows]
                for kind, tree in trees.items():
                    pairs, distances = tree.query_nearest(points, max_distance=2000 if kind == 'sea' else 900,
                                                         all_matches=False, return_distance=True)
                    for index, obj, meters in zip(pairs[0], pairs[1], distances):
                        score = math.exp(-float(meters) / (400 if kind == 'valley' else 150 if kind == 'farmland' else 300))
                        features[index][kind] = round(score, 4)
                        if kind in ('sea', 'lake', 'river', 'water'):
                            target = nearest_points(points[index], tree.geometries[obj])[1]
                            dx, dy = target.x - coords[index, 0], target.y - coords[index, 1]
                            if math.hypot(dx, dy) > 1:
                                features[index][kind + '_bearing'] = round(math.degrees(math.atan2(dx, dy)) % 360, 1)
                values = [(r['id'], json.dumps(f, separators=(',', ':'))) for r, f in zip(rows, features) if f]
                db.executemany('INSERT INTO road_landscape VALUES (?,?)', values)
                scanned += len(rows)
                stored += len(values)
                if scanned % (4096 * 50) == 0:
                    db.commit()
                    print(f'Landscape features: {scanned} roads scanned, {stored} stored', flush=True)
            counts = {kind: len(items) for kind, items in layers.items()}
            metadata = {'schema': '1', 'version': identity(['landscape-4', checksum, counts]),
                        'catalog_signature': identity(catalog.source), 'map_version': catalog.source['map_version'],
                        'built_at': datetime.now(timezone.utc).isoformat(), 'object_counts': json.dumps(counts),
                        'method': 'OSM proximity; lake includes reservoir/pond; valley explicit tag; farmland includes meadow/orchard/vineyard',
                        'source_sha256': checksum, 'road_samples': str(scanned), 'feature_samples': str(stored)}
            db.executemany('INSERT INTO metadata VALUES (?,?)', metadata.items())
            db.commit()
        finally:
            db.close()
        temporary.rename(output)
        print(json.dumps(metadata), flush=True)
    finally:
        catalog.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('pbf')
    parser.add_argument('catalog')
    parser.add_argument('output')
    args = parser.parse_args()
    build(args.pbf, args.catalog, args.output)
