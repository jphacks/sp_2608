import math

from geometry import samples


def preference_value(road, preference):
    weights = {'sea': (.8, .0), 'forest': (.0, .8), 'balanced': (.4, .4)}[preference]
    return weights[0] * road['sea'] + weights[1] * road['forest'] + .2 * road['nature']


def evaluate(points, catalog, preference, seconds, baseline_seconds):
    seen, total, covered, repeat, burden = {}, 0., 0., 0., 0.
    continuous, longest, sea, forest, nature = 0., 0., 0., 0., 0.
    for point, length in samples(points):
        total += length
        road = catalog.nearest(point)
        if road is None:
            continuous = 0
            continue
        covered += length
        burden += length * road['load']
        previous = seen.get(road['id'], 0.)
        unique = min(length, max(0., road['length'] - previous))
        seen[road['id']] = previous + length
        repeat += length - unique
        sea += unique * road['sea']
        forest += unique * road['forest']
        nature += unique * road['nature']
        continuous = continuous + unique if preference_value(road, preference) >= .45 and unique > 0 else 0
        longest = max(longest, continuous)
    sea_weight, forest_weight = {'sea': (1., .0), 'forest': (.0, 1.), 'balanced': (.5, .5)}[preference]
    # These are independent dimensions: coverage is reported, never treated as absence of scenery.
    utility = sea_weight * (1 - math.exp(-sea / 5000)) + forest_weight * (1 - math.exp(-forest / 5000))
    score = (100 * utility + 15 * (1 - math.exp(-nature / 10000))
             + 15 * (1 - math.exp(-longest / 2000)) - burden / 1000 * 2
             - repeat / 1000 * 12 - max(0, seconds - baseline_seconds) / 60 * .35)
    return {'score': score, 'seaMeters': round(sea), 'forestMeters': round(forest),
            'continuousMeters': round(longest), 'repeatedMeters': round(repeat),
            'coverage': covered / total if total else 0, 'edges': set(seen)}


def overlap(a, b):
    union = a | b
    return len(a & b) / len(union) if union else 1.


def select_diverse(candidates, count=3):
    ordered = sorted(candidates, key=lambda c: (-c['metrics']['score'], c['seconds'], c['key']))
    frontier = [c for c in ordered if not any(
        other['seconds'] <= c['seconds'] and
        other['metrics']['seaMeters'] >= c['metrics']['seaMeters'] and
        other['metrics']['forestMeters'] >= c['metrics']['forestMeters'] and
        other['metrics']['score'] > c['metrics']['score']
        for other in ordered if other is not c)]
    selected = []
    for candidate in frontier:
        if all(overlap(candidate['metrics']['edges'], old['metrics']['edges']) < .85 for old in selected):
            selected.append(candidate)
            if len(selected) == count:
                break
    return selected
