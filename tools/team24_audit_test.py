"""Audit mutation tests use synthetic data only; engine is the subject, not an oracle."""
import copy
import unittest
from team24_audit import audit, risk_budget
from datetime import date, timedelta
from team24_research import SYMBOLS


def fixture(count=270):
    dates = []
    day = date(2020, 1, 1)
    while len(dates) < count:
        if day.weekday() < 5:
            dates.append(day.isoformat())
        day += timedelta(days=1)
    return {s: [dict(date=d, open=100+i/10, close=100+i/10, adj_close=100+i/10,
                    trade_open=100+i/10, trade_close=100+i/10, trade_dividend=0.,
                    split_numerator=1., split_denominator=1.)
                for i, d in enumerate(dates)] for s in SYMBOLS}
from team24_research import simulate

def run(rows, candidate='balanced_trend', cost=10, policy='cushion80'):
    return simulate(rows, rows['SPY'][200]['date'], rows['SPY'][-1]['date'], candidate, cost, risk_policy=policy)


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.rows = fixture()
        self.data = {s: {r['date']: r for r in rows} for s, rows in self.rows.items()}
        self.result = run(self.rows)

    def test_valid_three_candidates_and_two_costs(self):
        for policy in ('cushion80', 'recovery_vol'):
            for candidate in ('rotation1', 'riskrank2', 'balanced_trend'):
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

    def test_anchor_tamper_rejected(self):
        self.result['history'][2]['rebalance_anchor_gross'] += .1
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)

    def test_indicator_tamper_rejected(self):
        self.result['history'][2]['signal_snapshot']['SPY']['vol63'] += .1
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
        for symbol in SYMBOLS:
            for r in rows[symbol][210:]:
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

    def test_hold_drift_has_no_trades(self):
        result = run(self.rows, policy='recovery_vol')
        self.assertEqual(audit(result, self.data)['status'], 'PASS')
        holds = [h for h in result['history'][2:] if h['execution_mode'] == 'HOLD']
        self.assertTrue(holds)
        self.assertTrue(all(h['trade_count'] == 0 for h in holds))
        for i,h in enumerate(result['history'][1:], 1):
            if h['execution_mode'] == 'HOLD':
                self.assertEqual(h['qty'], result['history'][i-1]['qty'])

    def test_exit_keeps_other_shares_and_anchor(self):
        rows = copy.deepcopy(self.rows)
        # Lose trend in one20% sleeve but keep overall drawdown below5%.
        for r in rows['IEF'][210:]:
            for key in ('open','close','adj_close','trade_open','trade_close'):
                r[key] = 105
        result = run(rows, policy='recovery_vol')
        data = {s:{r['date']:r for r in rs} for s,rs in rows.items()}
        self.assertEqual(audit(result, data)['status'], 'PASS')
        j = next(i for i,h in enumerate(result['history']) if h['execution_mode'] == 'EXIT')
        current, previous = result['history'][j], result['history'][j-1]
        self.assertEqual(current['qty']['IEF'],0)
        for s in ('SPY','QQQ','GLD'):
            self.assertEqual(current['qty'][s],previous['qty'][s])
        self.assertEqual(current['rebalance_anchor_gross'],previous['rebalance_anchor_gross'])

    def test_reduce_is_sell_only_and_uses_executed_reference(self):
        rows = copy.deepcopy(self.rows)
        for s in SYMBOLS:
            for i,r in enumerate(rows[s][210:],210):
                price = 117 + (i-210)*.05
                for key in ('open','close','adj_close','trade_open','trade_close'):
                    r[key] = price
        result = run(rows)
        data = {s:{r['date']:r for r in rs} for s,rs in rows.items()}
        self.assertEqual(audit(result,data)['status'],'PASS')
        reductions = [h for h in result['history'] if h['execution_mode']=='REDUCE']
        self.assertTrue(reductions)
        for h in reductions:
            self.assertEqual(h['rebalance_anchor_gross'],h['executed_target']['gross'])
            self.assertTrue(all(t['side']=='SELL' for t in result['trades'] if t['date']==h['date']))

    def test_mode_tamper_rejected(self):
        self.result['history'][0]['next_target']['mode'] = 'HOLD'
        with self.assertRaises(AssertionError):
            audit(self.result,self.data)

    def rotation_fixture(self):
        rows = copy.deepcopy(self.rows)
        for r in rows['QQQ'][216:]:
            for k in ('open','close','adj_close','trade_open','trade_close'):
                r[k] *= 1.2
        return rows

    def test_one_shot_funding_and_cap_tamper(self):
        rows = self.rotation_fixture()
        result = run(rows,'rotation1',policy='recovery_vol')
        data = {s:{r['date']:r for r in rs} for s,rs in rows.items()}
        self.assertEqual(audit(result,data)['status'],'PASS')
        funds = [h for h in result['history'] if h['execution_mode']=='FUND']
        self.assertEqual(len(funds),1)
        h = funds[0]
        self.assertEqual(h['next_target']['mode'],'HOLD')
        self.assertEqual(h['next_target']['fund_shortfall'],{})
        self.assertTrue(all(t['side']=='BUY' for t in result['trades'] if t['date']==h['date']))
        self.assertGreater(h['settled_today'],0)
        signal = next(h for h in result['history'] if h['next_target']['mode']=='FUND')
        signal['next_target']['fund_shortfall']['QQQ'] += 1
        with self.assertRaises(AssertionError):
            audit(result,data)

    def test_funding_split_adjusted_cap(self):
        rows = self.rotation_fixture()
        first = run(rows,'rotation1',policy='recovery_vol')
        day = next(h['date'] for h in first['history'] if h['execution_mode']=='FUND')
        j = next(i for i,r in enumerate(rows['QQQ']) if r['date']==day)
        for r in rows['QQQ'][j:]:
            for k in ('open','close','trade_open','trade_close'):
                r[k] /= 2
        rows['QQQ'][j]['split_numerator'] = 2
        result = run(rows,'rotation1',policy='recovery_vol')
        data = {s:{r['date']:r for r in rs} for s,rs in rows.items()}
        self.assertEqual(audit(result,data)['status'],'PASS')
        h = next(h for h in result['history'] if h['date']==day)
        cap = h['executed_target']['fund_shortfall']['QQQ']*2
        buys = sum(t['qty'] for t in result['trades'] if t['date']==day and t['side']=='BUY')
        self.assertGreater(buys,0)
        self.assertLessEqual(buys,cap)

    def test_gap_cancels_funding(self):
        rows = self.rotation_fixture()
        first = run(rows,'rotation1',policy='recovery_vol')
        day = next(h['date'] for h in first['history'] if h['execution_mode']=='FUND')
        for sym in SYMBOLS:
            for r in rows[sym]:
                if r['date'] >= day:
                    for k in ('open','close','adj_close','trade_open','trade_close'):
                        r[k] = 1
        result = run(rows,'rotation1',policy='recovery_vol')
        data = {s:{r['date']:r for r in rs} for s,rs in rows.items()}
        self.assertEqual(audit(result,data)['status'],'PASS')
        h = next(h for h in result['history'] if h['date']==day)
        self.assertEqual(h['execution_mode'],'HARD')
        self.assertTrue(all(t['side']=='SELL' for t in result['trades'] if t['date']>=day))

    def test_missing_history_rejected(self):
        self.result['history'].pop(1)
        with self.assertRaises(AssertionError):
            audit(self.result, self.data)


if __name__ == '__main__':
    unittest.main()
