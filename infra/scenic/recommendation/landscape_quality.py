"""Conservative view quality for time-dependent landscape routing."""

import math

from recommendation.store import angle_difference


WATER = ('sea', 'inland_water', 'lake', 'river')
DAYLIGHT = WATER + ('valley', 'open_nature', 'farmland')


def terrain_sun_visible(features, altitude, azimuth):
    horizon = features.get('horizon_degrees')
    if not horizon or len(horizon) != 8 or not all(math.isfinite(v) for v in horizon):
        return None
    position = (azimuth % 360) / 45
    index = int(position)
    angle = horizon[index] + (horizon[(index + 1) % 8] - horizon[index]) * (position - index)
    return altitude > max(0, angle)


def sun_alignment_bonus(features, category, altitude, azimuth, weather):
    if category not in WATER or not -6 <= altitude <= 6 or not weather:
        return 1.
    cloud, precipitation = weather.get('cloud_cover'), weather.get('precipitation')
    if cloud is None or precipitation is None or cloud > 40 or precipitation >= .1:
        return 1.
    if weather.get('visibility') is None or weather['visibility'] < 1000:
        return 1.
    if terrain_sun_visible(features, altitude, azimuth) is not True:
        return 1.
    if category == 'inland_water':
        kind = max(('lake', 'river', 'water'), key=lambda k: features.get(k, 0))
    else:
        kind = category
    direction = features.get(kind + '_bearing')
    if direction is None:
        return 1.
    alignment = max(0, math.cos(math.radians(angle_difference(direction, azimuth))))
    return 1 + .15 * alignment
