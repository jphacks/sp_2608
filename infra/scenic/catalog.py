import json
import math
import sqlite3
from collections import OrderedDict
from pathlib import Path

from geometry import segment_distance


SCHEMA = '''
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE road(id INTEGER PRIMARY KEY, way TEXT NOT NULL, sequence INTEGER NOT NULL,
 lat1 REAL, lon1 REAL, lat2 REAL, lon2 REAL, length REAL,
 sea REAL, forest REAL, nature REAL, urban REAL, load REAL);
CREATE VIRTUAL TABLE road_bounds USING rtree(id, min_lon, max_lon, min_lat, max_lat);
'''


class Catalog:
    def __init__(self, path):
        self.db = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
        self.db.execute('PRAGMA temp_store=MEMORY')
        self.db.execute('PRAGMA cache_size=-8192')
        self.db.row_factory = sqlite3.Row
        metadata = dict(self.db.execute('SELECT key,value FROM metadata').fetchall())
        if metadata.get('schema') != '1':
            self.db.close()
            raise ValueError('Unsupported catalog')
        if metadata.get('importer_version') and metadata.get('complete') != '1':
            self.db.close()
            raise ValueError('Regional catalog is not complete')
        self.bounds = json.loads(metadata['bounds'])
        self.source = metadata
        self._nearest_cache = {}
        self._match_tiles = OrderedDict()
        self.match_queries = 0
        self.match_tile_hits = 0

    def close(self):
        self.db.close()

    def contains(self, point):
        west, south, east, north = self.bounds
        return south <= point[0] <= north and west <= point[1] <= east

    def roads(self, bounds, limit=100_000):
        west, south, east, north = bounds
        return self.db.execute('''SELECT road.* FROM road_bounds b JOIN road ON b.id=road.id
            WHERE b.max_lon>=? AND b.min_lon<=? AND b.max_lat>=? AND b.min_lat<=?
            ORDER BY road.id LIMIT ?''', (west, east, south, north, limit)).fetchall()

    def nearest(self, point):
        key = (round(point[0], 6), round(point[1], 6))
        if key not in self._nearest_cache:
            if len(self._nearest_cache) >= 50_000:
                self._nearest_cache.clear()
            self._nearest_cache[key] = self._nearest(point)
        return self._nearest_cache[key]

    def match_roads(self, point):
        lat, lon = point
        dy = 25 / 111195
        dx = dy / max(.1, math.cos(math.radians(lat)))
        key = (math.floor(lat * 500), math.floor(lon * 500))
        if key not in self._match_tiles:
            south, west = key[0] / 500, key[1] / 500
            north, east = (key[0] + 1) / 500, (key[1] + 1) / 500
            padding = dy / max(.1, math.cos(math.radians(max(abs(south), abs(north)))))
            rows = self.db.execute('''SELECT road.*, b.min_lon, b.max_lon, b.min_lat, b.max_lat
                FROM road_bounds b JOIN road ON b.id=road.id
                WHERE b.max_lon>=? AND b.min_lon<=? AND b.max_lat>=? AND b.min_lat<=?
                ORDER BY road.id LIMIT 513''', (west - padding, east + padding, south - dy, north + dy)).fetchall()
            self.match_queries += 1
            self._match_tiles[key] = rows if len(rows) <= 512 else None
            if len(self._match_tiles) > 256:
                self._match_tiles.popitem(last=False)
        else:
            self.match_tile_hits += 1
        self._match_tiles.move_to_end(key)
        rows = self._match_tiles[key]
        if rows is None:
            self.match_queries += 1
            return self.roads((lon - dx, lat - dy, lon + dx, lat + dy), 100)
        return [r for r in rows if r['max_lon'] >= lon - dx and r['min_lon'] <= lon + dx
                and r['max_lat'] >= lat - dy and r['min_lat'] <= lat + dy][:100]

    def _nearest(self, point):
        dy = 45 / 111195
        dx = dy / max(.1, math.cos(math.radians(point[0])))
        roads = self.roads((point[1] - dx, point[0] - dy, point[1] + dx, point[0] + dy), 200)
        pairs = [(segment_distance(point, (r['lat1'], r['lon1']), (r['lat2'], r['lon2'])), r) for r in roads]
        best = min(pairs, key=lambda pair: (pair[0], pair[1]['id']), default=None)
        return best[1] if best and best[0] <= 35 else None
