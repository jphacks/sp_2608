import pathlib
import sqlite3
import tempfile
import unittest
import json
import hashlib
from unittest.mock import patch
from contextlib import closing

from prepare import correct_regional_admins, apply_routing_limits, prepare, routing_lua, SPEED_PROFILE


class RegionalAdminTest(unittest.TestCase):
    def test_preparation_records_source_only_after_successful_tile_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            source = root / 'region.osm.pbf'
            source.write_bytes(b'test-pbf')
            config = {'mjolnir': {}, 'loki': {}, 'httpd': {'service': {}}, 'service_limits': {'auto': {}}}
            with patch('prepare.subprocess.check_output', return_value=json.dumps(config)), \
                    patch('prepare.correct_regional_admins'), patch('prepare.subprocess.run') as run:
                prepare(root)
                self.assertEqual(run.call_count, 2)
            self.assertEqual(json.loads((root / 'routing-source.json').read_text())['source_sha256'],
                             hashlib.sha256(source.read_bytes()).hexdigest())
            provenance = json.loads((root / 'routing-source.json').read_text())
            self.assertEqual(SPEED_PROFILE, provenance['speed_profile'])
            self.assertEqual(hashlib.sha256(routing_lua().encode()).hexdigest(), provenance['routing_lua_sha256'])
            self.assertEqual('/data/routing.lua', json.loads((root / 'valhalla.json').read_text())['mjolnir']['graph_lua_name'])

    def test_failed_tile_build_does_not_claim_source_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            (root / 'region.osm.pbf').write_bytes(b'test-pbf')
            config = {'mjolnir': {}, 'loki': {}, 'httpd': {'service': {}}, 'service_limits': {'auto': {}}}
            with patch('prepare.subprocess.check_output', return_value=json.dumps(config)), \
                    patch('prepare.correct_regional_admins'), patch('prepare.subprocess.run', side_effect=RuntimeError):
                with self.assertRaises(RuntimeError):
                    prepare(root)
            self.assertFalse((root / 'routing-source.json').exists())

    def test_limits_allow_long_scenic_routes_and_standard_alternates(self):
        config = {'service_limits': {'auto': {'other': 7}, 'max_alternates': 0}}
        apply_routing_limits(config)
        self.assertEqual(config['service_limits']['auto'], {'other': 7, 'max_locations': 128, 'max_distance': 1000000})
        self.assertEqual(config['service_limits']['max_alternates'], 2)

    def traffic_sides(self, areas):
        with tempfile.TemporaryDirectory() as directory:
            database = pathlib.Path(directory) / 'admin.sqlite'
            with closing(sqlite3.connect(database)) as connection, connection:
                connection.execute(
                    'CREATE TABLE admins (admin_level INTEGER, name TEXT, '
                    'iso_code TEXT, drive_on_right INTEGER)'
                )
                connection.executemany('INSERT INTO admins VALUES (?, ?, ?, ?)', areas)
            correct_regional_admins(database)
            with closing(sqlite3.connect(database)) as connection:
                return connection.execute('SELECT drive_on_right FROM admins ORDER BY rowid').fetchall()

    def test_hokkaido_without_country_uses_left_hand_traffic(self):
        self.assertEqual(self.traffic_sides([(4, '北海道', '01', 1)]), [(0,)])

    def test_country_data_is_not_overridden(self):
        self.assertEqual(self.traffic_sides([
            (2, '日本', 'JP', 0), (4, '北海道', '01', 0),
        ]), [(0,), (0,)])

    def test_other_regions_are_not_overridden(self):
        self.assertEqual(self.traffic_sides([(4, 'Other', '01', 1)]), [(1,)])


if __name__ == '__main__':
    unittest.main()
