import math
import time
from urllib.error import HTTPError

from geometry import distance, route_points
from scoring import evaluate, preference_value, select_diverse


class NoRoute(Exception):
    pass


def scenic_sections(catalog, bounds, preference, maximum=6):
    roads = catalog.roads(bounds)
    grouped = {}
    for road in roads:
        if preference_value(road, preference) >= .45 and road['load'] < .8:
            grouped.setdefault(road['way'], []).append(road)
    runs = []
    for way, pieces in grouped.items():
        run = []
        for road in sorted(pieces, key=lambda r: r['sequence']):
            if run and road['sequence'] != run[-1]['sequence'] + 1:
                runs.append(run)
                run = []
            run.append(road)
        if run:
            runs.append(run)
    sections = []
    for run in runs:
        # Bound each target corridor; long roads become several independent opportunities.
        for start in range(0, len(run), 20):
            part = run[start:start + 20]
            length = sum(r['length'] for r in part)
            if length < 200:
                continue
            controls = [(part[0]['lat1'], part[0]['lon1'])]
            controls += [(r['lat2'], r['lon2']) for i, r in enumerate(part) if i % 5 == 4 or i == len(part) - 1]
            sections.append({'id': f"{part[0]['way']}:{part[0]['sequence']}", 'points': controls,
                             'edges': {r['id'] for r in part},
                             'value': sum(preference_value(r, preference) * r['length'] for r in part)})
    result = []
    for section in sorted(sections, key=lambda s: (-s['value'], s['id'])):
        midpoint = section['points'][len(section['points']) // 2]
        if all(distance(midpoint, old['points'][len(old['points']) // 2]) > 800 for old in result):
            result.append(section)
            if len(result) == maximum:
                break
    return result


class ScenicPlanner:
    def __init__(self, catalog, route, now=time.monotonic, max_calls=25, beam_width=4):
        self.catalog, self.route, self.now = catalog, route, now
        self.max_calls, self.beam_width = max_calls, beam_width

    def plan(self, origin, destination, options, preference, extra_seconds):
        deadline = self.now() + 45
        calls, cache = 0, {}

        def calculate(controls):
            nonlocal calls
            key = tuple(controls)
            if key in cache:
                return cache[key]
            if calls >= self.max_calls or self.now() >= deadline:
                return None
            calls += 1
            try:
                response = self.route([origin, *controls, destination], options, max(.1, min(8, deadline - self.now())))
                summary = response['trip']['summary']
                seconds = float(summary['time'])
                if not math.isfinite(seconds) or seconds <= 0:
                    raise NoRoute()
                if seconds > 14400 or float(summary['length']) > 250:
                    raise NoRoute()
                points = route_points(response)
                candidate = {'route': response, 'points': points, 'seconds': seconds,
                             'controls': controls, 'key': key}
                cache[key] = candidate
                return candidate
            except HTTPError as error:
                if error.code not in (400, 404) and calls == 1:
                    raise
            except OSError:
                if calls == 1:
                    raise
            except NoRoute:
                pass
            cache[key] = None
            return None

        base = calculate([])
        if base is None:
            raise NoRoute()
        budget = base['seconds'] + extra_seconds
        base['metrics'] = evaluate(base['points'], self.catalog, preference, base['seconds'], base['seconds'])
        candidates = [base]
        # Search only a pilot region; never pretend unrated roads have been evaluated.
        padding = min(.5, .03 + extra_seconds * 25 / 111195)
        bounds = (min(p[1] for p in base['points']) - padding, min(p[0] for p in base['points']) - padding,
                  max(p[1] for p in base['points']) + padding, max(p[0] for p in base['points']) + padding)
        sections = scenic_sections(self.catalog, bounds, preference)
        beam = [(base, frozenset())]
        for _ in range(2):
            expanded = []
            for parent, used in beam:
                for section in sections:
                    if section['id'] in used:
                        continue
                    for reverse in (False, True):
                        controls = parent['controls'] + (list(reversed(section['points'])) if reverse else section['points'])
                        candidate = calculate(controls)
                        if candidate is None or candidate['seconds'] > budget:
                            continue
                        metrics = evaluate(candidate['points'], self.catalog, preference, candidate['seconds'], base['seconds'])
                        candidate['metrics'] = metrics
                        # Validate the returned route, not just the requested entrance and exit.
                        if len(metrics['edges'] & section['edges']) / len(section['edges']) < .8:
                            continue
                        candidates.append(candidate)
                        expanded.append((candidate, used | {section['id']}))
                    if calls >= self.max_calls or self.now() >= deadline:
                        break
            beam = sorted(expanded, key=lambda pair: -pair[0]['metrics']['score'])[:self.beam_width]
            if not beam or calls >= self.max_calls or self.now() >= deadline:
                break
        choices = select_diverse(candidates)
        return {'baselineSeconds': math.ceil(base['seconds']), 'calls': calls,
                'catalog': self.catalog.source,
                'alternatives': [self.present(c, base['seconds']) for c in choices]}

    @staticmethod
    def present(candidate, baseline):
        metrics = {key: value for key, value in candidate['metrics'].items() if key != 'edges'}
        return {'route': candidate['route'], 'viaPoints': [dict(lat=p[0], lon=p[1]) for p in candidate['controls']],
                'extraSeconds': max(0, math.ceil(candidate['seconds'] - baseline)), 'metrics': metrics}
