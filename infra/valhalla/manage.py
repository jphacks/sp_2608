"""Local configuration, builds and preflight checks for the Hokkaido routing stack."""

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen

from auth import ensure_credentials, validate_credentials
from prepare import apply_routing_limits, SPEED_PROFILE

DIRECTORY = Path(__file__).resolve().parent
ROOT = DIRECTORY.parent.parent
DEFAULT_CONFIG = DIRECTORY / '.routing.json'
FILES = {
    'SCENIC_DATABASE': 'scenic-hokkaido.sqlite',
    'PLACES_DATABASE': 'places.sqlite',
    'SCENIC_RECOMMENDED_ROADS': 'road-recommendations-research-20261018-v7.sqlite',
    'SCENIC_LANDSCAPES': 'landscapes-hokkaido-v4.sqlite',
    'SCENIC_TERRAIN': 'terrain-hokkaido-v1.sqlite',
    'SCENIC_CONTEXT': 'context-hokkaido-v1.sqlite',
    'SCENIC_LANDCOVER': 'landcover-hokkaido-v3.sqlite',
}
PBF_URL = 'https://download.geofabrik.de/asia/japan/hokkaido-latest.osm.pbf'
TABLES = {
    'SCENIC_DATABASE': {'road', 'road_bounds', 'metadata', 'way_attributes'},
    'PLACES_DATABASE': {'places', 'metadata'},
    'SCENIC_RECOMMENDED_ROADS': {'recommendation', 'membership', 'road_part', 'source', 'metadata'},
    'SCENIC_LANDSCAPES': {'road_landscape', 'metadata'},
    'SCENIC_TERRAIN': {'road_terrain', 'metadata'},
    'SCENIC_CONTEXT': {'way_context', 'poi', 'poi_bounds', 'metadata'},
    'SCENIC_LANDCOVER': {'land_cover', 'metadata'},
}


def download_pbf(settings, fetch=urlopen):
    data = Path(settings['data_dir'])
    output = data / 'region.osm.pbf'
    temporary = data / 'region.osm.pbf.download'
    if output.exists() or temporary.exists() or (data / 'pbf-download.json').exists():
        raise ValueError('Existing PBF/download preserved. Reuse it, or choose a new data directory.')
    count, digest = 0, hashlib.sha256()
    with fetch(PBF_URL, timeout=60) as response, temporary.open('xb') as stream:
        advertised = response.headers.get('Content-Length')
        expected = int(advertised) if advertised else None
        if expected is not None and not 1 <= expected <= 512_000_000:
            raise ValueError('Unexpected Hokkaido PBF size; review the source before downloading.')
        while chunk := response.read(1024 * 1024):
            count += len(chunk)
            if count > 512_000_000:
                raise ValueError('PBF exceeds the explicit 512MB download limit.')
            stream.write(chunk)
            digest.update(chunk)
    if not count or (expected is not None and count != expected):
        raise ValueError('PBF download is incomplete; it was not published as region.osm.pbf.')
    with temporary.open('rb') as stream:
        header = stream.read(65540)
    if len(header) < 4 or not 1 <= int.from_bytes(header[:4], 'big') <= 65536 or b'OSMHeader' not in header:
        raise ValueError('Downloaded content does not have an OSM PBF header.')
    temporary.rename(output)
    manifest = {'url': PBF_URL, 'bytes': count, 'sha256': digest.hexdigest(),
                'acquired_at': datetime.now(timezone.utc).isoformat()}
    with (data / 'pbf-download.json').open('x') as stream:
        json.dump(manifest, stream, indent=2)
    print(f'Downloaded Hokkaido PBF: {count:,} bytes. SHA-256 recorded in pbf-download.json.')


def load_settings(path):
    if not path.is_file():
        raise ValueError('Run manage.py init --data-dir <directory> first.')
    settings = json.loads(path.read_text())
    if settings.get('schema') != 1 or not Path(settings.get('data_dir', '')).is_absolute():
        raise ValueError('Invalid .routing.json: schema=1 and an absolute data_dir are required.')
    if set(settings.get('files', {})) != set(FILES):
        raise ValueError('The configuration must specify all seven database files.')
    for name in settings['files'].values():
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*\.sqlite', name):
            raise ValueError('Database filenames must be plain .sqlite basenames, not paths or .building files.')
    if type(settings.get('weather')) is not bool:
        raise ValueError('weather must be true or false.')
    if 'routing_dir' in settings and not Path(settings['routing_dir']).is_absolute():
        raise ValueError('routing_dir must be an absolute path.')
    return settings


