"""Cached GSI DEM10B tiles for offline terrain preparation."""

from collections import OrderedDict
import hashlib
import io
import json
import math
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import numpy as np

TILE_URL = 'https://cyberjapandata.gsi.go.jp/xyz/dem_png/{z}/{x}/{y}.png'
SOURCE_URL = 'https://maps.gsi.go.jp/development/ichiran.html'
TERMS_URL = 'https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html'


def tile_pixels(latitudes, longitudes, zoom):
    count = 2 ** zoom
    lat = np.radians(np.clip(latitudes, -85.05112878, 85.05112878))
    return (np.asarray(longitudes) + 180) / 360 * count * 256, (1 - np.arcsinh(np.tan(lat)) / np.pi) / 2 * count * 256


def decode_png(raw):
    from PIL import Image
    with Image.open(io.BytesIO(raw)) as image:
        if image.size != (256, 256) or image.format != 'PNG':
            raise ValueError('Invalid DEM tile')
        rgb = np.asarray(image.convert('RGB'), dtype=np.int32)
    code = rgb[:, :, 0] * 65536 + rgb[:, :, 1] * 256 + rgb[:, :, 2]
    values = np.where(code > 2 ** 23, code - 2 ** 24, code).astype(np.float32) * .01
    values[code == 2 ** 23] = np.nan
    return values


class DemTiles:
    def __init__(self, directory, zoom=11):
        if not 9 <= zoom <= 14:
            raise ValueError('DEM zoom must be 9..14')
        self.directory, self.zoom = Path(directory), zoom
        self.cache = OrderedDict()

    def path(self, x, y):
        return self.directory / str(self.zoom) / str(x) / f'{y}.png'

    def array(self, x, y):
        key = int(x), int(y)
        if key not in self.cache:
            path = self.path(*key)
            self.cache[key] = decode_png(path.read_bytes()) if path.exists() else None
            if len(self.cache) > 128:
                self.cache.popitem(last=False)
        self.cache.move_to_end(key)
        return self.cache[key]

    def values(self, latitudes, longitudes):
        px, py = tile_pixels(latitudes, longitudes, self.zoom)
        x, y = np.floor(px / 256).astype(int), np.floor(py / 256).astype(int)
        result = np.full(np.shape(px), np.nan, dtype=np.float32)
        for tx, ty in np.unique(np.stack((x, y), axis=-1).reshape(-1, 2), axis=0):
            array = self.array(tx, ty)
            if array is None:
                continue
            mask = (x == tx) & (y == ty)
            result[mask] = array[np.floor(py[mask]).astype(int) % 256, np.floor(px[mask]).astype(int) % 256]
        return result

    def fetch(self, tiles, maximum=1000, interval=.3):
        if len(tiles) > maximum:
            raise ValueError(f'{len(tiles)} tiles exceed the explicit limit {maximum}')
        records = []
        for index, (x, y) in enumerate(sorted(tiles)):
            path = self.path(x, y)
            url = TILE_URL.format(z=self.zoom, x=x, y=y)
            status = 'cached'
            if not path.exists():
                try:
                    request = Request(url, headers={'User-Agent': 'TheaCar-offline-terrain/1.0'})
                    with urlopen(request, timeout=15) as response:
                        raw = response.read(1_000_001)
                    if len(raw) > 1_000_000:
                        raise ValueError('DEM tile exceeds size limit')
                    decode_png(raw)
                    path.parent.mkdir(parents=True, exist_ok=True)
                    temporary = path.with_suffix('.download')
                    temporary.write_bytes(raw)
                    temporary.replace(path)
                    status = 'downloaded'
                except HTTPError as error:
                    if error.code != 404:
                        raise
                    status = 'unavailable'
                time.sleep(interval)
            records.append({'x': x, 'y': y, 'url': url, 'status': status,
                            'sha256': hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None})
            if (index + 1) % 25 == 0:
                print(f'DEM tiles: {index + 1}/{len(tiles)}', flush=True)
        manifest = {'source_url': SOURCE_URL, 'terms_url': TERMS_URL, 'zoom': self.zoom,
                    'acquired_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'tiles': records}
        self.directory.mkdir(parents=True, exist_ok=True)
        (self.directory / f'manifest-z{self.zoom}.json').write_text(json.dumps(manifest, indent=2))
        return manifest


def terrain_features(elevation, horizons):
    """A valley proxy needs two walls and a more open perpendicular axis."""
    if not math.isfinite(elevation) or not all(math.isfinite(v) for v in horizons):
        return {}
    positive = [max(0., v) for v in horizons]
    valley = 0.
    for axis in range(4):
        walls = min(positive[axis], positive[axis + 4])
        along = (positive[(axis + 2) % 8] + positive[(axis + 6) % 8]) / 2
        valley = max(valley, min(1., max(0., (walls - 3) / 12)) * min(1., max(0., (walls - along) / max(3, walls))))
    return {'elevation_m': round(float(elevation), 1), 'terrain_valley': round(valley, 4),
            'terrain_openness': round(1 - sum(min(1, v / 30) for v in positive) / 8, 4),
            'horizon_degrees': [round(float(v), 1) for v in horizons]}
