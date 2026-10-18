import argparse
import datetime
import json
import math
import os
from pathlib import Path
import sqlite3

import osmium
from shapely import from_wkb, STRtree, union_all
from shapely.geometry import LineString, Point, box
from shapely.ops import transform

from catalog import SCHEMA


def build(source, output, bounds):
    west, south, east, north = bounds
    if not (-180 <= west < east <= 180 and -80 <= south < north <= 80 and east - west <= 2 and north - south <= 2):
        raise ValueError('Use a pilot region no larger than 2 by 2 degrees')
    output = Path(output)
    if output.exists():
        raise ValueError('Output exists; choose a new filename')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + '.building')
    if temporary.exists():
        raise ValueError('An unfinished build exists; choose a new filename')
    cx, cy = (west + east) / 2, (south + north) / 2
    sx, sy = 111195 * math.cos(math.radians(cy)), 111195

    def project(x, y, z=None):
        return (x - cx) * sx, (y - cy) * sy

    region = box(west - .03, south - .03, east + .03, north + .03)
    factories = osmium.geom.WKBFactory()
    layers = {name: [] for name in ('coast', 'forest', 'nature', 'building')}

    class Land(osmium.SimpleHandler):
        def way(self, way):
            if way.tags.get('natural') != 'coastline':
                return
            if not all(n.location.valid() for n in way.nodes) or len(way.nodes) < 2:
                return
            geometry = LineString([(n.lon, n.lat) for n in way.nodes])
            if geometry.intersects(region):
                layers['coast'].append(transform(project, geometry.intersection(region)))

        def area(self, area):
            natural, landuse = area.tags.get('natural'), area.tags.get('landuse')
            forest = natural == 'wood' or landuse == 'forest'
            nature = forest or natural in ('water', 'grassland', 'heath', 'scrub', 'wetland') or landuse in ('meadow', 'grass')
            building = area.tags.get('building') not in (None, 'no')
            if not (nature or building):
                return
            try:
                geometry = from_wkb(factories.create_multipolygon(area), on_invalid='raise')
                if not geometry.is_valid or not geometry.intersects(region):
                    return
                geometry = transform(project, geometry.intersection(region))
            except (RuntimeError, ValueError):
                return
            if forest:
                layers['forest'].append(geometry)
            if nature:
                layers['nature'].append(geometry)
            if building:
                layers['building'].append(geometry)

    print('Reading coastlines and land cover...', flush=True)
    Land().apply_file(str(source), locations=True, idx='flex_mem')
    trees = {name: STRtree(shapes) for name, shapes in layers.items()}

    def coverage(name, point, radius):
        buffer = point.buffer(radius, quad_segs=4)
        shapes = [layers[name][i] for i in trees[name].query(buffer, predicate='intersects')]
        return min(1., union_all(shapes).intersection(buffer).area / buffer.area) if shapes else 0.

    db = sqlite3.connect(temporary)
    db.executescript(SCHEMA)
    metadata = {'schema': '1', 'bounds': json.dumps(bounds), 'source': Path(source).name,
                'builtAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'attribution': '© OpenStreetMap contributors', 'license': 'ODbL-1.0',
                'visibility': 'proximity-only'}
    db.executemany('INSERT INTO metadata VALUES (?,?)', metadata.items())
    count = 0

    class Roads(osmium.SimpleHandler):
        def way(self, way):
            nonlocal count
            highway = way.tags.get('highway', '')
            allowed = ('motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'unclassified', 'residential')
            if highway.removesuffix('_link') not in allowed:
                return
            access = way.tags.get('motorcar', way.tags.get('motor_vehicle', way.tags.get('vehicle', way.tags.get('access', 'yes'))))
            if access in ('no', 'private', 'agricultural', 'forestry', 'destination'):
                return
            if not all(n.location.valid() for n in way.nodes) or len(way.nodes) < 2:
                return
            sequence = 0
            tunnel = way.tags.get('tunnel') not in (None, 'no') or way.tags.get('covered') == 'yes'
            unpaved = way.tags.get('surface') in ('unpaved', 'gravel', 'dirt', 'ground', 'sand', 'mud')
            try:
                narrow = float(way.tags.get('width', '9')) < 4
            except ValueError:
                narrow = False
            for a, b in zip(way.nodes, list(way.nodes)[1:]):
                ax, ay = project(a.lon, a.lat)
                bx, by = project(b.lon, b.lat)
                length = math.hypot(bx - ax, by - ay)
                pieces = max(1, math.ceil(length / 100))
                for index in range(pieces):
                    sequence += 1
                    f1, f2 = index / pieces, (index + 1) / pieces
                    lat1, lon1 = a.lat + (b.lat - a.lat) * f1, a.lon + (b.lon - a.lon) * f1
                    lat2, lon2 = a.lat + (b.lat - a.lat) * f2, a.lon + (b.lon - a.lon) * f2
                    if not (west <= (lon1 + lon2) / 2 <= east and south <= (lat1 + lat2) / 2 <= north) or length < 1:
                        continue
                    point = Point((ax + (bx - ax) * (f1 + f2) / 2), (ay + (by - ay) * (f1 + f2) / 2))
                    nearby = trees['coast'].query(point.buffer(2000))
                    sea_distance = min((point.distance(layers['coast'][i]) for i in nearby), default=math.inf)
                    sea = math.exp(-sea_distance / 300) if not tunnel else 0.
                    forest = coverage('forest', point, 100) if not tunnel else 0.
                    nature = coverage('nature', point, 500) if not tunnel else 0.
                    urban = coverage('building', point, 100)
                    load = min(1., .6 * unpaved + .4 * narrow + .1 * (highway == 'residential'))
                    count += 1
                    db.execute('INSERT INTO road VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)',
                               (count, str(way.id), sequence, lat1, lon1, lat2, lon2, length / pieces,
                                sea, forest, nature, urban, load))
                    db.execute('INSERT INTO road_bounds VALUES (?,?,?,?,?)',
                               (count, min(lon1, lon2), max(lon1, lon2), min(lat1, lat2), max(lat1, lat2)))
                    if count % 10000 == 0:
                        db.commit()
                        print(f'{count} road samples', flush=True)

    try:
        print('Scoring roads...', flush=True)
        Roads().apply_file(str(source), locations=True, idx='flex_mem')
        if count == 0:
            raise ValueError('No roads found in this region')
        db.commit()
    finally:
        db.close()
    os.rename(temporary, output)
    print(f'Created {output}: {count} road samples', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source')
    parser.add_argument('output')
    parser.add_argument('--bbox', required=True, help='west,south,east,north; maximum 2 by 2 degrees')
    args = parser.parse_args()
    build(args.source, args.output, list(map(float, args.bbox.split(','))))
