import hashlib
import hmac
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from research_lab import acceptance_test as fixtures
from research_lab.core import LabError
from research_lab.deployment import export_candidate, publish_market, signing_key, invalidate_market
from research_lab.server import Controller


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.RegistryAcceptanceTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.key = b'test-only-research-evidence-key-32bytes'

    def test_export_requires_current_qualified_evidence_and_preserves_paper_account(self):
        f = self.fixture
        ident, status = f.qualify_private_fixture()
        signed = export_candidate(f.lab, ident, status['report']['id'], status['revision'], self.key)
        self.assertEqual(status, f.lab.status())
        self.assertEqual(signed['signature'], hmac.new(self.key, signed['payloadJson'].encode(), hashlib.sha256).hexdigest())
        data = json.loads(signed['payloadJson'])
        self.assertEqual(data['candidateId'], ident)
        self.assertEqual(data['risk'], {'cashOnly': True, 'allHoldings': True, 'drawdownLimitPct': 10})
        self.assertNotIn('accountNo', data)
        with self.assertRaises(LabError):
            export_candidate(f.lab, ident, 'old-report', status['revision'], self.key)
        with self.assertRaises(LabError):
            export_candidate(f.lab, status['activeStrategy']['id'], status['report']['id'], status['revision'], self.key)

    def test_signed_market_uses_actual_indicators_and_failure_invalidates_it(self):
        f = self.fixture
        f.cycle(205)
        root = Path(f.tmp.name)
        (root / 'deployment-signing-key').write_bytes(self.key)
        target = publish_market(f.lab, root)
        envelope = json.loads(target.read_text(encoding='utf-8'))
        payload = json.loads(envelope['payloadJson'])
        expected = fixtures.reference.indicators({s: rs[:205] for s, rs in f.data.items()})
        for symbol, signal in payload['signals'].items():
            for name, value in signal.items():
                self.assertEqual(value, expected[symbol][-1][name])
        self.assertEqual(payload['kind'], 'MARKET')
        invalidate_market(root)
        self.assertEqual(json.loads(target.read_text()), {'blocked': True})

    def test_missing_short_key_fixture_and_failed_cycle_cannot_export(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(LabError): signing_key(folder)
            Path(folder, 'deployment-signing-key').write_text('short')
            with self.assertRaises(LabError): signing_key(folder)
            body = dict(candidateId='rotation1:recovery_vol', reportId='x', expectedRevision=0)
            fixture = Controller(folder, fixture=True, lab=Mock())
            with self.assertRaises(ValueError): fixture.decision('export', body)
            fixture.fixture = False
            fixture.error = 'collector failed'
            with self.assertRaises(ValueError): fixture.decision('export', body)

    def test_publisher_does_not_accept_changed_code_or_tampered_report(self):
        f = self.fixture
        ident, status = f.qualify_private_fixture()
        with f.lab._db() as db:
            db.execute('UPDATE reports SET payload=?', ('{}',))
        with self.assertRaises(LabError):
            export_candidate(f.lab, ident, status['report']['id'], status['revision'], self.key)


if __name__ == '__main__':
    unittest.main()
