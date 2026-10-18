import { AXES, FEATURE_KEYS, type RecommendInput, type Scene } from './engine';
import { isModeId } from './modes';
import { ROUTE_ID } from './route-source';
import type { ScenicRoute } from './scenic';

type ObjectValue = Record<string, unknown>;
type RouteInput = Omit<ScenicRoute, 'timeline'> & { timeline?: ScenicRoute['timeline']; route_id?: string };
export type RecommendationRequest = Omit<RecommendInput, 'pool' | 'scene'> & {
  pool?: { track_id: string; owners?: string[] }[];
  scene?: Scene;
  route?: RouteInput;
  include_catalog?: boolean;
};

export class InvalidRecommendation extends Error {}

function invalid(field: string): never { throw new InvalidRecommendation(`invalid ${field}`); }
function object(value: unknown, field: string): ObjectValue {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) invalid(field);
  return value as ObjectValue;
}
function array(value: unknown, field: string, max: number): unknown[] {
  if (!Array.isArray(value) || value.length > max) invalid(field);
  return value;
}
function text(value: unknown, field: string, max = 500): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max) invalid(field);
  return value;
}
function number(value: unknown, field: string, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) invalid(field);
  return value;
}
function optionalBoolean(value: unknown, field: string): void {
  if (value !== undefined && typeof value !== 'boolean') invalid(field);
}

function scene(value: unknown): Scene {
  const source = object(value, 'scene');
  const features = object(source.features === undefined ? {} : source.features, 'scene.features');
  for (const [key, value] of Object.entries(features)) {
    if (!FEATURE_KEYS.includes(key as typeof FEATURE_KEYS[number])) invalid(`scene.features.${key}`);
    if (typeof value === 'number') number(value, key, 0, 1);
    else {
      const feature = object(value, key);
      number(feature.v, `${key}.v`, 0, 1);
      if (feature.q !== undefined) number(feature.q, `${key}.q`, 0, 1);
    }
  }
  if (source.load !== undefined && source.load !== 'normal' && source.load !== 'high') invalid('scene.load');
  optionalBoolean(source.curvy, 'scene.curvy');
  optionalBoolean(source.highlight, 'scene.highlight');
  if (source.hour !== undefined) number(source.hour, 'scene.hour', 0, 23);
  return { ...source, features } as Scene;
}

function route(value: unknown): RouteInput {
  const source = object(value, 'route');
  number(source.position_m, 'route.position_m', 0, 2_000_000);
  if (source.next_duration_ms !== undefined) number(source.next_duration_ms, 'route.next_duration_ms', 1, 3_600_000);
  if (source.route_id !== undefined && (typeof source.route_id !== 'string' || !ROUTE_ID.test(source.route_id))) invalid('route.route_id');
  if (source.timeline === undefined && source.route_id === undefined) invalid('route.timeline or route.route_id');
  if (source.timeline !== undefined) {
    for (const item of array(source.timeline, 'route.timeline', 20_000)) {
      const block = object(item, 'route.timeline[]');
      const start = number(block.start_m, 'start_m', 0, 2_000_000);
      if (number(block.end_m, 'end_m', 0, 2_000_000) <= start) invalid('end_m');
      text(block.category, 'category', 40);
      if (block.confidence !== undefined) number(block.confidence, 'confidence', 0, 1);
      if (block.openness != null) number(block.openness, 'openness', 0, 1);
      optionalBoolean(block.visibility_verified, 'visibility_verified');
      if (block.recommended_rank != null && (typeof block.recommended_rank !== 'string' ||
          !['signature', 'panorama', 'recommended', 'scenic'].includes(block.recommended_rank))) invalid('recommended_rank');
      if (block.arrival_time !== undefined && (typeof block.arrival_time !== 'string' || !Number.isFinite(Date.parse(block.arrival_time)))) invalid('arrival_time');
    }
  }
  if (source.reveals !== undefined) for (const item of array(source.reveals, 'route.reveals', 20_000)) {
    number(object(item, 'route.reveals[]').start_m, 'route.reveals[].start_m', 0, 2_000_000);
  }
  return source as RouteInput;
}

export function parseRecommendation(value: unknown): RecommendationRequest {
  const source = object(value, 'request');
  if (source.scene === undefined && source.route === undefined) invalid('scene or route');
  const members = array(source.members, 'members', 20).map((item) => {
    const member = object(item, 'members[]');
    return { id: text(member.id, 'members[].id', 128), name: text(member.name, 'members[].name', 200) };
  });
  if (!members.length || new Set(members.map((m) => m.id)).size !== members.length) invalid('members');
  if (source.mode !== undefined && !isModeId(source.mode)) invalid('mode');
  if (source.cabin !== undefined && (typeof source.cabin !== 'string' ||
      !['lively', 'normal', 'quiet', 'resting'].includes(source.cabin))) invalid('cabin');
  optionalBoolean(source.include_catalog, 'include_catalog');
  for (const [field, min, max] of [['top_k', 1, 30], ['seed', 0, 0xffffffff]] as const) {
    if (source[field] !== undefined && !Number.isInteger(number(source[field], field, min, max))) invalid(field);
  }
  if (source.pool !== undefined) for (const item of array(source.pool, 'pool', 5_000)) {
    const track = object(item, 'pool[]');
    text(track.track_id, 'pool[].track_id');
    if (track.owners !== undefined) for (const owner of array(track.owners, 'pool[].owners', 20)) {
      if (!members.some((m) => m.id === text(owner, 'pool[].owners[]', 128))) invalid('pool[].owners[]');
    }
  }
  if (source.history !== undefined) for (const item of array(source.history, 'history', 1_000)) {
    const entry = object(item, 'history[]');
    text(entry.track_id, 'history[].track_id');
    if (entry.artist !== undefined) text(entry.artist, 'history[].artist');
    if (entry.owner_id !== undefined) text(entry.owner_id, 'history[].owner_id', 128);
  }
  if (source.recent_track_ids !== undefined) for (const id of array(source.recent_track_ids, 'recent_track_ids', 5_000)) text(id, 'recent_track_ids[]');
  if (source.previous_target !== undefined) for (const [axis, value] of Object.entries(object(source.previous_target, 'previous_target'))) {
    if (!AXES.includes(axis as typeof AXES[number])) invalid('previous_target');
    number(value, `previous_target.${axis}`, 0, 1);
  }
  return {
    ...source, members,
    ...(source.scene !== undefined ? { scene: scene(source.scene) } : {}),
    ...(source.route !== undefined ? { route: route(source.route) } : {}),
  } as RecommendationRequest;
}
