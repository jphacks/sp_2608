"""Replay a stored long-distance request without creating or changing a job."""

import argparse
import json
import os
from pathlib import Path
import sqlite3
from dataclasses import replace
from geometry import distance

from catalog import Catalog
from recommendation.environment import Forecast
from recommendation.models import Parameters, RecommendationRequest
from recommendation.portfolio import weighted_overlap
from recommendation.routing import ValhallaAdapter
from recommendation.search import CandidatePlanner
from recommendation.store import ScenicStore


def check(state, seconds, extra_minutes=None, evaluations=200, latest_failed=False, night_correction=None):
    db = sqlite3.connect(Path(state).resolve().as_uri() + '?mode=ro', uri=True)
    db.execute('PRAGMA temp_store=MEMORY')
    try:
        ids = [r[0] for r in db.execute('SELECT id FROM job ORDER BY created DESC LIMIT 64')]
        for ident in ids:
            payload, raw = db.execute('SELECT payload,result FROM job WHERE id=?', (ident,)).fetchone()
            old = json.loads(raw)
            if (old.get('status') == 'infeasible' if latest_failed else old.get('baseline', {}).get('distance_m', 0) >= 200000):
                query = RecommendationRequest.parse(json.loads(payload))
                break
        else:
            raise ValueError('No matching stored request')
    finally:
        db.close()
    if extra_minutes is not None:
        query = replace(query, extra_seconds=extra_minutes * 60)
    if night_correction is not None:
        query = replace(query, night_correction=night_correction == 'on')
    catalog = Catalog(os.environ.get('SCENIC_DATABASE', '/data/scenic-hokkaido.sqlite'))
    try:
        store = ScenicStore(catalog, recommended_roads=os.environ.get('SCENIC_RECOMMENDED_ROADS'))
        planner = CandidatePlanner(store, ValhallaAdapter('http://valhalla:8002', store.version),
            Forecast('disabled', query.departure.isoformat()), Parameters(max_seconds=seconds, max_evaluations=evaluations))
        def progress(planner, baseline, _):
            print(json.dumps({'stage': 'progress', 'feasible': len(planner.archive), 'search': planner.budget.status()}), flush=True)
        baseline, selected, _ = planner.plan(query, progress)
        landmarks = {'iwanai': (42.981, 140.51), 'suttsu': (42.79, 140.23), 'setana': (42.45, 139.86)}
        landmark_candidates = {name: [{'sea_km': c.evaluation['metrics']['sea_proximity_m'] / 1000,
            'score': c.score, 'seconds': c.evaluation['timing']['driving_seconds']}
            for c in sorted(planner.archive.values(), key=lambda c: -c.evaluation['metrics']['sea_proximity_m'])
            if min(distance(point, p) for p in c.points) < 5000][:3] for name, point in landmarks.items()}
        print(json.dumps({'baseline_seconds': planner.baseline_seconds, 'extra_seconds': query.extra_seconds,
            'night_correction': query.night_correction,
            'search': planner.budget.status(), 'feasible': len(planner.archive), 'landmark_candidates': landmark_candidates,
            'selected': [{'seconds': c.evaluation['timing']['driving_seconds'], 'score': c.score,
                'violations': c.evaluation['violations'], 'visits': len(c.visits), 'role': c.portfolio_role,
                'sea_km': c.evaluation['metrics']['sea_proximity_m'] / 1000,
                'near_landmarks': [name for name, point in landmarks.items()
                                   if min(distance(point, p) for p in c.points) < 5000]} for c in selected],
            'pairwise_overlap': [weighted_overlap(a.evaluation['physical_lengths'], b.evaluation['physical_lengths'])
                for i, a in enumerate(selected) for b in selected[i + 1:]]}), flush=True)
    finally:
        catalog.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', default='/state/recommendations.sqlite')
    parser.add_argument('--seconds', type=float, default=90)
    parser.add_argument('--extra-minutes', type=int, choices=range(361))
    parser.add_argument('--evaluations', type=int, choices=range(1, 201), default=200)
    parser.add_argument('--latest-failed', action='store_true')
    parser.add_argument('--night-correction', choices=('on', 'off'))
    args = parser.parse_args()
    check(args.state, args.seconds, args.extra_minutes, args.evaluations, args.latest_failed, args.night_correction)
