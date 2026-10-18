import math
from functools import lru_cache


def distance(a, b):
    lat = math.radians((a[0] + b[0]) / 2)
    return math.hypot((a[0] - b[0]) * 111195, (a[1] - b[1]) * 111195 * math.cos(lat))


def samples(points, spacing=60):
    for a, b in zip(points, points[1:]):
        length = distance(a, b)
        count = max(1, math.ceil(length / spacing))
        for i in range(count):
            fraction = (i + .5) / count
            yield (a[0] + (b[0] - a[0]) * fraction,
                   a[1] + (b[1] - a[1]) * fraction), length / count


def segment_distance(point, a, b):
    scale = math.cos(math.radians(point[0]))
    x, y = (point[1] - a[1]) * scale, point[0] - a[0]
    dx, dy = (b[1] - a[1]) * scale, b[0] - a[0]
    fraction = max(0, min(1, (x * dx + y * dy) / (dx * dx + dy * dy))) if dx or dy else 0
    return math.hypot(x - fraction * dx, y - fraction * dy) * 111195


def decode_shape(shape):
    return list(_decoded_shape(shape))


@lru_cache(maxsize=16)
def _decoded_shape(shape):
    result, index, latitude, longitude = [], 0, 0, 0
    while index < len(shape):
        values = []
        for _ in range(2):
            value, shift = 0, 0
            while True:
                if index >= len(shape) or shift > 30:
                    raise ValueError('Invalid polyline')
                part = ord(shape[index]) - 63
                index += 1
                if not 0 <= part <= 63:
                    raise ValueError('Invalid polyline')
                value |= (part & 31) << shift
                shift += 5
                if part < 32:
                    break
            values.append(~(value >> 1) if value & 1 else value >> 1)
        latitude += values[0]
        longitude += values[1]
        if not -90e6 <= latitude <= 90e6 or not -180e6 <= longitude <= 180e6:
            raise ValueError('Invalid coordinates')
        result.append((latitude / 1e6, longitude / 1e6))
        if len(result) > 200_000:
            raise ValueError('Shape too large')
    return tuple(result)


def route_points(route):
    points = []
    for leg in route['trip']['legs']:
        shape = decode_shape(leg['shape'])
        points.extend(shape[1:] if points and shape and points[-1] == shape[0] else shape)
    if len(points) < 2 or len(points) > 200_000:
        raise ValueError('Invalid shape')
    return points
