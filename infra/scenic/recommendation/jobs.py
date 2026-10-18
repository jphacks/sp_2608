from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from dataclasses import asdict, replace
from datetime import datetime, timezone
import json
import hashlib
import logging
from pathlib import Path
import sqlite3
import threading
import time
import uuid

from catalog import Catalog
from recommendation.evidence import EvidenceBuilder
from recommendation.environment import OpenMeteoForecasts
from recommendation.models import Infeasible, Parameters, RecommendationRequest, identity, integer, point
from recommendation.portfolio import select_portfolio
from recommendation.routing import ValhallaAdapter
from recommendation.search import CandidatePlanner
from recommendation.store import ScenicStore
from recommendation.data_status import source_status


class Busy(Exception):
    pass


class JobStore:
    def __init__(self, path, ttl=3600, maximum=64, clock=time.time):
        self.path, self.ttl, self.maximum, self.clock = str(path), ttl, maximum, clock
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.executescript('''
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS job(id TEXT PRIMARY KEY, created REAL, payload TEXT, result TEXT, snapshot TEXT);
                CREATE TABLE IF NOT EXISTS route(id TEXT PRIMARY KEY, job_id TEXT, payload TEXT);
                CREATE TABLE IF NOT EXISTS session(id TEXT PRIMARY KEY, created REAL, job_id TEXT, payload TEXT);
                CREATE INDEX IF NOT EXISTS route_job ON route(job_id);
            ''')
            for job_id, raw in db.execute('SELECT id,result FROM job').fetchall():
                result = json.loads(raw)
                if result.get('status') in ('queued', 'processing', 'improving'):
                    result.update(status='limited' if result.get('routes') else 'failed', error='service_restarted')
                    db.execute('UPDATE job SET result=? WHERE id=?', (json.dumps(result), job_id))

    @contextmanager
    def connection(self):
        connection = sqlite3.connect(self.path, timeout=10)
        try:
            yield connection
            connection.commit()
        finally:
            connection.close()

    def expire(self, db, reserve=1):
        old = [r[0] for r in db.execute('SELECT id FROM job WHERE created<? ORDER BY created', (self.clock() - self.ttl,))]
        count = db.execute('SELECT count(*) FROM job').fetchone()[0]
        if count + reserve > self.maximum:
            old += [r[0] for r in db.execute('SELECT id FROM job ORDER BY created LIMIT ?', (count - self.maximum + reserve,))]
        for job_id in set(old):
            db.execute('DELETE FROM route WHERE job_id=?', (job_id,))
            db.execute('DELETE FROM session WHERE job_id=?', (job_id,))
            db.execute('DELETE FROM job WHERE id=?', (job_id,))

    def create(self, payload):
        job_id = uuid.uuid4().hex
        with self.connection() as db:
            self.expire(db)
            db.execute('INSERT INTO job VALUES (?,?,?,?,?)', (job_id, self.clock(), json.dumps(payload),
                       json.dumps({'request_id': job_id, 'status': 'queued', 'revision': 1, 'routes': []}), None))
        return job_id

    def publish(self, job_id, result, snapshot=None):
        result = {**result, 'request_id': job_id}
        with self.connection() as db:
            previous = db.execute('SELECT result FROM job WHERE id=?', (job_id,)).fetchone()
            if not previous:
                return
            result['revision'] = json.loads(previous[0]).get('revision', 0) + 1
            db.execute('UPDATE job SET result=?,snapshot=coalesce(?,snapshot) WHERE id=?',
                       (json.dumps(result, allow_nan=False), json.dumps(snapshot) if snapshot is not None else None, job_id))
            routes = result.get('routes', []) + [c['comparison_route'] for c in result.get('counterfactuals', [])]
            for route in routes:
                existing = db.execute('SELECT payload FROM route WHERE id=?', (route['route_id'],)).fetchone()
                raw = json.dumps(route, sort_keys=True, allow_nan=False)
                if existing and existing[0] != raw:
                    raise ValueError('Immutable route revision changed')
                db.execute('INSERT OR IGNORE INTO route VALUES (?,?,?)', (route['route_id'], job_id, raw))
            baseline = result.get('baseline')
            if baseline and baseline.get('route_id'):
                db.execute('INSERT OR IGNORE INTO route VALUES (?,?,?)',
                           (baseline['route_id'], job_id, json.dumps(baseline, sort_keys=True, allow_nan=False)))

    def get(self, job_id):
        with self.connection() as db:
            row = db.execute('SELECT result FROM job WHERE id=? AND created>=?', (job_id, self.clock() - self.ttl)).fetchone()
        return json.loads(row[0]) if row else None

    def request(self, job_id):
        with self.connection() as db:
            row = db.execute('SELECT payload FROM job WHERE id=? AND created>=?', (job_id, self.clock() - self.ttl)).fetchone()
        return json.loads(row[0]) if row else None

    def snapshot(self, job_id):
        with self.connection() as db:
            row = db.execute('SELECT snapshot FROM job WHERE id=? AND created>=?', (job_id, self.clock() - self.ttl)).fetchone()
        return json.loads(row[0]) if row and row[0] else None

    def cleanup(self):
        with self.connection() as db:
            self.expire(db, reserve=0)

    def route(self, route_id):
        with self.connection() as db:
            row = db.execute('SELECT r.payload,r.job_id FROM route r JOIN job j ON r.job_id=j.id WHERE r.id=? AND j.created>=?',
                             (route_id, self.clock() - self.ttl)).fetchone()
        return (json.loads(row[0]), row[1]) if row else None

    def delete(self, job_id):
        with self.connection() as db:
            db.execute('DELETE FROM route WHERE job_id=?', (job_id,))
            db.execute('DELETE FROM session WHERE job_id=?', (job_id,))
            db.execute('DELETE FROM job WHERE id=?', (job_id,))

    def start_navigation(self, route_id, revision):
        stored = self.route(route_id)
        if not stored or stored[0]['revision'] != revision:
            raise Infeasible('route_revision_unavailable')
        route, job_id = stored
        session_id = uuid.uuid4().hex
        payload = {'session_id': session_id, 'route_id': route_id, 'revision': revision,
                   'snapshot_id': route['snapshot_id'], 'status': 'selected', 'elapsed_seconds': 0,
                   'visited_physical_ids': [], 'request_id': job_id}
        with self.connection() as db:
            db.execute('INSERT INTO session VALUES (?,?,?,?)', (session_id, self.clock(), job_id, json.dumps(payload)))
        return payload

    def session(self, session_id):
        with self.connection() as db:
            row = db.execute('SELECT s.payload,s.created FROM session s JOIN job j ON s.job_id=j.id WHERE s.id=? AND j.created>=?',
                             (session_id, self.clock() - self.ttl)).fetchone()
        return (json.loads(row[0]), row[1]) if row else None

    def update_session(self, session_id, payload):
        with self.connection() as db:
            db.execute('UPDATE session SET payload=? WHERE id=?', (json.dumps(payload), session_id))