def routing_directory(settings):
    return Path(settings.get('routing_dir', settings['data_dir']))


def initialize(path, data_dir):
    if path.exists():
        settings = load_settings(path)
        if Path(settings['data_dir']) != data_dir.resolve():
            raise ValueError('The saved data directory differs. Edit the config explicitly; it was not overwritten.')
    else:
        data_dir = data_dir.resolve()
        if any(c in str(data_dir) for c in ('\n', '\r', '\x00')):
            raise ValueError('Invalid data directory.')
        data_dir.mkdir(parents=True, exist_ok=True)
        settings = {'schema': 1, 'data_dir': str(data_dir), 'files': FILES.copy(), 'weather': True}
        with path.open('x') as stream:
            json.dump(settings, stream, ensure_ascii=False, indent=2)
    created = ensure_credentials(DIRECTORY / '.secrets')
    print('Configuration saved. Credentials created.' if created else 'Configuration and credentials validated; existing token unchanged.')
    print('Data directory:', settings['data_dir'])


def compose(settings, *args):
    # Saved configuration is authoritative even if an older terminal exported different paths.
    env = {key: value for key, value in os.environ.items()
           if not key.startswith('SCENIC_') and key not in ('VALHALLA_DATA_DIR', 'PLACES_DATABASE', 'COMPOSE_FILE', 'COMPOSE_PROFILES', 'COMPOSE_PROJECT_NAME')}
    env.update({key: '/data/' + name for key, name in settings['files'].items()})
    env.update(VALHALLA_DATA_DIR=settings['data_dir'], VALHALLA_ROUTING_DIR=str(routing_directory(settings)),
               SCENIC_WEATHER=str(settings['weather']).lower(),
               SCENIC_SEARCH_SECONDS='90')
    env['BGM_ENV_FILE'] = str(BGM_SECRETS if BGM_SECRETS.is_file() else DIRECTORY / 'bgm.defaults.env')
    command = ['docker', 'compose', '-p', 'theacar-routing', '--project-directory', str(DIRECTORY), '-f', str(DIRECTORY / 'compose.yaml'),
               '--profile', 'public', '--profile', 'scenic', *args]
    return subprocess.run(command, cwd=ROOT, env=env, check=True)


def checksum(path):
    with path.open('rb') as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
        return digest.hexdigest()


def database_check(path, required=()):
    if not path.is_file():
        raise ValueError('Missing database: ' + path.name + '. Run build-data before up.')
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)) as db:
        if db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('SQLite integrity check failed: ' + path.name)
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not set(required) <= tables:
            raise ValueError('Wrong database schema: ' + path.name)


