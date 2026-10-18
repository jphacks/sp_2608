import sqlite3
import tempfile
import unittest
import importlib.util
import hashlib
from contextlib import closing
from pathlib import Path

from places import normalize, search


class PlacesTest(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec('osmium'), 'Optional OSM importer dependency')
    def test_import_named_nodes_and_ways(self):
        from import_places import build
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'places.sqlite'
            build(Path(__file__).parent / 'fixtures' / 'places.osm', path)
            self.assertEqual(search(path, 'Test Station')[0]['name'], 'テスト駅')
            self.assertAlmostEqual(float(search(path, 'テスト公園')[0]['lat']), 43.075)
            self.assertEqual(search(path, '非公開施設'), [])
            with closing(sqlite3.connect(path)) as db:
                metadata = dict(db.execute('SELECT key,value FROM metadata'))
            self.assertEqual(metadata['source_sha256'], hashlib.sha256((Path(__file__).parent / 'fixtures' / 'places.osm').read_bytes()).hexdigest())
            with self.assertRaises(ValueError):
                build(Path(__file__).parent / 'fixtures' / 'places.osm', path)

    def test_normalization(self):
        self.assertEqual(normalize(' ＳＡＰＰＯＲＯ　駅 '), 'sapporo 駅')

    def test_search_ranking_tokens_and_literal_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'places.sqlite'
            with sqlite3.connect(path) as db:
                db.execute('CREATE TABLE places(id, name, label, lat, lon, normalized_name, terms, priority)')
                db.executemany('INSERT INTO places VALUES (?,?,?,?,?,?,?,?)', [
                    ('node:1', '札幌駅', '札幌駅 · station', 43.06, 141.35, '札幌駅', '札幌駅 sapporo 北海道', 3),
                    ('node:2', '札幌駅前店', '札幌駅前店', 43.07, 141.36, '札幌駅前店', '札幌駅前店', 1)])
            self.assertEqual(search(path, '札幌駅')[0]['place_id'], 'node:1')
            self.assertEqual(len(search(path, 'ＳＡＰＰＯＲＯ 北海道')), 1)
            self.assertEqual(search(path, "%_'; DROP TABLE places;--"), [])
            self.assertEqual(search(path, '存在しない施設'), [])
            with self.assertRaises(ValueError):
                search(path, '駅')
            with self.assertRaises(ValueError):
                search(path, 'x' * 121)
