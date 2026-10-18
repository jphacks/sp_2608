import json
from pathlib import Path
import sqlite3
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from recommended_roads import RANKS, RecommendedRoads
from road_selection import POLICY
from recommendation.evaluator import RouteEvaluator
from recommendation.environment import Forecast
from recommendation.portfolio import dominates
from recommendation.models import Parameters, RecommendationRequest
from recommendation.store import ScenicStore
from test_recommendation import request_body
import test_scenic
from test_scenic import response


class RegistryMarkdownTest(unittest.TestCase):
    def report(self, scope_note, section_reason, cautions):
        from export_road_registry import markdown
        metadata = dict(selection_policy='review-v2', geometry_policy='connected-v1',
                        version='test', map_version='test', built_at='2026-10-18')
        record = dict(id='test-road', name='試験道路', rank='scenic', status='active',
                      geometry_summary=dict(matched_meters=1000, component_count=1),
                      extent=dict(description='川沿いの区間'), scope_note=scope_note,
                      research_review=dict(method='source-research-v1', checked_on='2026-10-18',
                                           selection_reason='自治体の道路紹介', section_reason=section_reason,
                                           duplicate_check='重複なし', cautions=cautions, findings=[]))
        with patch('export_road_registry.read_registry', return_value=(metadata, {}, [record], {})):
            return markdown('unused.sqlite')

    def test_repeated_descriptions_are_printed_once(self):
        report = self.report('川沿いの区間', '川沿いの区間', '川沿いの区間')
        self.assertEqual(1, report.count('川沿いの区間'))
        self.assertIn('- 対象範囲: 川沿いの区間', report)
        self.assertNotIn('- 注意:', report)

    def test_distinct_scope_and_review_notes_are_preserved(self):
        report = self.report('冬季閉鎖あり', '橋から温泉まで', '落石規制を確認')
        for note in ('対象範囲: 川沿いの区間', '注意: 冬季閉鎖あり',
                     '区間の決め方: 橋から温泉まで', '調査時の注意: 落石規制を確認'):
            self.assertIn(note, report)


class RecommendedRoadsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = test_scenic.ScenicTest()
        self.fixture.setUp()
        self.store = ScenicStore(self.fixture.catalog)
        self.request = RecommendationRequest.parse(request_body())
        self.evaluator = RouteEvaluator(self.store, Parameters(), Forecast('disabled', '2026-10-17T00:00:00Z'))
        self.points = [self.fixture.coast_start, self.fixture.coast_end]

    def tearDown(self):
        self.fixture.tearDown()

    def add_recommendations(self, ranks):
        road_ids = [row['id'] for row in self.fixture.catalog.db.execute("SELECT id FROM road WHERE way='coast'")]
        self.store.cache.clear()
        self.store.match_cache.clear()
        self.store.recommendations.by_segment = {ident: [
            {'id': f'test-{rank}', 'name': '試験用の道', 'rank': rank, 'source_ids': ['osm'], 'scope_note': '試験データ'}
            for rank in ranks] for ident in road_ids}

    def evaluate(self, points=None):
        points = points or self.points
        return self.evaluator.evaluate(self.request, response(points, 100), points, (), 100)

    def test_ranks_are_weighted_and_overlap_is_not_double_counted(self):
        values = []
        for rank in RANKS:
            self.add_recommendations([rank])
            result = self.evaluate()
            values.append(result['components']['R'])
            self.assertTrue(any(block['recommended_roads'] for block in result['timeline']))
        self.assertEqual(values, sorted(values, reverse=True))
        self.add_recommendations(['signature', 'panorama'])
        self.assertAlmostEqual(values[0], self.evaluate()['components']['R'])

    def test_return_trip_cannot_farm_recommendation_rewards(self):
        self.add_recommendations(['signature'])
        first = self.evaluate()['components']['R']
        repeated = self.evaluate(self.points + list(reversed(self.points)))['components']['R']
        self.assertLessEqual(repeated, first + .000001)

    def test_missing_registry_does_not_fabricate_recommendations(self):
        result = self.evaluate()
        self.assertEqual(0, result['components']['R'])
        self.assertFalse(any(block['recommended_roads'] for block in result['timeline']))

    def test_unregistered_roads_remain_landscape_search_candidates(self):
        self.assertFalse(self.store.recommendations.by_segment)
        candidates, _ = self.store.candidates(self.request, Parameters(max_arcs=10), 3600)
        self.assertTrue(candidates)
        self.assertTrue(any(candidate.landscape_value > 0 for candidate in candidates))
        self.assertEqual(0, self.evaluate()['components']['R'])

    def test_tunnels_do_not_earn_scenic_road_rewards_or_labels(self):
        self.add_recommendations(['panorama'])
        self.store.attribute_cache['coast'] = {'tunnel': True}
        result = self.evaluate()
        self.assertEqual(0, result['components']['R'])
        self.assertFalse(any(block['recommended_roads'] for block in result['timeline']))

    def test_candidate_index_is_temporary_on_read_only_catalog(self):
        self.add_recommendations(['signature'])
        candidates, _ = self.store.candidates(self.request, Parameters(max_arcs=10), 3600)
        self.assertTrue(candidates)
        self.assertEqual(10, self.fixture.catalog.db.execute('SELECT count(*) FROM temp.recommended_ids').fetchone()[0])

    def test_fastest_route_does_not_dominate_a_more_recommended_route(self):
        plain = self.evaluate()
        self.add_recommendations(['signature'])
        enriched = self.evaluate()
        enriched['timing']['total_seconds'] += 120
        self.assertFalse(dominates(SimpleNamespace(key='plain', evaluation=plain),
                                   SimpleNamespace(key='enriched', evaluation=enriched), self.request))

    def test_registry_rejects_a_different_osm_version(self):
        path = Path(self.fixture.temp.name) / 'registry.sqlite'
        with sqlite3.connect(path) as db:
            db.execute('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)')
            db.executemany('INSERT INTO metadata VALUES (?,?)', [('schema', '1'), ('map_version', 'old')])
        with self.assertRaisesRegex(ValueError, 'does not match'):
            RecommendedRoads(path, self.store.version)

    def test_import_keeps_unverified_official_sections_pending(self):
        try:
            import osmium
        except ImportError:
            self.skipTest('Optional OSM importer is not installed')
        from import_recommended_roads import build
        root = Path(self.fixture.temp.name)
        pbf, manifest, output = root / 'test.osm', root / 'manifest.json', root / 'recommended.sqlite'
        pbf.write_text('<osm version="0.6"><way id="201" version="1"><tag k="highway" v="primary"/><tag k="name" v="試験用の道"/><tag k="scenic" v="yes"/></way></osm>')
        model = root / 'scenicroad01_daytrip.kml'
        model.write_text('<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><LineString>'
                         '<coordinates>141,43 141.01,43</coordinates></LineString></Placemark></kml>')
        path = self.fixture.catalog.db.execute('PRAGMA database_list').fetchone()[2]
        with sqlite3.connect(path) as db:
            db.execute("UPDATE road SET way='201' WHERE way='coast'")
        source = {'source_id': 'osm', 'source_url': 'https://www.openstreetmap.org/copyright',
                  'publisher': 'test', 'license': 'ODbL-1.0', 'version': 'test', 'acquired_at': '2026-10-17T00:00:00Z'}
        official = {**source, 'source_id': 'official', 'publisher': '試験用観光協会',
                    'publisher_type': 'tourism_organization', 'subject': 'road_introduction'}
        manifest.write_text(json.dumps({'selection_policy': POLICY, 'sources': [source, official], 'roads': [
            {'id': 'selected', 'name': '試験用の道', 'rank': 'signature', 'designation': 'selected',
             'source_ids': ['osm', 'official'], 'road_introduction_source_ids': ['official'],
             'osm_names': ['試験用の道'], 'review_required': True, 'scope_note': '試験用保留'},
            {'id': 'recommended', 'name': '試験用の道', 'rank': 'recommended', 'source_ids': ['osm', 'official'],
             'road_introduction_source_ids': ['official'],
             'osm_names': ['試験用の道'], 'scope_note': '試験用確認済み'}]}))
        build(pbf, path, manifest, output, model_courses=model)
        loaded = RecommendedRoads(output, self.store.version)
        self.assertTrue(loaded.by_segment)
        self.assertEqual(1, loaded.active_count)
        self.assertEqual(1, loaded.pending_count)
        self.assertEqual({'signature': 0, 'panorama': 0, 'recommended': 1, 'scenic': 0}, loaded.rank_counts)
        self.store.recommendations = loaded
        from recommendation.data_status import source_status
        inventory = next(item for item in source_status(self.store, False)['sources']
                         if item['id'] == 'reviewed-scenic-roads')
        self.assertEqual(1, inventory['active_count'])
        self.assertEqual(1, inventory['pending_count'])
        self.assertEqual(loaded.rank_counts, inventory['rank_counts'])
        self.assertTrue(all(item['id'] == 'recommended' for items in loaded.by_segment.values() for item in items))
        with sqlite3.connect(output) as db:
            self.assertEqual({'selected', 'recommended'}, {row[0] for row in db.execute('SELECT id FROM recommendation')})
        from export_road_registry import markdown, geojson
        report = markdown(output)
        self.assertIn('保留（未登録）', report)
        self.assertIn('試験用観光協会', report)
        self.assertIn('形状部分 1', report)
        features = geojson(output)['features']
        self.assertEqual({'selected', 'recommended'}, {f['properties']['id'] for f in features})
        self.assertTrue(all(f['geometry']['type'] == 'MultiLineString' for f in features))
        with self.assertRaisesRegex(ValueError, 'existing data'):
            build(pbf, path, manifest, output)
        incomplete = root / 'incomplete.sqlite'
        with self.assertRaisesRegex(ValueError, 'Unresolved recommendation sections'):
            build(pbf, path, manifest, incomplete, require_complete=True)
        self.assertFalse(incomplete.exists())

    def test_registry_requires_new_publication_policy(self):
        path = Path(self.fixture.temp.name) / 'legacy.sqlite'
        with sqlite3.connect(path) as db:
            db.execute('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)')
            db.executemany('INSERT INTO metadata VALUES (?,?)', [('schema', '1'), ('map_version', self.store.version)])
        with self.assertRaisesRegex(ValueError, 'requires rebuilding'):
            RecommendedRoads(path, self.store.version)


if __name__ == '__main__':
    unittest.main()
