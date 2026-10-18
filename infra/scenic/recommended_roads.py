"""Source-backed road recommendations, separate from estimated landscape features."""

import json
from pathlib import Path
import sqlite3
import unicodedata

from road_selection import POLICY, validate_selection

RANKS = {
    'signature': {'label': '秀逸な道', 'weight': 1.0, 'order': 4},
    'panorama': {'label': '絶景ロード', 'weight': .6, 'order': 3},
    'recommended': {'label': 'おすすめロード', 'weight': .3, 'order': 2},
    'scenic': {'label': '景観ロード', 'weight': .1, 'order': 1},
}


def normalize(value):
    return ''.join(unicodedata.normalize('NFKC', value).casefold().split())


class RecommendedRoads:
    def __init__(self, path, map_version, catalog_signature=None):
        self.by_segment, self.sources = {}, []
        self.version = 'none'
        self.active_count = 0
        self.rank_counts = dict.fromkeys(RANKS, 0)
        self.pending_count = 0
        if not path:
            return
        db = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
        try:
            metadata = dict(db.execute('SELECT key,value FROM metadata'))
            if metadata.get('schema') != '1' or metadata.get('map_version') != map_version:
                raise ValueError('Recommended-road database does not match the scenic map')
            if catalog_signature is not None and metadata.get('catalog_signature') != catalog_signature:
                raise ValueError('Recommended-road database does not match the scenic catalog build')
            if metadata.get('selection_policy') != POLICY:
                raise ValueError('Recommended-road database requires rebuilding with ' + POLICY)
            if metadata.get('geometry_policy') != 'connected-v1':
                raise ValueError('Recommended-road database requires rebuilding with connected geometry')
            self.version = metadata['version']
            self.pending_count = db.execute("SELECT count(*) FROM recommendation WHERE status='pending'").fetchone()[0]
            self.sources = [json.loads(row[0]) for row in db.execute('SELECT payload FROM source ORDER BY id')]
            records = {ident: json.loads(raw) for ident, raw in db.execute("SELECT id,payload FROM recommendation WHERE status='active'")}
            if any(r.get('registration_hold_reasons') or r.get('publication_issues') for r in records.values()):
                raise ValueError('Unreviewed roads cannot be active')
            validate_selection({'selection_policy': POLICY, 'roads': list(records.values())},
                               {source['source_id']: source for source in self.sources})
            self.active_count = len(records)
            for record in records.values():
                self.rank_counts[record['rank']] += 1
            for road_id, ident in db.execute('SELECT road_id,recommendation_id FROM membership ORDER BY road_id,recommendation_id'):
                if ident in records:
                    self.by_segment.setdefault(road_id, []).append(records[ident])
        finally:
            db.close()

    def at(self, road_id):
        return self.by_segment.get(road_id, [])

    @staticmethod
    def best(items):
        return max(items, key=lambda r: (RANKS[r['rank']]['order'], r['id']), default=None)
