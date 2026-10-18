import math

from geometry import distance
from recommendation.store import bearing


def curvature_profile(points, spacing=25):
    sampled = [points[0]]
    traveled, target = 0., spacing
    for first, second in zip(points, points[1:]):
        length = distance(first, second)
        if length <= 0:
            continue
        while target <= traveled + length:
            fraction = (target - traveled) / length
            sampled.append((first[0] + (second[0] - first[0]) * fraction,
                            first[1] + (second[1] - first[1]) * fraction))
            target += spacing
        traveled += length
    if len(sampled) < 5:
        return [None] * len(sampled)
    smooth = [sampled[0]] + [(sum(p[0] for p in sampled[i - 1:i + 2]) / 3,
                            sum(p[1] for p in sampled[i - 1:i + 2]) / 3) for i in range(1, len(sampled) - 1)] + [sampled[-1]]
    profile = [None]
    for a, b, c in zip(smooth, smooth[1:], smooth[2:]):
        delta = abs((bearing(b, c) - bearing(a, b) + 180) % 360 - 180)
        profile.append(math.radians(delta) / max(1, (distance(a, b) + distance(b, c)) / 2))
    profile.append(None)
    return profile


def line_of_sight(origin, target, elevation, eye_height=1.2, spacing=30):
    ground = elevation(origin)
    if ground is None or not math.isfinite(ground):
        return None
    length = distance(origin, target)
    if length <= 1:
        return None
    eye = ground + eye_height
    target_height = -length * length / (2 * 6371000)
    count = max(2, math.ceil(length / spacing))
    unknown = False
    blocked = False
    for i in range(1, count):
        fraction = i / count
        coordinate = (origin[0] + (target[0] - origin[0]) * fraction, origin[1] + (target[1] - origin[1]) * fraction)
        height = elevation(coordinate)
        if height is None or not math.isfinite(height):
            unknown = True
            continue
        adjusted = height - (length * fraction)**2 / (2 * 6371000)
        line = eye + fraction * (target_height - eye)
        if adjusted > line:
            blocked = True
    if blocked:
        return False
    return None if unknown else True


def reveal_events(blocks, minimum_after_meters=300):
    events = []
    seen = set()
    for previous, current in zip(blocks, blocks[1:]):
        if (current['category'] == 'sea' and current.get('visibility_verified') and
                current['end_m'] - current['start_m'] >= minimum_after_meters and
                previous['category'] == 'forest' and previous['end_m'] - previous['start_m'] >= 300 and
                current['start_m'] - previous['end_m'] <= 1 and current.get('openness') is not None):
            key = current.get('physical_position')
            if key in seen:
                continue
            seen.add(key)
            events.append({'start_m': current['start_m'], 'end_m': min(current['end_m'], current['start_m'] + 300),
                           'category': 'sea', 'strength': max(0, min(1, current['openness'])),
                           'source_ids': current['source_ids'], 'kind': 'forest_to_sea'})
    return events
