"""Independent audit mutation coverage; all price paths here are synthetic."""
import copy
import unittest

import team27_audit as auditor
import team27_research as subject
from team24_audit_test import fixture


class AuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = fixture(330)
        cls.data = {s:{r['date']:r for r in rows} for s,rows in cls.rows.items()}

    def result(self, candidate='fast63'):
        return subject.run(self.rows,candidate,self.rows['SPY'][253]['date'],self.rows['SPY'][-1]['date'],25,'recovery_vol')

    def test_all_new_candidates_and_policies(self):
        for candidate in subject.ALIASES:
            for policy in ('cushion80','recovery_vol'):
                for cost in (10,25):
                    result = subject.run(self.rows,candidate,self.rows['SPY'][253]['date'],self.rows['SPY'][-1]['date'],cost,policy)
                    self.assertEqual(auditor.audit(result,self.data)['status'],'PASS')

    def test_mutated_cash_fee_quantity_future_and_latch_rejected(self):
        original = self.result()
        mutations = [lambda r:r['history'][-1].__setitem__('cash',-1),
                     lambda r:r['trades'][0].__setitem__('fee',999),
                     lambda r:r['trades'][0].__setitem__('qty',100000),
                     lambda r:r['trades'][0].__setitem__('signal_date',r['trades'][0]['date']),
                     lambda r:r['history'][-1].__setitem__('hard',True),
                     lambda r:r['history'][-1]['signal_snapshot']['SPY'].__setitem__('momentum',999)]
        for mutate in mutations:
            result = copy.deepcopy(original)
            mutate(result)
            with self.assertRaises(AssertionError):
                auditor.audit(result,self.data)

    def test_gate_missing_duplicate_and_failing_cell(self):
        rows = [dict(period=p,cost_bps=c,start='2024-01-01',end='2025-01-01',
                     initial=100,final_equity=120,mdd_pct=5,hard_latch=None,pending_liquidation=False)
                for p in ('recent','latest','continuous') for c in (10,25)]
        self.assertTrue(auditor.gate(rows))
        for malformed in (rows[:-1],rows+[rows[0]],rows[:-1]+[rows[0]]):
            with self.assertRaises(AssertionError):
                auditor.gate(malformed)
        rows[-1]['hard_latch'] = {'date':'2025-01-01'}
        self.assertFalse(auditor.gate(rows))

    def test_future_prices_do_not_change_prior_indicators(self):
        cutoff = self.rows['SPY'][290]['date']
        changed = copy.deepcopy(self.data)
        for symbol in changed:
            for d in changed[symbol]:
                if d > cutoff:
                    changed[symbol][d]['adj_close'] *= 10
        for c in subject.ALIASES:
            before = auditor.indicator_cache(self.data,c)
            after = auditor.indicator_cache(changed,c)
            for s in before:
                self.assertEqual({d:v for d,v in before[s].items() if d <= cutoff},
                                 {d:v for d,v in after[s].items() if d <= cutoff})


if __name__ == '__main__':
    unittest.main()
