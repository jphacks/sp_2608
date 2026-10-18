import json
import math
import sqlite3
import tempfile
import unittest
from pathlib import Path

from catalog import Catalog, SCHEMA
from geometry import distance, decode_shape
from planner import ScenicPlanner, scenic_sections
from scoring import evaluate, select_diverse
from server import coordinate, validate


def encode(points):
    result, previous = '', (0, 0)
    for point in points:
        current = tuple(round(value * 1e6) for value in point)
        for value, old in zip(current, previous):
            delta = value - old
            value = ~(delta << 1) if delta < 0 else delta << 1
            while value >= 32:
                result += chr((value & 31 | 32) + 63)
                value >>= 5
            result += chr(value + 63)
        previous = current
    return result


def response(points, seconds):
    return {'trip': {'status': 0, 'summary': {'time': seconds, 'length': 1},
                     'legs': [{'shape': encode(points), 'maneuvers': []}]}}


class ScenicTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        path = Path(self.temp.name) / 'roads.sqlite'
        db = sqlite3.connect(path)
        db.executescript(SCHEMA)
        db.executemany('INSERT INTO metadata VALUES (?,?)', [('schema', '1'), ('bounds', '[140,42,142,44]')])
        self.origin, self.end = (43, 141), (43, 141.01)
        self.coast_start, self.coast_end = (43.003, 141), (43.003, 141.01)
        index = 0
        for way, latitude, sea in [('base', 43, 0.), ('coast', 43.003, 1.)]:
            for sequence in range(10):
                a, b = (latitude, 141 + sequence * .001), (latitude, 141 + (sequence + 1) * .001)
                index += 1
                db.execute('INSERT INTO road VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)',
                           (index, way, sequence, *a, *b, distance(a, b), sea, 0., sea, 0., 0.))
                db.execute('INSERT INTO road_bounds VALUES (?,?,?,?,?)', (index, a[1], b[1], a[0], b[0]))
        db.commit()
        db.close()
        self.catalog = Catalog(path)

    def tearDown(self):
        self.catalog.close()
        self.temp.cleanup()

    def test_polyline_round_trip_and_reject_truncation(self):
        self.assertEqual([self.origin, self.end], decode_shape(encode([self.origin, self.end])))
        with self.assertRaises(ValueError):
            decode_shape('_')

    def test_extracts_roads_without_registered_destinations(self):
        sections = scenic_sections(self.catalog, (140.9, 42.9, 141.1, 43.1), 'sea')
        self.assertEqual(1, len(sections))
        self.assertEqual('coast:0', sections[0]['id'])
        self.assertEqual(2, self.catalog.db.execute('PRAGMA temp_store').fetchone()[0])

    def test_duplicate_roads_do_not_increase_sea_reward(self):
        one = evaluate([self.coast_start, self.coast_end], self.catalog, 'sea', 100, 100)
        twice = evaluate([self.coast_start, self.coast_end, self.coast_start], self.catalog, 'sea', 200, 100)
        self.assertLessEqual(twice['seaMeters'], distance(self.coast_start, self.coast_end) + 1)
        self.assertGreater(twice['repeatedMeters'], 500)
        self.assertLess(twice['score'], one['score'])

    def test_unknown_roads_are_reported_as_unknown(self):
        metrics = evaluate([(43.1, 141), (43.1, 141.01)], self.catalog, 'sea', 100, 100)
        self.assertEqual(0, metrics['coverage'])
        self.assertEqual(0, metrics['seaMeters'])

    def test_budget_is_hard_constraint_and_calls_are_bounded(self):
        def routing(points, options, timeout):
            return response(points, 100 if len(points) == 2 else 300)
        result = ScenicPlanner(self.catalog, routing, max_calls=5).plan(self.origin, self.end, {}, 'sea', 50)
        self.assertEqual(1, len(result['alternatives']))
        self.assertEqual(0, result['alternatives'][0]['extraSeconds'])
        self.assertLessEqual(result['calls'], 5)

    def test_selects_scenic_route_when_budget_allows(self):
        def routing(points, options, timeout):
            return response(points, 100 if len(points) == 2 else 200)
        result = ScenicPlanner(self.catalog, routing).plan(self.origin, self.end, {}, 'sea', 200)
        self.assertGreater(result['alternatives'][0]['metrics']['seaMeters'], 500)
        self.assertGreater(result['alternatives'][0]['extraSeconds'], 0)

    def test_rejects_shortcut_which_does_not_traverse_target(self):
        def routing(points, options, timeout):
            return response([self.origin, self.end], 100)
        result = ScenicPlanner(self.catalog, routing).plan(self.origin, self.end, {}, 'sea', 200)
        self.assertEqual(1, len(result['alternatives']))
        self.assertEqual([], result['alternatives'][0]['viaPoints'])

    def test_keeps_baseline_when_candidate_request_fails(self):
        def routing(points, options, timeout):
            if len(points) > 2:
                raise TimeoutError()
            return response(points, 100)
        result = ScenicPlanner(self.catalog, routing, max_calls=3).plan(self.origin, self.end, {}, 'sea', 200)
        self.assertEqual(1, len(result['alternatives']))
        self.assertEqual(0, result['alternatives'][0]['extraSeconds'])

    def test_identical_candidates_are_not_padded_to_three(self):
        candidate = {'metrics': {'edges': {1, 2}, 'score': 1, 'seaMeters': 1, 'forestMeters': 0}, 'seconds': 100, 'key': ()}
        self.assertEqual(1, len(select_diverse([candidate, candidate.copy()])))

    def test_validates_request(self):
        request = {'origin': {'lat': 43, 'lon': 141}, 'destination': {'lat': 43.1, 'lon': 141.1}}
        self.assertEqual(900, validate(request)[-1])
        for value in [math.nan, True, 91, '43']:
            with self.assertRaises(ValueError):
                coordinate({'lat': value, 'lon': 141})
        for extra in [-1, 1801, True, 2.5]:
            with self.assertRaises(ValueError):
                validate(dict(request, extraSeconds=extra))


if __name__ == '__main__':
    unittest.main()
