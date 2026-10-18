import argparse
from dataclasses import replace
import json
import math
from pathlib import Path
import statistics
import time

from catalog import Catalog
from recommendation.environment import OpenMeteoForecasts
from recommendation.evidence import EvidenceBuilder
from recommendation.models import Infeasible, Parameters, RecommendationRequest
from recommendation.routing import ValhallaAdapter
from recommendation.search import CandidatePlanner
from recommendation.store import ScenicStore


def verify_response(response):
    mismatches = []
    sources = {s['source_id'] for s in response['sources']}
    for route in response['routes']:
        ledger = {e['evidence_id']: e for e in route['evidence']}
        if route['snapshot_id'] != response['snapshot_id']:
            mismatches.append('snapshot')
        for reason in route['reasons']:
            if not reason['evidence_ids'] or any(i not in ledger for i in reason['evidence_ids']):
                mismatches.append('evidence_reference')
            for evidence_id in reason['evidence_ids']:
                item = ledger.get(evidence_id, {})
                if item.get('route_id') != route['route_id'] or item.get('snapshot_id') != route['snapshot_id']:
                    mismatches.append('evidence_revision')
                if not set(item.get('source_ids', [])) <= sources:
                    mismatches.append('source')
            for span in reason['geometry_ranges']:
                if not 0 <= span['start_m'] <= span['end_m'] <= route['distance_m'] + 1:
                    mismatches.append('range')
    return mismatches


def run(args):
    output = Path(args.output)
    if output.exists() or args.repeats < 1:
        raise ValueError('Choose a new output filename and at least one repetition')
    cases = json.loads(Path(args.requests).read_text())
    report = []
    for case in cases:
        request = RecommendationRequest.parse(case['request'])
        for strategy in args.strategies.split(','):
            if strategy not in ('baseline', 'beam', 'alns'):
                raise ValueError('Supported strategies: baseline,beam,alns')
            for repeat in range(args.repeats):
                catalog = Catalog(args.catalog)
                started = time.monotonic()
                try:
                    store = ScenicStore(catalog, args.supplements)
                    parameters = Parameters(max_seconds=args.seconds, max_evaluations=args.evaluations,
                                            alns_iterations=0 if strategy != 'alns' else 300)
                    forecast = OpenMeteoForecasts(False).snapshot(request)
                    planner = CandidatePlanner(store, ValhallaAdapter(args.endpoint, store.version), forecast, parameters)
                    if strategy == 'baseline':
                        baseline = planner.evaluate(request, ())
                        if baseline is None:
                            raise Infeasible('baseline_infeasible')
                        selected, comparisons = [baseline], []
                    else:
                        baseline, selected, comparisons = planner.plan(request)
                    response = EvidenceBuilder(store, 'benchmark-snapshot').response(planner, request, selected, baseline, comparisons)
                    mismatches = verify_response(response)
                    violations = sum(len(c.evaluation['violations']) for c in selected)
                    row = {'case': case['name'], 'strategy': strategy, 'repeat': repeat,
                        'elapsed_seconds': time.monotonic() - started, 'search': planner.budget.status(),
                        'hard_violations': violations, 'evidence_mismatches': len(mismatches),
                        'routes': [{'score': c.score, 'total_seconds': c.evaluation['timing']['total_seconds'],
                                    'sea_effective_m': c.evaluation['metrics']['effective_m']['sea'],
                                    'load': c.evaluation['metrics']['load']} for c in selected], 'status': response['status']}
                except Infeasible as error:
                    row = {'case': case['name'], 'strategy': strategy, 'status': 'infeasible', 'error': error.code,
                           'elapsed_seconds': time.monotonic() - started}
                finally:
                    catalog.close()
                report.append(row)
                print(json.dumps({k: v for k, v in row.items() if k != 'routes'}), flush=True)
    summary = {}
    for strategy in args.strategies.split(','):
        rows = [r for r in report if r['strategy'] == strategy]
        times = sorted(r['elapsed_seconds'] for r in rows)
        summary[strategy] = {'sample_count': len(times), 'p50_seconds': statistics.median(times),
                            'p95_seconds': times[max(0, math.ceil(.95 * len(times)) - 1)],
                            'hard_violations': sum(r.get('hard_violations', 0) for r in rows),
                            'evidence_mismatches': sum(r.get('evidence_mismatches', 0) for r in rows)}
    output.write_text(json.dumps({'summary': summary, 'results': report}, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--catalog', required=True)
    parser.add_argument('--requests', required=True)
    parser.add_argument('--endpoint', default='http://valhalla:8002')
    parser.add_argument('--supplements')
    parser.add_argument('--strategies', default='baseline,beam,alns')
    parser.add_argument('--repeats', type=int, default=1)
    parser.add_argument('--seconds', type=float, default=90)
    parser.add_argument('--evaluations', type=int, default=200)
    parser.add_argument('--output', required=True)
    run(parser.parse_args())
