"""Smoke-test registry membership against short routes from a local Valhalla."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
import time
import urllib.request

from catalog import Catalog
from geometry import decode_shape
from recommendation.environment import Forecast
from recommendation.evaluator import RouteEvaluator
from recommendation.models import Parameters, RecommendationRequest
from recommendation.store import ScenicStore


def check(catalog_path, registry_path, routing_url, interval_seconds=6.1):
    catalog = Catalog(catalog_path)
    registry = sqlite3.connect(Path(registry_path).resolve().as_uri() + '?mode=ro', uri=True)
    try:
        store = ScenicStore(catalog, recommended_roads=registry_path)
        instant = datetime.now(timezone.utc).isoformat()
        evaluator = RouteEvaluator(store, Parameters(), Forecast('disabled', instant))
        passed, failed = 0, []
        previous_request = None
        records = [json.loads(row[0]) for row in registry.execute(
            "SELECT payload FROM recommendation WHERE status='active' ORDER BY id")]
        for record in records:
            ids = [r[0] for r in registry.execute('SELECT road_id FROM membership WHERE recommendation_id=? ORDER BY road_id',
                                                 (record['id'],))]
            row = None
            for index in sorted(range(len(ids)), key=lambda i: abs(i - len(ids) // 2)):
                candidate = catalog.db.execute('SELECT * FROM road WHERE id=?', (ids[index],)).fetchone()
                if not store.road(candidate)['tunnel']:
                    row = candidate
                    break
            if row is None:
                failed.append(record['id'])
                print(json.dumps({'id': record['id'], 'error': 'No above-ground road sample', 'ok': False}), flush=True)
                continue
            ident = row['id']
            neighbors = catalog.db.execute('SELECT * FROM road WHERE id BETWEEN ? AND ? AND way=? ORDER BY sequence',
                                           (ident - 3, ident + 3, row['way'])).fetchall()
            start = (neighbors[0]['lat1'], neighbors[0]['lon1'])
            end = (neighbors[-1]['lat2'], neighbors[-1]['lon2'])
            if store.road(row)['oneway'] == -1:
                start, end = end, start
            locations = [dict(zip(('lat', 'lon'), p)) for p in (start, end)]
            body = {'locations': locations, 'costing': 'auto', 'shape_format': 'polyline6'}
            try:
                if previous_request is not None:
                    time.sleep(max(0, previous_request + interval_seconds - time.monotonic()))
                previous_request = time.monotonic()
                request = urllib.request.Request(routing_url.rstrip('/') + '/route',
                    data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
                with urllib.request.urlopen(request, timeout=30) as response:
                    route = json.load(response)
                points = []
                for leg in route['trip']['legs']:
                    points.extend(decode_shape(leg['shape']))
                query = RecommendationRequest.parse({'origin': locations[0], 'destination': locations[1],
                    'departure_time': instant, 'budget': {'max_extra_driving_seconds': 900},
                    'preferences': {'sea': 1}, 'constraints': {'allow_unpaved': True}})
                result = evaluator.evaluate(query, route, points, (), route['trip']['summary']['time'])
                present = any(r['id'] == record['id'] for b in result['timeline'] for r in b['recommended_roads'])
                ok = present and result['components']['R'] > 0 and not result['violations']
                report = {'id': record['id'], 'timeline_present': present, 'violations': result['violations'], 'ok': ok}
            except (OSError, ValueError, KeyError) as error:
                ok = False
                report = {'id': record['id'], 'error': str(error), 'ok': False}
            print(json.dumps(report, ensure_ascii=False), flush=True)
            passed += int(ok)
            if not ok:
                failed.append(record['id'])
        print(json.dumps({'passed': passed, 'failed': failed}), flush=True)
        return not failed
    finally:
        registry.close()
        catalog.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('catalog')
    parser.add_argument('registry')
    parser.add_argument('--routing-url', default='http://127.0.0.1:8002')
    parser.add_argument('--interval-seconds', type=float, default=6.1)
    args = parser.parse_args()
    if not 0 <= args.interval_seconds <= 60:
        parser.error('interval-seconds must be between 0 and 60')
    raise SystemExit(0 if check(args.catalog, args.registry, args.routing_url, args.interval_seconds) else 1)
