from dataclasses import replace
from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from geometry import distance
from recommendation.environment import Forecast, OpenMeteoForecasts, solar_position
from recommendation.evaluator import RouteEvaluator, integrated_max, geographic_category
from recommendation.evidence import EvidenceBuilder
from recommendation.jobs import JobStore, RecommendationService
from recommendation.models import Anchor, CATEGORIES, Infeasible, InputError, Opportunity, Parameters, RecommendationRequest, Facility
from recommendation.portfolio import select_portfolio, weighted_overlap
from recommendation.search import CandidatePlanner
from recommendation.store import ScenicStore
from recommendation.terrain import line_of_sight, reveal_events, curvature_profile
import test_scenic
from test_scenic import response


def request_body():
    return {'origin': {'lat': 43, 'lon': 141}, 'destination': {'lat': 43, 'lon': 141.01},
            'departure_time': '2026-10-17T12:00:00+09:00',
            'budget': {'max_extra_driving_seconds': 900, 'max_total_seconds': 3600},
            'preferences': {'sea': 1}, 'constraints': {'allow_unpaved': True}, 'seed': 42}


class ModelsTest(unittest.TestCase):
    def test_night_correction_defaults_on_and_round_trips_as_boolean(self):
        self.assertTrue(RecommendationRequest.parse(request_body()).night_correction)
        for enabled in (True, False):
            request = RecommendationRequest.parse({**request_body(), 'night_correction': enabled})
            self.assertEqual(enabled, request.night_correction)
            self.assertIs(enabled, request.payload()['night_correction'])
            self.assertEqual(request, RecommendationRequest.parse(request.payload()))
        for invalid in (0, 1, 'false', None):
            with self.subTest(invalid=invalid), self.assertRaises(InputError):
                RecommendationRequest.parse({**request_body(), 'night_correction': invalid})

    def test_geographic_category_slightly_prefers_close_sea_only(self):
        def road(sea, forest, tunnel=False):
            return {'qualities': {'sea': sea, 'forest': forest}, 'tunnel': tunnel}
        self.assertEqual('sea', geographic_category(road(.7, .8)))
        self.assertEqual('forest', geographic_category(road(.4, .45)))
        self.assertEqual('forest', geographic_category(road(.5, 1.)))
        self.assertEqual('unclassified', geographic_category(road(1., 0., True)))
        self.assertEqual('unknown', geographic_category(None))

    def test_six_hour_detour_budget_round_trips_without_total_limit(self):
        body = {**request_body(), 'budget': {'max_extra_driving_seconds': 21600}}
        request = RecommendationRequest.parse(body)
        self.assertEqual(21600, request.extra_seconds)
        self.assertIsNone(request.max_total_seconds)
        self.assertEqual(request, RecommendationRequest.parse(request.payload()))
        with self.assertRaises(InputError):
            RecommendationRequest.parse({**body, 'budget': {'max_extra_driving_seconds': 21601}})

    def test_point_to_point_can_omit_total_budget_and_round_trip_cannot(self):
        body = {**request_body(), 'budget': {'max_extra_driving_seconds': 900}}
        request = RecommendationRequest.parse(body)
        self.assertIsNone(request.max_total_seconds)
        self.assertNotIn('max_total_seconds', request.payload()['budget'])
        self.assertEqual(request, RecommendationRequest.parse(request.payload()))
        with self.assertRaisesRegex(InputError, 'round_trip_total_budget_required'):
            RecommendationRequest.parse({**body, 'mode': 'round_trip'})

    def test_round_trip_and_preference_normalization(self):
        body = request_body()
        body.update(mode='round_trip', preferences={'sea': .6, 'forest': .6})
        request = RecommendationRequest.parse(body)
        self.assertEqual(request.origin, request.destination)
        self.assertAlmostEqual(1, sum(request.preferences.weights))
        self.assertEqual(request, RecommendationRequest.parse(request.payload()))

    def test_rejects_invalid_timezone_budget_weights_and_history(self):
        for key, value in [('departure_time', '2026-10-17T12:00:00'), ('preferences', {'sea': -1}),
                           ('preferences', {'sea': float('nan')}), ('preferences', {'sea': 0}),
                           ('visited_physical_ids', ['a'])]:
            with self.subTest(key=key, value=value):
                with self.assertRaises(InputError):
                    RecommendationRequest.parse({**request_body(), key: value})
        with self.assertRaises(InputError):
            RecommendationRequest.parse({**request_body(), 'budget': {'max_total_seconds': -1}})

    def test_required_dwell_must_fit_total_budget(self):
        body = request_body()
        body['stops'] = {'required_ids': ['station'], 'default_dwell_seconds': 3600}
        with self.assertRaises(InputError):
            RecommendationRequest.parse(body)

    def test_independent_category_integral_uses_best_traversal(self):
        first = dict.fromkeys(CATEGORIES, .2)
        second = dict.fromkeys(CATEGORIES, .8)
        self.assertAlmostEqual(80, integrated_max([(0, 100, first), (0, 100, second)], 'sea'))
        self.assertAlmostEqual(50, integrated_max([(0, 100, first), (50, 100, second)], 'sea'))

    def test_overlap_is_length_weighted(self):
        self.assertAlmostEqual(1000 / 1002, weighted_overlap({'a': 1000, 'b': 1}, {'a': 1000, 'c': 1}))

    def test_solar_day_and_night_are_not_visibility_claims(self):
        noon = datetime.fromisoformat('2026-06-21T12:00:00+09:00')
        night = datetime.fromisoformat('2026-06-21T00:00:00+09:00')
        self.assertGreater(solar_position((43, 141), noon)[0], 50)
        self.assertLess(solar_position((43, 141), night)[0], 0)

    def test_missing_terrain_is_not_interpreted_as_visible(self):
        self.assertIsNone(line_of_sight((43, 141), (43, 141.01), lambda point: None))
        self.assertFalse(line_of_sight((43, 141), (43, 141.01), lambda point: 100 if point[1] > 141.001 else 5))
        self.assertTrue(line_of_sight((43, 141), (43, 141.01), lambda point: 0))

    def test_straight_road_curvature_does_not_depend_on_vertex_density(self):
        sparse = [(43, 141), (43, 141.02)]
        dense = [(43, 141 + i * .0002) for i in range(101)]
        first, second = curvature_profile(sparse), curvature_profile(dense)
        self.assertEqual(len(first), len(second))
        self.assertTrue(all(value is None or value < 1e-6 for value in first + second))

    def test_reveal_requires_real_visibility_and_sustained_blocks(self):
        forest = {'start_m': 0, 'end_m': 500, 'category': 'forest'}
        sea = {'start_m': 500, 'end_m': 1000, 'category': 'sea', 'visibility_verified': True,
               'openness': .8, 'source_ids': ['terrain-test'], 'physical_position': 'p1'}
        self.assertEqual(1, len(reveal_events([forest, sea])))
        self.assertEqual([], reveal_events([forest, {**sea, 'visibility_verified': False}]))
        self.assertEqual([], reveal_events([forest, {**sea, 'end_m': 600}]))

    def test_forecast_is_interpolated_and_spatially_bounded(self):
        instant = datetime.fromisoformat('2026-10-17T12:00:00+09:00')
        forecast = Forecast('test', instant.isoformat(), ({'coordinate': (43, 141), 'records': [
            {'time': instant.timestamp() - 1800, 'visibility': 1000, 'precipitation': 0},
            {'time': instant.timestamp() + 1800, 'visibility': 3000, 'precipitation': 2}]},))
        self.assertEqual(2000, forecast.at((43, 141), instant)['visibility'])
        self.assertIsNone(forecast.at((35, 139), instant))
        self.assertIsNone(forecast.at((43, 141), datetime.fromisoformat('2026-10-18T12:00:00+09:00')))

    def test_forecast_failure_falls_back_to_an_empty_snapshot(self):
        def offline(url, timeout):
            raise OSError()
        forecast = OpenMeteoForecasts(True, offline).snapshot(RecommendationRequest.parse(request_body()))
        self.assertEqual((), forecast.cells)


