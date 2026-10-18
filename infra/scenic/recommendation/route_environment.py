"""Route-aligned display data; never used to score or reroute a candidate."""

import bisect
from datetime import timedelta
import math

from geometry import distance
from recommendation.environment import solar_position
from recommendation.evaluator import RouteEvaluator
from recommendation.models import Parameters
from recommendation.store import bearing, angle_difference
from recommendation.landscape_quality import terrain_sun_visible


def light_phase(point, moment):
    altitude, azimuth = solar_position(point, moment)
    if altitude < -6:
        phase = 'night'
    elif altitude > 6:
        phase = 'day'
    else:
        before = solar_position(point, moment - timedelta(minutes=5))[0]
        after = solar_position(point, moment + timedelta(minutes=5))[0]
        phase = 'dawn' if after > before else 'dusk'
    return altitude, azimuth, phase


def sky_condition(weather):
    if weather and weather.get('weather_code') in (95, 96, 99):
        return 'thunderstorm'
    if weather and (weather.get('snowfall', 0) > 0 or weather.get('weather_code') in (71, 73, 75, 77, 85, 86)):
        return 'snowfall'
    if weather and weather.get('weather_code') in (45, 48):
        return 'fog'
    if not weather or weather.get('cloud_cover') is None or weather.get('precipitation') is None:
        return 'unknown'
    if weather['precipitation'] >= .1:
        return 'precipitation'
    if weather.get('visibility', 20000) < 1000:
        return 'low_visibility'
    return 'clear' if weather['cloud_cover'] <= 10 else 'fair' if weather['cloud_cover'] <= 40 else 'cloudy'


def best_spots(profile, total):
    scored = []
    for item in profile:
        if item['tunnel'] or not item['matched']:
            continue
        features = item['features']
        values = {k: features.get(k) or 0 for k in ('sea', 'lake', 'river', 'valley', 'forest', 'open_nature')}
        if item.get('sea_visibility') is not None and item['sea_visibility'] <= .1:
            values['sea'] = 0
        category = max(values, key=values.get)
        if values[category] < .5:
            continue
        score = .65 * values[category] + .2 * (item['openness'] or 0)
        reasons = [category + '_proximity' if category in ('sea', 'lake', 'river', 'valley') else category]
        verified = category == 'sea' and item['visibility_verified']
        if verified:
            score += .1
            reasons.append('visibility_estimate')
        alignment = item.get('sun_view_alignment')
        if (category in ('sea', 'lake', 'river') and item['light_phase'] in ('dawn', 'dusk')
                and item['sky'] in ('unknown', 'clear', 'fair') and alignment is not None and alignment >= .65):
            # With no forecast, the timing is suggestive but clear skies are not assumed.
            score += .12 * alignment if item['sky'] == 'unknown' else .25 * alignment if item['sky'] in ('clear', 'fair') else 0
            reasons.append('sunrise_direction' if item['light_phase'] == 'dawn' else 'sunset_direction')
        if item['sky'] in ('precipitation', 'low_visibility', 'snowfall', 'fog', 'thunderstorm'):
            score *= .65
        if item['light_phase'] == 'night':
            continue
        if item['sky'] == 'unknown':
            reasons.append('forecast_unavailable')
        scored.append((score, item['start_m'], item, category, reasons))
    chosen = []
    for score, _, item, category, reasons in sorted(scored, key=lambda x: (-x[0], x[1])):
        center = (item['start_m'] + item['end_m']) / 2
        if any(abs(center - old['distance_m']) < max(1500, total * .08) for old in chosen):
            continue
        if item.get('coordinate') and any(distance(item['coordinate'], old['coordinate']) < 1500
                                          for old in chosen if old.get('coordinate')):
            continue
        chosen.append({'distance_m': round(center, 2), 'start_m': item['start_m'], 'end_m': item['end_m'],
                       'arrival_time': item['arrival_time'], 'category': category, 'reasons': reasons,
                       'score': round(score, 3), 'confidence': 'medium' if category == 'sea' and item['visibility_verified'] else 'low',
                       'coordinate': item.get('coordinate'),
                       'stopping_verified': False})
        if len(chosen) == 4:
            break
    return sorted(chosen, key=lambda s: s['distance_m'])


