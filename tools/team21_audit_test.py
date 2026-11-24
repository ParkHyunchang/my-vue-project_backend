"""Audit mutation tests use synthetic data only; engine is the subject, not an oracle."""
import copy
import unittest
from team21_audit import audit
from team21_test import fixture, run


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.rows = fixture()
        self.data = {s: {r['date']: r for r in rows} for s, rows in self.rows.items()}
        self.result = run(self.rows)

    def test_valid_three_candidates_and_two_costs(self):
        for candidate in ('spy_trend', 'sector_top1', 'sector_top3'):
            for cost in (10, 25):
                self.assertEqual(audit(run(self.rows, candidate, cost), self.data)['status'], 'PASS')

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
        self.result['history'][0]['next_target']['gross'] = .8
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
