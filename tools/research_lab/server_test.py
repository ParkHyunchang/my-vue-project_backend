import copy
import json
import tempfile
import threading
import unittest
from datetime import datetime, timezone
from pathlib import Path

from research_lab.server import Controller, Conflict, InstanceLock, handler_class
from research_lab.feed import normalize


class FakeLab:
    def __init__(self):
        self.calls = []

    def status(self):
        return copy.deepcopy({'revision': 0, 'lastMarketDate': None,
                              'candidates': [{'eligible': True, 'reasons': []}]})

    def run_cycle(self, path):
        self.calls.append(('cycle', str(path)))

    def approve(self, *args):
        self.calls.append(('approve', args))
        return self.status()

    def reject(self, *args):
        self.calls.append(('reject', args))
        return self.status()


class ServerTests(unittest.TestCase):
    def test_scheduler_setting_survives_restart_without_running_itself(self):
        with tempfile.TemporaryDirectory() as folder:
            lab = FakeLab()
            c = Controller(folder, lab=lab, provider=lambda: 'fixed')
            self.assertFalse(c.enabled)
            c.set_scheduler(True)
            restored = Controller(folder, lab=lab)
            self.assertTrue(restored.enabled)
            self.assertEqual(lab.calls, [])
            restored.set_scheduler(False)
            self.assertFalse(Controller(folder, lab=lab).enabled)

    def test_concurrent_cycle_and_approval_are_blocked(self):
        entered, finish = threading.Event(), threading.Event()
        def provider():
            entered.set()
            finish.wait(2)
            return 'fixed'
        with tempfile.TemporaryDirectory() as folder:
            c = Controller(folder, lab=FakeLab(), provider=provider)
            c.start_cycle()
            self.assertTrue(entered.wait(1))
            with self.assertRaises(Conflict):
                c.start_cycle()
            with self.assertRaises(Conflict):
                c.decision('approve', dict(candidateId='one', reportId='report', expectedRevision=1))
            finish.set()
            c.worker.join(3)
            self.assertFalse(c.running)
            self.assertEqual(len(c.lab.calls), 1)

    def test_failed_fetch_blocks_old_report_approval(self):
        with tempfile.TemporaryDirectory() as folder:
            c = Controller(folder, lab=FakeLab(), provider=lambda: (_ for _ in ()).throw(ValueError('bad data')))
            c.start_cycle()
            c.worker.join(2)
            self.assertEqual(c.error, 'bad data')
            self.assertFalse(c.status()['candidates'][0]['eligible'])
            with self.assertRaises(Conflict):
                c.decision('approve', dict(candidateId='one', reportId='report', expectedRevision=1))
            self.assertEqual(c.lab.calls, [])
            restored = Controller(folder, lab=FakeLab())
            self.assertEqual(restored.error, 'bad data')
            with self.assertRaises(Conflict):
                restored.decision('approve', dict(candidateId='one', reportId='report', expectedRevision=1))

    def test_no_arbitrary_decision_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            c = Controller(folder, lab=FakeLab())
            for body in ({}, {'candidateId': 'x', 'reportId': 'y', 'expectedRevision': True},
                         {'candidateId': 'x', 'reportId': 'y', 'expectedRevision': 1, 'force': True}):
                with self.assertRaises(ValueError):
                    c.decision('approve', body)
            self.assertEqual(c.lab.calls, [])

    def test_host_origin_and_secret_all_required(self):
        cls = handler_class(None, 'local-test-token')
        h = object.__new__(cls)
        h.client_address = ('127.0.0.1', 1234)
        good = {'Host': '127.0.0.1:13130', 'Origin': 'http://127.0.0.1:13130',
                'X-Strategy-Lab-Local': '1', 'X-Strategy-Lab-Token': 'local-test-token'}
        h.headers = good
        self.assertTrue(h.authorized())
        for key, value in [('Host', 'evil.test'), ('Origin', 'https://evil.test'),
                           ('X-Strategy-Lab-Local', ''), ('X-Strategy-Lab-Token', '')]:
            h.headers = good | {key: value}
            self.assertFalse(h.authorized())

    def test_process_workspace_lock(self):
        with tempfile.TemporaryDirectory() as folder:
            lock = InstanceLock(Path(folder) / 'instance.lock')
            try:
                with self.assertRaises((OSError, RuntimeError)):
                    InstanceLock(Path(folder) / 'instance.lock')
            finally:
                lock.close()
            InstanceLock(Path(folder) / 'instance.lock').close()

    def test_feed_preserves_prices_and_excludes_uncompleted_day(self):
        # Synthetic transport fixture: no downloaded market archive is needed.
        source = {'meta': {'symbol': 'SPY', 'currency': 'USD'},
                  'timestamp': [int(datetime.fromisoformat(day).replace(tzinfo=timezone.utc).timestamp())
                                for day in ('2006-01-03', '2026-11-16', '2026-11-17')],
                  'indicators': {'quote': [{'open': [100, 110, 120], 'high': [102, 112, 122],
                                           'low': [99, 109, 119], 'close': [101, 111, 121],
                                           'volume': [1000, 2000, 3000]}],
                                 'adjclose': [{'adjclose': [90, 100, 110]}]}}
        raw = json.dumps({'chart': {'result': [source]}}).encode()
        rows = normalize(raw, 'SPY', '2026-11-17')
        self.assertEqual(2, len(rows))
        self.assertEqual(rows[-1]['date'], '2026-11-16')
        self.assertEqual(rows[0]['open'], source['indicators']['quote'][0]['open'][0])
        self.assertEqual(rows[0]['adj_close'], source['indicators']['adjclose'][0]['adjclose'][0])
        with self.assertRaises(ValueError):
            normalize(raw, 'QQQ', '2026-11-17')


if __name__ == '__main__':
    unittest.main()
