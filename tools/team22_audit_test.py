"""Audit mutation tests use synthetic data only; engine is the subject, not an oracle."""
import copy
import unittest
from team22_audit import audit, risk_budget
from team21_test import fixture
from team22_research import simulate

def run(rows, candidate='spy_trend', cost=10, policy='cushion80'):
    return simulate(rows, rows['SPY'][200]['date'], rows['SPY'][-1]['date'], candidate, cost, risk_policy=policy)


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.rows = fixture()
        self.data = {s: {r['date']: r for r in rows} for s, rows in self.rows.items()}
        self.result = run(self.rows)

    def test_valid_three_candidates_and_two_costs(self):
        for policy in ('baseline20', 'cushion80', 'band80'):
            for candidate in ('spy_trend', 'sector_top1', 'sector_top3'):
                for cost in (10, 25):
                    self.assertEqual(audit(run(self.rows, candidate, cost, policy), self.data)['status'], 'PASS')

    def test_risk_boundaries_and_hard_override(self):
        for policy in ('baseline20', 'cushion80', 'band80'):
            self.assertEqual(risk_budget(policy, 10000, 10000, True), 0)
        self.assertAlmostEqual(risk_budget('baseline20', 10000, 10000, False), .2)
        self.assertAlmostEqual(risk_budget('cushion80', 10000, 10000, False), .8)
        for value, target in ((9600, .8), (9500, .4), (9300, .4), (9200, .2), (9100, .2), (9000, 0), (8999, 0)):
            self.assertEqual(risk_budget('band80', value, 10000, False), target)

    def test_policy_tamper_rejected(self):
        self.result['risk_policy'] = 'baseline20'
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_fee_tamper_rejected(self):
        self.result['trades'][0]['fee'] += 1
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_quantity_tamper_rejected(self):
        self.result['trades'][0]['qty'] += 100
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_future_signal_rejected(self):
        self.result['trades'][0]['signal_date'] = self.result['trades'][0]['date']
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_equity_tamper_rejected(self):
        self.result['history'][-1]['equity'] += 2
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_risk_target_tamper_rejected(self):
        self.result['history'][0]['next_target']['gross'] = .2
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_source_price_tamper_rejected(self):
        self.data['SPY'][self.result['trades'][0]['date']]['trade_open'] += 5
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_gap_latch_settlement_and_permanent_stop(self):
        rows = copy.deepcopy(self.rows)
        for r in rows['SPY'][210:]:
            for key in ('open', 'close', 'adj_close', 'trade_open', 'trade_close'):
                r[key] = 1
        data = {s: {r['date']: r for r in rs} for s, rs in rows.items()}
        result = run(rows)
        self.assertEqual(audit(result, data)['status'], 'PASS')
        self.assertIsNotNone(result['hard_latch'])
        sell = next(t for t in result['trades'] if t['side'] == 'SELL')
        state = next(h for h in result['history'] if h['date'] == sell['date'])
        state['unsettled'] = 0
        with self.assertRaises(AssertionError):
            audit(result, data)

    def test_dividend_double_count_rejected(self):
        self.result['history'][-1]['dividend_receivable'] += 3
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_missing_history_rejected(self):
        self.result['history'].pop(1)
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)


if __name__ == '__main__':
    unittest.main()
