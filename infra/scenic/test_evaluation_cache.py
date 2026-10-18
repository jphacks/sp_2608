import math
import sqlite3
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from geometry import decode_shape, samples
from recommendation.evaluator import RouteEvaluator
from recommendation.jobs import JobStore
from recommendation.models import Parameters
from recommendation.store import ScenicStore
from recommendation.environment import Forecast
import test_scenic
from test_scenic import encode, response
from test_recommendation import request_body
from recommendation.models import RecommendationRequest


class EvaluationCacheTest(unittest.TestCase):
    def setUp(self):
        self.fixture = test_scenic.ScenicTest()
        self.fixture.setUp()

    def tearDown(self):
        self.fixture.tearDown()

    def test_match_tile_candidates_equal_original_query(self):
        catalog = self.fixture.catalog
        for lat in (42.999999, 43., 43.000001, 43.003, 43.004):
            for step in range(100):
                lon = 140.999 + step * .00015
                dy = 25 / 111195
                dx = dy / max(.1, math.cos(math.radians(lat)))
                original = catalog.roads((lon - dx, lat - dy, lon + dx, lat + dy), 100)
                cached = catalog.match_roads((lat, lon))
                self.assertEqual([r['id'] for r in original], [r['id'] for r in cached])
        self.assertLess(catalog.match_queries, 100)
        self.assertGreater(catalog.match_tile_hits, 400)

    def test_segment_reuse_preserves_full_evaluation(self):
        evaluator = RouteEvaluator(ScenicStore(self.fixture.catalog), Parameters(), Forecast('disabled', 'test'))
        request = RecommendationRequest.parse(request_body())
        points = [self.fixture.coast_start, self.fixture.coast_end]
        route = response(points, 100)
        first = evaluator.evaluate(request, route, points, (), 100)
        self.assertTrue(evaluator.segment_cache)
        second = evaluator.evaluate(request, route, points, (), 100)
        self.assertEqual(first, second)
        self.assertEqual(tuple(samples(points, 50)), evaluator.segment_geometry(*points)[1])

    def test_dense_tiles_fall_back_to_original_bounded_query(self):
        with sqlite3.connect(Path(self.fixture.temp.name) / 'roads.sqlite') as db:
            db.executemany('''INSERT INTO road SELECT ?,way,sequence,lat1,lon1,lat2,lon2,length,
                sea,forest,nature,urban,load FROM road ORDER BY id LIMIT 1''', [(10000 + i,) for i in range(513)])
            db.executemany('''INSERT INTO road_bounds SELECT ?,min_lon,max_lon,min_lat,max_lat
                FROM road_bounds ORDER BY id LIMIT 1''', [(10000 + i,) for i in range(513)])
        lat, lon = self.fixture.origin
        dy = 25 / 111195
        dx = dy / math.cos(math.radians(lat))
        expected = self.fixture.catalog.roads((lon - dx, lat - dy, lon + dx, lat + dy), 100)
        actual = self.fixture.catalog.match_roads((lat, lon))
        self.assertEqual(100, len(actual))
        self.assertEqual([r['id'] for r in expected], [r['id'] for r in actual])
        self.assertTrue(any(rows is None for rows in self.fixture.catalog._match_tiles.values()))

    def test_cached_decode_does_not_share_mutable_results(self):
        points = [self.fixture.origin, self.fixture.end]
        encoded = encode(points)
        decoded = decode_shape(encoded)
        decoded.clear()
        self.assertEqual(points, decode_shape(encoded))

    def test_job_publications_increment_status_revision_not_route_revision(self):
        with TemporaryDirectory() as directory:
            jobs = JobStore(Path(directory) / 'jobs.sqlite')
            job = jobs.create({})
            route = {'route_id': 'route_test', 'revision': 1}
            jobs.publish(job, {'status': 'improving', 'revision': 1, 'routes': [route]})
            first = jobs.get(job)['revision']
            jobs.publish(job, {'status': 'improving', 'revision': 1, 'routes': [route]})
            self.assertGreater(jobs.get(job)['revision'], first)
            self.assertEqual(1, jobs.route('route_test')[0]['revision'])
