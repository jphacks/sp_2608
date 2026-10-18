from collections import Counter
from dataclasses import replace
import math
import random
import time

from geometry import distance, route_points
from recommendation.evaluator import RouteEvaluator
from recommendation.models import Anchor, Candidate, CATEGORIES, Infeasible, identity
from recommendation.portfolio import select_portfolio, weighted_overlap
from recommendation.store import bearing
from recommendation.corridors import corridor_seeds


class SearchBudget:
    def __init__(self, parameters, now=time.monotonic):
        self.parameters, self.now = parameters, now
        self.started = now()
        self.deadline = self.started + parameters.max_seconds
        self.calls = 0
        self.cache_hits = 0
        self.evaluations = 0
        self.failures = Counter()
        self.operators = Counter()
        self.candidate_scan_truncated = False
        self.terminated_early = False
        self.cancelled = lambda: False
        self.phase_seconds = {}
        self.candidate_count = 0
        self.scan_stats = {}
        self.corridor_stats = {}
        self.portfolio_stats = {}

    def available(self):
        return not self.cancelled() and max(self.calls, self.evaluations) < self.parameters.max_evaluations and self.now() < self.deadline

    def status(self):
        return {'calls': self.calls, 'cache_hits': self.cache_hits,
                'evaluations': self.evaluations,
                'elapsed_seconds': self.now() - self.started, 'evaluation_limit': self.parameters.max_evaluations,
                'deadline_seconds': self.parameters.max_seconds, 'limited': not self.available() or self.candidate_scan_truncated or self.terminated_early,
                'candidate_scan_truncated': self.candidate_scan_truncated,
                'failures': dict(self.failures), 'operators': dict(self.operators),
                'phase_seconds': dict(self.phase_seconds), 'candidate_count': self.candidate_count,
                'candidate_scan': dict(self.scan_stats), 'corridors': dict(self.corridor_stats),
                'portfolio': dict(self.portfolio_stats)}


