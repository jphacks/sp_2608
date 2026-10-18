import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const endpoint = process.env.SCENIC_URL || 'http://127.0.0.1:8003';
const token = readFileSync(new URL('../valhalla/.secrets/routing.token', import.meta.url), 'utf8').trim();
const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
let lastCall = 0;
const call = async (path, method = 'GET', body) => {
  await new Promise(resolve => setTimeout(resolve, Math.max(0, lastCall + 550 - Date.now())));
  lastCall = Date.now();
  const response = await fetch(endpoint + path, { method, headers, redirect: 'error',
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000) });
  assert.ok(response.ok, `HTTP ${response.status}`);
  return response.json();
};
const cases = process.env.SCENIC_CASES ? JSON.parse(readFileSync(process.env.SCENIC_CASES, 'utf8')) : [{ name: 'sapporo-otaru', request: {
  origin: { lat: 43.061, lon: 141.354 }, destination: { lat: 43.196, lon: 140.994 },
  mode: 'point_to_point', departure_time: process.env.SCENIC_DEPARTURE || new Date().toISOString(),
  budget: { max_extra_driving_seconds: 900, max_total_seconds: 7200 },
  preferences: { sea: .8, forest: .2, variety: .6 },
  constraints: { allow_tolls: false, allow_highways: false, allow_ferries: false, allow_unpaved: true }, seed: 42,
} }];
if (process.env.SCENIC_EXPECT_DATA === '1') {
  const status = await call('/route-recommendations/data-status');
  assert.equal(status.complete, false);
  for (const id of ['gsi-terrain', 'osm-context', 'esa-worldcover']) assert.equal(status.sources.find(source => source.id === id)?.status, 'loaded');
  assert.ok(status.sources.find(source => source.id === 'gsi-terrain').counts.horizon > 0);
  assert.ok(status.sources.find(source => source.id === 'osm-context').counts.by_kind.viewpoint > 0);
  assert.ok(status.gaps.some(gap => gap.id === 'jaxa-land-cover' && gap.status === 'excluded_by_scope'));
  console.log(JSON.stringify({ dataStatus: 'loaded-with-explicit-gaps', sources: status.sources.map(source => [source.id, source.status]) }));
}
async function testCase(test) {
  const started = Date.now();
  const payload = { ...test.request, departure_time: process.env.SCENIC_DEPARTURE || test.request.departure_time };
  const job = await call('/route-recommendations', 'POST', payload);
  try {
    let result;
    let firstResultMillis;
    let provisional;
    const improvingRevisions = new Set();
    for (let i = 0; i < 70; i++) {
      result = await call(`/route-recommendations/${job.request_id}?view=status`);
      if (['complete', 'limited', 'failed', 'infeasible'].includes(result.status)) break;
      if (result.status === 'improving') {
        improvingRevisions.add(result.revision);
        if (!provisional) {
          const full = await call(`/route-recommendations/${job.request_id}`);
          assert.ok(full.routes.length > 0);
          provisional = full.routes[0];
          firstResultMillis = Date.now() - started;
          const earlySession = await call('/navigation-sessions', 'POST', {
            route_id: provisional.route_id, revision: provisional.revision,
          });
          assert.equal(earlySession.route_id, provisional.route_id);
          console.log(JSON.stringify({ case: test.name, firstResultMillis, provisionalRoutes: full.routes.length }));
        }
      }
      if (i % 10 === 0) console.log(JSON.stringify({ case: test.name, status: result.status, elapsedMillis: Date.now() - started }));
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
    assert.ok(['complete', 'limited'].includes(result.status), JSON.stringify({ case: test.name, status: result.status, error: result.error }));
    assert.ok(!result.error, `${test.name}: search failed after provisional results: ${result.error}`);
    assert.ok(result.routes.length >= 1 && result.routes.length <= (payload.max_routes ?? 3));
    assert.equal(new Set(result.routes.map(r => r.route_id)).size, result.routes.length);
    if (test.expect_features) {
      assert.equal(result.parameters_version, '2.8-terrain-context');
      for (const category of test.expect_features) {
        assert.ok(result.routes.some(route => route.metrics.effective_m[category] > 0), `${test.name}: no scored ${category}`);
      }
      console.log(JSON.stringify({ case: test.name, scoredFeatures: result.routes.map(route =>
        Object.fromEntries(['lake', 'river', 'valley', 'farmland', 'urban'].map(category =>
          [category, Math.round(route.metrics.effective_m[category])])) ) }));
    }
    if (provisional) {
      const retained = await call(`/routes/${provisional.route_id}/evidence`);
      assert.deepEqual(retained.evidence, provisional.evidence);
      assert.ok(result.revision > 1);
    }
    const baseline = result.baseline.timing?.driving_seconds ?? result.baseline.driving_seconds;
    for (const route of result.routes) {
      assert.equal(route.snapshot_id, result.snapshot_id);
      assert.ok(route.timing.driving_seconds <= baseline + payload.budget.max_extra_driving_seconds);
      if (payload.budget.max_total_seconds !== undefined) assert.ok(route.timing.total_seconds <= payload.budget.max_total_seconds);
      assert.ok(route.metrics.coverage >= .8, `${test.name}: insufficient road feature coverage`);
      assert.equal(route.timing.total_seconds, route.timing.driving_seconds + route.timing.stop_seconds + route.timing.waiting_seconds);
      const ids = new Set(route.evidence.map(e => e.evidence_id));
      for (const reason of route.reasons) {
        assert.ok(reason.evidence_ids.length > 0);
        assert.ok(reason.evidence_ids.every(id => ids.has(id)));
        assert.ok(reason.geometry_ranges.every(r => r.start_m >= 0 && r.end_m <= route.distance_m + 1));
      }
      const ledger = await call(`/routes/${route.route_id}/evidence`);
      assert.equal(ledger.route_id, route.route_id);
      assert.deepEqual(ledger.evidence, route.evidence);
      if (process.env.SCENIC_EXPECT_ENVIRONMENT === '1') assert.ok(route.environment?.timeline?.length > 0);
      if (route.environment) {
        assert.ok(route.environment.timeline.length <= 192);
        assert.ok(route.environment.best_spots.length <= 4);
        for (const section of route.environment.timeline) {
          assert.ok(section.start_m >= 0 && section.end_m <= route.distance_m + 1);
          assert.ok(section.solar_altitude >= -90 && section.solar_altitude <= 90);
          assert.ok(Number.isFinite(Date.parse(section.arrival_time)));
          if (!section.weather_source_id) assert.equal(section.sky, 'unknown');
        }
        for (const spot of route.environment.best_spots) {
          assert.equal(spot.stopping_verified, false);
          assert.ok(spot.start_m <= spot.distance_m && spot.distance_m <= spot.end_m);
          assert.ok(spot.end_m <= route.distance_m + 1);
        }
      }
    }
    const selected = result.routes[0];
    const session = await call('/navigation-sessions', 'POST', { route_id: selected.route_id, revision: selected.revision });
    assert.equal(session.route_id, selected.route_id);
    assert.equal(session.snapshot_id, result.snapshot_id);
    const responseCharacters = JSON.stringify(result).length;
    assert.ok(responseCharacters <= 24_000_000, 'Response exceeds the Android client limit');
    console.log(JSON.stringify({ case: test.name, elapsedMillis: Date.now() - started, firstResultMillis,
      improvingRevisions: [...improvingRevisions], status: result.status, search: result.search,
      responseCharacters, baselineTimelineBlocks: result.baseline.timeline?.length,
      routes: result.routes.map(r => ({ role: result.portfolio_roles?.[r.route_id] ?? r.role, timing: r.timing,
        seaMeters: r.metrics.sea_proximity_m, forestMeters: r.metrics.threshold_m.forest,
        coverage: r.metrics.coverage, reasons: r.reasons.length, timelineBlocks: r.timeline.length })) }, null, 2));
  } finally {
    await call(`/route-recommendations/${job.request_id}`, 'DELETE');
  }
}
for (const test of cases) await testCase(test);
