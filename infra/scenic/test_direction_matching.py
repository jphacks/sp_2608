from dataclasses import replace
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

from recommendation.environment import Forecast
from recommendation.evaluator import RouteEvaluator
from recommendation.models import Infeasible, Parameters, RecommendationRequest
from recommendation.jobs import JobStore, RecommendationService
from recommendation.search import CandidatePlanner
from recommendation.store import ScenicStore, direction_allowed
import test_scenic
from test_recommendation import request_body


class DirectionMatchingTest(unittest.TestCase):
    def setUp(self):
        self.fixture = test_scenic.ScenicTest()
        self.fixture.setUp()
        self.store = ScenicStore(self.fixture.catalog)
        self.request = RecommendationRequest.parse(request_body())
        self.evaluator = RouteEvaluator(self.store, Parameters(), Forecast('disabled', 'test'))

    def tearDown(self):
        self.fixture.tearDown()

    def test_oneway_forward_reverse_and_bidirectional(self):
        for oneway, reverse, allowed in [(0, False, True), (0, True, True),
                                         (1, False, True), (1, True, False),
                                         (-1, False, False), (-1, True, True)]:
            with self.subTest(oneway=oneway, reverse=reverse):
                self.assertEqual(allowed, direction_allowed({'oneway': oneway}, reverse))

    def test_opposite_lane_is_excluded_before_nearest_road_selection(self):
        rows = self.fixture.catalog.roads((140.99, 42.99, 141.02, 43.01))
        first = dict(rows[0])
        second = {**first, 'id': 999, 'way': 'parallel',
                  'lat1': first['lat1'] + 10 / 111195, 'lat2': first['lat2'] + 10 / 111195}
        self.fixture.catalog.match_roads = Mock(return_value=[first, second])
        road = self.store.road
        self.store.road = lambda row: {**road(row), 'oneway': -1 if row['id'] == first['id'] else 1}
        point = (first['lat1'], (first['lon1'] + first['lon2']) / 2)
        matched, reverse = self.store.match(point, 90)
        self.assertEqual('parallel', matched['way'])
        self.assertFalse(reverse)
        matched, reverse = self.store.match(point, 270)
        self.assertEqual(first['way'], matched['way'])
        self.assertTrue(reverse)

    def opposite_only(self):
        road = self.store.road
        self.store.road = lambda row: {**road(row), 'oneway': -1}

    def evaluate(self, request=None):
        points = [self.fixture.coast_start, self.fixture.coast_end]
        return self.evaluator.evaluate(request or self.request, test_scenic.response(points, 100), points, (), 100)

    def test_only_opposite_direction_nearby_is_unknown_not_route_rejection(self):
        self.opposite_only()
        result = self.evaluate()
        self.assertEqual([], result['violations'])
        self.assertEqual(0, result['metrics']['coverage'])
        self.assertEqual(0, result['metrics']['effective_m']['sea'])
        self.assertEqual(0, result['metrics']['sea_proximity_m'])
        self.assertEqual({}, result['recommended'])
        self.assertEqual({'unknown'}, {block['category'] for block in result['timeline']})

    def test_evaluator_does_not_credit_conflicting_matches_from_other_matchers(self):
        self.store.match = Mock(return_value=({'oneway': -1}, False))
        result = self.evaluate()
        self.assertEqual([], result['violations'])
        self.assertEqual(0, result['metrics']['coverage'])
        self.assertEqual(0, result['metrics']['sea_proximity_m'])

    def test_strict_unknown_surface_constraint_is_not_relaxed(self):
        self.opposite_only()
        strict = replace(self.request, constraints=replace(self.request.constraints, allow_unpaved=False))
        result = self.evaluate(strict)
        self.assertIn('unpaved_or_unverified_surface', result['violations'])

    def test_no_route_from_routing_engine_still_fails(self):
        adapter = Mock(initial_alternates=[])
        adapter.cache_key.return_value = 'missing'
        adapter.calculate.side_effect = Infeasible('no_route')
        planner = CandidatePlanner(self.store, adapter, self.evaluator.forecast, Parameters())
        with self.assertRaises(Infeasible) as caught:
            planner.plan(self.request)
        self.assertEqual('baseline_unavailable', caught.exception.code)
        self.assertEqual(1, planner.budget.failures['no_route'])
        self.assertFalse(planner.archive)

    def test_infeasible_job_keeps_search_diagnostics(self):
        directory = Path(self.fixture.temp.name)
        jobs = JobStore(directory / 'jobs.sqlite')
        service = RecommendationService(jobs, str(directory / 'roads.sqlite'), 'http://unused')
        diagnostics = {'calls': 1, 'evaluations': 0, 'failures': {'no_route': 1}}
        with patch('recommendation.jobs.CandidatePlanner') as planner:
            planner.return_value.plan.side_effect = Infeasible('baseline_unavailable')
            planner.return_value.budget.status.return_value = diagnostics
            try:
                job = service.submit(self.request)
            finally:
                service.executor.shutdown(wait=True)
        result = jobs.get(job['request_id'])
        self.assertEqual('infeasible', result['status'])
        self.assertEqual('baseline_unavailable', result['error'])
        self.assertEqual(diagnostics, result['search'])