class RecommendationService:
    def __init__(self, jobs, catalog_path, endpoint, parameters=None, forecasts=None, supplements=None, adapter_factory=None,
                 recommended_roads=None, landscapes=None, terrain=None, context=None, landcover=None,
                 routing_directory=None):
        self.jobs, self.catalog_path, self.endpoint = jobs, catalog_path, endpoint
        self.parameters = parameters or Parameters()
        self.forecasts, self.supplements = forecasts or OpenMeteoForecasts(False), supplements
        self.recommended_roads = recommended_roads
        self.landscapes = landscapes
        self.terrain, self.context = terrain, context
        self.landcover = landcover
        self.routing_directory = Path(routing_directory) if routing_directory else Path(catalog_path).parent
        self.adapter_factory = adapter_factory or (lambda version: ValhallaAdapter(endpoint, version))
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='recommendations')
        self.slots = threading.BoundedSemaphore(1)
        self.cancel_events = {}
        package = Path(__file__).parent
        self.implementation_hash = hashlib.sha256(b''.join(p.read_bytes() for p in sorted(package.glob('*.py')))).hexdigest()

    def submit(self, request):
        if not self.slots.acquire(blocking=False):
            raise Busy()
        try:
            job_id = self.jobs.create(request.payload())
            self.cancel_events[job_id] = threading.Event()
            self.executor.submit(self.run, job_id, request)
            return {'request_id': job_id, 'status': 'queued', 'revision': 1}
        except Exception:
            self.slots.release()
            raise

    def routing_artifacts(self):
        return {name: hashlib.sha256((self.routing_directory / name).read_bytes()).hexdigest()
                if (self.routing_directory / name).is_file() else None for name in
                ('valhalla.json', 'tiles/tile_manifest.json', 'routing-source.json', 'routing.lua')}

    def data_status(self):
        catalog = Catalog(self.catalog_path)
        try:
            store = ScenicStore(catalog, self.supplements, self.recommended_roads, self.landscapes, self.terrain, self.context, self.landcover)
            return source_status(store, self.forecasts.enabled)
        finally:
            catalog.close()

    def run(self, job_id, request):
        catalog = None
        planner = None
        try:
            self.jobs.publish(job_id, {'status': 'processing', 'routes': [], 'revision': 1})
            catalog = Catalog(self.catalog_path)
            if not catalog.contains(request.origin) or not catalog.contains(request.destination):
                raise Infeasible('outside_catalog')
            store = ScenicStore(catalog, self.supplements, self.recommended_roads, self.landscapes, self.terrain, self.context, self.landcover)
            forecast = self.forecasts.snapshot(request)
            if forecast.cells:
                store.sources.append({'source_id': forecast.source_id, 'source_url': 'https://open-meteo.com/',
                    'license': 'CC-BY-4.0; Free API non-commercial terms apply', 'observed_at': None,
                    'acquired_at': forecast.acquired_at, 'version': forecast.source_id,
                    'spatial_coverage': [cell['coordinate'] for cell in forecast.cells]})
            snapshot = {'request': request.payload(), 'sources': store.sources, 'forecast': forecast.payload(),
                        'recommended_roads_version': store.recommendations.version,
                        'landscape_features_version': store.landscape_version,
                        'terrain_version': store.terrain.metadata['version'] if store.terrain else None,
                        'context_version': store.context.metadata['version'] if store.context else None,
                        'landcover_version': store.landcover.metadata['version'] if store.landcover else None,
                        'parameters': asdict(self.parameters), 'engine': 'valhalla-static-speed',
                        'map_version': store.version, 'seed': request.seed, 'implementation_sha256': self.implementation_hash}
            snapshot['routing_artifacts'] = self.routing_artifacts()
            snapshot_id = 'snapshot_' + identity(snapshot)
            planner = CandidatePlanner(store, self.adapter_factory(store.version), forecast, self.parameters)
            planner.budget.cancelled = self.cancel_events[job_id].is_set
            planner.snapshot_id = snapshot_id
            builder = EvidenceBuilder(store, snapshot_id)
            def publish(planner, baseline, _):
                selected = select_portfolio(list(planner.archive.values()), request, self.parameters)
                result = builder.response(planner, request, selected, baseline, status='improving')
                self.jobs.publish(job_id, result, snapshot)
            baseline, selected, counterfactuals = planner.plan(request, publish)
            self.jobs.publish(job_id, builder.response(planner, request, selected, baseline, counterfactuals), snapshot)
        except Infeasible as error:
            result = {'status': 'infeasible', 'error': error.code, 'revision': 1, 'routes': []}
            if planner is not None:
                result['search'] = planner.budget.status()
            self.jobs.publish(job_id, result)
        except Exception:
            logging.exception('Recommendation failed')
            previous = self.jobs.get(job_id)
            if previous and previous.get('routes'):
                self.jobs.publish(job_id, {**previous, 'status': 'limited', 'error': 'improvement_failed'})
            else:
                self.jobs.publish(job_id, {'status': 'failed', 'error': 'service_unavailable', 'revision': 1, 'routes': []})
        finally:
            if catalog:
                catalog.close()
            self.slots.release()
            self.cancel_events.pop(job_id, None)

    def delete(self, job_id):
        event = self.cancel_events.get(job_id)
        if event:
            event.set()
        self.jobs.delete(job_id)

    def replan(self, session_id, body):
        stored = self.jobs.session(session_id)
        if not stored:
            raise Infeasible('session_unavailable')
        session, started = stored
        request = RecommendationRequest.parse(self.jobs.request(session['request_id']))
        snapshot = self.jobs.snapshot(session['request_id'])
        catalog = Catalog(self.catalog_path)
        try:
            current_map = catalog.source.get('map_version') or identity(catalog.source)
        finally:
            catalog.close()
        if not snapshot or snapshot['map_version'] != current_map:
            raise Infeasible('map_version_changed')
        if snapshot.get('routing_artifacts') and snapshot['routing_artifacts'] != self.routing_artifacts():
            raise Infeasible('routing_version_changed')
        elapsed = max(integer(body.get('elapsed_seconds', 0), 0, 86400), session['elapsed_seconds'], int(max(0, self.jobs.clock() - started)))
        driving = integer(body['elapsed_driving_seconds'], 0, elapsed)
        if driving < session.get('elapsed_driving_seconds', 0):
            raise ValueError('Driving time cannot go backwards')
        visited = body.get('visited_physical_ids', [])
        if body.get('history_enabled') is not True or not isinstance(visited, list) or len(visited) > 20000 or not all(isinstance(v, str) and len(v) <= 160 for v in visited):
            raise ValueError('Explicit history consent required')
        visited = frozenset(visited) | frozenset(session['visited_physical_ids'])
        original = self.jobs.get(session['request_id'])
        baseline_seconds = original['baseline'].get('driving_seconds', original['baseline'].get('timing', {}).get('driving_seconds', 0))
        remaining_total = request.max_total_seconds - elapsed if request.max_total_seconds is not None else None
        driving_budget = request.max_driving_seconds if request.max_driving_seconds is not None else (
            request.max_total_seconds if request.mode == 'round_trip' else baseline_seconds + request.extra_seconds)
        remaining_drive = driving_budget - driving
        if (remaining_total is not None and remaining_total <= 0) or remaining_drive <= 0:
            raise Infeasible('budget_exhausted')
        updated = replace(request, origin=point(body['origin']), departure=datetime.now(timezone.utc), mode='point_to_point',
                          max_total_seconds=remaining_total, max_driving_seconds=int(remaining_drive),
                          extra_seconds=min(21600, int(remaining_drive)),
                          visited=visited, history_enabled=True)
        result = self.submit(updated)
        session.update(elapsed_seconds=elapsed, elapsed_driving_seconds=driving,
                       visited_physical_ids=sorted(visited), proposed_request_id=result['request_id'])
        self.jobs.update_session(session_id, session)
        return {**result, 'selection_required': True, 'current_route_id': session['route_id']}
