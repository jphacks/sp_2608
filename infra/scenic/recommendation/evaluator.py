from datetime import timedelta
import bisect
import math
from collections import OrderedDict
from recommended_roads import RecommendedRoads, RANKS

from geometry import distance, samples, decode_shape
from recommendation.environment import solar_position
from recommendation.models import CATEGORIES, identity
from recommendation.store import angle_difference, bearing, direction_allowed
from recommendation.terrain import reveal_events, curvature_profile
from recommendation.experience import scenery_components
from recommendation.landscape_quality import WATER, DAYLIGHT, sun_alignment_bonus


def clip(value):
    return max(0., min(1., value))


def geographic_category(road):
    if road is None:
        return 'unknown'
    if road['tunnel']:
        return 'unclassified'
    values = {k: road['qualities'].get(k) or 0. for k in CATEGORIES}
    if any(values[k] >= values['inland_water'] for k in ('lake', 'river')):
        values['inland_water'] = 0.
    # Close coastal roads get a small display preference, not a scoring bonus.
    if values['sea'] >= .5:
        values['sea'] *= 1.2
    category = max(CATEGORIES, key=values.get)
    return category if values[category] >= .25 else 'unclassified'


def append_timeline(timeline, block):
    if timeline and all(timeline[-1][k] == block[k] for k in ('category', 'source_ids', 'sea_side', 'visibility_verified', 'recommended_roads')):
        timeline[-1]['end_m'] = block['end_m']
        timeline[-1]['confidence'] = min(timeline[-1]['confidence'], block['confidence'])
        a, b = timeline[-1]['openness'], block['openness']
        timeline[-1]['openness'] = min(a, b) if a is not None and b is not None else None
    else:
        timeline.append(block)


def interval_union(intervals):
    merged = []
    for start, end in sorted(intervals, key=lambda pair: (pair[0], pair[1])):
        if not merged or start > merged[-1][1] + .01:
            merged.append([start, end])
        else:
            merged[-1][1] = max(merged[-1][1], end)
    return merged


def integrated_max(observations, category):
    cuts = sorted({v for start, end, quality in observations for v in (start, end)})
    return sum((end - start) * max((q[category] for a, b, q in observations if a < end and b > start), default=0)
               for start, end in zip(cuts, cuts[1:]))


