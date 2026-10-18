from pathlib import Path
import sqlite3
import tempfile
import unittest

try:
    from import_osm import build
except ImportError:
    build = None


@unittest.skipIf(build is None, 'Run with .venv-scenic to test the PBF importer')
class ImportTest(unittest.TestCase):
    def test_extracts_coast_and_forest_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'scenic.sqlite'
            source = Path(__file__).parent / 'fixtures/coast.osm'
            build(source, target, [140.9, 42.9, 141.1, 43.1])
            db = sqlite3.connect(target)
            roads = db.execute('SELECT sea,forest,nature FROM road').fetchall()
            db.close()
            self.assertGreater(len(roads), 5)
            self.assertTrue(all(.5 < road[0] < 1 for road in roads))
            self.assertTrue(all(road[1] > .9 for road in roads))
            with self.assertRaises(ValueError):
                build(source, target, [140.9, 42.9, 141.1, 43.1])
