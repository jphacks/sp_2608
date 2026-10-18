import json
import logging
import math
import os
import threading
import re
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from urllib.parse import urlsplit, parse_qs
from places import search as search_places

from catalog import Catalog
from planner import NoRoute, ScenicPlanner
from recommendation.environment import OpenMeteoForecasts
from recommendation.jobs import Busy, JobStore, RecommendationService
from recommendation.models import Infeasible, InputError, Parameters, RecommendationRequest, integer


def coordinate(value):
    lat, lon = value['lat'], value['lon']
    if isinstance(lat, bool) or isinstance(lon, bool) or not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
        raise ValueError('Invalid coordinates')
    if not math.isfinite(lat) or not math.isfinite(lon) or not -80 <= lat <= 80 or not -180 <= lon <= 180:
        raise ValueError('Invalid coordinates')
    return lat, lon


def validate(body):
    origin, destination = coordinate(body['origin']), coordinate(body['destination'])
    preference = body.get('preference', 'balanced')
    extra = body.get('extraSeconds', 900)
    if preference not in ('sea', 'forest', 'balanced') or type(extra) is not int or not 0 <= extra <= 1800:
        raise ValueError('Invalid preferences')
    options = body.get('options', {})
    if any(type(options.get(key, False)) is not bool for key in ('avoidTolls', 'avoidHighways', 'avoidFerries')):
        raise ValueError('Invalid options')
    return origin, destination, options, preference, extra


