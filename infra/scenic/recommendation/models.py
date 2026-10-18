from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
import hashlib
import json
import math

CATEGORIES = ('sea', 'forest', 'inland_water', 'open_nature', 'farmland', 'townscape',
              'lake', 'river', 'valley', 'urban')
VERSION = '2.8-terrain-context'


class InputError(ValueError):
    pass


class Infeasible(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def number(value, low, high):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not low <= value <= high:
        raise InputError('number_out_of_range')
    return float(value)


def integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise InputError('integer_out_of_range')
    return value


def point(value):
    return number(value['lat'], -80, 80), number(value['lon'], -180, 180)


def timestamp(value):
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if result.utcoffset() is None:
            raise ValueError()
        return result
    except (ValueError, TypeError, AttributeError):
        raise InputError('timezone_required') from None


def identity(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()[:32]


@dataclass(frozen=True)
class Parameters:
    version: str = VERSION
    max_arcs: int = 100
    max_stops: int = 30
    beam_width: int = 24
    beam_depth: int = 3
    profiles: int = 6
    max_evaluations: int = 200
    alns_iterations: int = 300
    max_seconds: float = 90
    beam_fraction: float = .55
    sample_meters: float = 50
    coverage_threshold: float = .9
    repair_attempts: int = 2
    diversity_floor: float = .20
    saturation: tuple = (5000, 4000, 3000, 4000, 4000, 3000, 3000, 3000, 3000, 3000)

    def __post_init__(self):
        if len(self.saturation) != len(CATEGORIES) or any(not math.isfinite(v) or v <= 0 for v in self.saturation):
            raise InputError('invalid_saturation')
        if not (1 <= self.max_evaluations <= 200 and 0 < self.max_seconds <= 120 and
                1 <= self.beam_width <= 24 and 1 <= self.max_arcs <= 100 and 0 <= self.alns_iterations <= 300 and
                1 <= self.beam_depth <= 6 and 1 <= self.profiles <= 6 and 0 <= self.max_stops <= 30 and
                1 <= self.sample_meters <= 100 and 0 < self.beam_fraction < 1 and 0 < self.coverage_threshold <= 1):
            raise InputError('invalid_search_parameters')


@dataclass(frozen=True)
class Preferences:
    weights: tuple
    variety: float = .5
    passenger_view: float = 0
    certified_road: float = 0

    @classmethod
    def parse(cls, value):
        if not set(value) <= set(CATEGORIES) | {'variety', 'passenger_view', 'certified_road'}:
            raise InputError('unknown_preference')
        raw = tuple(number(value.get(k, 0), 0, 1) for k in CATEGORIES)
        total = sum(raw)
        if total <= 0:
            raise InputError('empty_preferences')
        return cls(tuple(v / total for v in raw), *(number(value.get(k, default), 0, 1)
                   for k, default in [('variety', .5), ('passenger_view', 0), ('certified_road', 0)]))


@dataclass(frozen=True)
class Constraints:
    allow_tolls: bool = True
    allow_highways: bool = True
    allow_ferries: bool = False
    allow_unpaved: bool = True
    narrow_road_policy: str = 'penalize_unknown'
    passenger_side: str = 'left'

    @classmethod
    def parse(cls, value):
        if not set(value) <= {'allow_tolls', 'allow_highways', 'allow_ferries', 'allow_unpaved', 'narrow_road_policy', 'passenger_side'}:
            raise InputError('unsupported_constraint')
        kwargs = {}
        for name, default in [('allow_tolls', True), ('allow_highways', True), ('allow_ferries', False), ('allow_unpaved', True)]:
            item = value.get(name, default)
            if type(item) is not bool:
                raise InputError('invalid_constraint')
            kwargs[name] = item
        kwargs['narrow_road_policy'] = value.get('narrow_road_policy', 'penalize_unknown')
        kwargs['passenger_side'] = value.get('passenger_side', 'left')
        if kwargs['narrow_road_policy'] not in ('penalize_unknown', 'require_verified_width') or kwargs['passenger_side'] not in ('left', 'right'):
            raise InputError('invalid_constraint')
        return cls(**kwargs)


@dataclass(frozen=True)
class StopRequest:
    desired_count: int = 0
    dwell_seconds: int = 600
    required_ids: tuple = ()
    equipment: tuple = ()
    max_continuous_seconds: int = 5400

    @classmethod
    def parse(cls, value):
        if not set(value) <= {'desired_count', 'default_dwell_seconds', 'required_ids', 'equipment', 'max_continuous_seconds'}:
            raise InputError('unsupported_stop_constraint')
        ids = value.get('required_ids', [])
        equipment = value.get('equipment', [])
        if (not isinstance(ids, list) or len(ids) > 3 or len(set(ids)) != len(ids) or
                not all(isinstance(i, str) and 0 < len(i) <= 100 for i in ids) or
                not isinstance(equipment, list) or len(equipment) > 10 or
                not all(isinstance(i, str) and 0 < len(i) <= 80 for i in equipment)):
            raise InputError('invalid_stops')
        desired = integer(value.get('desired_count', 0), 0, 3)
        return cls(max(desired, len(ids)), integer(value.get('default_dwell_seconds', 600), 60, 7200),
                   tuple(ids), tuple(equipment), integer(value.get('max_continuous_seconds', 5400), 600, 21600))


@dataclass(frozen=True)
class RecommendationRequest:
    origin: tuple
    destination: tuple
    mode: str
    departure: datetime
    extra_seconds: int
    max_total_seconds: int | None
    preferences: Preferences
    constraints: Constraints
    stops: StopRequest
    max_routes: int = 3
    seed: int = 0
    visited: frozenset = frozenset()
    history_enabled: bool = False
    max_driving_seconds: int | None = None
    night_correction: bool = True

    @classmethod
    def parse(cls, body):
        try:
            mode = body.get('mode', 'point_to_point')
            if mode not in ('point_to_point', 'round_trip'):
                raise InputError('unsupported_mode')
            origin = point(body['origin'])
            destination = origin if mode == 'round_trip' else point(body['destination'])
            budget = body['budget']
            if not set(budget) <= {'max_extra_driving_seconds', 'max_total_seconds', 'max_driving_seconds'}:
                raise InputError('unsupported_budget')
            extra = integer(budget.get('max_extra_driving_seconds', 1800), 0, 21600)
            total = integer(budget['max_total_seconds'], 60, 86400) if 'max_total_seconds' in budget else None
            if mode == 'round_trip' and total is None:
                raise InputError('round_trip_total_budget_required')
            stops = StopRequest.parse(body.get('stops', {}))
            if total is not None and len(stops.required_ids) * stops.dwell_seconds >= total:
                raise InputError('insufficient_stop_budget')
            visited = body.get('visited_physical_ids', [])
            if not isinstance(visited, list) or len(visited) > 20000 or not all(isinstance(i, str) and len(i) <= 160 for i in visited):
                raise InputError('invalid_history')
            enabled = body.get('history_enabled', False)
            if type(enabled) is not bool or (visited and not enabled):
                raise InputError('history_consent_required')
            night_correction = body.get('night_correction', True)
            if type(night_correction) is not bool:
                raise InputError('invalid_night_correction')
            return cls(origin, destination, mode, timestamp(body['departure_time']), extra, total,
                       Preferences.parse(body.get('preferences', {'sea': .5, 'forest': .5})),
                       Constraints.parse(body.get('constraints', {})), stops,
                       integer(body.get('max_routes', 3), 1, 6), integer(body.get('seed', 0), 0, 2**31 - 1),
                       frozenset(visited), enabled,
                       integer(budget['max_driving_seconds'], 1, 86400) if 'max_driving_seconds' in budget else None,
                       night_correction)
        except (KeyError, TypeError, AttributeError):
            raise InputError('invalid_request') from None

    def payload(self):
        return {'origin': dict(zip(('lat', 'lon'), self.origin)), 'destination': dict(zip(('lat', 'lon'), self.destination)),
                'mode': self.mode, 'departure_time': self.departure.isoformat(),
                'budget': {'max_extra_driving_seconds': self.extra_seconds,
                           **({'max_total_seconds': self.max_total_seconds} if self.max_total_seconds is not None else {}),
                           **({'max_driving_seconds': self.max_driving_seconds} if self.max_driving_seconds is not None else {})},
                'preferences': {**dict(zip(CATEGORIES, self.preferences.weights)), 'variety': self.preferences.variety,
                                'passenger_view': self.preferences.passenger_view, 'certified_road': self.preferences.certified_road},
                'constraints': asdict(self.constraints),
                'stops': {'desired_count': self.stops.desired_count, 'default_dwell_seconds': self.stops.dwell_seconds,
                          'required_ids': list(self.stops.required_ids), 'equipment': list(self.stops.equipment),
                          'max_continuous_seconds': self.stops.max_continuous_seconds},
                'max_routes': self.max_routes, 'seed': self.seed, 'night_correction': self.night_correction,
                'visited_physical_ids': sorted(self.visited), 'history_enabled': self.history_enabled}


@dataclass(frozen=True)
class Anchor:
    coordinate: tuple
    kind: str = 'through'
    heading: float | None = None
    facility_id: str | None = None


@dataclass(frozen=True)
class Facility:
    id: str
    name: str
    entrance: tuple
    equipment: frozenset = frozenset()
    windows: tuple = ()
    source_id: str = ''
    category: str = 'rest_area'


@dataclass(frozen=True)
class Opportunity:
    id: str
    anchors: tuple
    physical: tuple = ()
    category: str = 'sea'
    estimated_value: float = 0
    facility: Facility | None = None
    repair_anchors: tuple = ()
    direction: int = 1
    landscape_value: float | None = None


@dataclass
class Candidate:
    key: str
    visits: tuple
    anchors: tuple
    route: dict
    points: list
    evaluation: dict
    route_id: str = ''
    portfolio_role: str = ''

    @property
    def score(self):
        return self.evaluation['score']
