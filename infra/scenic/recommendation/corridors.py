"""Spatially distributed road opportunities and coarse routing gates."""

from collections import defaultdict
import heapq
import math

from geometry import distance
from recommendation.models import Anchor, Opportunity, identity


def route_frame(point, origin, destination):
    scale = math.cos(math.radians(origin[0]))
    dx = (destination[1] - origin[1]) * scale
    dy = destination[0] - origin[0]
    x, y = (point[1] - origin[1]) * scale, point[0] - origin[0]
    length = math.hypot(dx, dy)
    if length < .001:
        return 0., distance(origin, point)
    return (x * dx + y * dy) / length ** 2, (dx * y - dy * x) / length * 111195


def region(point, request):
    progress, lateral = route_frame(point, request.origin, request.destination)
    if distance(request.origin, request.destination) < 1000:
        return round(point[0] * 5), round(point[1] * 5)
    return max(-1, min(6, int(progress * 6))), round(lateral / 20000)


class SpatialOpportunities:
    def __init__(self, request, per_region=12):
        self.request, self.per_region = request, per_region
        self.groups = defaultdict(list)
        self.offered = 0

    def add(self, priority, opportunity):
        point = opportunity.anchors[len(opportunity.anchors) // 2].coordinate
        heap = self.groups[(region(point, self.request), opportunity.category)]
        item = (priority, opportunity.id, opportunity)
        self.offered += 1
        if len(heap) < self.per_region:
            heapq.heappush(heap, item)
        elif item[:2] > heap[0][:2]:
            heapq.heapreplace(heap, item)

    def select(self, limit):
        groups = sorted((sorted(heap, reverse=True) for heap in self.groups.values()),
                        key=lambda items: items[0][:2], reverse=True)
        selected, seen = [], set()
        for index in range(self.per_region):
            for group in groups:
                if index >= len(group):
                    continue
                opportunity = group[index][2]
                point = opportunity.anchors[0].coordinate
                if opportunity.id in seen or any(distance(point, old.anchors[0].coordinate) < 400 for old in selected):
                    continue
                seen.add(opportunity.id)
                selected.append(opportunity)
                if len(selected) == limit:
                    return selected
        return selected


def corridor_seeds(opportunities, request, limit=24):
    def landscape(road):
        return road.estimated_value if road.landscape_value is None else road.landscape_value
    roads = [v for v in opportunities if not v.facility and v.anchors and landscape(v) > 0]
    if not roads:
        return []
    groups = defaultdict(list)
    for road in roads:
        point = road.anchors[len(road.anchors) // 2].coordinate
        groups[region(point, request)].append(road)
    representatives = [max(items, key=lambda v: (landscape(v), v.id)) for items in groups.values()]
    # Visit separate regions before spending the remaining budget on local detail.
    ordered = []
    remaining = list(representatives)
    while remaining:
        def priority(road):
            point = road.anchors[len(road.anchors) // 2].coordinate
            lower = distance(request.origin, point) + distance(point, request.destination)
            extra = max(0, lower - distance(request.origin, request.destination))
            separation = min((distance(point, old.anchors[len(old.anchors) // 2].coordinate)
                              for old in ordered), default=50000)
            return min(50000, separation) / (50000 + extra * .25), landscape(road), road.id
        best = max(remaining, key=priority)
        ordered.append(best)
        remaining.remove(best)
    seeds, seen = [], set()

    def add(items):
        anchors = tuple(Anchor(v.anchors[len(v.anchors) // 2].coordinate) for v in items)
        key = identity([a.coordinate for a in anchors])
        if key in seen:
            return
        seen.add(key)
        seeds.append((Opportunity('corridor:' + key, anchors, category=items[0].category,
                                  estimated_value=sum(v.estimated_value for v in items)),))

    bands = defaultdict(list)
    for road in ordered:
        point = road.anchors[len(road.anchors) // 2].coordinate
        progress, lateral = route_frame(point, request.origin, request.destination)
        if -.1 <= progress <= 1.1:
            bands[round(lateral / 20000)].append((progress, road))
    # A chain can exceed the time budget even when a single gate in its band is feasible.
    for road in ordered:
        if len(seeds) >= limit * 3 // 4:
            break
        add([road])
    for band in sorted(bands, key=lambda b: (abs(b), b)):
        members = sorted(bands[band], key=lambda pair: (pair[0], pair[1].id))
        if len(members) < 2:
            continue
        count = min(6, len(members))
        add([members[round(i * (len(members) - 1) / (count - 1))][1] for i in range(count)])
        if len(seeds) >= limit:
            break
    for road in ordered:
        if len(seeds) >= limit:
            break
        add([road])
    return seeds[:limit]
