"""Build a tiled scenic catalog from an entire regional OSM extract."""

import argparse
from collections import defaultdict
import datetime
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import time

import numpy as np
import osmium
import shapely
from shapely.geometry import LineString, box
from shapely.ops import transform

from catalog import SCHEMA
from enrich_osm import attributes
from geometry import distance


VERSION = 'regional-1'
ALLOWED = {'motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'unclassified', 'residential'}
STAGING = '''
CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS features(id INTEGER PRIMARY KEY, osm_id INTEGER UNIQUE, flags INTEGER, geometry BLOB);
CREATE VIRTUAL TABLE IF NOT EXISTS feature_bounds USING rtree(id, west, east, south, north);
CREATE TABLE IF NOT EXISTS raw_road(id INTEGER PRIMARY KEY, way TEXT, sequence INTEGER,
 lat1 REAL, lon1 REAL, lat2 REAL, lon2 REAL, length REAL, tile_x INTEGER, tile_y INTEGER, UNIQUE(way,sequence));
CREATE INDEX IF NOT EXISTS road_tile ON raw_road(tile_x,tile_y,id);
CREATE TABLE IF NOT EXISTS way_attributes(way TEXT PRIMARY KEY, payload TEXT NOT NULL);
'''


def stage(source, path, tile_degrees, checksum):
    db = sqlite3.connect(path)
    db.executescript(STAGING)
    metadata = dict(db.execute('SELECT key,value FROM metadata'))
    signature = json.dumps([VERSION, checksum, tile_degrees])
    if metadata.get('signature', signature) != signature:
        db.close()
        raise ValueError('Source or build parameters differ from the staged data')
    db.execute('INSERT OR IGNORE INTO metadata VALUES (?,?)', ('signature', signature))
    db.commit()
    if metadata.get('ready') == '1':
        db.close()
        return
    factory = osmium.geom.WKBFactory()
    started = time.monotonic()
    counts = defaultdict(int)

    def feature(osm_id, flags, geometry):
        if geometry.is_empty or not geometry.is_valid:
            counts['invalid_features'] += 1
            return
        cursor = db.execute('INSERT OR IGNORE INTO features(osm_id,flags,geometry) VALUES (?,?,?)',
                            (osm_id, flags, shapely.to_wkb(geometry)))
        if cursor.rowcount:
            west, south, east, north = geometry.bounds
            db.execute('INSERT INTO feature_bounds VALUES (?,?,?,?,?)', (cursor.lastrowid, west, east, south, north))
        counts['features'] += 1

    class Handler(osmium.SimpleHandler):
        def way(self, way):
            if way.tags.get('natural') == 'coastline' and len(way.nodes) >= 2 and all(n.location.valid() for n in way.nodes):
                feature(way.id * 2, 1, LineString([(n.lon, n.lat) for n in way.nodes]))
            if way.tags.get('highway', '').removesuffix('_link') not in ALLOWED:
                return
            access = way.tags.get('motorcar', way.tags.get('motor_vehicle', way.tags.get('vehicle', way.tags.get('access', 'yes'))))
            if access in ('no', 'private', 'agricultural', 'forestry', 'destination'):
                return
            if len(way.nodes) < 2 or not all(n.location.valid() for n in way.nodes):
                return
            db.execute('INSERT OR IGNORE INTO way_attributes VALUES (?,?)', (str(way.id), json.dumps(attributes(way.tags))))
            sequence = 0
            points = [(n.lat, n.lon) for n in way.nodes]
            for a, b in zip(points, points[1:]):
                length = distance(a, b)
                if length < 1:
                    continue
                pieces = max(1, math.ceil(length / 100))
                rows = []
                for i in range(pieces):
                    sequence += 1
                    f, g = i / pieces, (i + 1) / pieces
                    lat1, lon1 = a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
                    lat2, lon2 = a[0] + (b[0] - a[0]) * g, a[1] + (b[1] - a[1]) * g
                    rows.append((str(way.id), sequence, lat1, lon1, lat2, lon2, length / pieces,
                                 math.floor((lon1 + lon2) / 2 / tile_degrees), math.floor((lat1 + lat2) / 2 / tile_degrees)))
                db.executemany('INSERT OR IGNORE INTO raw_road(way,sequence,lat1,lon1,lat2,lon2,length,tile_x,tile_y) '
                               'VALUES (?,?,?,?,?,?,?,?,?)', rows)
            counts['ways'] += 1
            if counts['ways'] % 10000 == 0:
                db.commit()
                print(f'Staging: {counts["ways"]} road ways, {counts["features"]} landscape features, '
                      f'{time.monotonic() - started:.0f}s', flush=True)

        def area(self, area):
            natural, landuse = area.tags.get('natural'), area.tags.get('landuse')
            forest = natural == 'wood' or landuse == 'forest'
            nature = forest or natural in ('water', 'grassland', 'heath', 'scrub', 'wetland') or landuse in ('meadow', 'grass')
            building = area.tags.get('building') not in (None, 'no')
            if not (nature or building):
                return
            try:
                geometry = shapely.from_wkb(factory.create_multipolygon(area))
            except (RuntimeError, ValueError, shapely.errors.GEOSException):
                counts['invalid_features'] += 1
                return
            feature(area.id * 2 + 1, (2 if forest else 0) | (4 if nature else 0) | (8 if building else 0), geometry)

    try:
        print('Staging OSM roads and landscape geometry (existing staging rows are reused)...', flush=True)
        Handler().apply_file(str(source), locations=True, idx='flex_mem')
        if not db.execute('SELECT 1 FROM raw_road LIMIT 1').fetchone():
            raise ValueError('No drivable roads in the input extract')
        db.execute('INSERT OR REPLACE INTO metadata VALUES (?,?)', ('invalid_features', str(counts['invalid_features'])))
        db.execute('INSERT OR REPLACE INTO metadata VALUES (?,?)', ('ready', '1'))
        db.commit()
    finally:
        db.close()


def coverage(shapes, points, radius, tree=None, disjoint=False):
    result = np.zeros(len(points))
    if not len(shapes):
        return result
    buffers = shapely.buffer(points, radius, quad_segs=4)
    tree = tree if tree is not None else shapely.STRtree(shapes)
    pairs = tree.query(buffers, predicate='intersects')
    if not pairs.size:
        return result
    point_ids, shape_ids = pairs
    full = shapely.covers(shapes[shape_ids], buffers[point_ids])
    result[point_ids[full]] = 1.
    partial = result[point_ids] == 0
    point_ids, shape_ids = point_ids[partial], shape_ids[partial]
    if not len(point_ids):
        return result
    intersections = shapely.intersection(shapes[shape_ids], buffers[point_ids])
    if disjoint:
        result += np.bincount(point_ids, weights=shapely.area(intersections), minlength=len(points)) / shapely.area(buffers)
        return np.minimum(1., result)
    groups = defaultdict(list)
    for point_id, geometry in zip(point_ids, intersections):
        groups[int(point_id)].append(geometry)
    areas = shapely.area(buffers)
    for point_id, geometries in groups.items():
        area = shapely.area(geometries[0] if len(geometries) == 1 else shapely.union_all(geometries))
        result[point_id] = min(1., area / areas[point_id])
    return result


def fragment_polygons(shapes, size=2000):
    pieces = []
    for geometry in shapes:
        west, south, east, north = geometry.bounds
        if max(east - west, north - south) <= size * 2:
            pieces.append(geometry)
            continue
        xs = np.arange(math.floor(west / size), math.ceil(east / size)) * size
        ys = np.arange(math.floor(south / size), math.ceil(north / size)) * size
        xx, yy = np.meshgrid(xs, ys)
        cells = shapely.box(xx.ravel(), yy.ravel(), xx.ravel() + size, yy.ravel() + size)
        intersections = shapely.get_parts(shapely.intersection(geometry, cells))
        pieces.extend(intersections[shapely.area(intersections) > 0])
    return np.array(pieces, dtype=object)


def score_tile(stage_db, output, x, y, size):
    west, south = x * size, y * size
    east, north = west + size, south + size
    cx, cy = (west + east) / 2, (south + north) / 2
    sx, sy = 111195 * math.cos(math.radians(cy)), 111195
    halo_x, halo_y = 3000 / sx, 3000 / sy
    clip = box(west - halo_x, south - halo_y, east + halo_x, north + halo_y)

    def project(lon, lat, z=None):
        return (np.asarray(lon) - cx) * sx, (np.asarray(lat) - cy) * sy

    layers = {flag: [] for flag in (1, 2, 4, 8)}
    for flags, raw in stage_db.execute('''SELECT f.flags,f.geometry FROM feature_bounds b JOIN features f ON f.id=b.id
            WHERE b.east>=? AND b.west<=? AND b.north>=? AND b.south<=?''',
            (west - halo_x, east + halo_x, south - halo_y, north + halo_y)):
        geometry = shapely.from_wkb(raw).intersection(clip)
        if geometry.is_empty:
            continue
        geometry = transform(project, geometry)
        for flag in layers:
            if flags & flag:
                layers[flag].append(geometry)
    arrays = {flag: np.array(geometries, dtype=object) for flag, geometries in layers.items()}
    for flag in (2, 4, 8):
        arrays[flag] = shapely.get_parts(shapely.disjoint_subset_union_all(arrays[flag])) if len(arrays[flag]) else arrays[flag]
        if flag in (2, 4):
            arrays[flag] = fragment_polygons(arrays[flag])
    for shapes in arrays.values():
        shapely.prepare(shapes)
    coast = shapely.STRtree(arrays[1])
    trees = {flag: shapely.STRtree(arrays[flag]) for flag in (2, 4, 8)}
    cursor = stage_db.execute('''SELECT r.id,r.way,r.sequence,r.lat1,r.lon1,r.lat2,r.lon2,r.length,a.payload
        FROM raw_road r JOIN way_attributes a ON a.way=r.way WHERE r.tile_x=? AND r.tile_y=? ORDER BY r.id''', (x, y))
    count = 0
    while rows := cursor.fetchmany(256):
        coordinates = np.array([[(r[4] + r[6]) / 2, (r[3] + r[5]) / 2] for r in rows])
        px, py = project(coordinates[:, 0], coordinates[:, 1])
        points = shapely.points(px, py)
        sea = np.zeros(len(rows))
        if len(arrays[1]):
            indices, distances = coast.query_nearest(points, max_distance=2000, return_distance=True, all_matches=False)
            sea[indices[0]] = np.exp(-distances / 300)
        forest = coverage(arrays[2], points, 100, trees[2], disjoint=True)
        nature = coverage(arrays[4], points, 500, trees[4], disjoint=True)
        urban = coverage(arrays[8], points, 100, trees[8], disjoint=True)
        road_rows, bound_rows = [], []
        for i, row in enumerate(rows):
            attr = json.loads(row[8])
            tunnel = attr['tunnel']
            load = min(1., .6 * (attr['unpaved'] is True) + .4 * (attr['width_m'] is not None and attr['width_m'] < 4))
            road_rows.append((*row[:8], 0. if tunnel else float(sea[i]), 0. if tunnel else float(forest[i]),
                              0. if tunnel else float(nature[i]), float(urban[i]), load))
            bound_rows.append((row[0], min(row[4], row[6]), max(row[4], row[6]), min(row[3], row[5]), max(row[3], row[5])))
        output.executemany('INSERT INTO road VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)', road_rows)
        output.executemany('INSERT INTO road_bounds VALUES (?,?,?,?,?)', bound_rows)
        count += len(rows)
    return count


def build(source, output, tile_degrees=.25, resume=False):
    source, output = Path(source), Path(output)
    if not math.isfinite(tile_degrees) or not .05 <= tile_degrees <= .5:
        raise ValueError('Tile size must be 0.05 to 0.5 degrees')
    if output.exists():
        raise ValueError('Output exists; choose a new filename')
    partial = output.with_suffix(output.suffix + '.building')
    work = output.with_suffix(output.suffix + '.work.sqlite')
    if not resume and (partial.exists() or work.exists()):
        raise ValueError('Unfinished build exists; use --resume with the same source and parameters')
    output.parent.mkdir(parents=True, exist_ok=True)
    with source.open('rb') as stream:
        checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
    stage(source, work, tile_degrees, checksum)
    staging = sqlite3.connect(work)
    db = sqlite3.connect(partial)
    started = time.monotonic()
    try:
        metadata = dict(db.execute('SELECT key,value FROM metadata')) if db.execute("SELECT 1 FROM sqlite_master WHERE name='metadata'").fetchone() else {}
        signature = json.dumps([VERSION, checksum, tile_degrees])
        if metadata and metadata.get('build_signature') != signature:
            raise ValueError('Partial output belongs to a different build')
        if not metadata:
            db.executescript(SCHEMA)
            db.executescript('CREATE TABLE way_attributes(way TEXT PRIMARY KEY,payload TEXT NOT NULL); '
                             'CREATE TABLE completed_tiles(x INTEGER,y INTEGER,samples INTEGER,PRIMARY KEY(x,y));')
            bounds = list(staging.execute('SELECT min(min(lon1,lon2)),min(min(lat1,lat2)),max(max(lon1,lon2)),max(max(lat1,lat2)) FROM raw_road').fetchone())
            values = {'schema': '1', 'bounds': json.dumps(bounds), 'source': source.name,
                      'builtAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      'map_version': hashlib.sha256(signature.encode()).hexdigest(),
                      'source_sha256': checksum, 'feature_version': '2.0', 'observed_at': '',
                      'attribution': '© OpenStreetMap contributors', 'license': 'ODbL-1.0', 'visibility': 'proximity-only',
                      'build_signature': signature, 'importer_version': VERSION, 'tile_degrees': str(tile_degrees),
                      'halo_meters': '3000', 'sample_max_meters': '100',
                      'invalid_features': dict(staging.execute('SELECT key,value FROM metadata')).get('invalid_features', '0')}
            db.executemany('INSERT INTO metadata VALUES (?,?)', values.items())
            db.executemany('INSERT INTO way_attributes VALUES (?,?)', staging.execute('SELECT way,payload FROM way_attributes'))
            db.commit()
        done = set(db.execute('SELECT x,y FROM completed_tiles'))
        tiles = staging.execute('SELECT tile_x,tile_y,count(*) FROM raw_road GROUP BY tile_x,tile_y ORDER BY tile_y,tile_x').fetchall()
        print(f'Scoring {len(tiles)} occupied tiles; {len(done)} already complete', flush=True)
        for index, (x, y, expected) in enumerate(tiles, 1):
            if (x, y) in done:
                continue
            with db:
                count = score_tile(staging, db, x, y, tile_degrees)
                if count != expected:
                    raise ValueError('Tile lost road samples')
                db.execute('INSERT INTO completed_tiles VALUES (?,?,?)', (x, y, count))
            print(f'Tile {index}/{len(tiles)} ({x * tile_degrees:.2f},{y * tile_degrees:.2f}): '
                  f'{count} samples, scoring {time.monotonic() - started:.0f}s', flush=True)
        expected = staging.execute('SELECT count(*) FROM raw_road').fetchone()[0]
        actual = db.execute('SELECT count(*) FROM road').fetchone()[0]
        if actual != expected or db.execute('SELECT count(*) FROM road_bounds').fetchone()[0] != actual:
            raise ValueError('Incomplete catalog')
        if db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('Catalog integrity check failed')
        db.execute('INSERT OR REPLACE INTO metadata VALUES (?,?)', ('complete', '1'))
        db.execute('INSERT OR REPLACE INTO metadata VALUES (?,?)', ('sample_count', str(actual)))
        db.commit()
    finally:
        db.close()
        staging.close()
    partial.rename(output)
    print(f'Created {output}: {actual} road samples', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source')
    parser.add_argument('output')
    parser.add_argument('--tile-degrees', type=float, default=.25)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    build(args.source, args.output, args.tile_degrees, args.resume)
