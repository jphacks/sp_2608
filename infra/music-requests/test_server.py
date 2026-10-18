import unittest

from server import Relay


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.now = 0
        self.relay = Relay(clock=lambda: self.now)
        created = self.relay.create()
        self.room, self.host = created['room'], created['host']
        self.track = dict(uri='spotify:track:' + 'a' * 22, title='曲', artist='歌手')

    def search(self):
        job = self.relay.submit(self.room, dict(kind='search', query='曲'))['id']
        self.relay.complete(self.room, self.host, dict(id=job, tracks=[self.track]))
        return job

    def test_search_add_and_repeated_ack(self):
        search = self.search()
        self.assertEqual([self.track], self.relay.result(self.room, search)['tracks'])
        job = self.relay.submit(self.room, dict(kind='add', uri=self.track['uri']))['id']
        self.assertEqual(job, self.relay.poll(self.room, self.host)['jobs'][0]['id'])
        for _ in range(2):
            self.relay.complete(self.room, self.host, dict(id=job))
        self.assertEqual([self.track], self.relay.result(self.room)['history'])
        self.assertEqual([], self.relay.poll(self.room, self.host)['jobs'])

    def test_failed_add_does_not_enter_history(self):
        self.search()
        job = self.relay.submit(self.room, dict(kind='add', uri=self.track['uri']))['id']
        self.relay.complete(self.room, self.host, dict(id=job, error='接続失敗'))
        self.assertEqual('failed', self.relay.result(self.room, job)['status'])
        self.assertEqual([], self.relay.result(self.room)['history'])

    def test_host_token_required_and_close_invalidates_invite(self):
        with self.assertRaises(PermissionError):
            self.relay.poll(self.room, 'wrong')
        self.relay.close(self.room, self.host)
        with self.assertRaises(ValueError):
            self.relay.result(self.room)

    def test_slow_operations_get_individual_heartbeats(self):
        for _ in range(3):
            self.relay.submit(self.room, dict(kind='search', query='曲'))
        for _ in range(3):
            jobs = self.relay.poll(self.room, self.host)['jobs']
            self.assertEqual(1, len(jobs))
            self.now += 90
            self.relay.complete(self.room, self.host, dict(id=jobs[0]['id'], tracks=[]))
        self.assertEqual([], self.relay.poll(self.room, self.host)['jobs'])

    def test_heartbeat_and_absolute_expiry(self):
        self.now = 121
        with self.assertRaises(ValueError):
            self.relay.poll(self.room, self.host)
        self.setUp()
        for _ in range(144):
            self.now += 100
            self.relay.poll(self.room, self.host)
        self.now += 1
        with self.assertRaises(ValueError):
            self.relay.poll(self.room, self.host)

    def test_only_valid_search_results_can_be_added(self):
        for uri in (self.track['uri'], [], None):
            with self.assertRaises(ValueError):
                self.relay.submit(self.room, dict(kind='add', uri=uri))
        job = self.relay.submit(self.room, dict(kind='search', query='曲'))['id']
        with self.assertRaises(ValueError):
            self.relay.complete(self.room, self.host, dict(id=job, tracks={}))
        self.relay.complete(self.room, self.host, dict(id=job, tracks=[dict(uri=[], title='x', artist='y'), self.track]))
        self.assertEqual([self.track], self.relay.result(self.room, job)['tracks'])

    def test_submission_limit(self):
        for _ in range(30):
            self.relay.submit(self.room, dict(kind='search', query='曲'))
        with self.assertRaises(ValueError):
            self.relay.submit(self.room, dict(kind='search', query='曲'))


if __name__ == '__main__':
    unittest.main()
