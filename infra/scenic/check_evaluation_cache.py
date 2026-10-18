"""Compare cached and uncached evaluation of stored routes without modifying jobs."""

import argparse
from contextlib import nullcontext
import json
import math
import os
from pathlib import Path
import sqlite3
import time
from unittest.mock import patch

from catalog import Catalog
import geometry
from recommendation.environment import Forecast
from recommendation.evaluator import RouteEvaluator
from recommendation.models import Parameters, RecommendationRequest
from recommendation.store import ScenicStore, bearing


def check(state):
    with sqlite3.connect(Path(state).resolve().as_uri() + '?mode=ro', uri=True) as db:
        db.execute('PRAGMA temp_store=MEMORY')
        for ident, in db.execute('SELECT id FROM job ORDER BY created DESC LIMIT 64').fetchall():
            raw_query, raw_result = db.execute('SELECT payload,result FROM job WHERE id=?', (ident,)).fetchone()
            result = json.loads(raw_result)
            routes = [r for r in result.get('routes', []) if not r.get('schedule')][:3]
            if routes and result.get('baseline', {}).get('timing'):
                query = RecommendationRequest.parse(json.loads(raw_query))
                break
        else:
            raise ValueError('No stored routes without stops')
    baseline_seconds = result['baseline']['timing']['driving_seconds']
    evaluations, timings = {}, {}
    for cached in (False, True):
        catalog = Catalog(os.environ.get('SCENIC_DATABASE', '/data/scenic-hokkaido.sqlite'))
        try:
            store = ScenicStore(catalog, recommended_roads=os.environ.get('SCENIC_RECOMMENDED_ROADS'))
            evaluator = RouteEvaluator(store, Parameters(), Forecast('disabled', query.departure.isoformat()))
            if not cached:
                def original_match(point):
                    dy = 25 / 111195
                    dx = dy / max(.1, math.cos(math.radians(point[0])))
                    catalog.match_queries += 1
                    return catalog.roads((point[1] - dx, point[0] - dy, point[1] + dx, point[0] + dy), 100)
                catalog.match_roads = original_match
                evaluator.segment_geometry = lambda a, b: (bearing(a, b), tuple(geometry.samples([a, b], evaluator.parameters.sample_meters)))
                original_geometry = evaluator.route_geometry
                def uncached_geometry(points):
                    evaluator.geometry_cache.clear()
                    return original_geometry(points)
                evaluator.route_geometry = uncached_geometry
            context = nullcontext() if cached else patch('geometry._decoded_shape', geometry._decoded_shape.__wrapped__)
            started = time.monotonic()
            with context:
                measured = [evaluator.evaluate(query, r['route'], geometry.route_points(r['route']), (), baseline_seconds)
                            for _ in range(2) for r in routes]
            elapsed = time.monotonic() - started
            evaluations[cached] = measured
            timings[cached] = {'seconds': round(elapsed, 3), 'match_sql_queries': catalog.match_queries,
                               'match_tile_hits': catalog.match_tile_hits}
        finally:
            catalog.close()
    assert evaluations[False] == evaluations[True], 'Evaluation changed with caching'
    print(json.dumps({'route_count': len(routes), 'passes': 2, 'evaluation_identical': True,
                      'uncached': timings[False], 'cached': timings[True]}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', default='/state/recommendations.sqlite')
    check(parser.parse_args().state)