class RecommendationTest(unittest.TestCase):
    def test_night_correction_off_restores_sea_quality_not_geographic_distances(self):
        points = [self.fixture.coast_start, self.fixture.coast_end]
        night_request = replace(self.request, departure=self.request.departure.replace(hour=23))
        enabled = self.evaluate(points, request=night_request)
        disabled = self.evaluate(points, request=replace(night_request, night_correction=False))
        noon = self.evaluate(points)
        self.assertEqual(0, enabled['metrics']['effective_m']['sea'])
        self.assertGreater(disabled['metrics']['effective_m']['sea'], 0)
        self.assertEqual(noon['metrics']['effective_m']['sea'], disabled['metrics']['effective_m']['sea'])
        self.assertEqual(enabled['metrics']['sea_proximity_m'], disabled['metrics']['sea_proximity_m'])
        self.assertEqual([b['category'] for b in enabled['timeline']], [b['category'] for b in disabled['timeline']])
        self.assertEqual(enabled['violations'], disabled['violations'])

    def test_coastal_timeline_does_not_disappear_at_night(self):
        points = [self.fixture.coast_start, self.fixture.coast_end]
        noon = self.evaluate(points)
        night_request = replace(self.request, departure=self.request.departure.replace(hour=23))
        night = self.evaluate(points, request=night_request)
        self.assertTrue(any(block['category'] == 'sea' for block in noon['timeline']))
        self.assertEqual([b['category'] for b in noon['timeline']], [b['category'] for b in night['timeline']])
        self.assertGreater(noon['metrics']['effective_m']['sea'], night['metrics']['effective_m']['sea'])
        self.assertEqual(noon['metrics']['sea_proximity_m'], night['metrics']['sea_proximity_m'])

    def test_display_emphasis_does_not_change_route_scores(self):
        points = [self.fixture.coast_start, self.fixture.coast_end]
        original = self.evaluate(points)
        with patch('recommendation.evaluator.geographic_category', return_value='forest'):
            changed = self.evaluate(points)
        self.assertEqual(original['score'], changed['score'])
        self.assertEqual(original['metrics'], changed['metrics'])

    def setUp(self):
        self.fixture = test_scenic.ScenicTest()
        self.fixture.setUp()
        self.store = ScenicStore(self.fixture.catalog)
        self.request = RecommendationRequest.parse(request_body())
        self.parameters = Parameters(max_evaluations=25, max_seconds=5, max_arcs=10, beam_width=4, alns_iterations=25)
        self.forecast = Forecast('disabled', '2026-10-17T00:00:00Z')
        self.evaluator = RouteEvaluator(self.store, self.parameters, self.forecast)

    def tearDown(self):
        self.fixture.tearDown()

    def evaluate(self, points, seconds=100, visits=(), request=None):
        route = response(points, seconds)
        if visits:
            stop = visits[0].facility.entrance
            index = min(range(len(points)), key=lambda i: distance(points[i], stop))
            first = points[:index + 1]
            second = points[index:]
            if len(first) == 1:
                first = first * 2
            if len(second) == 1:
                second = second * 2
            route['trip']['legs'] = [response(first, seconds / 2)['trip']['legs'][0], response(second, seconds / 2)['trip']['legs'][0]]
        return self.evaluator.evaluate(request or self.request, route, points, visits, 100)

    def test_budget_and_unknown_surface_are_hard_constraints(self):
        result = self.evaluate([self.fixture.coast_start, self.fixture.coast_end], 1200)
        self.assertIn('driving_budget', result['violations'])
        strict = replace(self.request, constraints=replace(self.request.constraints, allow_unpaved=False, narrow_road_policy='require_verified_width'))
        result = self.evaluate([self.fixture.coast_start, self.fixture.coast_end], request=strict)
        self.assertIn('width_not_verified', result['violations'])
        self.assertIn('unpaved_or_unverified_surface', result['violations'])

    def test_long_trip_uses_baseline_plus_detour_without_hidden_total_cap(self):
        points = [self.fixture.coast_start, self.fixture.coast_end]
        request = replace(self.request, max_total_seconds=None, extra_seconds=900)
        for seconds, exceeded in [(11339, False), (12239, False), (12240, True)]:
            with self.subTest(seconds=seconds):
                result = self.evaluator.evaluate(request, response(points, seconds), points, (), 11339)
                self.assertEqual(exceeded, 'driving_budget' in result['violations'])
                self.assertNotIn('total_budget', result['violations'])
        bounded = replace(request, max_total_seconds=7200)
        result = self.evaluator.evaluate(bounded, response(points, 11339), points, (), 11339)
        self.assertIn('total_budget', result['violations'])

    def test_planner_scans_using_long_baseline_plus_detour(self):
        request = replace(self.request, max_total_seconds=None)
        adapter = Mock(initial_alternates=[])
        adapter.cache_key.return_value = 'baseline'
        adapter.calculate.return_value = response([self.fixture.origin, self.fixture.end], 11339)
        planner = CandidatePlanner(self.store, adapter, self.forecast, self.parameters)
        with patch.object(self.store, 'candidates', return_value=([], False)) as candidates:
            baseline, selected, _ = planner.plan(request)
        self.assertIsNotNone(baseline)
        self.assertTrue(selected)
        self.assertEqual(12239, candidates.call_args.args[2])

    def test_slow_baseline_does_not_consume_the_candidate_scan_phase(self):
        clock = [0.]
        adapter = Mock(initial_alternates=[])
        adapter.cache_key.return_value = 'baseline'
        def calculate(*args):
            clock[0] = 4.
            return response([self.fixture.origin, self.fixture.end], 100)
        adapter.calculate.side_effect = calculate
        planner = CandidatePlanner(self.store, adapter, self.forecast, self.parameters, now=lambda: clock[0])
        with patch.object(self.store, 'candidates', return_value=([], True)) as candidates:
            planner.plan(self.request)
        self.assertGreater(candidates.call_args.args[4], clock[0])
        self.assertLessEqual(candidates.call_args.args[4], self.parameters.max_seconds)
        self.assertEqual(4, planner.budget.phase_seconds['baseline'])
        self.assertTrue(candidates.call_args.kwargs['reference_points'])

    def test_replan_keeps_remaining_driving_budget_without_total_cap(self):
        request = replace(self.request, max_total_seconds=None)
        service = RecommendationService.__new__(RecommendationService)
        service.jobs = Mock()
        service.jobs.session.return_value = ({'request_id': 'job', 'elapsed_seconds': 0,
                                             'visited_physical_ids': [], 'route_id': 'route'}, 0)
        service.jobs.clock.return_value = 600
        service.jobs.request.return_value = request.payload()
        service.jobs.snapshot.return_value = {'map_version': 'map'}
        service.jobs.get.return_value = {'baseline': {'driving_seconds': 11339}}
        service.catalog_path = 'unused'
        service.submit = Mock(return_value={'request_id': 'updated'})
        with patch('recommendation.jobs.Catalog') as catalog:
            catalog.return_value.source = {'map_version': 'map'}
            service.replan('session', {'origin': {'lat': 43, 'lon': 141}, 'elapsed_seconds': 600,
                                      'elapsed_driving_seconds': 500, 'history_enabled': True,
                                      'visited_physical_ids': []})
        updated = service.submit.call_args.args[0]
        self.assertIsNone(updated.max_total_seconds)
        self.assertEqual(11739, updated.max_driving_seconds)
        service.jobs.snapshot.return_value = {'map_version': 'map', 'routing_artifacts': {'routing.lua': 'old'}}
        service.routing_artifacts = Mock(return_value={'routing.lua': 'new'})
        service.submit.reset_mock()
        with patch('recommendation.jobs.Catalog') as catalog:
            catalog.return_value.source = {'map_version': 'map'}
            with self.assertRaisesRegex(Infeasible, 'routing_version_changed'):
                service.replan('session', {})
        service.submit.assert_not_called()

    def test_candidate_scan_uses_spatial_index_and_preserves_feature_overrides(self):
        statements = []
        self.store.catalog.db.set_trace_callback(statements.append)
        try:
            candidates, truncated = self.store.candidates(self.request, self.parameters, 3600)
            self.assertTrue(candidates)
            self.assertFalse(truncated)
            self.assertEqual(10, self.store.scan_stats['road_samples'])
            self.assertTrue(any('road_bounds' in s and 'r.sea>=0.45' in s for s in statements))
            statements.clear()
            self.store.feature_overrides['supplemented-road'] = {'sea': 1.}
            self.store.candidates(self.request, self.parameters, 3600)
            self.assertTrue(any('road_bounds' in s for s in statements))
            self.assertFalse(any('r.sea>=0.45' in s for s in statements))
        finally:
            self.store.catalog.db.set_trace_callback(None)

    def test_expired_scan_reports_truncation_without_scanning_roads(self):
        candidates, truncated = self.store.candidates(self.request, self.parameters, 3600,
                                                     now=lambda: 5., deadline=5.)
        self.assertEqual([], candidates)
        self.assertTrue(truncated)
        self.assertEqual(0, self.store.scan_stats['tiles'])
        self.assertEqual(0, self.store.scan_stats['road_samples'])

    def test_repetition_does_not_double_effective_length(self):
        points = [(43.003, 141 + i * .001) for i in range(11)]
        one = self.evaluate(points)
        twice = self.evaluate(points + list(reversed(points[:-1])), 200)
        self.assertAlmostEqual(one['metrics']['effective_m']['sea'], twice['metrics']['effective_m']['sea'], delta=2)
        self.assertLess(twice['score'], one['score'])

    def test_history_disables_incremental_reward(self):
        points = [(43.003, 141 + i * .001) for i in range(11)]
        one = self.evaluate(points)
        with_history = replace(self.request, visited=frozenset(one['physical_lengths']), history_enabled=True)
        result = self.evaluate(points, request=with_history)
        self.assertEqual(0, result['metrics']['effective_m']['sea'])

    def test_waiting_or_slow_driving_does_not_increase_distance_reward(self):
        points = [(43.003, 141 + i * .001) for i in range(11)]
        one, slow = self.evaluate(points, 100), self.evaluate(points, 300)
        self.assertAlmostEqual(one['metrics']['effective_m']['sea'], slow['metrics']['effective_m']['sea'], delta=2)
        self.assertLess(slow['score'], one['score'])

    def test_required_stop_hours_and_arrival_are_verified(self):
        facility = Facility('station', '試験用施設', self.fixture.coast_end, frozenset({'toilet'}),
            ((datetime.fromisoformat('2026-10-17T11:00:00+09:00'), datetime.fromisoformat('2026-10-17T14:00:00+09:00')),), 'osm')
        visit = Opportunity('stop:station', (Anchor(facility.entrance, 'break', facility_id=facility.id),), facility=facility)
        request = replace(self.request, stops=replace(self.request.stops, desired_count=1, required_ids=('station',)))
        result = self.evaluate([self.fixture.coast_start, self.fixture.coast_end], visits=(visit,), request=request)
        self.assertEqual([], result['violations'])
        self.assertEqual(600, result['timing']['stop_seconds'])
        unknown = replace(visit, facility=replace(facility, windows=()))
        self.assertIn('required_stop_hours_unverified', self.evaluate([self.fixture.coast_start, self.fixture.coast_end], visits=(unknown,), request=request)['violations'])
        inaccessible = replace(visit, facility=replace(facility, entrance=(43.1, 141)))
        self.assertIn('stop_entrance_not_reached', self.evaluate([self.fixture.coast_start, self.fixture.coast_end], visits=(inaccessible,), request=request)['violations'])

    def test_tunnel_does_not_receive_scenic_rewards(self):
        original = self.store.match
        def tunnel(point, heading):
            match = original(point, heading)
            if match:
                road, reverse = match
                return {**road, 'qualities': dict.fromkeys(CATEGORIES, 0.), 'tunnel': True}, reverse
            return None
        self.store.match = tunnel
        result = self.evaluate([self.fixture.coast_start, self.fixture.coast_end])
        self.assertEqual(0, result['metrics']['effective_m']['sea'])
        self.assertEqual(0, result['metrics']['sea_proximity_m'])

    def test_mandatory_stop_cannot_be_inferred_from_passing_nearby(self):
        facility = Facility('station', '試験用施設', self.fixture.coast_end)
        visit = Opportunity('stop:station', (Anchor(facility.entrance, 'break', facility_id=facility.id),), facility=facility)
        points = [self.fixture.coast_start, self.fixture.coast_end]
        evaluation = self.evaluator.evaluate(self.request, response(points, 100), points, (visit,), 100)
        self.assertIn('stop_legs_unverified', evaluation['violations'])

    def test_stop_clock_uses_the_break_leg_not_an_earlier_pass(self):
        facility = Facility('station', '試験用施設', self.fixture.coast_end)
        visit = Opportunity('stop:station', (Anchor(facility.entrance, 'break', facility_id=facility.id),), facility=facility)
        points = [self.fixture.coast_start, self.fixture.coast_end, self.fixture.coast_start, self.fixture.coast_end, self.fixture.end]
        route = response(points, 100)
        route['trip']['legs'] = [response(points[:4], 80)['trip']['legs'][0], response(points[3:], 20)['trip']['legs'][0]]
        result = self.evaluator.evaluate(self.request, route, points, (visit,), 100)
        self.assertEqual(3, result['schedule'][0]['geometry_index'])
        arrival = datetime.fromisoformat(result['schedule'][0]['arrival_time'])
        self.assertGreater((arrival - self.request.departure).total_seconds(), 50)

    def test_parameters_and_unrelated_candidates_do_not_change_existing_score(self):
        points = [self.fixture.coast_start, self.fixture.coast_end]
        before = self.evaluate(points)
        other = self.evaluate([self.fixture.origin, self.fixture.end])
        after = self.evaluate(points, request=replace(self.request, extra_seconds=1800))
        self.assertAlmostEqual(before['score'], after['score'])

    def test_search_and_evidence_are_bound_to_actual_shape(self):
        class Adapter:
            def cache_key(_, request, anchors):
                return str([(a.coordinate, a.kind) for a in anchors])
            def calculate(_, request, anchors, timeout):
                points = []
                for a, b in zip(anchors, anchors[1:]):
                    count = max(1, int(distance(a.coordinate, b.coordinate) / 30))
                    points.extend((a.coordinate[0] + (b.coordinate[0] - a.coordinate[0]) * i / count,
                                   a.coordinate[1] + (b.coordinate[1] - a.coordinate[1]) * i / count) for i in range(count))
                points.append(anchors[-1].coordinate)
                return response(points, sum(distance(a, b) for a, b in zip(points, points[1:])) / 10)
        planner = CandidatePlanner(self.store, Adapter(), self.forecast, self.parameters)
        baseline, selected, comparisons = planner.plan(self.request)
        self.assertLessEqual(planner.budget.calls, 25)
        self.assertTrue(selected)
        presented = EvidenceBuilder(self.store, 'snapshot-test').response(planner, self.request, selected, baseline, comparisons)
        for route in presented['routes']:
            self.assertLessEqual(route['timing']['total_seconds'], self.request.max_total_seconds)
            ids = {e['evidence_id'] for e in route['evidence']}
            for reason in route['reasons']:
                self.assertTrue(reason['evidence_ids'])
                self.assertLessEqual(set(reason['evidence_ids']), ids)
                for span in reason['geometry_ranges']:
                    self.assertGreaterEqual(span['start_m'], 0)
                    self.assertLessEqual(span['end_m'], route['distance_m'] + 1)
        json.dumps(presented, allow_nan=False)


class JobStoreTest(unittest.TestCase):
    def test_immutable_routes_expiry_and_deletion(self):
        with tempfile.TemporaryDirectory() as directory:
            clock = [100]
            store = JobStore(Path(directory) / 'jobs.sqlite', ttl=10, clock=lambda: clock[0])
            job = store.create(request_body())
            route = {'route_id': 'route_a', 'revision': 1, 'snapshot_id': 'snapshot_a'}
            store.publish(job, {'status': 'complete', 'routes': [route]})
            session = store.start_navigation('route_a', 1)
            self.assertEqual('route_a', session['route_id'])
            with self.assertRaises(ValueError):
                store.publish(job, {'routes': [{**route, 'snapshot_id': 'changed'}]})
            clock[0] = 111
            self.assertIsNone(store.get(job))
            self.assertIsNone(store.route('route_a'))
            store.delete(job)
            self.assertIsNone(store.session(session['session_id']))


if __name__ == '__main__':
    unittest.main()
