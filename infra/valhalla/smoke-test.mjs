import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const endpoint = process.env.VALHALLA_URL ?? 'http://127.0.0.1:8002';
const token = process.env.ROUTING_TOKEN_FILE ? readFileSync(process.env.ROUTING_TOKEN_FILE, 'utf8').trim() : '';
const url = new URL(endpoint);
assert.ok(!token || url.protocol === 'https:' || ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname), 'Tokens require HTTPS outside loopback');
const response = await fetch(endpoint + '/route', {
  method: 'POST',
  headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
  redirect: 'error',
  body: JSON.stringify({
    locations: JSON.parse(process.env.TEST_LOCATIONS ?? 'null') ?? [
      { lat: 43.7384, lon: 7.4246, type: 'break' },
      { lat: 43.7355, lon: 7.4206, type: 'through' },
      { lat: 43.7312, lon: 7.4185, type: 'break' },
    ],
    costing: 'auto',
    units: 'kilometers',
    language: 'ja-JP',
  }),
  signal: AbortSignal.timeout(30000),
});
assert.equal(response.status, 200, await response.clone().text());
const { trip } = await response.json();
assert.equal(trip.status, 0);
assert.equal(trip.legs.length, 1, 'Through points must not split the drive into arrival legs');
assert.ok(trip.summary.length > 0);
assert.ok(trip.legs[0].shape.length > 0);
assert.ok(trip.legs[0].maneuvers.some(step => step.instruction.length > 0));
assert.ok(trip.legs[0].maneuvers.some(step => /[\u3040-\u30ff]/u.test(step.instruction)), 'Japanese instructions are required');
console.log(JSON.stringify({ kilometers: trip.summary.length, seconds: trip.summary.time, maneuvers: trip.legs[0].maneuvers.length }));
