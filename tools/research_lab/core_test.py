import copy
import json
import tempfile
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

from research_lab.core import Lab, LabError
from research_lab.data import digest
from research_lab.replay import summary
from team24_test import fixture


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = datetime(2020, 12, 31, tzinfo=timezone.utc)
        self.lab = Lab(self.tmp.name, clock=lambda:self.now)
        self.addCleanup(self.tmp.cleanup)

    def snapshot(self, count=261):
        data = fixture(count)
        for rows in data.values():
            for row in rows:
                row.update(high=row['close']+2, low=row['close']-2, volume=1000,
                           dividend=0, split_numerator=1, split_denominator=1)
        dates = [r['date'] for r in data['SPY']]
        return dict(data=data,benchmark={d:1000 for d in dates},dates=dates,snapshot_hash=digest(data))

    def cycle(self, count):
        with patch('research_lab.core.load_snapshot',return_value=self.snapshot(count)), patch.object(self.lab,'_historical',return_value={}):
            return self.lab.run_cycle('unused')

    def qualify_private_fixture(self):
        """Forge evidence solely inside unittest, never via production API."""
        candidate = self.lab.catalog[0]['id']
        with self.lab._db() as db:
            state = self.lab._read(db)
            state['registered_at'] = self.now.isoformat()
            state['registered_date'] = self.now.date().isoformat()
            state['last_market_date'] = self.now.date().isoformat()
            state['historical'][candidate] = {'passed':True,'auditPassed':True}
            account = state['shadows'][candidate]
            account['history'] = [{'equity':10500} for _ in range(60)]
            account['trades'] = [{} for _ in range(20)]
            account['peak'] = 10500
            evidence = {'data':digest(state['market']),'code':self.lab.code_hash,'historical':state['historical'],
                        'forward':{k:summary(v) for k,v in state['shadows'].items()},'active':summary(state['active'])}
            rid = digest(evidence)
            state['report'] = {'id':rid}
            db.execute('INSERT INTO reports VALUES(?,?)',(rid,json.dumps(evidence)))
            self.lab._save(db,state)
        return candidate,rid

    def test_registration_catchup_restart_and_idempotence(self):
        first = self.cycle(258)
        self.assertEqual(first['paperAccount']['days'],0)
        catchup = self.cycle(260)
        self.assertEqual(catchup['paperAccount']['days'],0)
        self.now = datetime(2021,1,8,tzinfo=timezone.utc)
        current = self.cycle(266)
        expected = sum(d > '2020-12-31' for d in self.snapshot(266)['dates'])
        self.assertEqual(current['paperAccount']['days'],expected)
        self.assertEqual(self.cycle(266)['revision'],current['revision'])
        restarted = Lab(self.tmp.name, clock=lambda:self.now).status()
        self.assertEqual(restarted['paperAccount'],current['paperAccount'])

    def test_correction_rolls_back(self):
        before = self.cycle(258)
        bad = self.snapshot(260)
        bad['data']['SPY'][0]['open'] += 10
        with patch('research_lab.core.load_snapshot',return_value=bad):
            with self.assertRaises(ValueError):self.lab.run_cycle('unused')
        self.assertEqual(before,self.lab.status())

    def test_approval_preserves_account_and_revision_prevents_duplicate(self):
        candidate,rid = self.qualify_private_fixture()
        before = self.lab.status()
        after = self.lab.approve(candidate,rid,0)
        self.assertEqual(before['paperAccount'],after['paperAccount'])
        self.assertEqual(before['activeStrategy'],after['activeStrategy'])
        self.assertEqual(after['pendingSwitch']['candidateId'],candidate)
        with self.assertRaises(LabError):self.lab.approve(candidate,rid,0)
        with self.assertRaises(LabError):self.lab.approve(candidate,rid,1)
        self.assertEqual(Lab(self.tmp.name,clock=lambda:self.now).status()['pendingSwitch'],after['pendingSwitch'])

    def test_bad_report_and_tampered_account_block(self):
        candidate,rid = self.qualify_private_fixture()
        with self.assertRaises(LabError):self.lab.approve(candidate,'stale',0)
        with self.lab._db() as db:
            state=self.lab._read(db);state['active']['hard']=True;self.lab._save(db,state)
        with self.assertRaises(LabError):self.lab.approve(candidate,rid,0)
        self.assertTrue(self.lab.status()['paperAccount']['hardLatched'])

    def test_reject_and_immature_gates(self):
        s=self.cycle(258);candidate=self.lab.catalog[0]['id']
        with self.assertRaises(LabError):self.lab.approve(candidate,s['report']['id'],s['revision'])
        after=self.lab.reject(candidate,s['report']['id'],s['revision'])
        self.assertFalse(next(c for c in after['candidates'] if c['id']==candidate)['eligible'])
        with self.assertRaises(LabError):self.lab.approve(candidate,s['report']['id'],after['revision'])


if __name__=='__main__':unittest.main()
