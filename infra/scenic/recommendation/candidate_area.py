"""Order small spatial queries along the trip before exploring farther away."""

import math

from geometry import distance


def reference_samples(points, spacing=10000):
    result = [points[0]]
    traveled, target = 0., spacing
    for a, b in zip(points, points[1:]):
        length = distance(a, b)
        while length and target <= traveled + length:
            fraction = (target - traveled) / length
            result.append((a[0] + (b[0] - a[0]) * fraction, a[1] + (b[1] - a[1]) * fraction))
            target += spacing
        traveled += length
    if result[-1] != points[-1]:
        result.append(points[-1])
    return result


def candidate_tiles(bounds, reference, step=.2):
    west, south, east, north = bounds
    samples = reference_samples(reference)
    groups = {}
    for x in range(math.floor(west / step), math.floor(east / step) + 1):
        for y in range(math.floor(south / step), math.floor(north / step) + 1):
            tile = (max(west, x * step), max(south, y * step),
                    min(east, (x + 1) * step), min(north, (y + 1) * step))
            if tile[0] >= tile[2] or tile[1] >= tile[3]:
                continue
            center = ((tile[1] + tile[3]) / 2, (tile[0] + tile[2]) / 2)
            separation, index = min((distance(center, point), i) for i, point in enumerate(samples))
            group = min(11, index * 12 // len(samples))
            groups.setdefault(group, []).append((separation, x, y, tile))
    ordered = [sorted(groups[k]) for k in sorted(groups)]
    return [group[index][3] for index in range(max(map(len, ordered), default=0))
            for group in ordered if index < len(group)]
