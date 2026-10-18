from contextlib import closing
from datetime import timedelta
import io
import importlib.util
import json
from pathlib import Path
import sqlite3
import unittest

import numpy as np

from gsi_dem import decode_png, terrain_features, tile_pixels
from import_context import incline, poi_kind
from recommendation.data_status import source_status
from recommendation.environment import Forecast, OpenMeteoForecasts
from recommendation.landscape_quality import terrain_sun_visible, sun_alignment_bonus
from recommendation.models import Parameters, RecommendationRequest, identity
from recommendation.store import ScenicStore
from test_recommendation import request_body
import test_scenic


class DemTest(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec('PIL'), 'Install requirements-terrain.txt for PNG decoding tests')
    def test_signed_centimetres_and_nodata_are_decoded(self):
        from PIL import Image
        pixels = np.zeros((256, 256, 3), dtype=np.uint8)
        pixels[0, 0] = [0, 0, 100]
        pixels[0, 1] = [255, 255, 156]
        pixels[0, 2] = [128, 0, 0]
        stream = io.BytesIO()
        Image.fromarray(pixels).save(stream, format='PNG')
        values = decode_png(stream.getvalue())
        self.assertAlmostEqual(1, values[0, 0])
        self.assertAlmostEqual(-1, values[0, 1])
        self.assertTrue(np.isnan(values[0, 2]))

    def test_tile_coordinates_follow_web_mercator_axes(self):
        x, y = tile_pixels(np.array([0, 43]), np.array([0, 141]), 11)
        self.assertEqual(1024 * 256, x[0])
        self.assertEqual(1024 * 256, y[0])
        self.assertGreater(x[1], x[0])
        self.assertLess(y[1], y[0])

    def test_valley_needs_walls_and_an_open_axis_not_just_a_mountain(self):
        valley = terrain_features(100, [18, 12, 0, 12, 18, 12, 0, 12])
        self.assertGreater(valley['terrain_valley'], .9)
        self.assertEqual(0, terrain_features(100, [0] * 8)['terrain_valley'])
        self.assertEqual(0, terrain_features(100, [20] * 8)['terrain_valley'])
        self.assertEqual({}, terrain_features(100, [float('nan')] + [20] * 7))

    def test_unknown_or_blocking_horizon_never_gives_a_sun_bonus(self):
        weather = {'visibility': 20000, 'cloud_cover': 5, 'precipitation': 0}
        features = {'lake_bearing': 270, 'horizon_degrees': [15] * 8}
        self.assertFalse(terrain_sun_visible(features, 2, 270))
        self.assertEqual(1, sun_alignment_bonus(features, 'lake', 2, 270, weather))
        self.assertIsNone(terrain_sun_visible({}, 2, 270))
        self.assertEqual(1, sun_alignment_bonus({'lake_bearing': 270}, 'lake', 2, 270, weather))


class ContextWeatherTest(unittest.TestCase):
    def test_road_context_does_not_invent_grade_or_misclassify_poi(self):
        self.assertEqual(-.05, incline({'incline': '-5%'}))
        self.assertIsNone(incline({'incline': 'up'}))
        self.assertIsNone(incline({'incline': '200%'}))
        self.assertEqual('viewpoint', poi_kind({'tourism': 'viewpoint'}))
        self.assertEqual('rest_area', poi_kind({'highway': 'rest_area'}))
        self.assertIsNone(poi_kind({'amenity': 'private_house'}))

    def test_discrete_weather_codes_and_circular_wind_are_not_linearly_averaged(self):
        request = RecommendationRequest.parse(request_body())
        stamp = request.departure.timestamp()
        forecast = Forecast('test', request.departure.isoformat(), ({'coordinate': request.origin, 'records': [
            {'time': stamp, 'weather_code': 0, 'temperature_2m': -5, 'wind_direction_10m': 350},
            {'time': stamp + 3600, 'weather_code': 95, 'temperature_2m': -1, 'wind_direction_10m': 10}]} ,))
        weather = forecast.at(request.origin, request.departure + timedelta(minutes=30))
        self.assertEqual(95, weather['weather_code'])
        self.assertEqual(-3, weather['temperature_2m'])
        self.assertAlmostEqual(0, weather['wind_direction_10m'])
        self.assertNotIn('snow_depth', weather)

    def test_negative_temperatures_are_preserved_and_missing_snow_is_not_zero(self):
        request = RecommendationRequest.parse(request_body())
        stamp = request.departure.timestamp()
        def fetch(url, timeout):
            return {'latitude': 43, 'longitude': 141, 'hourly': {'time': [stamp, stamp + 3600],
                    'temperature_2m': [-5, -1], 'cloud_cover': [5, 5], 'precipitation': [0, 0]}}
        forecast = OpenMeteoForecasts(True, fetch).snapshot(request)
        self.assertEqual(-5, forecast.cells[0]['records'][0]['temperature_2m'])
        self.assertIsNone(forecast.cells[0]['records'][0]['snow_depth'])