class CandidatePlanner:
    def __init__(self, store, adapter, forecast, parameters, now=time.monotonic):
        self.store, self.adapter, self.forecast, self.parameters = store, adapter, forecast, parameters
        self.evaluator = RouteEvaluator(store, parameters, forecast)
        self.budget = SearchBudget(parameters, now)
        self.cache, self.archive = {}, {}
        self.baseline_seconds = 0.
        self.reference = None
        self.snapshot_id = ''

    def evaluate(self, request, visits, repair=True):
        anchors = (Anchor(request.origin, 'break'),) + tuple(a for v in visits for a in v.anchors) + (Anchor(request.destination, 'break'),)
        if len(anchors) > 126:
            self.budget.failures['anchor_limit'] += 1
            return None
        key = self.adapter.cache_key(request, anchors)
        if key in self.cache:
            self.budget.cache_hits += 1
            return self.cache[key]
        if not self.budget.available():
            return None
        self.budget.calls += 1
        try:
            route = self.adapter.calculate(request, anchors, min(8, max(.1, self.budget.deadline - self.budget.now())))
            self.budget.evaluations += 1
            points = route_points(route)
            if len(points) > 60000 or route['trip']['summary']['length'] > 1000:
                raise Infeasible('route_size_limit')
            if not self.baseline_seconds and request.mode != 'round_trip':
                self.baseline_seconds = float(route['trip']['summary']['time'])
            evaluation = self.evaluator.evaluate(request, route, points, visits, self.baseline_seconds)
            candidate = Candidate(key, tuple(visits), anchors, route, points, evaluation)
            if not visits and request.mode != 'round_trip':
                self.reference = candidate
            missing = []
            for visit in visits:
                if not visit.physical:
                    continue
                target_length = sum(length for _, length in visit.physical)
                directed = {s['physical_id'] for s in evaluation['directed_segments'] if s['direction'] == visit.direction}
                covered = sum(min(length, evaluation['physical_lengths'].get(p, 0)) for p, length in visit.physical if p in directed)
                if covered / max(target_length, 1) < self.parameters.coverage_threshold:
                    missing.append(visit)
            if missing and repair:
                for attempt in range(self.parameters.repair_attempts):
                    refined = tuple(replace(v, anchors=tuple(dict.fromkeys((*v.anchors, *v.repair_anchors))))
                                    if v in missing else v for v in visits)
                    # Anchors must follow the target road, not the order in which missing anchors were discovered.
                    refined = tuple(replace(v, anchors=self.order_anchors(v)) if v in missing else v for v in refined)
                    result = self.evaluate(request, refined, repair=False)
                    if result:
                        self.cache[key] = result
                        return result
                    visits = refined
            if missing:
                evaluation['violations'].append('arc_coverage')
            if evaluation['violations']:
                self.budget.failures.update(evaluation['violations'])
                self.cache[key] = None
                return None
            candidate.route_id = 'route_' + identity([self.snapshot_id, request.payload(), key, self.store.version, self.parameters.version, self.forecast.source_id,
                                                      evaluation['timing'], route['trip']['legs']])
            self.cache[key] = candidate
            self.archive[key] = candidate
            return candidate
        except Infeasible as error:
            self.budget.failures[error.code] += 1
        except (OSError, ValueError, KeyError, TypeError, IndexError):
            self.budget.failures['routing_or_evaluation_failed'] += 1
        self.cache[key] = None
        return None

    @staticmethod
    def order_anchors(visit):
        if not visit.repair_anchors:
            return visit.anchors
        points = [visit.anchors[0].coordinate] + [a.coordinate for a in visit.repair_anchors] + [visit.anchors[-1].coordinate]
        cumulative = [0.]
        for a, b in zip(points, points[1:]):
            cumulative.append(cumulative[-1] + distance(a, b))
        def position(anchor):
            from geometry import segment_distance
            index = min(range(len(points) - 1), key=lambda i: segment_distance(anchor.coordinate, points[i], points[i + 1]))
            fraction = RouteEvaluator.project(anchor.coordinate, points[index], points[index + 1])
            return cumulative[index] + fraction * (cumulative[index + 1] - cumulative[index])
        return tuple(sorted(visit.anchors, key=position))

    @staticmethod
    def reversed(visit):
        if visit.facility:
            return visit
        anchors = []
        for index, anchor in enumerate(reversed(visit.anchors)):
            others = list(reversed(visit.anchors))
            heading = bearing(anchor.coordinate, others[index + 1].coordinate) if index + 1 < len(others) else None
            anchors.append(replace(anchor, heading=heading))
        return replace(visit, id=visit.id.removesuffix(':reverse') + ':reverse', anchors=tuple(anchors),
                       repair_anchors=tuple(reversed(visit.repair_anchors)), direction=-visit.direction)

    def plan(self, request, publish=None):
        rng = random.Random(request.seed)
        phase_started = self.budget.now()
        baseline = self.evaluate(request, ()) if request.mode != 'round_trip' else None
        # Required stops may make the baseline infeasible; its travel time remains the reference.
        if request.mode != 'round_trip' and not self.baseline_seconds:
            raise Infeasible('baseline_unavailable')
        if publish and self.archive:
            publish(self, baseline, [])
        for route in getattr(self.adapter, 'initial_alternates', []):
            if not self.budget.available():
                break
            try:
                self.adapter.verify(route, request)
                self.budget.evaluations += 1
                points = route_points(route)
                if len(points) > 60000 or route['trip']['summary']['length'] > 1000:
                    raise Infeasible('route_size_limit')
                evaluation = self.evaluator.evaluate(request, route, points, (), self.baseline_seconds)
                if not evaluation['violations']:
                    key = identity([request.payload(), route['trip']['legs']])
                    candidate = Candidate(key, (), (Anchor(request.origin, 'break'), Anchor(request.destination, 'break')),
                                          route, points, evaluation, 'route_' + identity([self.snapshot_id, key]))
                    self.archive[key] = candidate
            except (Infeasible, ValueError, KeyError, TypeError):
                self.budget.failures['invalid_standard_alternate'] += 1
        if publish and self.archive:
            publish(self, baseline, [])
        maximum = request.max_total_seconds if request.mode == 'round_trip' else self.baseline_seconds + request.extra_seconds
        if request.max_total_seconds is not None:
            maximum = min(maximum, request.max_total_seconds)
        if request.max_driving_seconds is not None:
            maximum = min(maximum, request.max_driving_seconds)
        self.budget.phase_seconds['baseline'] = self.budget.now() - phase_started
        phase_started = self.budget.now()
        remaining = max(0, self.budget.deadline - phase_started)
        candidates, truncated = self.store.candidates(request, self.parameters, maximum,
                            self.budget.now, phase_started + min(10, remaining * .2),
                            reference_points=self.reference.points if self.reference else None)
        self.budget.candidate_scan_truncated = truncated
        self.budget.phase_seconds['candidate_scan'] = self.budget.now() - phase_started
        self.budget.candidate_count = len(candidates)
        self.budget.scan_stats = getattr(self.store, 'scan_stats', {})
        mandatory = [c for c in candidates if c.facility and c.facility.id in request.stops.required_ids]
        if {c.facility.id for c in mandatory} != set(request.stops.required_ids):
            raise Infeasible('required_facility_unavailable')
        seed = self.evaluate(request, mandatory) if mandatory else baseline
        if mandatory and not seed:
            # Try distinct orders before concluding the mandatory schedule is impossible.
            seed = self.evaluate(request, tuple(reversed(mandatory)))
        beam = [seed] if seed else []
        phase_started = self.budget.now()
        seeds = corridor_seeds(candidates, request)
        corridor_deadline = phase_started + max(0, self.budget.deadline - phase_started) * .35
        attempted, accepted = 0, 0
        for gates in seeds:
            if not self.budget.available() or self.budget.now() >= corridor_deadline:
                break
            attempted += 1
            evaluated = self.evaluate(request, tuple(mandatory) + gates)
            if evaluated:
                accepted += 1
                beam.append(evaluated)
        self.budget.corridor_stats = {'generated': len(seeds), 'attempted': attempted, 'accepted': accepted,
                                      'truncated': attempted < len(seeds)}
        self.budget.terminated_early |= attempted < len(seeds)
        self.budget.phase_seconds['corridors'] = self.budget.now() - phase_started
        beam = self.diverse_beam(beam)
        if publish and self.archive:
            publish(self, baseline, [])
        profiles = [request.preferences.weights]
        for _ in range(self.parameters.profiles - 1):
            jitter = [w * rng.uniform(.8, 1.2) for w in request.preferences.weights]
            profiles.append(tuple(w / sum(jitter) for w in jitter))
        expansions = []
        for opportunity in candidates:
            if opportunity in mandatory:
                continue
            expansions.append(opportunity)
            if not opportunity.facility:
                expansions.append(self.reversed(opportunity))
        phase_started = self.budget.now()
        phase_end = phase_started + max(0, self.budget.deadline - phase_started) * self.parameters.beam_fraction
        for depth in range(self.parameters.beam_depth):
            proposals = []
            parents = beam or [None]
            for parent in parents:
                visits = parent.visits if parent else tuple(mandatory)
                used = {v.id.removesuffix(':reverse') for v in visits}
                for opportunity in expansions:
                    if opportunity.id.removesuffix(':reverse') in used:
                        continue
                    if opportunity.facility and sum(v.facility is not None for v in visits) >= request.stops.desired_count:
                        continue
                    for position in range(len(visits) + 1):
                        proposed = visits[:position] + (opportunity,) + visits[position:]
                        anchors = [request.origin] + [v.anchors[0].coordinate for v in proposed] + [request.destination]
                        lower = sum(distance(a, b) for a, b in zip(anchors, anchors[1:])) / (400 / 3.6)
                        if lower + sum(request.stops.dwell_seconds for v in proposed if v.facility) > maximum:
                            continue
                        profile = profiles[(depth + position) % len(profiles)]
                        category = opportunity.category
                        preference = profile[CATEGORIES.index(category)] if category != 'stop' else 1
                        approximate_extra = sum(distance(a, b) for a, b in zip(anchors, anchors[1:])) / 15
                        priority = opportunity.estimated_value * preference / max(60, approximate_extra)
                        proposals.append((-priority, identity([v.id for v in proposed]), proposed))
            expanded = list(beam)
            seen = set()
            reserve = max(2, int(self.parameters.max_evaluations * .5 * (depth + 1) / self.parameters.beam_depth))
            for _, key, proposed in sorted(proposals):
                if not self.budget.available() or self.budget.now() >= phase_end or self.budget.calls >= reserve:
                    break
                if key in seen:
                    continue
                seen.add(key)
                evaluated = self.evaluate(request, proposed)
                if evaluated:
                    expanded.append(evaluated)
            beam = self.diverse_beam(expanded)
            if publish and self.archive:
                publish(self, baseline, [])
            if not self.budget.available() or self.budget.now() >= phase_end:
                break
        self.alns(request, beam, expansions, rng)
        selected = select_portfolio(list(self.archive.values()), request, self.parameters)
        counterfactuals = self.counterfactuals(request, selected)
        selected = select_portfolio(list(self.archive.values()), request, self.parameters, self.budget.portfolio_stats)
        if not selected:
            raise Infeasible('mandatory_constraints_unsatisfied' if request.stops.required_ids else 'no_feasible_route')
        return baseline, selected, counterfactuals

    def diverse_beam(self, candidates):
        selected, seen = [], set()
        for candidate in sorted(candidates, key=lambda c: (-c.score, c.key)):
            if candidate.key in seen:
                continue
            seen.add(candidate.key)
            if all(weighted_overlap(candidate.evaluation['physical_lengths'], old.evaluation['physical_lengths']) < .97 for old in selected):
                selected.append(candidate)
                if len(selected) >= self.parameters.beam_width:
                    break
        return selected

    def alns(self, request, seeds, opportunities, rng):
        if not seeds or not opportunities:
            return
        current = max(seeds, key=lambda c: c.score)
        weights = dict.fromkeys(('remove', 'cluster', 'replace', 'insert', 'reverse', 'regret', 'rest'), 1.)
        scores, counts = Counter(), Counter()
        temperature = .05
        for iteration in range(self.parameters.alns_iterations):
            if not self.budget.available() or self.budget.calls >= self.parameters.max_evaluations - 3:
                self.budget.terminated_early = True
                break
            operator = rng.choices(list(weights), list(weights.values()))[0]
            visits = list(current.visits)
            optional = [i for i, v in enumerate(visits) if not v.facility or v.facility.id not in request.stops.required_ids]
            available = [v for v in opportunities if v.id.removesuffix(':reverse') not in {old.id.removesuffix(':reverse') for old in visits}]
            if operator in ('remove', 'replace', 'cluster') and optional:
                optional.sort(key=lambda i: visits[i].estimated_value)
                index = rng.choice(optional[:3])
                category = visits[index].category
                if operator == 'replace':
                    available.sort(key=lambda v: v.category != category)
                if operator == 'cluster':
                    center = visits[index].anchors[0].coordinate
                    visits = [v for i, v in enumerate(visits) if i not in optional or distance(v.anchors[0].coordinate, center) > 3000]
                else:
                    visits.pop(index)
            if operator == 'reverse' and optional:
                index = rng.choice(optional)
                visits[index] = self.reversed(visits[index])
            if operator in ('replace', 'insert', 'cluster', 'regret', 'rest') and available and len(visits) < 6:
                if operator == 'rest':
                    available = [v for v in available if v.facility]
                available = [v for v in available if not v.facility or sum(p.facility is not None for p in visits) < request.stops.desired_count]
                if available:
                    opportunity = rng.choice(available[:max(1, min(20, len(available)))])
                    positions = list(range(len(visits) + 1))
                    positions.sort(key=lambda i: distance(request.origin if i == 0 else visits[i - 1].anchors[-1].coordinate,
                                                            opportunity.anchors[0].coordinate) +
                                   distance(opportunity.anchors[-1].coordinate,
                                            request.destination if i == len(visits) else visits[i].anchors[0].coordinate))
                    if operator == 'regret' and len(available) > 1:
                        def regret(v):
                            costs = sorted(distance(request.origin if i == 0 else visits[i - 1].anchors[-1].coordinate, v.anchors[0].coordinate) +
                                distance(v.anchors[-1].coordinate, request.destination if i == len(visits) else visits[i].anchors[0].coordinate) for i in positions)
                            return costs[1] - costs[0] if len(costs) > 1 else v.estimated_value
                        opportunity = max(available[:20], key=regret)
                        positions.sort(key=lambda i: distance(request.origin if i == 0 else visits[i - 1].anchors[-1].coordinate,
                            opportunity.anchors[0].coordinate) + distance(opportunity.anchors[-1].coordinate,
                            request.destination if i == len(visits) else visits[i].anchors[0].coordinate))
                    visits.insert(positions[0], opportunity)
            candidate = self.evaluate(request, tuple(visits))
            self.budget.operators[operator] += 1
            counts[operator] += 1
            reward = 0
            if candidate:
                best_before = max((c.score for c in self.archive.values() if c.key != candidate.key), default=-math.inf)
                delta = candidate.score - current.score
                if delta >= 0 or rng.random() < math.exp(max(-700, delta / max(temperature, 1e-6))):
                    reward = 5 if candidate.score > best_before else 2 if delta > 0 else 1
                    current = candidate
            scores[operator] += reward
            temperature *= .995
            if (iteration + 1) % 25 == 0:
                for name in weights:
                    if counts[name]:
                        weights[name] = max(.05, .8 * weights[name] + .2 * scores[name] / counts[name])
                scores.clear()
                counts.clear()

    def counterfactuals(self, request, selected):
        results = []
        for candidate in selected:
            optional = [v for v in candidate.visits if not v.facility]
            if not optional or not self.budget.available():
                continue
            target = max(optional, key=lambda v: v.estimated_value)
            alternative = self.evaluate(request, tuple(v for v in candidate.visits if v.id != target.id))
            if alternative:
                results.append({'route_id': candidate.route_id, 'removed_arc_id': target.id,
                                'comparison_route_id': alternative.route_id,
                                'driving_seconds_difference': candidate.evaluation['timing']['driving_seconds'] - alternative.evaluation['timing']['driving_seconds'],
                                'sea_proximity_m_difference': candidate.evaluation['metrics']['sea_proximity_m'] - alternative.evaluation['metrics']['sea_proximity_m']})
        return results
