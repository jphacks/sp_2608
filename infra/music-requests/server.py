#!/usr/bin/env python3
"""Ephemeral music request relay. Spotify credentials never leave the host phone."""
import argparse
import json
import re
import secrets
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

class Relay:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.lock = threading.RLock()
        self.rooms = {}
        self.creations = deque()

    def create(self):
        with self.lock:
            now = self.clock()
            self.rooms = {k: v for k, v in self.rooms.items() if now - v['created'] < 14400 and now - v['seen'] < 120}
            while self.creations and now - self.creations[0] > 60:
                self.creations.popleft()
            if len(self.rooms) >= 100 or len(self.creations) >= 20:
                raise ValueError('受付が混み合っています。しばらく待ってください。')
            self.creations.append(now)
            room, host = secrets.token_urlsafe(24), secrets.token_urlsafe(32)
            self.rooms[room] = dict(host=host, created=now, seen=now, jobs={}, tracks={}, history=[], rate=deque())
            return dict(room=room, host=host)

    def room(self, key, host=None):
        room = self.rooms.get(key)
        now = self.clock()
        if not room or now - room['created'] > 14400 or now - room['seen'] > 120:
            raise ValueError('受付は終了しています。新しいQRコードを読み取ってください。')
        if host is not None and not secrets.compare_digest(room['host'], host):
            raise PermissionError()
        return room

    def submit(self, key, data):
        with self.lock:
            room = self.room(key)
            now = self.clock()
            while room['rate'] and now - room['rate'][0] > 60:
                room['rate'].popleft()
            if len(room['rate']) >= 30:
                raise ValueError('リクエストが多いため、少し待ってください。')
            room['jobs'] = {k: v for k, v in room['jobs'].items() if now - v['at'] < 120 or v['status'] == 'pending'}
            if sum(v['status'] == 'pending' for v in room['jobs'].values()) >= 30:
                raise ValueError('受付が混み合っています。')
            kind = data.get('kind')
            if kind == 'search':
                query = data.get('query', '')
                if not isinstance(query, str) or not 1 <= len(query.strip()) <= 100:
                    raise ValueError('曲名を100文字以内で入力してください。')
                payload = dict(query=query.strip())
            elif kind == 'add':
                uri = data.get('uri')
                track = room['tracks'].get(uri) if isinstance(uri, str) else None
                if not track:
                    raise ValueError('もう一度検索して曲を選んでください。')
                payload = dict(track=track)
            else:
                raise ValueError('不明な操作です。')
            room['rate'].append(now)
            job = secrets.token_urlsafe(18)
            room['jobs'][job] = dict(id=job, kind=kind, **payload, status='pending', at=now)
            return dict(id=job)

    def poll(self, key, host):
        with self.lock:
            room = self.room(key, host)
            room['seen'] = self.clock()
            # One Spotify operation per heartbeat keeps slow batches from expiring the room.
            return dict(jobs=[{k: v for k, v in j.items() if k != 'at'} for j in room['jobs'].values() if j['status'] == 'pending'][:1])

    def complete(self, key, host, data):
        with self.lock:
            room = self.room(key, host)
            job = room['jobs'].get(data.get('id'))
            if not job:
                raise ValueError('要求の期限が切れました。')
            if job['status'] != 'pending':
                return dict(ok=True)
            error = data.get('error')
            if error:
                job.update(status='failed', error=str(error)[:200])
            elif job['kind'] == 'search':
                tracks = data.get('tracks', [])
                if not isinstance(tracks, list):
                    raise ValueError('検索結果が不正です。')
                tracks = [t for t in tracks[:10] if isinstance(t, dict) and isinstance(t.get('uri'), str) and re.fullmatch(r'spotify:track:[a-zA-Z0-9]{22}', t['uri']) and isinstance(t.get('title'), str) and isinstance(t.get('artist'), str)]
                tracks = [dict(uri=t['uri'], title=t['title'][:300], artist=t['artist'][:300]) for t in tracks]
                if len(room['tracks']) > 500:
                    room['tracks'].clear()
                room['tracks'].update({t['uri']: t for t in tracks})
                job.update(status='done', tracks=tracks)
            else:
                job.update(status='done')
                room['history'].append(job['track'])
                room['history'] = room['history'][-30:]
            return dict(ok=True)

    def result(self, key, job_id=None):
        with self.lock:
            room = self.room(key)
            if not job_id:
                return dict(history=room['history'])
            job = room['jobs'].get(job_id)
            if not job:
                raise ValueError('要求の期限が切れました。')
            return {k: v for k, v in job.items() if k not in ('at', 'query')}

    def close(self, key, host):
        with self.lock:
            self.room(key, host)
            del self.rooms[key]
            return dict(ok=True)

class Handler(BaseHTTPRequestHandler):
    relay = Relay()
    def log_message(self, *args):
        pass  # URLs contain invitation tokens; do not log them.

    def reply(self, status, value, content_type='application/json; charset=utf-8'):
        body = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        try:
            path = urlsplit(self.path).path.split('/')
            if len(path) == 3 and path[1] == 'r':
                with self.relay.lock:
                    self.relay.room(path[2])
                self.reply(200, Path(__file__).with_name('index.html').read_bytes(), 'text/html; charset=utf-8')
            elif self.path in ('/app.js', '/style.css'):
                name = self.path[1:]
                self.reply(200, Path(__file__).with_name(name).read_bytes(), 'text/javascript; charset=utf-8' if name.endswith('.js') else 'text/css; charset=utf-8')
            elif len(path) in (4, 5) and path[1] == 'api' and path[3] == 'result':
                self.reply(200, self.relay.result(path[2], path[4] if len(path) == 5 else None))
            else:
                self.reply(404, dict(error='ページが見つかりません。'))
        except ValueError as e:
            self.reply(410, dict(error=str(e)))

    def do_POST(self):
        try:
            # JSON + same-origin browser requests prevent cross-site submissions.
            if self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
                raise ValueError('JSONが必要です。')
            origin = self.headers.get('Origin')
            if origin and urlsplit(origin).netloc != self.headers.get('Host'):
                raise PermissionError()
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 16384:
                raise ValueError('要求サイズが不正です。')
            data = json.loads(self.rfile.read(size))
            if not isinstance(data, dict):
                raise ValueError('要求が不正です。')
            path = urlsplit(self.path).path.split('/')
            if self.path == '/api/rooms':
                result = self.relay.create()
            elif len(path) == 4 and path[1] == 'api':
                key, action = path[2:]
                host = self.headers.get('Authorization', '').removeprefix('Bearer ')
                if action == 'submit':
                    result = self.relay.submit(key, data)
                elif action == 'poll':
                    result = self.relay.poll(key, host)
                elif action == 'complete':
                    result = self.relay.complete(key, host, data)
                elif action == 'close':
                    result = self.relay.close(key, host)
                else:
                    raise ValueError('不明な操作です。')
            else:
                raise ValueError('不明な操作です。')
            self.reply(200, result)
        except PermissionError:
            self.reply(403, dict(error='操作は許可されていません。'))
        except (ValueError, TypeError, KeyError) as e:
            self.reply(400, dict(error=str(e)))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', default=8788, type=int)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    server.daemon_threads = True
    print(f'Music request relay listening on {args.bind}:{args.port}', flush=True)
    server.serve_forever()
