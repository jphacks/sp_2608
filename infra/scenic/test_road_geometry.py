import io
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile

from road_geometry import published_sections, match_lines, trace_straight_road
import test_scenic


class PublishedGeometryTest(unittest.TestCase):
    def test_nested_kmz_keeps_section_id_and_excludes_nonselected_lines(self):
        xml = b'''<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
          <Placemark><styleUrl>#line-FF5252-5000-nodesc</styleUrl><LineString><coordinates>141,43 141.01,43</coordinates></LineString></Placemark>
          <Placemark><styleUrl>#line-1267FF-5000-nodesc</styleUrl><LineString><coordinates>142,44 142.01,44</coordinates></LineString></Placemark>
          </Document></kml>'''
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as archive:
            archive.writestr('doc.kml', xml)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'route.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('scenicroad13.kmz', nested.getvalue())
            result = published_sections(path)
        self.assertEqual([[(141., 43.), (141.01, 43.)]], result['selected-13'])


class GeometryMatchingTest(unittest.TestCase):
    def setUp(self):
        try:
            import shapely
        except ImportError:
            self.skipTest('Optional geometry importer is not installed')
        self.fixture = test_scenic.ScenicTest()
        self.fixture.setUp()

    def tearDown(self):
        self.fixture.tearDown()

    def test_matches_full_line_and_rejects_crossing_or_different_ref(self):
        catalog = self.fixture.catalog
        path = catalog.db.execute('PRAGMA database_list').fetchone()[2]
        with sqlite3.connect(path) as db:
            db.execute('INSERT INTO road VALUES (999,?,?,?,?,?,?,?,?,?,?,?,?)',
                       ('cross', 0, 43.002, 141.005, 43.004, 141.005, 222, 0, 0, 0, 0, 0))
            db.execute('INSERT INTO road_bounds VALUES (999,141.005,141.005,43.002,43.004)')
        lines = [[(141., 43.003), (141.01, 43.003)]]
        ids, report = match_lines(catalog, lines)
        self.assertNotIn(999, ids)
        self.assertGreater(report['coverage'], .99)
        ids, report = match_lines(catalog, lines, {'coast': ['10']}, ['20'])
        self.assertEqual([], ids)
        self.assertEqual(0, report['coverage'])

    def test_straight_trace_uses_connected_roads_and_reports_extent(self):
        ids, report = trace_straight_road(self.fixture.catalog, self.fixture.coast_start, 90, 810,
                                          (140.99,43.002,141.02,43.004))
        self.assertTrue(ids)
        self.assertGreater(report['coverage'], .95)
        self.assertLessEqual(report['coverage'], 1)


if __name__ == '__main__':
    unittest.main()