def validate_data(settings):
    sys.path.insert(0, str(ROOT / 'infra/scenic'))
    from catalog import Catalog
    from places import search
    from recommendation.store import ScenicStore

    data = Path(settings['data_dir'])
    paths = {key: data / name for key, name in settings['files'].items()}
    for key, path in paths.items():
        database_check(path, TABLES[key])
    catalog = Catalog(paths['SCENIC_DATABASE'])
    try:
        pbf = data / 'region.osm.pbf'
        if not pbf.is_file():
            raise ValueError('Missing region.osm.pbf. Keep the original source with its databases.')
        source_hash = checksum(pbf)
        if catalog.source.get('source_sha256') != source_hash:
            raise ValueError('The PBF differs from the scenic catalog. Rebuild in a new data directory.')
        store = ScenicStore(catalog, recommended_roads=paths['SCENIC_RECOMMENDED_ROADS'],
                            landscapes=paths['SCENIC_LANDSCAPES'], terrain=paths['SCENIC_TERRAIN'],
                            context=paths['SCENIC_CONTEXT'], landcover=paths['SCENIC_LANDCOVER'])
        for database, field in ((store.terrain, 'horizon'), (store.context, 'poi'), (store.landcover, 'classified')):
            if json.loads(database.metadata.get('counts', '{}')).get(field, 0) <= 0:
                raise ValueError('Empty source database: ' + database.name)
        if not catalog.contains((43.0618, 141.3545)) or not catalog.contains((41.7687, 140.7288)):
            raise ValueError('This setup requires a Hokkaido catalog containing Sapporo and Hakodate.')
        if not store.recommendations.by_segment:
            raise ValueError('No active registered roads were loaded.')
        if not search(paths['PLACES_DATABASE'], '函館'):
            raise ValueError('The places database has no Hakodate result. Check its source region.')
        with closing(sqlite3.connect(paths['PLACES_DATABASE'].as_uri() + '?mode=ro', uri=True)) as db:
            metadata = dict(db.execute('SELECT key,value FROM metadata'))
        if metadata.get('source_sha256') and metadata['source_sha256'] != source_hash:
            raise ValueError('The places database was built from a different PBF.')
        if not metadata.get('source_sha256'):
            print('WARN: legacy places database has no source hash; rebuild it for provenance verification.')
        provenance = routing_directory(settings) / 'routing-source.json'
        if provenance.is_file():
            if json.loads(provenance.read_text()).get('source_sha256') != source_hash:
                raise ValueError('Valhalla and scenic databases use different PBF sources.')
        else:
            print('WARN: legacy Valhalla tiles have no routing-source.json; their original PBF cannot be verified.')
        count = catalog.db.execute('SELECT count(*) FROM road').fetchone()[0]
        print(f'OK: seven databases, catalog signatures, Hokkaido bounds and place search ({count:,} road samples).')
    finally:
        catalog.close()


def validate_graph(settings):
    data = routing_directory(settings)
    if not (data / 'valhalla.json').is_file():
        raise ValueError('Missing valhalla.json. Run manage.py prepare first.')
    config = json.loads((data / 'valhalla.json').read_text())
    if not (data / 'admin.sqlite').is_file() or not any((data / 'tiles').rglob('*.gph')):
        raise ValueError('Missing Valhalla graph/admin data. Run prepare and check its exit status.')
    if config.get('mjolnir', {}).get('tile_dir') != '/data/tiles':
        raise ValueError('valhalla.json tile_dir must be /data/tiles for this setup.')
    provenance = data / 'routing-source.json'
    if 'routing_dir' in settings and not provenance.is_file():
        raise ValueError('The separate routing build has not completed: routing-source.json is missing.')
    profile = json.loads(provenance.read_text()) if provenance.is_file() else {}
    if profile.get('speed_profile') == SPEED_PROFILE:
        script = data / 'routing.lua'
        if (not script.is_file() or checksum(script) != profile.get('routing_lua_sha256') or
                config['mjolnir'].get('graph_lua_name') != '/data/routing.lua'):
            raise ValueError('Routing speed profile does not match its build record. Rebuild in a new directory.')
        print('OK: routing speed profile ' + SPEED_PROFILE)
    else:
        print('WARN: legacy routing speeds. Rebuild routing tiles to apply the Hokkaido speed profile.')
    limits = config.get('service_limits', {})
    auto = limits.get('auto', {})
    if (auto.get('max_locations', 0) < 128 or auto.get('max_distance', 0) < 1000000 or
            limits.get('max_alternates', 0) < 2):
        print('WARN: old Valhalla limits. Run upgrade-config, then up to recreate Valhalla.')


BGM_SECRETS = DIRECTORY / '.secrets' / 'bgm.env'


def bgm_secret_names(path=None):
    """bgm.env に値のある項目名。値は読み出さない。"""
    path = path or BGM_SECRETS
    if not path.is_file():
        return set()
    names = set()
    for line in path.read_text().splitlines():
        name, separator, value = line.partition('=')
        if separator and value.strip() and not name.strip().startswith('#'):
            names.add(name.strip())
    return names


