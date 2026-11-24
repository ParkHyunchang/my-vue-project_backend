"""Audit mutation tests use synthetic data only; engine is the subject, not an oracle."""
import copy
import unittest
from team23_audit import audit, risk_budget
from team21_test import fixture
from team23_research import simulate

def run(rows, candidate='spy_trend', cost=10, policy='cushion80'):
    return simulate(rows, rows['SPY'][200]['date'], rows['SPY'][-1]['date'], candidate, cost, risk_policy=policy)


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.rows = fixture()
        self.data = {s: {r['date']: r for r in rows} for s, rows in self.rows.items()}
        self.result = run(self.rows)

    def test_valid_three_candidates_and_two_costs(self):
        for policy in ('cushion80', 'recovery_vol'):
            for candidate in ('spy_trend', 'sector_breakout', 'spy_pullback'):
                for cost in (10, 25):
                    self.assertEqual(audit(run(self.rows, candidate, cost, policy), self.data)['status'], 'PASS')

    def test_risk_boundaries_and_hard_override(self):
        for policy in ('cushion80', 'recovery_vol'):
            self.assertEqual(risk_budget(policy, 10000, 10000, True, .1), 0)
        self.assertAlmostEqual(risk_budget('cushion80', 10000, 10000, False, .1), .8)
        for value, target in ((9600, .8), (9500, .4), (9300, .4), (9200, .2), (9100, .2)):
            self.assertEqual(risk_budget('recovery_vol', value, 10000, False, .1), target)
        self.assertEqual(risk_budget('recovery_vol', 10000, 10000, False, 1), .2)

    def test_policy_tamper_rejected(self):
        self.result['risk_policy'] = 'invalid'
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_age_tamper_rejected(self):
        self.result['history'][2]['holding_age']['SPY'] += 1
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_indicator_tamper_rejected(self):
        self.result['history'][2]['signal_snapshot']['SPY']['prior20_high'] += 3
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_volatility_tamper_rejected(self):
        self.result['history'][2]['market_vol20'] += .05
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

    def test_breakout_age_exit_and_new_entry_audited(self):
        result = run(self.rows, 'sector_breakout', policy='recovery_vol')
        self.assertEqual(audit(result, self.data)['status'], 'PASS')
        aged = [h for h in result['history'] if any(a == 10 for a in h['holding_age'].values())]
        self.assertTrue(aged)
        for h in aged:
            for symbol, age in h['holding_age'].items():
                if age == 10:
                    self.assertNotIn(symbol, h['next_target']['basket'])
        aged[0]['holding_age'][next(s for s, a in aged[0]['holding_age'].items() if a == 10)] = 9
        with self.assertRaises(AssertionError):
            audit(result, self.data)

    def test_active_pullback_and_time_exit_audited(self):
        rows = copy.deepcopy(self.rows)
        # Above the long trend but two-day pullback followed by a slow decline:
        # enter after day202, remain below SMA5 until the five-close time exit.
        for i in range(201, 213):
            price = 120.0 - .1 * (i-200)
            for key in ('open', 'close', 'adj_close', 'trade_open', 'trade_close'):
                rows['SPY'][i][key] = price
        data = {s: {r['date']: r for r in rs} for s, rs in rows.items()}
        result = run(rows, 'spy_pullback', policy='recovery_vol')
        self.assertEqual(audit(result, data)['status'], 'PASS')
        self.assertTrue(any(t['side'] == 'BUY' for t in result['trades']))
        time_exit = next(h for h in result['history'] if h['holding_age']['SPY'] == 5)
        self.assertNotIn('SPY', time_exit['next_target']['basket'])
        time_exit['next_target']['basket'] = ['SPY']
        with self.assertRaises(AssertionError):
            audit(result, data)

    def test_missing_history_rejected(self):
        self.result['history'].pop(1)
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)


if __name__ == '__main__':
    unittest.main()
