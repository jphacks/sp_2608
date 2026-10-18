from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
from catalog import Catalog

try:
    import import_region
    import numpy as np
    import shapely
    from shapely.geometry import Point, box
except ImportError:
    import_region = None


@unittest.skipIf(import_region is None, 'Optional regional importer dependencies')
class RegionImportTest(unittest.TestCase):
    def test_coverage_does_not_double_count_overlapping_polygons(self):
        shapes = np.array([box(-200, -200, 0, 200), box(-200, -200, 0, 200)], dtype=object)
        values = import_region.coverage(shapes, np.array([Point(0, 0), Point(500, 500)], dtype=object), 100)
        self.assertAlmostEqual(values[0], .5)
        self.assertEqual(values[1], 0.)
        merged = shapely.get_parts(shapely.disjoint_subset_union_all(shapes))
        self.assertTrue(np.allclose(values, import_region.coverage(merged,
            np.array([Point(0, 0), Point(500, 500)], dtype=object), 100, disjoint=True)))

    def test_fragmented_landscape_preserves_buffer_area_at_boundaries(self):
        geometry = box(-6000, -6000, 6000, 6000).difference(box(-300, -300, 300, 300))
        original = np.array([geometry], dtype=object)
        fragmented = import_region.fragment_polygons(original)
        points = np.array([Point(0, 0), Point(2000, 2000), Point(5800, 0)], dtype=object)
        self.assertTrue(np.allclose(import_region.coverage(original, points, 500),
                                    import_region.coverage(fragmented, points, 500, disjoint=True)))

    def test_tiled_catalog_resumes_without_duplicating_roads(self):
        source = Path(__file__).parent / 'fixtures' / 'region-boundary.osm'
        original = import_region.score_tile
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'region.sqlite'
            calls = []

            def interrupted(*args):
                calls.append(1)
                count = original(*args)
                if len(calls) == 2:
                    raise RuntimeError('Simulated interruption before tile commit')
                return count

            with patch.object(import_region, 'score_tile', interrupted):
                with self.assertRaises(RuntimeError):
                    import_region.build(source, output, .05)
            partial = sqlite3.connect(output.with_suffix('.sqlite.building'))
            try:
                self.assertEqual(partial.execute('SELECT count(*) FROM completed_tiles').fetchone()[0], 1)
            finally:
                partial.close()
            with self.assertRaises(ValueError):
                import_region.build(source, output, .1, resume=True)
            with self.assertRaises(ValueError):
                Catalog(output.with_suffix('.sqlite.building'))
            import_region.build(source, output, .05, resume=True)
            db = sqlite3.connect(output)
            try:
                rows = db.execute('SELECT sea,forest,nature FROM road').fetchall()
                self.assertGreater(len(rows), 5)
                self.assertTrue(all(.5 < r[0] < 1 and r[1] > .9 for r in rows))
                self.assertEqual(len(rows), db.execute('SELECT count(*) FROM road_bounds').fetchone()[0])
                self.assertEqual(len(rows), db.execute('SELECT count(*) FROM (SELECT DISTINCT way,sequence FROM road)').fetchone()[0])
                self.assertGreater(db.execute('SELECT count(*) FROM way_attributes').fetchone()[0], 0)
                self.assertEqual(dict(db.execute('SELECT key,value FROM metadata'))['complete'], '1')
            finally:
                db.close()
            with self.assertRaises(ValueError):
                import_region.build(source, output)
