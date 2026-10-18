import json
import math
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from recommendation.models import Infeasible, identity


class ValhallaAdapter:
    def __init__(self, endpoint, map_version, fetch=None):
        self.endpoint, self.map_version = endpoint.rstrip('/'), map_version
        self.fetch = fetch or self._fetch
        self.initial_alternates = []

    @staticmethod
    def _fetch(url, body, timeout):
        request = Request(url, json.dumps(body, allow_nan=False).encode(), {'Content-Type': 'application/json'})
        try:
            with urlopen(request, timeout=timeout) as response:
                raw = response.read(16_000_001)
            if len(raw) > 16_000_000:
                raise ValueError('Route response too large')
            return json.loads(raw)
        except HTTPError as error:
            if error.code in (400, 404):
                raise Infeasible('no_route') from None
            raise

    def calculate(self, request, anchors, timeout, alternates=None):
        if alternates is None:
            alternates = 2 if len(anchors) == 2 and request.mode == 'point_to_point' else 0
        locations = []
        for anchor in anchors:
            value = {'lat': anchor.coordinate[0], 'lon': anchor.coordinate[1], 'type': anchor.kind,
                     'radius': 30, 'search_cutoff': 100}
            if anchor.heading is not None:
                value['heading'] = anchor.heading
                value['heading_tolerance'] = 45
            locations.append(value)
        c = request.constraints
        body = {'locations': locations, 'costing': 'auto', 'units': 'kilometers', 'language': 'ja-JP',
                'directions_type': 'instructions', 'alternates': alternates,
                'costing_options': {'auto': {'use_tolls': .5 if c.allow_tolls else 0.,
                    'use_highways': 1. if c.allow_highways else 0., 'use_ferry': .5 if c.allow_ferries else 0.,
                    'exclude_unpaved': not c.allow_unpaved}}}
        result = self.fetch(self.endpoint + '/route', body, timeout)
        self.verify(result, request)
        if len(anchors) == 2:
            self.initial_alternates = result.get('alternates', [])[:2]
        return {'trip': result['trip']}

    @staticmethod
    def verify(result, request):
        trip = result['trip']
        if trip['status'] != 0:
            raise Infeasible('no_route')
        summary = trip['summary']
        if any(not math.isfinite(float(summary[k])) or float(summary[k]) < 0 for k in ('time', 'length')):
            raise ValueError('Invalid route summary')
        c = request.constraints
        for key, allowed in [('has_toll', c.allow_tolls), ('has_highway', c.allow_highways), ('has_ferry', c.allow_ferries)]:
            if summary.get(key, False) and not allowed:
                raise Infeasible('road_constraint')
        for leg in trip['legs']:
            for maneuver in leg.get('maneuvers', []):
                for key, allowed in [('toll', c.allow_tolls), ('highway', c.allow_highways), ('ferry', c.allow_ferries)]:
                    if maneuver.get(key, False) and not allowed:
                        raise Infeasible('road_constraint')

    def cache_key(self, request, anchors):
        return identity({'anchors': [(a.coordinate, a.kind, a.heading, a.facility_id) for a in anchors],
                         'constraints': request.payload()['constraints'], 'map_version': self.map_version,
                         'departure': request.departure.isoformat(), 'adapter_version': '2.0-static-speed'})