class RouteEvaluator:
    def __init__(self, store, parameters, forecast):
        self.store, self.parameters, self.forecast = store, parameters, forecast
        self.segment_cache = OrderedDict()
        self.geometry_cache = OrderedDict()

    def route_geometry(self, points):
        key = tuple(points)
        if key not in self.geometry_cache:
            cumulative = [0.]
            for a, b in zip(points, points[1:]):
                cumulative.append(cumulative[-1] + distance(a, b))
            self.geometry_cache[key] = (cumulative, curvature_profile(points))
            if len(self.geometry_cache) > 8:
                self.geometry_cache.popitem(last=False)
        self.geometry_cache.move_to_end(key)
        return self.geometry_cache[key]

    def segment_geometry(self, a, b):
        key = (a, b)
        if key not in self.segment_cache:
            self.segment_cache[key] = (bearing(a, b), tuple(samples([a, b], self.parameters.sample_meters)))
            if len(self.segment_cache) > 20000:
                self.segment_cache.popitem(last=False)
        self.segment_cache.move_to_end(key)
        return self.segment_cache[key]

    def evaluate(self, request, route, points, visits, baseline_seconds):
        cumulative, curvature = self.route_geometry(points)
        total_length = cumulative[-1]
        driving = float(route['trip']['summary']['time'])
        schedule, violations = self.schedule(request, route, points, visits, cumulative)
        context_notes = set()
        stop_seconds = sum(s['dwell_seconds'] for s in schedule)
        total_seconds = driving + stop_seconds
        if request.mode != 'round_trip' and driving > baseline_seconds + request.extra_seconds + .001:
            violations.add('driving_budget')
        if request.max_total_seconds is not None and total_seconds > request.max_total_seconds + .001:
            violations.add('total_budget')
        if request.max_driving_seconds is not None and driving > request.max_driving_seconds + .001:
            violations.add('remaining_driving_budget')
        stop_ids = {s['facility_id'] for s in schedule}
        if not set(request.stops.required_ids) <= stop_ids:
            violations.add('required_stop_missing')
        history, observations, lengths = {}, {}, {}
        driven = dict.fromkeys(CATEGORIES, 0.)
        threshold_m = dict.fromkeys(CATEGORIES, 0.)
        continuous = {k: [] for k in CATEGORIES}
        active = {k: None for k in CATEGORIES}
        timeline, unknown, confidence_sum, road_burden, load_unknown, offset = [], 0., 0., 0., 0., 0.
        near_sea, certified, physical_ranges, directed = [], {}, {}, []
        missing_features = dict.fromkeys(CATEGORIES, 0.)
        certified_physical = set()
        ranked_meters = dict.fromkeys(RANKS, 0.)
        recommended = {}
        experience_timeline = []
        for a, b in zip(points, points[1:]):
            heading, sampled = self.segment_geometry(a, b)
            for point, length in sampled:
                start_m, end_m = offset, offset + length
                offset = end_m
                at_seconds = self.driving_time_at(route, points, start_m, total_length)
                at_seconds += sum(s['dwell_seconds'] for s in schedule if s['distance_m'] <= start_m)
                moment = request.departure + timedelta(seconds=at_seconds)
                match = self.store.match(point, heading)
                if match and not direction_allowed(*match):
                    match = None
                qualities = dict.fromkeys(CATEGORIES, 0.)
                physical, unique, confidence = None, 0., 0.
                source_ids, visibility_verified, side = [], False, 'unknown'
                openness = None
                recommendations = []
                if match:
                    road, reverse = match
                    for key, value in road['features'].get('access_conditions', {}).items():
                        if key.endswith(':conditional'):
                            context_notes.add('OSMに条件付き通行のタグがある区間を含みます。条件の完全な解釈と実況規制は未接続です。')
                        elif key in ('winter_service', 'snowplowing') and value in ('no', 'limited'):
                            context_notes.add('OSMに冬季サービス・除雪が限定的と記録された区間があります。現在の積雪や通行可否は未確認です。')
                        elif key == 'seasonal' and value not in ('no', 'false'):
                            context_notes.add('OSMに季節による利用条件のタグがある区間を含みます。当日の開通を確認したものではありません。')
                        elif key == 'ford' and value not in ('no', 'false'):
                            context_notes.add('OSMに浅瀬を渡る区間のタグがあります。現在の水量・通行可否は未確認です。')
                    recommendations = road.get('recommendations', []) if not road['tunnel'] else []
                    for category in CATEGORIES:
                        if road['qualities'][category] is None:
                            missing_features[category] += length
                    physical = road['physical_id']
                    lengths[physical] = road['length']
                    physical_ranges.setdefault(physical, []).append((start_m, end_m))
                    axis = (road['a'], road['b'])
                    fraction = self.project(point, *axis)
                    road_start = max(0, fraction * road['length'] - length / 2)
                    road_end = min(road['length'], fraction * road['length'] + length / 2)
                    before = sum(y - x for x, y in history.get(physical, []))
                    merged = interval_union(history.get(physical, []) + [(road_start, road_end)])
                    unique = max(0, sum(y - x for x, y in merged) - before)
                    history[physical] = merged
                    if physical in request.visited:
                        unique = 0
                    best = RecommendedRoads.best(recommendations)
                    if best:
                        ranked_meters[best['rank']] += unique
                    for item in recommendations:
                        entry = recommended.setdefault(item['id'], {'name': item['name'], 'rank': item['rank'],
                            'source_ids': item['source_ids'], 'scope_note': item['scope_note'], 'meters': 0., 'ranges': []})
                        entry['meters'] += unique
                        entry['ranges'].append((start_m, end_m))
                    confidence = road['confidence']
                    source_ids = road['source_ids']
                    qualities, visibility_verified, side = self.qualities(request, road, reverse, point, heading, moment)
                    openness = road['openness']
                    if not visibility_verified:
                        confidence = confidence * (1 - request.preferences.weights[0]) + .3 * request.preferences.weights[0]
                    if physical in request.visited:
                        qualities = dict.fromkeys(CATEGORIES, 0.)
                    observations.setdefault(physical, []).append((road_start, road_end, qualities))
                    directed.append({'physical_id': physical, 'direction': -1 if reverse else 1,
                                     'start_m': start_m, 'end_m': end_m})
                    if not request.constraints.allow_unpaved and road['unpaved'] is not False:
                        violations.add('unpaved_or_unverified_surface')
                    if request.constraints.narrow_road_policy == 'require_verified_width' and (road['width'] is None or road['width'] < 4):
                        violations.add('width_not_verified')
                    proxy = [road['features'].get(k) for k in ('narrow', 'rough', 'grade', 'sharp')]
                    if proxy[3] is None:
                        proxy[3] = curvature[min(len(curvature) - 1, int((start_m + end_m) / 2 / 25))]
                    if proxy[2] is not None:
                        proxy[2] = clip((abs(proxy[2]) - .05) / .07)
                    if proxy[3] is not None:
                        proxy[3] = clip((proxy[3] - .01) / .03)
                    load_unknown += length * sum(v is None for v in proxy) / 4
                    load = sum((.5 if v is None else v) * w for v, w in zip(proxy, (.4, .25, .2, .15)))
                    altitude, azimuth = solar_position(point, moment)
                    if 0 < altitude < 12 and angle_difference(heading, azimuth) < 15:
                        load = clip(load + .1)
                    road_burden += length * load
                    if road['qualities']['sea'] >= .5 and not road['tunnel'] and unique > 0:
                        near_sea.append((start_m, end_m, min(length, unique)))
                    for official in road['certified']:
                        certified_physical.add(physical)
                        certified.setdefault(official['id'], {'name': official['name'], 'source_id': official['source_id'], 'ranges': [], 'meters': 0.})
                        certified[official['id']]['meters'] += unique
                        certified[official['id']]['ranges'].append((start_m, end_m))
                else:
                    for category in CATEGORIES:
                        missing_features[category] += length
                    unknown += length
                    load_unknown += length
                    road_burden += length * .5
                    if not request.constraints.allow_unpaved:
                        violations.add('unpaved_or_unverified_surface')
                    if request.constraints.narrow_road_policy == 'require_verified_width':
                        violations.add('width_not_verified')
                confidence_sum += length * confidence
                for k, value in qualities.items():
                    driven[k] += value * length
                    if value >= .45:
                        threshold_m[k] += min(length, unique)
                    if value >= .45 and unique > length * .5:
                        if active[k] is None:
                            active[k] = start_m
                    elif active[k] is not None:
                        continuous[k].append((active[k], start_m))
                        active[k] = None
                category = geographic_category(match[0] if match else None)
                block = {'start_m': start_m, 'end_m': end_m, 'category': category,
                         'confidence': confidence, 'source_ids': source_ids, 'arrival_time': moment.isoformat(),
                         'sea_side': side, 'visibility_verified': visibility_verified}
                block['recommended_roads'] = [{'id': r['id'], 'name': r['name'], 'rank': r['rank'], 'source_ids': r['source_ids']}
                                               for r in recommendations]
                block['openness'] = openness
                block['physical_position'] = physical
                experience_category = max(CATEGORIES, key=qualities.get)
                if confidence == 0:
                    experience_category = 'unknown'
                elif qualities[experience_category] < .25:
                    experience_category = 'unclassified'
                append_timeline(experience_timeline, {**block, 'category': experience_category})
                append_timeline(timeline, block)
        for k in CATEGORIES:
            if active[k] is not None:
                continuous[k].append((active[k], total_length))
        effective = {k: sum(integrated_max(v, k) for v in observations.values()) for k in CATEGORIES}
        unique_length = sum(sum(b - a for a, b in v) for v in history.values())
        repeated = max(0, total_length - unknown - unique_length)
        longest = {k: sorted(continuous[k], key=lambda r: r[1] - r[0], reverse=True)[:2] for k in CATEGORIES}
        quality, continuity, scales = scenery_components(request, self.parameters, effective, longest, total_length, baseline_seconds)
        blocks = [b for b in experience_timeline if b['end_m'] - b['start_m'] >= 300 and b['category'] in CATEGORIES]
        transitions = {}
        for first, second in zip(blocks, blocks[1:]):
            if first['category'] == second['category'] or second['start_m'] - first['end_m'] > 100:
                continue
            key = (first['category'], second['category'])
            transitions[key] = min(request.preferences.weights[CATEGORIES.index(k)] for k in key)
        reveals = reveal_events(experience_timeline)
        reveal_value = sum(e['strength'] * request.preferences.weights[0] for e in reveals)
        variety = request.preferences.variety * .5 * ((1 - math.exp(-sum(transitions.values()) / 2)) + (1 - math.exp(-reveal_value / 2)))
        stop_quality = self.stop_score(request, schedule, driving)
        certified_length = sum(sum(b - a for a, b in history[p]) for p in certified_physical)
        a, b = request.preferences.certified_road, int(request.stops.desired_count > 0)
        poi = (a * (1 - math.exp(-certified_length / 3000)) + b * stop_quality) / (a + b) if a + b else 0
        required_dwell = len(request.stops.required_ids) * request.stops.dwell_seconds
        time_penalty = .8 * clip(max(0, driving - baseline_seconds) / max(900, request.extra_seconds)) + .2 * clip(max(0, stop_seconds - required_dwell) / 1800)
        maneuvers = [m for leg in route['trip']['legs'] for m in leg.get('maneuvers', [])]
        uturns = sum(m.get('type') in (9, 10) for m in maneuvers)
        complex_count = sum(3 if m.get('type') in (9, 10) else 1 if m.get('type') in (11, 12, 14, 15, 16, 17, 18) else 0 for m in maneuvers)
        load = .75 * road_burden / max(total_length, 1) + .25 * clip(complex_count / max(total_length / 1000, 1) / 2)
        repeat_penalty = .8 * clip(repeated / max(total_length, 1) / .25) + .2 * clip(uturns / 2)
        preference_missing = sum(w * missing_features[k] / max(total_length, 1)
                                 for k, w in zip(CATEGORIES, request.preferences.weights))
        uncertainty = clip(1 - confidence_sum / max(total_length, 1) + .2 * preference_missing)
        components = {'Q': quality, 'C': .15 * continuity, 'V': .10 * variety, 'P': .15 * poi,
                      'PT': -.25 * time_penalty, 'L': -.20 * load, 'H': -.20 * repeat_penalty, 'U': -.10 * uncertainty}
        rank_value = max((info['weight'] * (1 - math.exp(-ranked_meters[rank] / 3000))
                                       for rank, info in RANKS.items()), default=0)
        rank_weight = .12 + .18 * request.preferences.certified_road
        components['R'] = rank_weight * rank_value
        metrics = {'effective_m': effective, 'threshold_m': threshold_m, 'driven_quality_m': driven,
                   'sea_proximity_m': sum(r[2] for r in near_sea), 'continuous': longest,
                   'certified_unique_m': certified_length, 'repeated_m': repeated, 'unique_driven_m': unique_length,
                   'total_m': total_length, 'load': load, 'unknown_load_rate': load_unknown / max(total_length, 1),
                   'coverage': clip(1 - unknown / max(total_length, 1)), 'confidence': 1 - uncertainty,
                   'variety': variety, 'stop_fit': stop_quality}
        metrics['recommended_unique_m'] = ranked_meters
        metrics['recommendation_value'] = rank_value
        metrics['saturation_m'] = dict(zip(CATEGORIES, scales))
        metrics['missingness'] = {k: v / max(total_length, 1) for k, v in missing_features.items()}
        metrics['preference_data_coverage'] = clip(1 - preference_missing)
        return {'score': sum(components.values()), 'components': components, 'metrics': metrics,
                'context_notes': sorted(context_notes),
                'violations': sorted(violations), 'timeline': timeline, 'schedule': schedule,
                'timing': {'driving_seconds': driving, 'stop_seconds': stop_seconds, 'waiting_seconds': 0,
                           'total_seconds': total_seconds, 'extra_driving_seconds': max(0, driving - baseline_seconds)},
                'physical_lengths': {p: sum(b - a for a, b in v) for p, v in history.items()},
                'physical_ranges': physical_ranges, 'directed_segments': directed, 'certified': certified,
                'near_sea_ranges': near_sea, 'reveals': reveals, 'recommended': recommended,
                'evaluation_version': self.parameters.version}

    @staticmethod
    def project(point, a, b):
        scale = math.cos(math.radians(point[0]))
        dx, dy = (b[1] - a[1]) * scale, b[0] - a[0]
        return clip(((point[1] - a[1]) * scale * dx + (point[0] - a[0]) * dy) / (dx * dx + dy * dy)) if dx or dy else 0

    def qualities(self, request, road, reverse, point, heading, moment):
        directional = road['features'].get('directional', {}).get('reverse' if reverse else 'forward', {})
        visibility = directional.get('sea_visibility', road['sea_visibility'])
        verified = visibility is not None
        values = {k: v or 0 for k, v in road['qualities'].items()}
        values['sea'] *= visibility if visibility is not None else .5
        for category in ('inland_water', 'lake', 'river', 'valley'):
            view = directional.get(category + '_visibility', road['features'].get(category + '_visibility'))
            exposure = road.get('openness')
            estimated_view = .7 if exposure is None else .7 * (.6 + .4 * clip(exposure))
            values[category] *= clip(view) if view is not None else estimated_view
            if category in ('lake', 'river'):
                target = road['features'].get(category + '_bearing')
                if target is not None:
                    delta = (target - heading + 180) % 360 - 180
                    water_side = 'front' if abs(delta) <= 45 else 'rear' if abs(delta) >= 135 else 'right' if delta > 0 else 'left'
                    if water_side not in (request.constraints.passenger_side, 'front'):
                        values[category] *= 1 - .2 * request.preferences.passenger_view
        side = 'unknown'
        target = directional.get('coast_bearing', road['coast_bearing'])
        if target is not None:
            delta = (target - heading + 180) % 360 - 180
            side = 'front' if abs(delta) <= 45 else 'rear' if abs(delta) >= 135 else 'right' if delta > 0 else 'left'
            if side not in (request.constraints.passenger_side, 'front'):
                values['sea'] *= 1 - .2 * request.preferences.passenger_view
        weather = self.forecast.at(point, moment)
        if weather:
            correction = clip(weather.get('visibility', 20000) / 15000) * math.exp(-weather.get('precipitation', 0) / 5)
            for category in DAYLIGHT:
                values[category] *= correction
        altitude, azimuth = solar_position(point, moment)
        if request.night_correction:
            light = clip((altitude + 6) / 12)
            for category in DAYLIGHT:
                values[category] *= light
        # Disabling night correction must not manufacture a sunrise benefit at night.
        for category in WATER:
            values[category] = clip(values[category] * sun_alignment_bonus(road['features'], category, altitude, azimuth, weather))
        return values, verified, side

    def driving_time_at(self, route, points, offset, total_length=None):
        # Maneuver distances retain the route's own speed model; no other candidate's clock is used.
        if getattr(self, '_clock_route', None) is not route:
            self._clock_route = route
            self._clock_total = total_length if total_length is not None else sum(distance(a, b) for a, b in zip(points, points[1:]))
            self._clock_distances, self._clock_times = [0.], [0.]
            for leg in route['trip']['legs']:
                for maneuver in leg.get('maneuvers', []):
                    self._clock_distances.append(self._clock_distances[-1] + float(maneuver.get('length', 0)) * 1000)
                    self._clock_times.append(self._clock_times[-1] + float(maneuver.get('time', 0)))
        if self._clock_distances[-1] <= 0 or self._clock_times[-1] <= 0:
            return route['trip']['summary']['time'] * clip(offset / max(self._clock_total, 1))
        target = offset / max(self._clock_total, 1) * self._clock_distances[-1]
        index = min(len(self._clock_distances) - 2, max(0, bisect.bisect_right(self._clock_distances, target) - 1))
        fraction = (target - self._clock_distances[index]) / max(.001, self._clock_distances[index + 1] - self._clock_distances[index])
        modeled = self._clock_times[index] + fraction * (self._clock_times[index + 1] - self._clock_times[index])
        return modeled / self._clock_times[-1] * route['trip']['summary']['time']

    def schedule(self, request, route, points, visits, cumulative=None):
        schedule, violations, distance_so_far = [], set(), 0.
        facilities = [v.facility for v in visits if v.facility]
        if facilities and len(route['trip']['legs']) != len(facilities) + 1:
            return [], {'stop_legs_unverified'}
        leg_ends, geometry_size, last_point = [], 0, None
        for leg in route['trip']['legs']:
            shape = decode_shape(leg['shape'])
            if not shape:
                return [], {'stop_legs_unverified'}
            joined = geometry_size > 0 and last_point == shape[0]
            geometry_size += len(shape) - int(joined)
            leg_ends.append(geometry_size - 1)
            last_point = shape[-1]
        seen = set()
        points_offset = cumulative
        if points_offset is None:
            points_offset = [0.]
            for a, b in zip(points, points[1:]):
                points_offset.append(points_offset[-1] + distance(a, b))
        previous = 0
        for facility_index, facility in enumerate(facilities):
            if facility.id in seen:
                violations.add('duplicate_stop')
                continue
            seen.add(facility.id)
            leg_end = decode_shape(route['trip']['legs'][facility_index]['shape'])[-1]
            if distance(leg_end, facility.entrance) > 35:
                violations.add('stop_entrance_not_reached')
                continue
            index = leg_ends[facility_index]
            if index >= len(points) or index < previous or distance(points[index], facility.entrance) > 35:
                violations.add('stop_entrance_not_reached')
                continue
            previous = index
            at = self.driving_time_at(route, points, points_offset[index]) + sum(s['dwell_seconds'] for s in schedule)
            arrival = request.departure + timedelta(seconds=at)
            departure = arrival + timedelta(seconds=request.stops.dwell_seconds)
            open_verified = any(start <= arrival and departure <= end for start, end in facility.windows)
            if facility.windows and not open_verified:
                violations.add('stop_closed')
            if facility.id in request.stops.required_ids and not open_verified:
                violations.add('required_stop_hours_unverified')
            fit = len(set(request.stops.equipment) & facility.equipment) / len(request.stops.equipment) if request.stops.equipment else 1.
            if facility.id in request.stops.required_ids and fit < 1:
                violations.add('required_equipment_missing')
            schedule.append({'facility_id': facility.id, 'name': facility.name, 'distance_m': points_offset[index],
                             'arrival_time': arrival.isoformat(), 'departure_time': departure.isoformat(),
                             'dwell_seconds': request.stops.dwell_seconds, 'hours_verified': open_verified,
                             'equipment_fit': fit, 'source_id': facility.source_id, 'geometry_index': index})
        return schedule, violations

    @staticmethod
    def stop_score(request, schedule, driving):
        desired = request.stops.desired_count
        if not desired:
            return 0.
        selected = schedule[:desired]
        score = sum(s['equipment_fit'] for s in selected) / desired
        clocks = [0.] + [(datetime_from_iso(s['arrival_time']) - request.departure).total_seconds() -
                       sum(p['dwell_seconds'] for p in schedule[:i]) for i, s in enumerate(schedule)] + [driving]
        longest = max((b - a for a, b in zip(clocks, clocks[1:])), default=driving)
        return score * min(1, request.stops.max_continuous_seconds / max(1, longest))


def datetime_from_iso(value):
    from datetime import datetime
    return datetime.fromisoformat(value)


