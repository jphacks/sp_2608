"""Prepare source-bound terrain features without runtime elevation requests."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import sqlite3

import numpy as np

from catalog import Catalog
from gsi_dem import DemTiles, SOURCE_URL, TERMS_URL, tile_pixels, terrain_features
from recommendation.models import identity


def required_tiles(catalog, zoom):
    tiles = set()
    cursor = catalog.db.execute('SELECT lat1,lon1,lat2,lon2 FROM road')
    while rows := cursor.fetchmany(4096):
        lat = np.array([(r[0] + r[2]) / 2 for r in rows])
        lon = np.array([(r[1] + r[3]) / 2 for r in rows])
        for north in (-1100, 0, 1100):
            for east in (-1100, 0, 1100):
                px, py = tile_pixels(lat + north / 111195, lon + east / (111195 * np.cos(np.radians(lat))), zoom)
                tiles.update(map(tuple, np.unique(np.stack((px.astype(int) // 256, py.astype(int) // 256), axis=-1), axis=0).tolist()))
    return tiles


def build(catalog_path, cache, output, zoom=11, download=False, maximum=1000):
    output = Path(output)
    temporary = output.with_suffix(output.suffix + '.building')
    if output.exists() or temporary.exists():
        raise ValueError('Choose a new output filename')
    catalog = Catalog(catalog_path)
    try:
        tiles = required_tiles(catalog, zoom)
        print(json.dumps({'required_tiles': len(tiles), 'zoom': zoom, 'download': download}), flush=True)
        if not download:
            return
        dem = DemTiles(cache, zoom)
        manifest = dem.fetch(tiles, maximum)
        version = identity(['gsi-terrain-1', zoom, [(t['x'], t['y'], t['sha256']) for t in manifest['tiles']]])
        output.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(temporary)
        try:
            db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);'
                             'CREATE TABLE road_terrain(road_id INTEGER PRIMARY KEY,payload TEXT NOT NULL,valley REAL);')
            cursor = catalog.db.execute('SELECT id,lat1,lon1,lat2,lon2,length FROM road ORDER BY id')
            counts = dict.fromkeys(('scanned', 'elevation', 'horizon', 'valley', 'grade'), 0)
            while rows := cursor.fetchmany(4096):
                lat = np.array([(r['lat1'] + r['lat2']) / 2 for r in rows])
                lon = np.array([(r['lon1'] + r['lon2']) / 2 for r in rows])
                elevation = dem.values(lat, lon)
                horizons = np.full((len(rows), 8), np.nan)
                for axis in range(8):
                    theta = axis * math.pi / 4
                    angles = []
                    for radius in (250, 500, 1000):
                        heights = dem.values(lat + math.cos(theta) * radius / 111195,
                            lon + math.sin(theta) * radius / (111195 * np.cos(np.radians(lat))))
                        angles.append(np.degrees(np.arctan2(heights - elevation, radius)))
                    horizons[:, axis] = np.max(angles, axis=0)
                first = dem.values(np.array([r['lat1'] for r in rows]), np.array([r['lon1'] for r in rows]))
                last = dem.values(np.array([r['lat2'] for r in rows]), np.array([r['lon2'] for r in rows]))
                values = []
                for index, row in enumerate(rows):
                    feature = terrain_features(elevation[index], horizons[index])
                    if math.isfinite(elevation[index]):
                        feature.setdefault('elevation_m', round(float(elevation[index]), 1))
                        counts['elevation'] += 1
                    if 'horizon_degrees' in feature:
                        counts['horizon'] += 1
                    if feature.get('terrain_valley', 0) >= .45:
                        counts['valley'] += 1
                    resolution = 156543.034 * math.cos(math.radians(lat[index])) / 2 ** zoom
                    if row['length'] >= max(30, resolution * 2) and np.isfinite(first[index]) and np.isfinite(last[index]):
                        grade = float(last[index] - first[index]) / row['length']
                        if abs(grade) <= .5:
                            feature['terrain_grade'] = round(grade, 4)
                            counts['grade'] += 1
                    if feature:
                        values.append((row['id'], json.dumps(feature, separators=(',', ':')), feature.get('terrain_valley')))
                db.executemany('INSERT INTO road_terrain VALUES (?,?,?)', values)
                counts['scanned'] += len(rows)
                if counts['scanned'] % 204800 == 0:
                    db.commit()
                    print(json.dumps(counts), flush=True)
            metadata = {'schema': '1', 'version': version, 'catalog_signature': identity(catalog.source),
                        'map_version': catalog.source.get('map_version', ''), 'source_url': SOURCE_URL,
                        'terms_url': TERMS_URL, 'license': 'PDL-1.0; GSI terms apply',
                        'attribution': '地理院タイル（標高タイル）を加工して旅れこが作成',
                        'zoom': str(zoom), 'built_at': datetime.now(timezone.utc).isoformat(),
                        'acquired_at': manifest['acquired_at'], 'counts': json.dumps(counts),
                        'manifest_sha256': hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()}
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
    parser.add_argument('catalog')
    parser.add_argument('cache')
    parser.add_argument('output')
    parser.add_argument('--zoom', type=int, default=11)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--max-tiles', type=int, default=1000)
    args = parser.parse_args()
    build(args.catalog, args.cache, args.output, args.zoom, args.download, args.max_tiles)
