import math

from recommendation.models import CATEGORIES
from recommendation.experience import experience_value


def weighted_overlap(first, second, downweight=None):
    keys = first.keys() | second.keys()
    weight = lambda k: .2 if downweight and k in downweight else 1.
    intersection = sum(min(first.get(k, 0), second.get(k, 0)) * weight(k) for k in keys)
    union = sum(max(first.get(k, 0), second.get(k, 0)) * weight(k) for k in keys)
    return intersection / union if union else 1.


def dominates(a, b, request):
    first, second = a.evaluation, b.evaluation
    axes = [(first['timing']['total_seconds'], second['timing']['total_seconds'], 30),
            (first['metrics']['load'], second['metrics']['load'], .01),
            (-first['metrics'].get('recommendation_value', 0), -second['metrics'].get('recommendation_value', 0), .01)]
    for k, w in zip(CATEGORIES, request.preferences.weights):
        if w >= .1:
            axes.append((-first['metrics']['effective_m'][k], -second['metrics']['effective_m'][k], 100))
    if request.stops.desired_count:
        axes.append((-first['metrics']['stop_fit'], -second['metrics']['stop_fit'], .01))
    return all(a <= b + tolerance for a, b, tolerance in axes) and any(a < b - tolerance for a, b, tolerance in axes)


def feature_distance(a, b, request):
    first, second = a.evaluation, b.evaluation
    values = [abs(first['timing']['total_seconds'] - second['timing']['total_seconds']) / 7200,
              abs(first['metrics']['load'] - second['metrics']['load']),
              abs(first['metrics'].get('recommendation_value', 0) - second['metrics'].get('recommendation_value', 0))]
    values += [abs(first['metrics']['effective_m'][k] - second['metrics']['effective_m'][k]) / 10000
               for k, w in zip(CATEGORIES, request.preferences.weights) if w >= .1]
    return sum(min(1, v) for v in values) / len(values)


def select_portfolio(archive, request, parameters, diagnostics=None):
    valid = [c for c in archive if not c.evaluation['violations']]
    if diagnostics is not None:
        diagnostics.clear()
        diagnostics.update(archive=len(archive), invalid=len(archive) - len(valid), dominated=0, similar=0, selected=0)
    if not valid:
        return []
    pool = [c for c in valid if not any(
            dominates(other, c, request) for other in valid if other.key != c.key)]
    if not pool:
        pool = [max(valid, key=lambda c: c.score)]
    if diagnostics is not None:
        diagnostics['dominated'] = len(valid) - len(pool)
    selected = []
    theme = '海沿いを満喫' if request.preferences.weights[0] >= .5 else '森林を満喫' if request.preferences.weights[1] >= .5 else '自然を満喫'
    strategies = [
        (theme, lambda c: (experience_value(c, request), c.score, c.key)),
        ('景観と時間のバランス', lambda c: (c.score + .20 * min(
            (1 - weighted_overlap(c.evaluation['physical_lengths'], s.evaluation['physical_lengths']) for s in selected),
            default=0), -c.evaluation['timing']['total_seconds'], c.key)),
        ('寄り道ひかえめ', lambda c: (-c.evaluation['timing']['total_seconds'], c.score, c.key)),
    ]

    def separated(candidate):
        return all(.7 * (1 - weighted_overlap(candidate.evaluation['physical_lengths'], s.evaluation['physical_lengths']))
                   + .3 * feature_distance(candidate, s, request) >= parameters.diversity_floor for s in selected)

    for role, key in strategies:
        if role == '寄り道ひかえめ':
            shortest = max(pool, key=key)
            eligible = [shortest] if shortest not in selected and separated(shortest) else []
        else:
            eligible = [c for c in pool if c not in selected and separated(c)]
        if eligible:
            chosen = max(eligible, key=key)
            chosen.portfolio_role = role
            selected.append(chosen)
        if len(selected) >= request.max_routes:
            if diagnostics is not None:
                diagnostics.update(selected=len(selected), similar=sum(not separated(c) for c in pool if c not in selected))
            return selected
    # Shared roads alone are not proof of topological necessity, so no automatic exemption is applied.
    while len(selected) < request.max_routes:
        ranked = []
        for candidate in pool:
            if any(candidate.key == s.key for s in selected):
                continue
            separation = min(.7 * (1 - weighted_overlap(candidate.evaluation['physical_lengths'], s.evaluation['physical_lengths']))
                             + .3 * feature_distance(candidate, s, request) for s in selected)
            if separation < parameters.diversity_floor:
                continue
            quality = max(0, min(1, (candidate.score + .75) / 2.15))
            ranked.append((.7 * quality + .3 * separation, candidate.key, candidate))
        if not ranked:
            break
        chosen = max(ranked, key=lambda item: item[:2])[2]
        chosen.portfolio_role = '別の景観を楽しむ'
        selected.append(chosen)
    if diagnostics is not None:
        diagnostics.update(selected=len(selected), similar=sum(not separated(c) for c in pool if c not in selected))
    return selected
