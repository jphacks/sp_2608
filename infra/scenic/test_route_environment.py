from datetime import datetime, timedelta
import json
from pathlib import Path
import sqlite3
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from recommendation.environment import Forecast
from recommendation.evaluator import RouteEvaluator
from recommendation.models import Parameters, RecommendationRequest, identity
from recommendation.route_environment import build_profile, sky_condition, light_phase, best_spots
from recommendation.store import ScenicStore
from test_recommendation import request_body
import test_scenic
from test_scenic import response


class RouteEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.fixture = test_scenic.ScenicTest()
        self.fixture.setUp()
        self.store = ScenicStore(self.fixture.catalog)
        self.request = RecommendationRequest.parse(request_body())
        self.forecast = Forecast('disabled', self.request.departure.isoformat())
        self.points = [self.fixture.coast_start, self.fixture.coast_end]
        self.route = response(self.points, 100)
        self.evaluation = RouteEvaluator(self.store, Parameters(), self.forecast).evaluate(self.request, self.route, self.points, (), 100)
        self.candidate = SimpleNamespace(points=self.points, route=self.route, evaluation=self.evaluation)

    def tearDown(self):
        self.fixture.tearDown()

    def test_profile_uses_route_clock_and_does_not_mutate_scoring(self):
        previous = json.dumps(self.evaluation, sort_keys=True)
        result = build_profile(self.store, self.forecast, self.candidate, self.request)
        self.assertGreater(len(result['timeline']), 0)
        self.assertLessEqual(len(result['timeline']), 192)
        self.assertEqual(self.request.departure, datetime.fromisoformat(result['timeline'][0]['start_time']))
        self.assertEqual(self.request.departure + timedelta(seconds=100), datetime.fromisoformat(result['timeline'][-1]['end_time']))
        self.assertEqual(previous, json.dumps(self.evaluation, sort_keys=True))
        self.assertTrue(all(s['sky'] == 'unknown' for s in result['timeline']))
        self.assertTrue(all(s['features']['lake'] is None for s in result['timeline']))

    def test_stops_are_included_in_passage_times(self):
        self.candidate.evaluation = {**self.evaluation, 'schedule': [{'distance_m': 0, 'dwell_seconds': 600}]}
        result = build_profile(self.store, self.forecast, self.candidate, self.request)
        self.assertEqual(self.request.departure + timedelta(seconds=700), datetime.fromisoformat(result['timeline'][-1]['end_time']))

    def test_missing_weather_never_becomes_clear_and_precipitation_is_not_called_rain(self):
        self.assertEqual('unknown', sky_condition(None))
        self.assertEqual('unknown', sky_condition({'cloud_cover': 0}))
        self.assertEqual('clear', sky_condition({'cloud_cover': 5, 'precipitation': 0}))
        self.assertEqual('cloudy', sky_condition({'cloud_cover': 80, 'precipitation': 0}))
        self.assertEqual('precipitation', sky_condition({'cloud_cover': 80, 'precipitation': 1}))

    def test_night_is_not_suggested_as_a_daylight_viewpoint(self):
        with patch('recommendation.route_environment.light_phase', return_value=(-30, 0, 'night')):
            result = build_profile(self.store, self.forecast, self.candidate, self.request)
        self.assertEqual([], result['best_spots'])

    def test_dawn_and_dusk_follow_altitude_change_not_a_fixed_clock_hour(self):
        with patch('recommendation.route_environment.solar_position', side_effect=[(0, 90), (-1, 90), (1, 90)]):
            self.assertEqual('dawn', light_phase(self.points[0], self.request.departure)[2])
        with patch('recommendation.route_environment.solar_position', side_effect=[(0, 270), (1, 270), (-1, 270)]):
            self.assertEqual('dusk', light_phase(self.points[0], self.request.departure)[2])

    def test_sidecar_is_bound_to_catalog_and_provides_water_to_display_and_routing(self):
        path = Path(self.fixture.temp.name) / 'landscape.sqlite'
        with sqlite3.connect(path) as db:
            db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT); CREATE TABLE road_landscape(road_id INTEGER PRIMARY KEY,payload TEXT);')
            db.executemany('INSERT INTO metadata VALUES (?,?)', [('schema', '1'), ('version', 'test'),
                ('catalog_signature', identity(self.fixture.catalog.source))])
            db.executemany('INSERT INTO road_landscape VALUES (?,?)', [(r[0], '{"lake":0.9,"lake_bearing":270}')
                for r in self.fixture.catalog.db.execute('SELECT id FROM road')])
        store = ScenicStore(self.fixture.catalog, landscapes=path)
        result = build_profile(store, self.forecast, self.candidate, self.request)
        self.assertTrue(result['landscape_available'])
        self.assertTrue(any(s['features']['lake'] == .9 for s in result['timeline']))
        road = store.match(self.points[0], 90)[0]
        self.assertEqual(.9, road['qualities']['inland_water'])
        self.assertEqual(.9, road['qualities']['lake'])

    def test_distributed_spots_are_limited_and_never_claim_verified_stopping(self):
        sections = []
        for i in range(30):
            sections.append({'start_m': i * 1000, 'end_m': (i + 1) * 1000, 'arrival_time': self.request.departure.isoformat(),
                'features': {'sea': .8}, 'openness': .8, 'visibility_verified': False, 'sun_view_alignment': 1,
                'light_phase': 'dusk', 'sky': 'unknown', 'tunnel': False, 'matched': True})
        result = best_spots(sections, 30000)
        self.assertEqual(4, len(result))
        self.assertTrue(all(not s['stopping_verified'] for s in result))
        self.assertTrue(all('forecast_unavailable' in s['reasons'] for s in result))
        self.assertTrue(all('sunset_direction' in s['reasons'] for s in result))

    def test_tunnels_do_not_generate_viewpoints(self):
        for road in self.fixture.catalog.db.execute('SELECT DISTINCT way FROM road'):
            self.store.attribute_cache[road[0]] = {'tunnel': True}
        self.store.cache.clear()
        self.store.match_cache.clear()
        result = build_profile(self.store, self.forecast, self.candidate, self.request)
        self.assertEqual([], result['best_spots'])

    def test_known_blocked_sea_view_is_not_promoted_as_a_viewpoint(self):
        for road in self.fixture.catalog.db.execute('SELECT * FROM road'):
            physical = self.store.physical_id(road)
            self.store.feature_overrides[physical] = {'sea_visibility': 0, 'forest': 0, 'open_nature': 0}
            self.store.feature_sources[physical] = 'test'
        self.store.cache.clear()
        self.store.match_cache.clear()
        result = build_profile(self.store, self.forecast, self.candidate, self.request)
        self.assertFalse(any(s['category'] == 'sea' for s in result['best_spots']))