def build_profile(store, forecast, candidate, request, limit=192):
    points, route = candidate.points, candidate.route
    if len(points) < 2:
        return {'timeline': [], 'best_spots': [], 'method': 'route-environment-v1'}
    cumulative = [0.]
    for a, b in zip(points, points[1:]):
        cumulative.append(cumulative[-1] + distance(a, b))
    total = cumulative[-1]
    if total <= 0:
        return {'timeline': [], 'best_spots': [], 'method': 'route-environment-v1'}
    count = max(1, min(limit, math.ceil(total / 500)))
    clock = RouteEvaluator(store, Parameters(), forecast)
    profile = []
    for i in range(count):
        start, end = total * i / count, total * (i + 1) / count
        middle = (start + end) / 2
        index = min(len(points) - 2, max(0, bisect.bisect_right(cumulative, middle) - 1))
        fraction = (middle - cumulative[index]) / max(.001, cumulative[index + 1] - cumulative[index])
        a, b = points[index], points[index + 1]
        point = tuple(x + (y - x) * fraction for x, y in zip(a, b))
        heading = bearing(a, b)
        match = store.match(point, heading)
        road = match[0] if match else None
        def at(meters):
            seconds = clock.driving_time_at(route, points, meters, total)
            seconds += sum(s['dwell_seconds'] for s in candidate.evaluation['schedule'] if s['distance_m'] <= meters)
            return request.departure + timedelta(seconds=seconds)
        moment = at(middle)
        weather = forecast.at(point, moment)
        altitude, azimuth, phase = light_phase(point, moment)
        landscape = store.landscape_at(road['id']) if road else None
        directional = road['features'].get('directional', {}).get('reverse' if match[1] else 'forward', {}) if road else {}
        visibility = directional.get('sea_visibility', road.get('sea_visibility')) if road else None
        tunnel = bool(road and road['tunnel'])
        features = {key: (0 if tunnel else landscape.get(key, 0)) if landscape is not None else None
                    for key in ('lake', 'river', 'water', 'valley')}
        if road and not tunnel:
            features['valley'] = road['qualities']['valley']
        for key in ('sea', 'forest', 'open_nature'):
            features[key] = road['qualities'].get(key) if road else None
        urban = road.get('urban_ratio') if road and not tunnel else None
        openness = road.get('openness') if road and not tunnel else None
        if openness is None and road and not tunnel:
            openness = max(0, 1 - (features['forest'] or 0) - (urban or 0))
        water_kind = max(('sea', 'lake', 'river', 'water'), key=lambda k: features.get(k) or 0)
        view = (landscape or {}).get(water_kind + '_bearing')
        alignment = max(0, math.cos(math.radians(angle_difference(view, azimuth)))) if view is not None else None
        sun_terrain_visible = terrain_sun_visible(road['features'], altitude, azimuth) if road else None
        if sun_terrain_visible is False:
            alignment = None
        profile.append({'start_m': round(start, 2), 'end_m': round(end, 2),
                        'start_time': at(start).isoformat(), 'end_time': at(end).isoformat(),
                        'arrival_time': moment.isoformat(), 'elapsed_seconds': round((moment - request.departure).total_seconds()),
                        'solar_altitude': round(altitude, 1), 'solar_azimuth': round(azimuth, 1), 'light_phase': phase,
                        'sky': sky_condition(weather), 'cloud_cover': weather.get('cloud_cover') if weather else None,
                        'precipitation': weather.get('precipitation') if weather else None,
                        'weather_source_id': forecast.source_id if weather else None,
                        'features': features, 'urban_ratio': urban, 'openness': openness,
                        'sun_view_alignment': alignment, 'sea_visibility': visibility,
                        'visibility_verified': visibility is not None and visibility >= .5, 'coordinate': point,
                        'elevation_m': road['features'].get('elevation_m') if road else None,
                        'terrain_valley': road['features'].get('terrain_valley') if road else None,
                        'horizon_degrees': road['features'].get('horizon_degrees') if road else None,
                        'sun_terrain_visible': sun_terrain_visible,
                        'weather_details': weather or {},
                        'matched': road is not None, 'tunnel': tunnel})
    return {'timeline': profile, 'best_spots': best_spots(profile, total), 'method': 'route-environment-v1',
            'sampling_meters': round(total / count), 'weather_acquired_at': forecast.acquired_at,
            'landscape_available': store.has_landscapes,
            'warning': 'OSM・利用可能な地表DEM・予報と概算太陽位置からの推定。眺望・朝焼け夕焼け・道路面の状態・安全な停車場所を保証しません。'}
