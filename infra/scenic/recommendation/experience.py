"""Distance-based experience scores shared by evaluation and portfolio selection."""

import math
from recommendation.models import CATEGORIES


def scenery_components(request, parameters, effective, continuous, total_meters, baseline_seconds):
    reference_seconds = baseline_seconds if request.mode != 'round_trip' else request.max_total_seconds
    reference_meters = max(0, reference_seconds or 0) * 15
    scales = [max(base, min(120000, reference_meters * .18)) for base in parameters.saturation]
    distance_value = sum(w * (1 - math.exp(-effective[k] / scale))
                         for k, w, scale in zip(CATEGORIES, request.preferences.weights, scales))
    ratio = sum(w * min(1, effective[k] / max(total_meters, 1))
                for k, w in zip(CATEGORIES, request.preferences.weights))
    continuous_scale = max(3000, min(30000, reference_meters * .04))
    continuity = sum(w * (1 - math.exp(-sum((b - a) * factor for (a, b), factor in
                     zip(continuous[k], (1., .4))) / continuous_scale))
                     for k, w in zip(CATEGORIES, request.preferences.weights))
    return .75 * distance_value + .25 * ratio, continuity, scales


def experience_value(candidate, request):
    ev = candidate.evaluation
    metrics = ev['metrics']
    length = max(metrics.get('unique_driven_m', metrics.get('total_m', 1)), 1)
    weighted = sum(w * metrics['effective_m'][k] for k, w in zip(CATEGORIES, request.preferences.weights))
    continuity = sum(w * sum((b - a) * f for (a, b), f in zip(metrics.get('continuous', {}).get(k, []), (1., .4)))
                     for k, w in zip(CATEGORIES, request.preferences.weights))
    # The scale depends on the request, never on what other candidates happened to be found.
    scales = metrics.get('saturation_m', {})
    scale = max(1000, sum(w * scales.get(k, 30000) for k, w in zip(CATEGORIES, request.preferences.weights)))
    return (.65 * (1 - math.exp(-weighted / scale)) + .2 * min(1, weighted / length)
            + .15 * (1 - math.exp(-continuity / max(3000, scale * .25)))
            - .1 * metrics.get('repeated_m', 0) / max(metrics.get('total_m', length), 1)
            - .05 * metrics['load'])