def doctor(settings):
    validate_credentials(DIRECTORY / '.secrets')
    print('OK: token and gateway configuration match (token not printed).')
    # 景観BGMは任意。選曲はできるが、Spotify の資格情報が無いと選んだ曲を再生できない。
    names = bgm_secret_names()
    if not {'SPOTIFY_CLIENT_ID', 'SPOTIFY_CLIENT_SECRET'} <= names:
        print('WARN: BGM can choose tracks but cannot play them: add SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET to infra/valhalla/.secrets/bgm.env.')
    elif 'GEMINI_API_KEY' not in names:
        print('OK: BGM Spotify credentials present. GEMINI_API_KEY is not set, so /bgm/annotate is unavailable (catalog tracks still work).')
    else:
        print('OK: BGM Spotify and Gemini credentials present (values not printed).')
    validate_graph(settings)
    validate_data(settings)


def upgrade_config(settings):
    path = routing_directory(settings) / 'valhalla.json'
    original = path.read_bytes()
    config = json.loads(original)
    apply_routing_limits(config)
    if json.loads(original) == config:
        print('Valhalla limits already match; unchanged.')
        return
    suffix = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    backup = path.with_name('valhalla.json.backup-' + suffix)
    with backup.open('xb') as stream:
        stream.write(original)
    temporary = path.with_name('valhalla.json.updating')
    with temporary.open('x') as stream:
        json.dump(config, stream, indent=2)
    temporary.replace(path)
    print('Routing limits updated; original preserved as', backup.name)
    print('Run manage.py up when no one is navigating; the configuration requires container recreation.')


def build_data(settings, only=None):
    data = Path(settings['data_dir'])
    pbf = data / 'region.osm.pbf'
    python = ROOT / '.venv-scenic/bin/python'
    if not pbf.is_file() or not python.is_file():
        raise ValueError('region.osm.pbf and .venv-scenic are required. Follow the clean setup guide first.')
    paths = {key: data / name for key, name in settings['files'].items()}
    catalog = paths['SCENIC_DATABASE']
    stages = {
        'roads': ('SCENIC_DATABASE', 'import_region.py', [pbf, catalog]),
        'places': ('PLACES_DATABASE', 'import_places.py', [pbf, paths['PLACES_DATABASE']]),
        'landscapes': ('SCENIC_LANDSCAPES', 'import_landscapes.py', [pbf, catalog, paths['SCENIC_LANDSCAPES']]),
        'registry': ('SCENIC_RECOMMENDED_ROADS', 'import_recommended_roads.py', []),
        'context': ('SCENIC_CONTEXT', 'import_context.py', [pbf, catalog, paths['SCENIC_CONTEXT']]),
        'terrain': ('SCENIC_TERRAIN', 'import_terrain.py', [catalog, data / 'gsi-dem', paths['SCENIC_TERRAIN'], '--download', '--max-tiles', '550']),
        'landcover': ('SCENIC_LANDCOVER', 'import_worldcover.py', [catalog, data / 'esa-worldcover-2021', paths['SCENIC_LANDCOVER'], '--download', '--max-bytes', '120000000']),
    }
    for name, (key, script, args) in stages.items():
        if only and name != only:
            continue
        output = paths[key]
        if output.exists():
            database_check(output, TABLES[key])
            print('Existing database preserved:', output.name, flush=True)
            continue
        if output.with_suffix('.sqlite.building').exists() and name != 'roads':
            raise ValueError(f'Interrupted build: {output.name}.building. Select a new filename in .routing.json; do not delete completed data.')
        if name == 'roads' and output.with_suffix('.sqlite.building').exists():
            args = [*args, '--resume']
        if name == 'registry':
            sys.path.insert(0, str(ROOT / 'infra/scenic'))
            from fetch_road_geometries import fetch
            archive_dir = data / 'recommendation-sources'
            report = fetch(archive_dir)
            resource = next(item for item in report['resources'] if item['id'] == 'fee75228-c8ff-43b6-9c53-b67a7389b2ad')
            models = next(item for item in report['resources'] if item['id'] == '30ca6984-bc52-49b5-8971-e9260d9dbaf5')
            args = [pbf, catalog, ROOT / 'infra/scenic/data/hokkaido-recommended-roads.json', output,
                    '--official-sections', archive_dir / resource['file'],
                    '--model-courses', archive_dir / models['file'], '--require-official-complete']
        print('Building:', name, flush=True)
        subprocess.run([str(python), '-u', str(ROOT / 'infra/scenic' / script), *map(str, args)], cwd=ROOT, check=True)
    if only is None:
        validate_data(settings)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def validate_endpoint(endpoint):
    url = urlsplit(endpoint)
    if (url.username or url.password or url.query or url.fragment or url.path not in ('', '/') or
            not url.hostname or (url.scheme != 'https' and not (url.scheme == 'http' and url.hostname in ('127.0.0.1', 'localhost', '::1')))):
        raise ValueError('Use an HTTPS base URL without a path, query or credentials; HTTP is allowed only on loopback.')
    return endpoint.rstrip('/')


