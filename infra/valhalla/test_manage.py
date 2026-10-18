import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import manage
from auth import ensure_credentials


class ManageTest(unittest.TestCase):
    def settings(self, directory):
        return {'schema': 1, 'data_dir': str(directory), 'files': manage.FILES.copy(), 'weather': True}

    def test_download_validates_header_length_and_preserves_existing_source(self):
        content = b'\x00\x00\x00\x0bOSMHeader--' + b'data'
        class Response(io.BytesIO):
            headers = {'Content-Length': str(len(content))}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch('sys.stdout', new_callable=io.StringIO):
                manage.download_pbf(self.settings(root), lambda *args, **kwargs: Response(content))
            self.assertEqual((root / 'region.osm.pbf').read_bytes(), content)
            self.assertEqual(json.loads((root / 'pbf-download.json').read_text())['bytes'], len(content))
            with self.assertRaises(ValueError):
                manage.download_pbf(self.settings(root), lambda *args: self.fail('Must not download'))

    def test_incomplete_download_does_not_become_pbf(self):
        class Response(io.BytesIO):
            headers = {'Content-Length': '100'}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                manage.download_pbf(self.settings(root), lambda *args, **kwargs: Response(b'short'))
            self.assertFalse((root / 'region.osm.pbf').exists())

    def test_config_requires_complete_plain_database_filenames(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'settings.json'
            settings = self.settings(Path(temporary))
            path.write_text(json.dumps(settings))
            self.assertEqual(manage.load_settings(path), settings)
            for invalid in ('../other.sqlite', 'unfinished.sqlite.building', '/data/other.sqlite'):
                settings['files']['SCENIC_DATABASE'] = invalid
                path.write_text(json.dumps(settings))
                with self.assertRaises(ValueError):
                    manage.load_settings(path)

    def test_config_init_preserves_existing_token_and_settings(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / 'settings.json'
            with patch.object(manage, 'DIRECTORY', root), patch('sys.stdout', new_callable=io.StringIO):
                manage.initialize(config, root / 'data')
                original_config = config.read_bytes()
                original_token = (root / '.secrets/routing.token').read_bytes()
                manage.initialize(config, root / 'data')
                self.assertEqual(original_config, config.read_bytes())
                self.assertEqual(original_token, (root / '.secrets/routing.token').read_bytes())
                with self.assertRaises(ValueError):
                    manage.initialize(config, root / 'other')

    def test_saved_settings_override_stale_exports(self):
        with tempfile.TemporaryDirectory() as temporary:
            settings = self.settings(Path(temporary))
            with patch.dict('os.environ', {'VALHALLA_DATA_DIR': '/old', 'SCENIC_LANDSCAPES': '/old/db',
                                           'SCENIC_SUPPLEMENTS': '/old/feature-overrides.json'}), patch('subprocess.run') as run:
                manage.compose(settings, 'ps')
                env = run.call_args.kwargs['env']
                self.assertEqual(env['VALHALLA_DATA_DIR'], temporary)
                self.assertEqual(env['VALHALLA_ROUTING_DIR'], temporary)
                self.assertEqual(env['SCENIC_LANDSCAPES'], '/data/landscapes-hokkaido-v4.sqlite')
                self.assertNotIn('SCENIC_SUPPLEMENTS', env)
                self.assertEqual(env['SCENIC_WEATHER'], 'true')

    def test_routing_graph_can_be_switched_without_changing_scenic_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            settings = self.settings(Path(temporary))
            settings['routing_dir'] = str(Path(temporary) / 'routing-v1')
            with patch('subprocess.run') as run:
                manage.compose(settings, 'ps')
                env = run.call_args.kwargs['env']
                self.assertEqual(temporary, env['VALHALLA_DATA_DIR'])
                self.assertEqual(settings['routing_dir'], env['VALHALLA_ROUTING_DIR'])
            config = Path(temporary) / 'settings.json'
            config.write_text(json.dumps(settings))
            self.assertEqual(manage.load_settings(config), settings)
            settings['routing_dir'] = 'relative/path'
            config.write_text(json.dumps(settings))
            with self.assertRaisesRegex(ValueError, 'absolute'):
                manage.load_settings(config)

    def test_bgm_env_uses_existing_secrets_or_empty_defaults(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            secret = root / 'bgm.env'
            with patch.object(manage, 'BGM_SECRETS', secret), patch('subprocess.run') as run:
                manage.compose(self.settings(root), 'ps')
                self.assertEqual(str(manage.DIRECTORY / 'bgm.defaults.env'), run.call_args.kwargs['env']['BGM_ENV_FILE'])
                secret.write_text('SPOTIFY_CLIENT_ID=test\n')
                manage.compose(self.settings(root), 'ps')
                self.assertEqual(str(secret), run.call_args.kwargs['env']['BGM_ENV_FILE'])

    def test_endpoint_disallows_token_leaking_redirect_targets_and_plain_remote_http(self):
        self.assertEqual(manage.validate_endpoint('https://routing.example.com/'), 'https://routing.example.com')
        self.assertEqual(manage.validate_endpoint('http://127.0.0.1:8003'), 'http://127.0.0.1:8003')
        for value in ('http://example.com', 'https://user:secret@example.com', 'https://example.com/route',
                      'https://example.com?token=abc', 'https://example.com/#token', 'file:///tmp/a'):
            with self.assertRaises(ValueError):
                manage.validate_endpoint(value)

    def test_upgrade_only_changes_limits_and_preserves_backup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = {'service_limits': {'auto': {'max_locations': 128, 'max_distance': 500000}, 'max_alternates': 0},
                      'mjolnir': {'tile_dir': '/data/tiles'}}
            path = root / 'valhalla.json'
            original = json.dumps(config).encode()
            path.write_bytes(original)
            with patch('sys.stdout', new_callable=io.StringIO):
                manage.upgrade_config(self.settings(root))
                manage.upgrade_config(self.settings(root))
            backups = list(root.glob('valhalla.json.backup-*'))
            self.assertEqual(len(backups), 1)
            self.assertEqual(backups[0].read_bytes(), original)
            updated = json.loads(path.read_text())
            self.assertEqual(updated['mjolnir'], config['mjolnir'])
            self.assertEqual(updated['service_limits']['max_alternates'], 2)

    def test_speed_profile_requires_unchanged_script_and_completed_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            graph = root / 'routing'
            graph.mkdir()
            (graph / 'tiles').mkdir()
            (graph / 'tiles/test.gph').touch()
            (graph / 'admin.sqlite').touch()
            (graph / 'valhalla.json').write_text(json.dumps({
                'mjolnir': {'tile_dir': '/data/tiles', 'graph_lua_name': '/data/routing.lua'},
                'service_limits': {'auto': {'max_locations': 128, 'max_distance': 1000000}, 'max_alternates': 2}}))
            settings = {**self.settings(root), 'routing_dir': str(graph)}
            with self.assertRaisesRegex(ValueError, 'has not completed'):
                manage.validate_graph(settings)
            (graph / 'routing.lua').write_text('test-script')
            (graph / 'routing-source.json').write_text(json.dumps({
                'speed_profile': manage.SPEED_PROFILE,
                'routing_lua_sha256': manage.checksum(graph / 'routing.lua')}))
            with patch('sys.stdout', new_callable=io.StringIO):
                manage.validate_graph(settings)
            (graph / 'routing.lua').write_text('changed')
            with self.assertRaisesRegex(ValueError, 'does not match'):
                manage.validate_graph(settings)

    def test_missing_database_is_not_silently_created(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'missing.sqlite'
            with self.assertRaises(ValueError):
                manage.database_check(path)
            self.assertFalse(path.exists())

    def test_empty_sqlite_is_not_a_valid_places_database(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'places.sqlite'
            sqlite3.connect(path).close()
            with self.assertRaisesRegex(ValueError, 'Wrong database schema'):
                manage.database_check(path, manage.TABLES['PLACES_DATABASE'])

    def test_build_refuses_interrupted_sidecar_without_overwriting(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / '.venv-scenic/bin').mkdir(parents=True)
            (root / '.venv-scenic/bin/python').touch()
            (root / 'region.osm.pbf').touch()
            building = root / 'terrain-hokkaido-v1.sqlite.building'
            building.write_bytes(b'preserved')
            with patch.object(manage, 'ROOT', root):
                with self.assertRaisesRegex(ValueError, 'Interrupted build'):
                    manage.build_data(self.settings(root), 'terrain')
            self.assertEqual(building.read_bytes(), b'preserved')

    def test_registry_build_keeps_official_geometry_and_model_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / '.venv-scenic/bin').mkdir(parents=True)
            (root / '.venv-scenic/bin/python').touch()
            (root / 'region.osm.pbf').touch()
            report = {'resources': [
                {'id': 'fee75228-c8ff-43b6-9c53-b67a7389b2ad', 'file': 'selected.zip'},
                {'id': '30ca6984-bc52-49b5-8971-e9260d9dbaf5', 'file': 'models.zip'}]}
            fetcher = SimpleNamespace(fetch=lambda directory: report)
            with patch.object(manage, 'ROOT', root), patch.dict('sys.modules', {'fetch_road_geometries': fetcher}), \
                    patch('manage.subprocess.run') as run, patch('sys.stdout', new_callable=io.StringIO):
                manage.build_data(self.settings(root), 'registry')
            command = run.call_args.args[0]
            self.assertEqual(str(root / 'recommendation-sources/selected.zip'),
                             command[command.index('--official-sections') + 1])
            self.assertEqual(str(root / 'recommendation-sources/models.zip'),
                             command[command.index('--model-courses') + 1])
            self.assertIn('--require-official-complete', command)

    def test_api_inventory_must_match_the_saved_registry_not_merely_be_loaded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            settings = self.settings(root)
            path = root / settings['files']['SCENIC_RECOMMENDED_ROADS']
            with sqlite3.connect(path) as db:
                db.executescript('CREATE TABLE metadata(key TEXT,value TEXT);'
                                 'CREATE TABLE recommendation(status TEXT,payload TEXT);')
                db.execute("INSERT INTO metadata VALUES ('version','new')")
                db.executemany('INSERT INTO recommendation VALUES (?,?)',
                               [('active', '{"rank":"panorama"}'), ('pending', '{"rank":"scenic"}')])
            inventory = {'version': 'new', 'active_count': 1, 'pending_count': 1,
                         'rank_counts': {'signature': 0, 'panorama': 1, 'recommended': 0, 'scenic': 0}}
            manage.check_registry_inventory(settings, inventory)
            for key, wrong in [('version', 'old'), ('active_count', 32), ('pending_count', 0), ('rank_counts', {})]:
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'differs'):
                    manage.check_registry_inventory(settings, {**inventory, key: wrong})
            with self.assertRaisesRegex(ValueError, 'differs'):
                manage.check_registry_inventory(settings, {'status': 'loaded'})

    def test_api_check_requires_loaded_satellite_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ensure_credentials(root / '.secrets')
            class Response:
                status = 200
                def __enter__(self):
                    return self
                def __exit__(self, *args):
                    pass
                def read(self, limit):
                    return json.dumps({'sources': [{'id': name, 'status': 'loaded'} for name in
                        ('osm-roads', 'reviewed-scenic-roads', 'osm-landscapes', 'gsi-terrain', 'osm-context')]}).encode()
            def fetch(request, timeout):
                auth = request.get_header('Authorization')
                if not auth or auth == 'Bearer invalid':
                    from urllib.error import HTTPError
                    raise HTTPError(request.full_url, 401, 'Unauthorized', {}, None)
                return Response()
            with patch.object(manage, 'DIRECTORY', root), patch('manage.build_opener') as opener:
                opener.return_value.open.side_effect = fetch
                with self.assertRaisesRegex(ValueError, 'esa-worldcover'):
                    manage.check_api(self.settings(root), 'http://127.0.0.1:8003')


    def test_bgm_secret_names_lists_only_filled_keys_without_reading_values_out(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'bgm.env'
            self.assertEqual(manage.bgm_secret_names(path), set())
            path.write_text('SPOTIFY_CLIENT_ID=abc\nSPOTIFY_CLIENT_SECRET=\n# GEMINI_API_KEY=x\nbroken line\n')
            self.assertEqual(manage.bgm_secret_names(path), {'SPOTIFY_CLIENT_ID'})


if __name__ == '__main__':
    unittest.main()