class TerrainRoutingTest(unittest.TestCase):
    def setUp(self):
        self.fixture = test_scenic.ScenicTest()
        self.fixture.setUp()

    def tearDown(self):
        self.fixture.tearDown()

    def terrain(self, signature=None):
        path = Path(self.fixture.temp.name) / 'terrain.sqlite'
        with closing(sqlite3.connect(path)) as db, db:
            db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT);'
                             'CREATE TABLE road_terrain(road_id INTEGER PRIMARY KEY,payload TEXT,valley REAL);')
            db.executemany('INSERT INTO metadata VALUES (?,?)', [
                ('schema', '1'), ('version', 'test'), ('source_url', 'https://maps.gsi.go.jp/development/ichiran.html'),
                ('license', 'PDL-1.0'), ('catalog_signature', signature or identity(self.fixture.catalog.source))])
            db.executemany('INSERT INTO road_terrain VALUES (?,?,?)', [(row[0], json.dumps({
                'terrain_valley': .95, 'terrain_openness': .8, 'terrain_grade': .1,
                'elevation_m': 100, 'horizon_degrees': [0] * 8}), .95)
                for row in self.fixture.catalog.db.execute("SELECT id FROM road WHERE way='base'")])
        return path

    def test_derived_valleys_reach_candidates_without_osm_valley_registration(self):
        store = ScenicStore(self.fixture.catalog, terrain=self.terrain())
        request = RecommendationRequest.parse({**request_body(), 'preferences': {'valley': 1}})
        candidates, _ = store.candidates(request, Parameters(), 3600)
        self.assertTrue(candidates)
        self.assertTrue(any(c.category == 'valley' for c in candidates))
        road = store.match(self.fixture.origin, 90)[0]
        self.assertEqual(.95, road['qualities']['valley'])
        self.assertEqual(.1, road['features']['grade'])
        self.assertIn('gsi-terrain', road['source_ids'])

    def test_bridge_never_gets_ground_grade(self):
        store = ScenicStore(self.fixture.catalog, terrain=self.terrain())
        store.attribute_cache['base'] = {'bridge': True}
        self.assertIsNone(store.match(self.fixture.origin, 90)[0]['features']['grade'])

    def test_mismatched_dem_is_rejected(self):
        with self.assertRaises(ValueError):
            ScenicStore(self.fixture.catalog, terrain=self.terrain('wrong-map'))

    def test_inventory_never_claims_global_completion_from_loaded_files(self):
        store = ScenicStore(self.fixture.catalog)
        status = source_status(store, False)
        self.assertFalse(status['complete'])
        self.assertTrue(any(source['status'] == 'unconfigured' for source in status['sources']))
        self.assertTrue(any(gap['status'] == 'excluded_by_scope' for gap in status['gaps']))
        registry = next(source for source in status['sources'] if source['id'] == 'reviewed-scenic-roads')
        self.assertEqual(0, registry['active_count'])
        self.assertEqual(0, registry['pending_count'])
        self.assertEqual(0, sum(registry['rank_counts'].values()))

    def test_satellite_cover_fills_gaps_without_turning_water_or_buildings_into_beauty(self):
        path = Path(self.fixture.temp.name) / 'cover.sqlite'
        with closing(sqlite3.connect(path)) as db, db:
            db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT);'
                             'CREATE TABLE land_cover(road_id INTEGER PRIMARY KEY,payload TEXT,forest REAL,open_nature REAL,farmland REAL);')
            db.executemany('INSERT INTO metadata VALUES (?,?)', [
                ('schema', '1'), ('version', '2021-test'), ('source_url', 'https://esa-worldcover.org/en/data-access'),
                ('license', 'CC-BY-4.0'), ('observed_at', '2021'),
                ('catalog_signature', identity(self.fixture.catalog.source))])
            db.executemany('INSERT INTO land_cover VALUES (?,?,?,?,?)', [(row[0], json.dumps({
                'forest': 1., 'open_nature': 0., 'farmland': 0., 'built_up': 1., 'surface_water': 1.}), 1., 0., 0.)
                for row in self.fixture.catalog.db.execute("SELECT id FROM road WHERE way='base'")])
        store = ScenicStore(self.fixture.catalog, landcover=path)
        road = store.match(self.fixture.origin, 90)[0]
        self.assertEqual(.75, road['qualities']['forest'])
        self.assertEqual(0, road['qualities']['sea'])
        self.assertIsNone(road['qualities']['inland_water'])
        self.assertIsNone(road['qualities']['townscape'])
        self.assertEqual(0, road['qualities']['urban'])
        self.assertEqual('2021', next(source['observed_at'] for source in store.sources if source['source_id'] == 'esa-worldcover'))
        request = RecommendationRequest.parse({**request_body(), 'preferences': {'forest': 1}})
        self.assertTrue(store.candidates(request, Parameters(), 3600)[0])