def check_registry_inventory(settings, inventory):
    path = Path(settings['data_dir']) / settings['files']['SCENIC_RECOMMENDED_ROADS']
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)) as db:
        metadata = dict(db.execute('SELECT key,value FROM metadata'))
        ranks = dict.fromkeys(('signature', 'panorama', 'recommended', 'scenic'), 0)
        for raw, in db.execute("SELECT payload FROM recommendation WHERE status='active'"):
            ranks[json.loads(raw)['rank']] += 1
        pending = db.execute("SELECT count(*) FROM recommendation WHERE status='pending'").fetchone()[0]
    expected = {'version': metadata['version'], 'active_count': sum(ranks.values()),
                'rank_counts': ranks, 'pending_count': pending}
    if any(inventory.get(key) != value for key, value in expected.items()):
        raise ValueError('API road registry differs from .routing.json. Recreate scenic with the saved configuration and verify the endpoint.')


def check_api(settings, endpoint):
    endpoint = validate_endpoint(endpoint)
    token = validate_credentials(DIRECTORY / '.secrets')
    opener = build_opener(NoRedirect)

    def call(path, expected=200, auth=token, body=None):
        # Cloudflare は Python の既定の User-Agent を 1010（403）で拒否するので、公開 URL の検査用に名乗る。
        headers = {'Content-Type': 'application/json', 'User-Agent': 'TheaCar-manage/1'}
        if auth:
            headers['Authorization'] = 'Bearer ' + auth
        request = Request(endpoint + path, json.dumps(body).encode() if body else None, headers)
        try:
            with opener.open(request, timeout=30) as response:
                status, raw = response.status, response.read(24_000_001)
        except HTTPError as error:
            with error:
                status, raw = error.code, b''
        if status != expected:
            raise ValueError(f'{path}: expected HTTP {expected}, got {status}. Check the URL, token, profiles and gateway logs.')
        if expected == 200:
            if len(raw) > 24_000_000:
                raise ValueError('API response exceeds the check limit.')
            return json.loads(raw)

    call('/status', 401, auth=None)
    call('/status', 401, auth='invalid')
    call('/status')
    status = call('/route-recommendations/data-status')
    sources = {item['id']: item['status'] for item in status['sources']}
    for name in ('osm-roads', 'reviewed-scenic-roads', 'osm-landscapes', 'gsi-terrain', 'osm-context', 'esa-worldcover'):
        if sources.get(name) != 'loaded':
            raise ValueError('API source not loaded: ' + name)
    if settings['weather'] and sources.get('open-meteo') != 'configured':
        raise ValueError('Weather was enabled locally but is not enabled on the server.')
    check_registry_inventory(settings, next(item for item in status['sources'] if item['id'] == 'reviewed-scenic-roads'))
    if not call('/search?q=%E5%87%BD%E9%A4%A8'):
        raise ValueError('Hakodate search returned no places.')
    route = call('/route', body={'locations': [{'lat': 43.0686, 'lon': 141.3507}, {'lat': 43.048, 'lon': 141.354}],
                                 'costing': 'auto', 'units': 'kilometers', 'alternates': 2})
    if route['trip']['status'] != 0 or not route['trip']['legs']:
        raise ValueError('Sapporo route check did not produce a route.')
    call('/bgm/modes', 401, auth=None)
    modes = call('/bgm/modes')
    if modes.get('default') not in {mode.get('id') for mode in modes.get('modes', [])}:
        raise ValueError('BGM API returned an unexpected mode list.')
    bgm = call('/bgm/recommend', body={'members': [{'id': 'check', 'name': 'check'}], 'pool': [], 'mode': 'discover',
                                       'seed': 1, 'scene': {'features': {'sea_proximity': 1}}})
    if not bgm.get('chosen') or bgm.get('pool_size', 0) < 1000:
        raise ValueError('BGM catalog is not loaded. Run manage.py logs and check the bgm container.')
    # 選んだ曲を再生するには Spotify の曲 ID への照合が要る。BGM は任意なので、使えなくても全体は止めない。
    try:
        links = call('/bgm/link/spotify', body={'track_ids': [bgm['chosen']['track_id']]})
        bgm_playable = bool(links.get('links'))
    except ValueError:
        bgm_playable = False
    if not bgm_playable:
        print('WARN: BGM cannot match tracks to Spotify, so the app cannot play them. Add SPOTIFY_CLIENT_ID and '
              'SPOTIFY_CLIENT_SECRET to infra/valhalla/.secrets/bgm.env, then run manage.py up.')
    print('OK: unauthenticated/invalid tokens rejected; valid token, all sources, search, Sapporo route and BGM accepted.'
          + ('' if bgm_playable else ' (BGM playback not configured)'))
    print('Weather configured does not guarantee that forecasts are available at every route point.')


