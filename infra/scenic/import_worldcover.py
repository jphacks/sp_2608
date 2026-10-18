"""Registration-free ESA WorldCover subset and conservative land-cover features."""

import argparse
from collections import OrderedDict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import sqlite3
from urllib.request import Request, urlopen

import numpy as np

from catalog import Catalog
from recommendation.models import identity

SOURCE = 'https://esa-worldcover.org/en/data-access'
BASE_URL = 'https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/'
CLASSES = {'forest': (10, 95), 'open_nature': (20, 30, 90, 100), 'farmland': (40,),
           'built_up': (50,), 'surface_water': (80,), 'bare': (60,), 'snow_ice_2021': (70,)}
GRID_SIZE = 9000


def filename(lat, lon):
    return f'ESA_WorldCover_10m_2021_v200_{"N" if lat >= 0 else "S"}{abs(lat):02d}{"E" if lon >= 0 else "W"}{abs(lon):03d}_Map.tif'


def fetch_tiles(tiles, directory, maximum_bytes, offline=False):
    directory = Path(directory)
    records, total = [], 0
    for lat, lon in tiles:
        name = filename(lat, lon)
        url = BASE_URL + name
        if offline:
            size = (directory / name).stat().st_size
            etag = None
        else:
            with urlopen(Request(url, method='HEAD'), timeout=20) as response:
                size = int(response.headers['Content-Length'])
                etag = response.headers.get('ETag')
        total += size
        records.append({'lat': lat, 'lon': lon, 'file': name, 'url': url, 'bytes': size, 'etag': etag})
    if total > maximum_bytes:
        raise ValueError(f'{total} bytes exceed the explicit limit {maximum_bytes}')
    directory.mkdir(parents=True, exist_ok=True)
    for record in records:
        path = directory / record['file']
        if not path.exists():
            temporary = path.with_suffix('.download')
            with urlopen(record['url'], timeout=60) as response, temporary.open('wb') as stream:
                count = 0
                while chunk := response.read(1024 * 1024):
                    count += len(chunk)
                    if count > record['bytes']:
                        raise ValueError('WorldCover file exceeds advertised size')
                    stream.write(chunk)
            if count != record['bytes']:
                raise ValueError('Incomplete WorldCover file')
            temporary.rename(path)
        if path.stat().st_size != record['bytes']:
            raise ValueError('Cached WorldCover file has the wrong size')
        with path.open('rb') as stream:
            record['sha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        print(f'WorldCover cached: {record["file"]}', flush=True)
    return records


class LandCover:
    def __init__(self, directory):
        self.directory, self.cache = Path(directory), OrderedDict()

    def tile(self, lat, lon):
        import rasterio
        from rasterio.enums import Resampling
        key = int(lat), int(lon)
        if key not in self.cache:
            path = self.directory / filename(*key)
            if not path.exists():
                self.cache[key] = None
            else:
                with rasterio.open(path) as dataset:
                    if dataset.crs.to_epsg() != 4326 or dataset.count != 1:
                        raise ValueError('Unexpected WorldCover coordinate system')
                    self.cache[key] = dataset.read(1, out_shape=(GRID_SIZE, GRID_SIZE), resampling=Resampling.nearest)
            if len(self.cache) > 8:
                self.cache.popitem(last=False)
        self.cache.move_to_end(key)
        return self.cache[key]

    def values(self, lat, lon):
        tile_lat, tile_lon = (np.floor(lat / 3) * 3).astype(int), (np.floor(lon / 3) * 3).astype(int)
        result = np.zeros(lat.shape, dtype=np.uint8)
        for y, x in np.unique(np.stack((tile_lat, tile_lon), axis=-1), axis=0):
            tile = self.tile(y, x)
            if tile is None:
                continue
            mask = (tile_lat == y) & (tile_lon == x)
            row = np.clip(((y + 3 - lat[mask]) / 3 * GRID_SIZE).astype(int), 0, GRID_SIZE - 1)
            column = np.clip(((lon[mask] - x) / 3 * GRID_SIZE).astype(int), 0, GRID_SIZE - 1)
            result[mask] = tile[row, column]
        return result

    def coverage(self, lat, lon, radius, spacing):
        counts = {kind: np.zeros(len(lat)) for kind in CLASSES}
        valid, total = np.zeros(len(lat)), 0
        for north in range(-radius, radius + 1, spacing):
            for east in range(-radius, radius + 1, spacing):
                if north * north + east * east > radius * radius:
                    continue
                total += 1
                values = self.values(lat + north / 111195, lon + east / (111195 * np.cos(np.radians(lat))))
                valid += np.isin(values, tuple(code for group in CLASSES.values() for code in group))
                for kind, codes in CLASSES.items():
                    counts[kind] += np.isin(values, codes)
        return {kind: np.where(valid >= .8 * total, values / np.maximum(1, valid), np.nan)
                for kind, values in counts.items()}


def build(catalog_path, cache, output, download=False, maximum_bytes=200_000_000, offline=False):
    output = Path(output)
    temporary = output.with_suffix(output.suffix + '.building')
    if output.exists() or temporary.exists():
        raise ValueError('Choose a new output filename')
    catalog = Catalog(catalog_path)
    try:
        tiles = catalog.db.execute('SELECT DISTINCT CAST(lat1/3 AS INTEGER)*3,CAST(lon1/3 AS INTEGER)*3 FROM road ORDER BY 1,2').fetchall()
        print(json.dumps({'tiles': [tuple(t) for t in tiles], 'download': download}), flush=True)
        if not download and not offline:
            return
        records = fetch_tiles(tiles, cache, maximum_bytes, offline)
        land = LandCover(cache)
        output.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(temporary)
        counts = {'scanned': 0, 'classified': 0}
        try:
            db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);'
                             'CREATE TABLE land_cover(road_id INTEGER PRIMARY KEY,payload TEXT,forest REAL,open_nature REAL,farmland REAL);')
            cursor = catalog.db.execute('SELECT id,lat1,lon1,lat2,lon2 FROM road ORDER BY id')
            while rows := cursor.fetchmany(8192):
                lat = np.array([(r['lat1'] + r['lat2']) / 2 for r in rows])
                lon = np.array([(r['lon1'] + r['lon2']) / 2 for r in rows])
                near = land.coverage(lat, lon, 100, 50)
                around = land.coverage(lat, lon, 500, 250)
                values = []
                for index, row in enumerate(rows):
                    if not np.isfinite(near['forest'][index]) or not np.isfinite(around['forest'][index]):
                        continue
                    feature = {kind: round(float(near[kind][index] if kind in ('forest', 'built_up') else around[kind][index]), 4) for kind in CLASSES}
                    values.append((row['id'], json.dumps(feature, separators=(',', ':')), feature['forest'], feature['open_nature'], feature['farmland']))
                db.executemany('INSERT INTO land_cover VALUES (?,?,?,?,?)', values)
                counts['scanned'] += len(rows)
                counts['classified'] += len(values)
                if counts['scanned'] % 204800 == 0:
                    db.commit()
                    print(json.dumps(counts), flush=True)
            meta = {'schema': '1', 'catalog_signature': identity(catalog.source),
                    'version': identity(['worldcover-2', [(r['file'], r['sha256']) for r in records], 'overview-nearest-9000', '100m/500m-13-samples']),
                    'counts': json.dumps(counts), 'source_url': SOURCE, 'license': 'CC-BY-4.0',
                    'attribution': 'ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium',
                    'observed_at': '2021', 'built_at': datetime.now(timezone.utc).isoformat(),
                    'method': '2021 v200 provided overview nearest sampling at ~27-37m; 13 samples per 100m/500m neighbourhood',
                    'files': json.dumps(records)}
            db.executemany('INSERT INTO metadata VALUES (?,?)', meta.items())
            db.commit()
        finally:
            db.close()
        temporary.rename(output)
        print(json.dumps({k: v for k, v in meta.items() if k != 'files'}), flush=True)
    finally:
        catalog.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('catalog')
    parser.add_argument('cache')
    parser.add_argument('output')
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--max-bytes', type=int, default=200_000_000)
    args = parser.parse_args()
    build(args.catalog, args.cache, args.output, args.download, args.max_bytes, args.offline)
