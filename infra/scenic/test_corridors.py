from copy import deepcopy
from dataclasses import replace
from types import SimpleNamespace
import unittest

from recommendation.corridors import SpatialOpportunities, corridor_seeds
from recommendation.experience import scenery_components
from recommendation.models import Anchor, CATEGORIES, Opportunity, Parameters
from recommendation.portfolio import select_portfolio
from test_recommendation import request_body, RecommendationRequest


class CorridorsTest(unittest.TestCase):
    def setUp(self):
        self.request = replace(RecommendationRequest.parse(request_body()), origin=(43., 141.),
                               destination=(41.8, 140.7), max_total_seconds=None, extra_seconds=5400)

    def road(self, ident, point, value=1000):
        return Opportunity(ident, (Anchor(point), Anchor((point[0] - .005, point[1]))),
                           physical=((ident, 500),), estimated_value=value)

    def test_nearby_high_scores_cannot_fill_all_regional_slots(self):
        pool = SpatialOpportunities(self.request)
        for i in range(100):
            pool.add(10000 - i, self.road(str(i), (42.9 - i * .001, 140.9)))
        coast = self.road('coast', (42.7, 140.1))
        pool.add(100, coast)
        selected = pool.select(10)
        self.assertIn(coast, selected)
        self.assertEqual(len(selected), len({v.id for v in selected}))
        self.assertLessEqual(len(selected), 10)

    def test_corridor_gates_are_derived_from_roads_and_do_not_claim_arc_coverage(self):
        roads = [self.road(str(i), (43 - i * .2, 140.3 + (i % 2) * .05)) for i in range(6)]
        seeds = corridor_seeds(roads, self.request)
        self.assertTrue(any(len(seed[0].anchors) > 1 for seed in seeds))
        source_points = {v.anchors[len(v.anchors) // 2].coordinate for v in roads}
        self.assertLessEqual(len(seeds), 24)
        for (gate,) in seeds:
            self.assertFalse(gate.physical)
            self.assertTrue(all(a.coordinate in source_points and a.kind == 'through' for a in gate.anchors))
        self.assertEqual(seeds, corridor_seeds(list(reversed(roads)), self.request))
        self.assertEqual([], corridor_seeds([], self.request))

    def test_long_trip_still_values_longer_seaside_experience(self):
        p = Parameters()
        continuous = dict.fromkeys(CATEGORIES, ())
        short = dict.fromkeys(CATEGORIES, 0.)
        short['sea'] = 20000
        long = {**short, 'sea': 50000}
        first, _, scales = scenery_components(self.request, p, short, continuous, 250000, 11626)
        second, _, _ = scenery_components(self.request, p, long, continuous, 320000, 11626)
        self.assertGreater(scales[0], 30000)
        self.assertGreater(second - first, .15)

    def candidate(self, key, seconds, sea, score, road, recommendation=0):
        metrics = {'load': .2, 'recommendation_value': recommendation,
                   'effective_m': {k: sea if k == 'sea' else 0 for k in CATEGORIES},
                   'continuous': {k: [(0, sea / 2)] if k == 'sea' else [] for k in CATEGORIES},
                   'unique_driven_m': 250000, 'total_m': 250000, 'repeated_m': 0}
        return SimpleNamespace(key=key, score=score, portfolio_role='', evaluation={
            'violations': [], 'timing': {'total_seconds': seconds}, 'metrics': metrics,
            'physical_lengths': {road: 200000}})

    def test_seaside_role_survives_old_score_cutoff_and_selection_is_immutable(self):
        fast = self.candidate('fast', 11000, 20000, .8, 'a', 1)
        balanced = self.candidate('balanced', 12000, 30000, .9, 'b', .7)
        coast = self.candidate('coast', 15000, 70000, .5, 'c', .1)
        originals = [deepcopy(c.evaluation) for c in (fast, balanced, coast)]
        stats = {}
        selected = select_portfolio([fast, balanced, coast], self.request, Parameters(), stats)
        self.assertEqual(['coast', 'balanced', 'fast'], [c.key for c in selected])
        self.assertEqual(['海沿いを満喫', '景観と時間のバランス', '寄り道ひかえめ'], [c.portfolio_role for c in selected])
        self.assertEqual(originals, [c.evaluation for c in (fast, balanced, coast)])
        self.assertEqual(3, stats['selected'])
        self.assertEqual(1, len(select_portfolio([fast, balanced, coast], replace(self.request, max_routes=1), Parameters())))

    def test_dominated_invalid_and_nearly_identical_routes_are_not_padded(self):
        fast = self.candidate('fast', 11000, 20000, .8, 'a', 1)
        worse = self.candidate('worse', 12000, 10000, .5, 'b', .5)
        invalid = self.candidate('invalid', 10000, 100000, 1., 'c', 1)
        invalid.evaluation['violations'] = ['driving_budget']
        duplicate = self.candidate('duplicate', 11000, 20000, .8, 'a', 1)
        stats = {}
        selected = select_portfolio([fast, worse, invalid, duplicate], self.request, Parameters(), stats)
        self.assertEqual(1, len(selected))
        self.assertEqual(1, stats['invalid'])
        self.assertEqual(1, stats['dominated'])
        self.assertEqual(1, stats['similar'])

    def test_slower_remaining_route_is_not_mislabeled_as_the_time_saving_choice(self):
        fast = self.candidate('fast', 11000, 20000, .9, 'a', 1)
        coast = self.candidate('coast', 15000, 70000, .5, 'b', .1)
        other = self.candidate('other', 13000, 30000, .7, 'c', .5)
        selected = select_portfolio([fast, coast, other], self.request, Parameters())
        self.assertEqual(3, len(selected))
        self.assertEqual('別の景観を楽しむ', other.portfolio_role)

    def test_six_distinct_routes_are_allowed_but_duplicates_are_not_padded(self):
        request = replace(self.request, max_routes=6)
        routes = [self.candidate(str(i), 11000 + i * 1000, 20000 + i * 5000, .8, str(i), 1)
                  for i in range(6)]
        self.assertEqual(6, len(select_portfolio(routes, request, Parameters())))
        body = {**request_body(), 'max_routes': 6}
        self.assertEqual(6, RecommendationRequest.parse(body).max_routes)
        self.assertEqual(3, RecommendationRequest.parse(request_body()).max_routes)
        with self.assertRaises(ValueError):
            RecommendationRequest.parse({**body, 'max_routes': 7})


if __name__ == '__main__':
    unittest.main()