def route(points, options, timeout):
    body = {'locations': [dict(lat=p[0], lon=p[1], type='break' if i in (0, len(points) - 1) else 'through')
                          for i, p in enumerate(points)], 'costing': 'auto', 'units': 'kilometers',
            'language': 'ja-JP', 'directions_type': 'instructions',
            'costing_options': {'auto': {'use_tolls': 0 if options.get('avoidTolls') else .5,
                                         'use_highways': 0 if options.get('avoidHighways') else 1,
                                         'use_ferry': 0 if options.get('avoidFerries', True) else .5}}}
    request = Request(os.environ.get('VALHALLA_INTERNAL_URL', 'http://valhalla:8002') + '/route',
                      json.dumps(body).encode(), {'Content-Type': 'application/json'})
    with urlopen(request, timeout=timeout) as response:
        raw = response.read(16_000_001)
    if len(raw) > 16_000_000:
        raise ValueError('Response too large')
    result = json.loads(raw)
    if result['trip']['status'] != 0:
        raise NoRoute()
    for leg in result['trip']['legs']:
        for maneuver in leg['maneuvers']:
            if any(options.get(option, default) and maneuver.get(flag, False) for option, flag, default in
                   (('avoidTolls', 'toll', False), ('avoidHighways', 'highway', False), ('avoidFerries', 'ferry', True))):
                raise NoRoute()
    return result


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    slots = threading.BoundedSemaphore(1)
    recommendations = None

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def reply(self, status, body):
        raw = json.dumps(body, allow_nan=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(raw)
        self.close_connection = True

    def do_POST(self):
        if self.path != '/scenic/route':
            return self.handle_recommendation('POST')
        if self.path != '/scenic/route':
            return self.reply(404, {'error': 'not_found'})
        if not self.slots.acquire(blocking=False):
            return self.reply(429, {'error': 'busy'})
        catalog = None
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 16384 or self.headers.get('Transfer-Encoding'):
                return self.reply(400, {'error': 'invalid_request'})
            body = json.loads(self.rfile.read(size))
            origin, destination, options, preference, extra = validate(body)
            catalog = Catalog(os.environ.get('SCENIC_DATABASE', '/data/scenic.sqlite'))
            if not catalog.contains(origin) or not catalog.contains(destination):
                return self.reply(422, {'error': 'outside_catalog'})
            result = ScenicPlanner(catalog, route).plan(origin, destination, options, preference, extra)
            self.reply(200, result)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            self.reply(400, {'error': 'invalid_request_or_response'})
        except NoRoute:
            self.reply(422, {'error': 'no_route'})
        except (OSError, HTTPError):
            self.reply(503, {'error': 'routing_unavailable'})
        except Exception:
            logging.exception('Scenic search failed')
            self.reply(503, {'error': 'catalog_unavailable'})
        finally:
            if catalog:
                catalog.close()
            self.slots.release()

    def do_GET(self):
        if urlsplit(self.path).path == '/search':
            try:
                if len(self.path) > 4096:
                    raise ValueError('Query too long')
                params = parse_qs(urlsplit(self.path).query)
                return self.reply(200, search_places(os.environ.get('PLACES_DATABASE', '/data/places.sqlite'),
                                                   params.get('q', [''])[0], int(params.get('limit', ['5'])[0])))
            except (ValueError, TypeError):
                return self.reply(400, {'error': 'invalid_search'})
            except (OSError, sqlite3.Error):
                return self.reply(503, {'error': 'places_unavailable'})
        self.handle_recommendation('GET')

    def do_DELETE(self):
        self.handle_recommendation('DELETE')

    def read_json(self):
        size = int(self.headers.get('Content-Length', '0'))
        if not 0 < size <= 1_000_000 or self.headers.get('Transfer-Encoding'):
            raise InputError('invalid_body')
        result = json.loads(self.rfile.read(size))
        if not isinstance(result, dict):
            raise InputError('object_required')
        return result

    def handle_recommendation(self, method):
        service = self.recommendations
        if service is None:
            return self.reply(503, {'error': 'recommendations_unavailable'})
        try:
            path, separator, query = self.path.partition('?')
            if separator and (method != 'GET' or query != 'view=status'):
                return self.reply(404, {'error': 'not_found'})
            if self.path == '/route-recommendations' and method == 'POST':
                return self.reply(202, service.submit(RecommendationRequest.parse(self.read_json())))
            if path == '/route-recommendations/data-status' and method == 'GET' and not separator:
                return self.reply(200, service.data_status())
            if self.path == '/navigation-sessions' and method == 'POST':
                body = self.read_json()
                return self.reply(201, service.jobs.start_navigation(body['route_id'], integer(body['revision'], 1, 2**31 - 1)))
            match = re.fullmatch(r'/route-recommendations/([a-f0-9]{32})', path)
            if match:
                if method == 'GET':
                    result = service.jobs.get(match[1])
                    if result and query == 'view=status' and result['status'] in ('queued', 'processing', 'improving'):
                        result = {k: result[k] for k in ('request_id', 'status', 'revision', 'search') if k in result}
                    return self.reply(200, result) if result else self.reply(404, {'error': 'recommendation_expired'})
                if method == 'DELETE':
                    service.delete(match[1])
                    return self.reply(200, {'status': 'deleted'})
            match = re.fullmatch(r'/route-recommendations/([a-f0-9]{32})/snapshot', path)
            if match and method == 'GET':
                result = service.jobs.snapshot(match[1])
                return self.reply(200, result) if result else self.reply(404, {'error': 'snapshot_unavailable'})
            match = re.fullmatch(r'/routes/(route_[a-f0-9]{32})/evidence', self.path)
            if match and method == 'GET':
                result = service.jobs.route(match[1])
                if not result:
                    return self.reply(404, {'error': 'route_expired'})
                route = result[0]
                return self.reply(200, {key: route[key] for key in ('route_id', 'revision', 'snapshot_id', 'evidence', 'timeline', 'reasons', 'warnings')})
            match = re.fullmatch(r'/navigation-sessions/([a-f0-9]{32})/replan', self.path)
            if match and method == 'POST':
                return self.reply(202, service.replan(match[1], self.read_json()))
            return self.reply(404, {'error': 'not_found'})
        except Busy:
            self.reply(429, {'error': 'queue_full'})
        except Infeasible as error:
            self.reply(422, {'error': error.code})
        except (ValueError, KeyError, TypeError):
            self.reply(400, {'error': 'invalid_request'})
        except OSError:
            self.reply(503, {'error': 'storage_unavailable'})
        except Exception:
            logging.exception('Recommendation HTTP failure')
            self.reply(503, {'error': 'service_unavailable'})

    def log_message(self, *_):
        pass


if __name__ == '__main__':
    Handler.recommendations = RecommendationService(
        JobStore(os.environ.get('SCENIC_STATE', '/state/recommendations.sqlite')),
        os.environ.get('SCENIC_DATABASE', '/data/scenic.sqlite'),
        os.environ.get('VALHALLA_INTERNAL_URL', 'http://valhalla:8002'),
        parameters=Parameters(max_seconds=float(os.environ.get('SCENIC_SEARCH_SECONDS', '90'))),
        forecasts=OpenMeteoForecasts(os.environ.get('SCENIC_WEATHER', 'false').lower() == 'true'),
        supplements=os.environ.get('SCENIC_SUPPLEMENTS') or None,
        recommended_roads=os.environ.get('SCENIC_RECOMMENDED_ROADS') or None,
        landscapes=os.environ.get('SCENIC_LANDSCAPES') or None,
        terrain=os.environ.get('SCENIC_TERRAIN') or None,
        context=os.environ.get('SCENIC_CONTEXT') or None,
        landcover=os.environ.get('SCENIC_LANDCOVER') or None,
        routing_directory=os.environ.get('SCENIC_ROUTING_DIRECTORY') or None)
    def cleanup():
        while True:
            threading.Event().wait(60)
            Handler.recommendations.jobs.cleanup()
    threading.Thread(target=cleanup, daemon=True, name='recommendation-retention').start()
    ThreadingHTTPServer((os.environ.get('SCENIC_BIND', '127.0.0.1'), 8004), Handler).serve_forever()
