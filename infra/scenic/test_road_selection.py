import copy
import json
from pathlib import Path
import unittest

from road_selection import POLICY, selection_basis, validate_selection


class RoadSelectionTest(unittest.TestCase):
    def setUp(self):
        self.sources = {'official': {'publisher': '観光協会', 'kind': 'primary',
                                    'publisher_type': 'tourism_organization', 'subject': 'road_introduction',
                                    'source_url': 'https://example.org/road'},
                        'reviews': {'publisher': '口コミサイト', 'kind': 'reviews'},
                        'article': {'publisher': '編集部', 'kind': 'editorial'}}
        self.road = {'id': 'road', 'rank': 'panorama', 'source_ids': ['official', 'reviews'],
                     'road_introduction_source_ids': ['official'],
                     'review_observations': [{'source_id': 'reviews', 'subject': 'road',
                         'rating': 4.1, 'count': 56, 'checked_on': '2026-10-17'}]}

    def test_road_reviews_with_sufficient_support_qualify(self):
        self.assertTrue(selection_basis(self.road, self.sources)['panorama_eligible'])

    def test_three_five_star_reviews_do_not_qualify(self):
        self.road['review_observations'][0].update(rating=5, count=3)
        self.assertFalse(selection_basis(self.road, self.sources)['panorama_eligible'])

    def test_attraction_and_pass_reviews_do_not_rate_entire_roads(self):
        for subject in ('attraction', 'pass'):
            self.road['review_observations'][0].update(subject=subject, rating=5, count=1000)
            self.assertFalse(selection_basis(self.road, self.sources)['panorama_eligible'])

    def test_low_ratings_and_small_samples_do_not_qualify(self):
        for rating, count in ((3.8, 1000), (4.0, 20), (5.0, 19)):
            self.road['review_observations'][0].update(rating=rating, count=count)
            self.assertFalse(selection_basis(self.road, self.sources)['panorama_eligible'])

    def test_syndication_does_not_add_independent_support(self):
        self.road['review_observations'] = []
        self.road['source_ids'] = ['official', 'article']
        self.sources['article']['independence_group'] = '観光協会'
        self.assertFalse(selection_basis(self.road, self.sources)['panorama_eligible'])

    def test_editorial_and_primary_support_qualify_without_fake_reviews(self):
        self.road.pop('review_observations')
        self.road['source_ids'] = ['official', 'article']
        basis = selection_basis(self.road, self.sources)
        self.assertTrue(basis['panorama_eligible'])
        self.assertEqual([], basis['qualifying_review_publishers'])

    def test_invalid_or_unlinked_observations_are_rejected(self):
        for changes in ({'rating': float('nan')}, {'rating': 6}, {'count': -1},
                        {'count': True}, {'subject': 'hotel'}, {'source_id': 'official'}):
            road = copy.deepcopy(self.road)
            road['review_observations'][0].update(changes)
            with self.assertRaises(ValueError):
                selection_basis(road, self.sources)

    def test_publication_rejects_unsupported_policy_and_insufficient_support(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            validate_selection({'selection_policy': 'unknown', 'roads': []}, self.sources)
        self.road['source_ids'] = ['official']
        self.road['review_observations'] = []
        with self.assertRaisesRegex(ValueError, 'Insufficient'):
            validate_selection({'selection_policy': POLICY, 'roads': [self.road]}, self.sources)
        self.road['rank'] = 'recommended'
        self.road['source_ids'] = ['article']
        with self.assertRaisesRegex(ValueError, 'road introduction'):
            validate_selection({'selection_policy': POLICY, 'roads': [self.road]}, self.sources)

    def test_every_rank_requires_an_explicit_road_introduction(self):
        for rank in ('signature', 'panorama', 'recommended', 'scenic'):
            road = {**self.road, 'rank': rank, 'road_introduction_source_ids': []}
            with self.subTest(rank=rank), self.assertRaisesRegex(ValueError, 'road introduction'):
                validate_selection({'selection_policy': POLICY, 'roads': [road]}, self.sources)

    def test_scenic_road_with_official_introduction_is_allowed(self):
        road = {**self.road, 'rank': 'scenic'}
        validate_selection({'selection_policy': POLICY, 'roads': [road]}, self.sources)
        self.assertEqual(POLICY, road['selection_basis']['policy'])

    def test_model_course_osm_and_articles_are_not_registration_evidence(self):
        for changes in ({'subject': 'tourism_itinerary'}, {'publisher_type': 'community'},
                        {'kind': 'editorial'}, {'source_url': ''}):
            sources = copy.deepcopy(self.sources)
            sources['official'].update(changes)
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, 'road introduction'):
                validate_selection({'selection_policy': POLICY, 'roads': [self.road]}, sources)

    def test_legacy_or_missing_policy_is_not_silently_accepted(self):
        for manifest in ({'roads': [self.road]}, {'selection_policy': 'review-v1', 'roads': [self.road]}):
            with self.assertRaisesRegex(ValueError, 'Unsupported'):
                validate_selection(manifest, self.sources)

    def test_missing_introduction_is_retained_only_as_an_explicit_hold(self):
        road = {**self.road, 'road_introduction_source_ids': [],
                'registration_hold_reasons': ['道路紹介資料の確認待ち']}
        validate_selection({'selection_policy': POLICY, 'roads': [road]}, self.sources)
        self.assertNotIn('selection_basis', road)

    def test_background_primary_sources_do_not_inflate_panorama_evidence(self):
        self.road['review_observations'] = []
        self.sources['background'] = {'publisher': '別の自治体', 'kind': 'primary', 'subject': 'background'}
        self.road['source_ids'] = ['official', 'background']
        self.assertFalse(selection_basis(self.road, self.sources)['panorama_eligible'])

    def test_manifest_is_complete_and_policy_validated(self):
        manifest = json.loads((Path(__file__).parent / 'data/hokkaido-recommended-roads.json').read_text())
        validate_selection(manifest, {s['source_id']: s for s in manifest['sources']})
        roads = [r for r in manifest['roads'] if r['rank'] in ('panorama', 'recommended')]
        self.assertEqual(31, len(roads))
        self.assertEqual(15, sum(r['rank'] == 'panorama' for r in roads))
        self.assertTrue(all(r.get('osm_names') and r.get('bounds') and r.get('scope_note') for r in roads))

    def researched_road(self):
        return {**self.road, 'rank': 'scenic', 'reviewed_at': '2026-10-18',
                'research_review': {
                    'method': 'source-research-v1', 'checked_on': '2026-10-18',
                    'driving_evidence': 'described_road_landscape',
                    'selection_reason': '車道の沿道景観を紹介', 'section_reason': '起終点を区別',
                    'duplicate_check': '既存IDとの重複を確認', 'cautions': '規制は別途確認',
                    'findings': [{'source_id': 'official', 'locator': '道路紹介の段落', 'fact': '車窓の森'}]}}

    def test_second_research_batch_has_source_findings_and_preserves_official_designations(self):
        manifest = json.loads((Path(__file__).parent / 'data/hokkaido-recommended-roads.json').read_text())
        sources = {s['source_id']: s for s in manifest['sources']}
        roads = {r['id']: r for r in manifest['roads']}
        batch = {'nijukken-sakura-road', 'yushun-sakura-road', 'thoroughbred-ginza',
                 'biei-shirakaba-road', 'yakumo-panorama-road', 'tenma-road', 'sounkyo-gorge-road',
                 'aizankei-forest-road', 'tenninkyo-gorge-road', 'ginzendai-mountain-road',
                 'shiranuka-charo-road', 'akkeshi-kiritappu-coast', 'ororon'}
        for ident in batch:
            with self.subTest(road=ident):
                record = roads[ident]
                self.assertEqual('source-research-v1', record['research_review']['method'])
                validate_selection({'selection_policy': POLICY, 'roads': [record]}, sources)
                self.assertNotEqual('signature', record['rank'])
        self.assertEqual(15, sum(r['rank'] == 'signature' for r in roads.values()))
        self.assertTrue(roads['ginzendai-mountain-road']['registration_hold_reasons'])
        self.assertEqual(['106'], roads['ororon']['osm_refs'])

    def test_third_batch_limits_registration_to_the_introduced_road_sections(self):
        manifest = json.loads((Path(__file__).parent / 'data/hokkaido-recommended-roads.json').read_text())
        roads = {r['id']: r for r in manifest['roads']}
        sources = {s['source_id']: s for s in manifest['sources']}
        expected = {'hakodate-esan-coast': 'recommended', 'mori-yakumo-coast': 'recommended',
                    'kussharo-iou-road': 'panorama', 'naganuma-jukai-road': 'recommended',
                    'abashiri-tanno-hills': 'scenic', 'koshimizu-coastal-flowers': 'scenic'}
        for ident, rank in expected.items():
            with self.subTest(road=ident):
                self.assertEqual(rank, roads[ident]['rank'])
                validate_selection({'selection_policy': POLICY, 'roads': [roads[ident]]}, sources)
                self.assertGreater(roads[ident]['extent']['minimum_meters'], 0)
                self.assertGreater(roads[ident]['max_meters'], roads[ident]['extent']['minimum_meters'])
        self.assertNotIn('osm_refs', roads['hakodate-esan-coast'])
        self.assertLess(roads['kussharo-iou-road']['bounds'][2], 144.4544485)

    def test_new_reviews_require_traceable_source_findings(self):
        road = self.researched_road()
        manifest = {'selection_policy': POLICY, 'research_review_required_since': '2026-10-18', 'roads': [road]}
        validate_selection(manifest, self.sources)
        for field in ('selection_reason', 'section_reason', 'duplicate_check', 'cautions', 'findings'):
            invalid = copy.deepcopy(manifest)
            invalid['roads'][0]['research_review'].pop(field)
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_selection(invalid, self.sources)
        invalid = copy.deepcopy(manifest)
        invalid['roads'][0].pop('research_review')
        with self.assertRaisesRegex(ValueError, 'research review'):
            validate_selection(invalid, self.sources)

    def test_road_landscape_description_does_not_automatically_become_recommended(self):
        road = self.researched_road()
        road['rank'] = 'recommended'
        manifest = {'selection_policy': POLICY, 'roads': [road]}
        with self.assertRaisesRegex(ValueError, 'Driving recommendation'):
            validate_selection(manifest, self.sources)
        road['research_review']['driving_evidence'] = 'explicit_recommendation'
        validate_selection(manifest, self.sources)

    def test_research_cannot_cite_unlinked_sources_or_an_empty_locator(self):
        for updates in ({'source_id': 'article'}, {'locator': ''}, {'fact': ''}):
            road = self.researched_road()
            road['research_review']['findings'][0].update(updates)
            with self.subTest(updates=updates), self.assertRaisesRegex(ValueError, 'research finding'):
                validate_selection({'selection_policy': POLICY, 'roads': [road]}, self.sources)


if __name__ == '__main__':
    unittest.main()