def wait_gateway():
    opener = build_opener(NoRedirect)
    for attempt in range(15):
        try:
            with opener.open('http://127.0.0.1:8003/status', timeout=2):
                raise ValueError('Gateway accepted unauthenticated traffic. Do not expose it.')
        except HTTPError as error:
            code = error.code
            error.close()
            if code == 401:
                return
        except OSError:
            pass
        if attempt < 14:
            time.sleep(1)
    raise ValueError('The authenticated gateway did not become ready. Run manage.py logs.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    sub = parser.add_subparsers(dest='command', required=True)
    init = sub.add_parser('init')
    init.add_argument('--data-dir', type=Path, required=True)
    build = sub.add_parser('build-data')
    build.add_argument('--only', choices=['roads', 'places', 'landscapes', 'registry', 'context', 'terrain', 'landcover'])
    check = sub.add_parser('check')
    check.add_argument('--endpoint', default='http://127.0.0.1:8003')
    for name in ('doctor', 'download-pbf', 'prepare', 'up', 'status', 'logs', 'down', 'upgrade-config'):
        sub.add_parser(name)
    args = parser.parse_args()
    if sys.version_info < (3, 11):
        raise ValueError('Use Python 3.11 or later; the setup guide uses Python 3.12.')
    if args.command == 'init':
        initialize(args.config, args.data_dir)
        return
    settings = load_settings(args.config)
    if args.command == 'doctor':
        doctor(settings)
    elif args.command == 'download-pbf':
        download_pbf(settings)
    elif args.command == 'build-data':
        build_data(settings, args.only)
    elif args.command == 'upgrade-config':
        upgrade_config(settings)
    elif args.command == 'check':
        check_api(settings, args.endpoint)
    elif args.command == 'prepare':
        data = routing_directory(settings)
        if not (data / 'region.osm.pbf').is_file():
            raise ValueError('Download region.osm.pbf into the saved data directory before prepare.')
        if (data / 'tiles').exists():
            raise ValueError('Existing tiles preserved. Use a new data directory for rebuilding.')
        compose(settings, '--profile', 'tools', 'run', '--rm', 'prepare')
    elif args.command == 'up':
        doctor(settings)
        # bgm はリポジトリのコードからイメージを作る。コードの更新を反映するため毎回ビルドする（変更が無ければキャッシュ）。
        compose(settings, 'up', '-d', '--build', '--force-recreate', '--wait', '--wait-timeout', '180')
        wait_gateway()
        check_api(settings, 'http://127.0.0.1:8003')
    elif args.command == 'status':
        compose(settings, 'ps')
    elif args.command == 'logs':
        compose(settings, 'logs', '--tail', '80', 'valhalla', 'scenic', 'bgm', 'public-gateway')
    elif args.command == 'down':
        compose(settings, 'down')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, sqlite3.Error, subprocess.CalledProcessError, KeyError, StopIteration) as error:
        # Never dump HTTP headers, a request body, credential contents or subprocess environments.
        detail = str(error) if isinstance(error, (ValueError, sqlite3.Error)) else type(error).__name__
        raise SystemExit('Setup stopped: ' + detail + '\nSee docs/environment-setup.md; existing data and credentials were preserved.')
