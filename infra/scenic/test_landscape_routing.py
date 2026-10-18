from dataclasses import replace
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from recommendation.environment import Forecast
from recommendation.evaluator import RouteEvaluator
from recommendation.landscape_quality import sun_alignment_bonus
from recommendation.models import CATEGORIES, Parameters, RecommendationRequest, identity
from recommendation.store import ScenicStore
from recommendation.search import CandidatePlanner
from test_recommendation import request_body
import test_scenic


class LandscapeRoutingTest(unittest.TestCase):
    def setUp(self):
        self.fixture = test_scenic.ScenicTest()
        self.fixture.setUp()
        self.parameters = Parameters()
        self.request = RecommendationRequest.parse({**request_body(), 'preferences': {'lake': 1}})
        self.forecast = Forecast('disabled', self.request.departure.isoformat())

    def tearDown(self):
        self.fixture.tearDown()

    def store(self, features=None, kinds=None):
        path = Path(self.fixture.temp.name) / 'landscapes.sqlite'
        with closing(sqlite3.connect(path)) as db, db:
            db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT);'
                             'CREATE TABLE road_landscape(road_id INTEGER PRIMARY KEY,payload TEXT);')
            db.executemany('INSERT INTO metadata VALUES (?,?)', [
                ('schema', '1'), ('version', 'landscape-test'),
                ('catalog_signature', identity(self.fixture.catalog.source)),
                ('object_counts', json.dumps(dict.fromkeys(kinds or ('lake', 'river', 'water', 'valley', 'farmland'), 1)))])
            if features:
                db.executemany('INSERT INTO road_landscape VALUES (?,?)', [
                    (row['id'], json.dumps(features)) for row in self.fixture.catalog.db.execute('SELECT id FROM road WHERE way=\'base\'')])
        return ScenicStore(self.fixture.catalog, landscapes=path)

    def evaluate(self, store, points, request=None):
        route = test_scenic.response(points, 100)
        return RouteEvaluator(store, self.parameters, self.forecast).evaluate(request or self.request, route, points, (), 100)

    def test_new_preferences_round_trip_and_keep_legacy_water(self):
        for category in ('lake', 'river', 'valley', 'farmland', 'urban', 'inland_water'):
            with self.subTest(category=category):
                request = RecommendationRequest.parse({**request_body(), 'preferences': {category: 1}})
                self.assertEqual(1., request.preferences.weights[CATEGORIES.index(category)])
                self.assertEqual(request, RecommendationRequest.parse(request.payload()))

    def test_water_and_valley_only_roads_survive_candidate_sql_prefilter(self):
        store = self.store({'lake': .95, 'river': .9, 'valley': .9, 'farmland': .9})
        for category in ('lake', 'river', 'valley', 'farmland', 'inland_water'):
            request = RecommendationRequest.parse({**request_body(), 'preferences': {category: 1}})
            with self.subTest(category=category):
                arcs, _ = store.candidates(request, self.parameters, 3600)
                self.assertTrue(arcs)
                self.assertTrue(any(arc.category == category for arc in arcs))
                self.assertTrue(all(arc.landscape_value > 0 for arc in arcs))

    def test_new_features_change_route_score_and_flow_not_just_display(self):
        store = self.store({'lake': .95, 'river': .85, 'valley': .9, 'farmland': .9})
        scenic = self.evaluate(store, [self.fixture.origin, self.fixture.end])
        other = self.evaluate(store, [self.fixture.coast_start, self.fixture.coast_end])
        self.assertGreater(scenic['components']['Q'], other['components']['Q'])
        self.assertGreater(scenic['score'], other['score'])
        for category in ('lake', 'river', 'valley', 'farmland'):
            self.assertGreater(scenic['metrics']['effective_m'][category], 0)
            self.assertGreater(scenic['metrics']['threshold_m'][category], 0)
        self.assertTrue(any(block['category'] == 'lake' for block in scenic['timeline']))
        self.assertEqual('2.8-terrain-context', scenic['evaluation_version'])

    def test_beam_and_alns_accept_new_categories_after_corridor_phase(self):
        store = self.store({'lake': .95, 'river': .85, 'valley': .9})
        adapter = Mock(initial_alternates=[])
        adapter.cache_key.side_effect = lambda request, anchors: str([(a.coordinate, a.kind) for a in anchors])
        adapter.calculate.return_value = test_scenic.response([self.fixture.origin, self.fixture.end], 100)
        for category in ('lake', 'river', 'valley'):
            request = RecommendationRequest.parse({**request_body(), 'preferences': {category: 1}})
            planner = CandidatePlanner(store, adapter, self.forecast,
                                       Parameters(max_evaluations=20, alns_iterations=5))
            with self.subTest(category=category), patch('recommendation.search.corridor_seeds', return_value=[]):
                _, selected, _ = planner.plan(request)
                self.assertTrue(selected)
                self.assertTrue(planner.budget.operators)

    def test_duplicate_water_road_does_not_double_count_effective_reward(self):
        store = self.store({'lake': .95})
        one = self.evaluate(store, [self.fixture.origin, self.fixture.end])
        twice = self.evaluate(store, [self.fixture.origin, self.fixture.end, self.fixture.origin])
        self.assertLessEqual(twice['metrics']['effective_m']['lake'], one['metrics']['effective_m']['lake'] + 1)
        self.assertGreater(twice['metrics']['repeated_m'], 300)

    def test_unknown_sidecar_is_missing_not_measured_zero(self):
        store = ScenicStore(self.fixture.catalog)
        result = self.evaluate(store, [self.fixture.origin, self.fixture.end])
        self.assertGreater(result['metrics']['missingness']['lake'], .9)
        self.assertLess(result['metrics']['preference_data_coverage'], .1)
        self.assertIsNone(store.match(self.fixture.origin, 90)[0]['qualities']['lake'])

    def test_old_sidecar_does_not_claim_farmland_has_been_measured(self):
        store = self.store({'lake': .95}, kinds=('sea', 'lake', 'river', 'water', 'valley'))
        self.assertIsNone(store.match(self.fixture.origin, 90)[0]['qualities']['farmland'])

    def test_tunnels_have_no_landscape_reward_or_candidate(self):
        store = self.store({'lake': 1, 'river': 1, 'valley': 1, 'farmland': 1})
        for row in self.fixture.catalog.db.execute('SELECT DISTINCT way FROM road'):
            store.attribute_cache[row[0]] = {'tunnel': True}
        result = self.evaluate(store, [self.fixture.origin, self.fixture.end])
        self.assertEqual(0, result['metrics']['effective_m']['lake'])
        self.assertEqual([], store.candidates(self.request, self.parameters, 3600)[0])

    def test_known_blocked_water_view_is_not_rewarded(self):
        store = self.store({'lake': 1})
        road = store.match(self.fixture.origin, 90)[0]
        road['features']['lake_visibility'] = 0
        evaluator = RouteEvaluator(store, self.parameters, self.forecast)
        values, _, _ = evaluator.qualities(self.request, road, False, self.fixture.origin, 90, self.request.departure)
        self.assertEqual(0, values['lake'])

    def test_night_switch_applies_to_water_valley_and_farmland(self):
        store = self.store({'lake': 1, 'river': 1, 'valley': 1, 'farmland': 1})
        road = store.match(self.fixture.origin, 90)[0]
        evaluator = RouteEvaluator(store, self.parameters, self.forecast)
        with patch('recommendation.evaluator.solar_position', return_value=(-20, 270)):
            on = evaluator.qualities(self.request, road, False, self.fixture.origin, 90, self.request.departure)[0]
            off = evaluator.qualities(replace(self.request, night_correction=False), road, False, self.fixture.origin, 90, self.request.departure)[0]
        for category in ('lake', 'river', 'valley', 'farmland'):
            self.assertEqual(0, on[category])
            self.assertGreater(off[category], 0)

    def test_sun_direction_bonus_requires_daybreak_and_good_forecast(self):
        features = {'lake_bearing': 270, 'horizon_degrees': [0] * 8}
        clear = {'cloud_cover': 5, 'precipitation': 0, 'visibility': 15000}
        self.assertAlmostEqual(1.15, sun_alignment_bonus(features, 'lake', 2, 270, clear))
        for altitude, azimuth, weather in [(-20, 270, clear), (30, 270, clear), (2, 90, clear),
                (2, 270, None), (2, 270, {**clear, 'cloud_cover': 80}),
                (2, 270, {**clear, 'precipitation': 1}), (2, 270, {**clear, 'visibility': 100})]:
            self.assertEqual(1, sun_alignment_bonus(features, 'lake', altitude, azimuth, weather))

    def test_rain_and_low_visibility_reduce_all_new_outdoor_qualities(self):
        store = self.store({'lake': 1, 'river': 1, 'valley': 1, 'farmland': 1})
        road = store.match(self.fixture.origin, 90)[0]
        dry = SimpleNamespace(at=lambda *args: {'cloud_cover': 5, 'precipitation': 0, 'visibility': 20000})
        wet = SimpleNamespace(at=lambda *args: {'cloud_cover': 90, 'precipitation': 5, 'visibility': 1000})
        with patch('recommendation.evaluator.solar_position', return_value=(40, 180)):
            clear = RouteEvaluator(store, self.parameters, dry).qualities(self.request, road, False, self.fixture.origin, 90, self.request.departure)[0]
            rain = RouteEvaluator(store, self.parameters, wet).qualities(self.request, road, False, self.fixture.origin, 90, self.request.departure)[0]
        for category in ('lake', 'river', 'valley', 'farmland'):
            self.assertLess(rain[category], clear[category])

    def test_water_side_respects_passenger_preference_and_driving_direction(self):
        store = self.store({'lake': 1, 'lake_bearing': 180})
        road = store.match(self.fixture.origin, 90)[0]
        request = replace(self.request, preferences=replace(self.request.preferences, passenger_view=1))
        evaluator = RouteEvaluator(store, self.parameters, self.forecast)
        right = evaluator.qualities(request, road, False, self.fixture.origin, 90, request.departure)[0]['lake']
        left = evaluator.qualities(request, road, True, self.fixture.origin, 270, request.departure)[0]['lake']
        self.assertAlmostEqual(.8 * left, right)

    def test_missing_farmland_and_new_quality_coverage_do_not_relax_driving_budget(self):
        store = self.store({'lake': 1})
        request = replace(self.request, extra_seconds=0)
        points = [self.fixture.origin, self.fixture.end]
        result = RouteEvaluator(store, self.parameters, self.forecast).evaluate(request, test_scenic.response(points, 101), points, (), 100)
        self.assertIn('driving_budget', result['violations'])
