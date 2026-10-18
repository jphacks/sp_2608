import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const endpoint = process.env.SCENIC_URL || 'http://127.0.0.1:8003';
const token = readFileSync(new URL('../valhalla/.secrets/routing.token', import.meta.url), 'utf8').trim();
const points = JSON.parse(process.env.SCENIC_LOCATIONS || '[{"lat":43.061,"lon":141.354},{"lat":43.196,"lon":140.994}]');
const started = Date.now();
const response = await fetch(endpoint + '/scenic/route', {
  method: 'POST', redirect: 'error', signal: AbortSignal.timeout(65000),
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ origin: points[0], destination: points[1], preference: 'sea', extraSeconds: 900,
    options: { avoidTolls: true, avoidHighways: true, avoidFerries: true } }),
});
assert.equal(response.status, 200, `Scenic API returned HTTP ${response.status}`);
const result = await response.json();
assert.ok(result.alternatives.length >= 1 && result.alternatives.length <= 3);
assert.ok(result.calls <= 25);
for (const alternative of result.alternatives) {
  assert.ok(alternative.route.trip.summary.time <= result.baselineSeconds + 900);
  assert.ok(alternative.metrics.coverage >= 0 && alternative.metrics.coverage <= 1);
}
console.log(JSON.stringify({ elapsedMillis: Date.now() - started, baselineSeconds: result.baselineSeconds, calls: result.calls,
  alternatives: result.alternatives.map(a => ({ seconds: a.route.trip.summary.time, ...a.metrics })) }, null, 2));
