import json
import pathlib
import sqlite3
import subprocess
import hashlib
from datetime import datetime, timezone
from contextlib import closing


SPEED_PROFILE = 'hokkaido-roads-v1'


def routing_lua():
    directory = pathlib.Path(__file__).parent / 'lua'
    return '\n'.join((directory / name).read_text() for name in
                     ('graph-3.5.1.lua', 'hokkaido-speeds.lua'))


def apply_routing_limits(config):
    config['service_limits']['auto'].update({'max_locations': 128, 'max_distance': 1000000})
    config['service_limits']['max_alternates'] = 2
    return config


def correct_regional_admins(database):
    with closing(sqlite3.connect(database)) as connection, connection:
        areas = connection.execute(
            'SELECT rowid, admin_level, name, iso_code FROM admins'
        ).fetchall()
        if len(areas) == 1 and areas[0][1:] == (4, '北海道', '01'):
            connection.execute(
                'UPDATE admins SET drive_on_right = 0 WHERE rowid = ?',
                (areas[0][0],),
            )
            print('Hokkaido regional extract: using left-hand traffic.', flush=True)


def prepare(root=None):
    root = pathlib.Path('/data') if root is None else pathlib.Path(root)
    source = root / 'region.osm.pbf'
    if not source.is_file():
        raise SystemExit('Place a regional OSM extract at data/region.osm.pbf first.')
    if (root / 'tiles').exists():
        raise SystemExit('Existing tiles found. Build a new dataset in a separate data directory.')
    config = json.loads(subprocess.check_output(['valhalla_build_config'], text=True))
    config['mjolnir'].update({
        'tile_dir': '/data/tiles',
        'tile_extract': '',
        'admin': '/data/admin.sqlite',
        'concurrency': 2,
        'id_table_size': 10000000,
    })
    config['mjolnir'].pop('traffic_extract', None)
    config['mjolnir'].pop('default_speeds_config', None)
    script = routing_lua()
    (root / 'routing.lua').write_text(script)
    config['mjolnir']['graph_lua_name'] = '/data/routing.lua'
    config['loki']['actions'] = ['route', 'status']
    config['httpd']['service']['listen'] = 'tcp://*:8002'
    apply_routing_limits(config)
    config_path = root / 'valhalla.json'
    config_path.write_text(json.dumps(config, indent=2))
    subprocess.run(['valhalla_build_admins', '-c', str(config_path), str(source)], check=True)
    correct_regional_admins(root / 'admin.sqlite')
    subprocess.run(['valhalla_build_tiles', '-c', str(config_path), str(source)], check=True)
    with source.open('rb') as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    (root / 'routing-source.json').write_text(json.dumps({
        'schema': 1, 'source_sha256': digest.hexdigest(), 'source_file': source.name,
        'built_at': datetime.now(timezone.utc).isoformat(),
        'speed_profile': SPEED_PROFILE,
        'routing_lua_sha256': hashlib.sha256(script.encode()).hexdigest(),
    }, indent=2))


if __name__ == '__main__':
    prepare()
