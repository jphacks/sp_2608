import { describe, expect, it } from 'vitest';
import { InvalidRecommendation, parseRecommendation } from '../src/recommend-input';

const valid = { scene: { features: { sea_proximity: { v: .8, q: .6 } } }, members: [{ id: 'a', name: 'A' }] };

describe('recommendation input', () => {
  it('keeps optional conditions and valid playlist entries', () => {
    const input = { ...valid, scene: { load: 'high' }, pool: [{ track_id: 'isrc:A', owners: ['a'] }],
      history: [{ track_id: 'isrc:B', owner_id: 'departed' }], top_k: 5, seed: 1 };
    expect(parseRecommendation(input)).toEqual({ ...input, scene: { load: 'high', features: {} } });
    expect(parseRecommendation({ members: valid.members, route: { position_m: 0, route_id: 'route_' + 'a'.repeat(32) } }).route?.position_m).toBe(0);
  });

  it.each([
    { history: {} }, { history: [null] }, { pool: [null] }, { pool: [{ track_id: 'a', owners: {} }] },
    { pool: [{ track_id: 'a', owners: ['unknown'] }] }, { members: [null] }, { members: [] },
    { members: [valid.members[0], valid.members[0]] }, { cabin: 'invalid' }, { cabin: ['quiet'] }, { top_k: 0 }, { seed: 1.5 },
    { scene: null }, { scene: { features: [] } }, { scene: { features: null } }, { scene: { features: { sea_proximity: { v: 2 } } } },
    { scene: { hour: 24 } }, { include_catalog: 'true' }, { recent_track_ids: {} }, { previous_target: { energy: -1 } },
    { route: { position_m: -1, timeline: [] } }, { route: { position_m: 0, timeline: [null] } },
    { route: { position_m: 0, timeline: [{ start_m: 10, end_m: 5, category: 'sea' }] } },
    { route: { position_m: 0, timeline: [{ start_m: 0, end_m: 10, category: 'sea', recommended_rank: ['signature'] }] } },
    { route: { position_m: 0, timeline: [], reveals: [null] } },
    { pool: Array(5001).fill({ track_id: 'a' }) },
  ])('rejects malformed input before database access: %j', (patch) => {
    expect(() => parseRecommendation({ ...valid, ...patch })).toThrow(InvalidRecommendation);
  });
});
