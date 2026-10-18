from dataclasses import dataclass
from datetime import datetime, timezone
import json
import math
import calendar
import threading
import time
from urllib.parse import urlencode
from urllib.request import urlopen

from geometry import distance
from recommendation.models import identity

WEATHER_BOUNDS = {
    'visibility': (0, 200000), 'precipitation': (0, 1000), 'cloud_cover': (0, 100),
    'wind_speed_10m': (0, 150), 'wind_gusts_10m': (0, 200), 'wind_direction_10m': (0, 360),
    'temperature_2m': (-100, 70), 'relative_humidity_2m': (0, 100),
    'precipitation_probability': (0, 100), 'snowfall': (0, 200), 'snow_depth': (0, 100),
    'weather_code': (0, 99), 'shortwave_radiation': (0, 2000),
}


def solar_position(coordinate, moment):
    """Approximate solar altitude/azimuth; not a visibility or glare measurement."""
    utc = moment.astimezone(timezone.utc)
    day = utc.timetuple().tm_yday
    hour = utc.hour + utc.minute / 60 + utc.second / 3600
    gamma = 2 * math.pi / (366 if calendar.isleap(utc.year) else 365) * (day - 1 + (hour - 12) / 24)
    equation = 229.18 * (.000075 + .001868 * math.cos(gamma) - .032077 * math.sin(gamma)
                        - .014615 * math.cos(2 * gamma) - .040849 * math.sin(2 * gamma))
    declination = (.006918 - .399912 * math.cos(gamma) + .070257 * math.sin(gamma)
                   - .006758 * math.cos(2 * gamma) + .000907 * math.sin(2 * gamma)
                   - .002697 * math.cos(3 * gamma) + .00148 * math.sin(3 * gamma))
    hour_angle = math.radians((hour * 60 + equation + 4 * coordinate[1]) / 4 - 180)
    latitude = math.radians(coordinate[0])
    cosine = math.sin(latitude) * math.sin(declination) + math.cos(latitude) * math.cos(declination) * math.cos(hour_angle)
    altitude = math.degrees(math.asin(max(-1, min(1, cosine))))
    azimuth = (math.degrees(math.atan2(math.sin(hour_angle), math.cos(hour_angle) * math.sin(latitude)
                                    - math.tan(declination) * math.cos(latitude))) + 180) % 360
    return altitude, azimuth


@dataclass(frozen=True)
class Forecast:
    source_id: str
    acquired_at: str
    cells: tuple = ()

    def at(self, coordinate, moment):
        if not self.cells:
            return None
        cell = min(self.cells, key=lambda c: distance(coordinate, c['coordinate']))
        if distance(coordinate, cell['coordinate']) > 50000:
            return None
        records = cell['records']
        instant = moment.timestamp()
        if not records or instant < records[0]['time'] or instant > records[-1]['time']:
            return None
        for first, second in zip(records, records[1:]):
            if first['time'] <= instant <= second['time']:
                fraction = (instant - first['time']) / max(1, second['time'] - first['time'])
                result = {key: first[key] + (second[key] - first[key]) * fraction
                          for key in WEATHER_BOUNDS if key not in ('weather_code', 'wind_direction_10m')
                          and first.get(key) is not None and second.get(key) is not None}
                if first.get('weather_code') is not None and second.get('weather_code') is not None:
                    result['weather_code'] = first['weather_code'] if fraction < .5 else second['weather_code']
                if first.get('wind_direction_10m') is not None and second.get('wind_direction_10m') is not None:
                    delta = (second['wind_direction_10m'] - first['wind_direction_10m'] + 180) % 360 - 180
                    result['wind_direction_10m'] = (first['wind_direction_10m'] + delta * fraction) % 360
                return result
        return records[-1] if instant == records[-1]['time'] else None

    def payload(self):
        return {'source_id': self.source_id, 'issue_time': None, 'acquired_at': self.acquired_at, 'cells': self.cells}


class OpenMeteoForecasts:
    def __init__(self, enabled=False, fetch=None):
        self.enabled = enabled
        self.fetch = fetch or self._fetch
        self.cache = {}
        self.lock = threading.Lock()

    @staticmethod
    def _fetch(url, timeout):
        with urlopen(url, timeout=timeout) as response:
            raw = response.read(2_000_001)
        if len(raw) > 2_000_000:
            raise ValueError('Forecast response too large')
        return json.loads(raw)

    def snapshot(self, request, timeout=6):
        acquired = datetime.now(timezone.utc).isoformat()
        if not self.enabled:
            return Forecast('weather-disabled', acquired)
        steps = min(6, max(1, math.ceil(distance(request.origin, request.destination) / 60000)))
        points = list(dict.fromkeys(tuple(round(v, 1) for v in point) for point in
            [request.origin, request.destination] + [tuple(a + (b - a) * i / steps
              for a, b in zip(request.origin, request.destination)) for i in range(1, steps)]))
        cells = []
        deadline = time.monotonic() + timeout
        for point in points:
            key = (*point, request.departure.date().isoformat())
            with self.lock:
                cached = self.cache.get(key)
            if cached and time.monotonic() - cached[0] <= 900:
                cells.append(cached[1])
                continue
            if time.monotonic() >= deadline:
                break
            try:
                query = urlencode({'latitude': point[0], 'longitude': point[1], 'hourly':
                    ','.join(WEATHER_BOUNDS), 'timeformat': 'unixtime',
                    'wind_speed_unit': 'ms', 'timezone': 'UTC', 'forecast_days': 7})
                body = self.fetch('https://api.open-meteo.com/v1/forecast?' + query, max(.1, deadline - time.monotonic()))
                hourly = body['hourly']
                records = []
                for i, instant in enumerate(hourly['time']):
                    record = {'time': instant}
                    for variable, (low, high) in WEATHER_BOUNDS.items():
                        value = hourly.get(variable, [None] * len(hourly['time']))[i]
                        record[variable] = value if not isinstance(value, bool) and isinstance(value, (float, int)) and math.isfinite(value) and low <= value <= high else None
                    records.append(record)
                cell = {'coordinate': (float(body['latitude']), float(body['longitude'])), 'records': records}
                with self.lock:
                    if len(self.cache) >= 128:
                        self.cache.clear()
                    self.cache[key] = (time.monotonic(), cell)
                cells.append(cell)
            except (OSError, ValueError, KeyError, TypeError, IndexError):
                continue
        return Forecast('open-meteo:' + identity(cells), acquired, tuple(cells))
