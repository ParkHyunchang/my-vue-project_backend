import copy
import unittest
from datetime import date, timedelta

from strategy_v2_audit import audit


def fixture(gap=False):
    days = ['2020-01-30', '2020-01-31', '2020-02-03', '2020-02-04']
    prices = {(day, symbol): {'open': 100., 'close': 100.} for day in days for symbol in ('SPY', 'QQQ')}
    if gap:
        prices[days[3], 'SPY'] = {'open': 88., 'close': 88.}
    ledger = [{'date': days[2], 'signal_date': days[1], 'side': 'BUY', 'symbol': 'SPY', 'quantity': 90., 'price': 100., 'fee': 0., 'reason': 'REBALANCE'}]
    if gap:
        ledger.append({'date': days[3], 'signal_date': days[3], 'side': 'SELL', 'symbol': 'SPY', 'quantity': 90., 'price': 88., 'fee': 0., 'reason': 'HARD_OPEN_GAP'})
    rows = []
    for i, day in enumerate(days):
        equity = 8920. if gap and i == 3 else 10000.
        rows.append({'date': day, 'equity_usd': equity, 'cash_usd': equity if i < 2 or (gap and i == 3) else 1000.,
                     'external_flow_usd': 0., 'fees_usd': 0., 'peak_equity_usd': 10000., 'drawdown_percent': (1-equity/10000)*100,
                     'sp500_total_return_index': prices[day, 'SPY']['close'], 'hard_latched': gap and i == 3, 'soft_active': False,
                     'decision_target_spy': .9 if i == 1 else 0. if gap and i == 3 else '', 'decision_target_qqq': 0. if i == 1 or (gap and i == 3) else '',
                     'executed_target_spy': .9 if i == 2 else 0. if gap and i == 3 else '', 'executed_target_qqq': 0. if i == 2 or (gap and i == 3) else ''})
    gain = rows[-1]['equity_usd']/10000
    years = (date.fromisoformat(days[-1])-date.fromisoformat(days[0])).days/365.25
    report = {'start': days[0], 'end': days[-1], 'initial_cash_usd': 10000., 'fee_bps_per_side': 0., 'slippage_bps_per_side': 0.,
              'latch_date': days[-1] if gap else None, 'metrics': {'strategy_twr': gain-1, 'strategy_cagr': gain**(1/years)-1, 'strategy_max_drawdown': 1-gain}}
    return prices, rows, ledger, report


class IndependentAuditTest(unittest.TestCase):
    def test_reconstructs_cash_ledger(self):
        self.assertEqual(audit(*fixture())['status'], 'PASS')

    def test_open_gap_same_day_liquidation(self):
        self.assertEqual(audit(*fixture(True))['latch_date'], '2020-02-04')

    def test_rejects_negative_cash(self):
        args = fixture(); args[2][0]['quantity'] = 101.
        with self.assertRaisesRegex(ValueError, 'cash ceiling'):
            audit(*args)


    def test_future_prices_do_not_change_prior_decisions_or_fills(self):
        # Import only for this metamorphic check; the ledger auditor above does not
        # import or reuse engine calculations. No real historical returns opened.
        from strategy_v2 import Parameters, simulate
        prices = {}
        day = date(2020, 1, 1)
        while day <= date(2020, 3, 10):
            if day.weekday() < 5:
                for symbol, scale in [('SPY', 1), ('QQQ', 1.1)]:
                    value = (100 + (day-date(2020, 1, 1)).days * .1) * scale
                    prices[day.isoformat(), symbol] = dict(open=value, high=value, low=value, close=value)
            day += timedelta(days=1)
        altered = copy.deepcopy(prices)
        for (day, _), bar in altered.items():
            if day > '2020-02-14':
                for key in bar:
                    bar[key] *= 5
        params = Parameters(sma_sessions=3, momentum_sessions=3, volatility_sessions=3)
        for candidate in ('A', 'B', 'C'):
            original = simulate(prices, candidate, '2020-01-10', '2020-03-10', parameters=params)
            changed = simulate(altered, candidate, '2020-01-10', '2020-03-10', parameters=params)
            for index in (0, 1):
                before = [row for row in original[index] if row['date'] <= '2020-02-14']
                after = [row for row in changed[index] if row['date'] <= '2020-02-14']
                self.assertEqual(before, after, candidate)

    def test_rejects_same_day_buy_signal(self):
        args = fixture(); args[2][0]['signal_date'] = args[2][0]['date']
        with self.assertRaisesRegex(ValueError, 'next session'):
            audit(*args)

    def test_rejects_buy_after_gap_latch(self):
        args = fixture(True)
        bad = copy.deepcopy(args[2][0]); bad.update(date='2020-02-04', signal_date='2020-02-03', price=88.)
        args[2].append(bad)
        with self.assertRaisesRegex(ValueError, 'permanent hard latch'):
            audit(*args)

    def test_rejects_fee_manipulation(self):
        args = fixture(); args[2][0]['fee'] = 10.
        with self.assertRaisesRegex(ValueError, 'commission'):
            audit(*args)

    def test_rejects_valuation_manipulation(self):
        args = fixture(); args[1][-1]['equity_usd'] += 1
        with self.assertRaisesRegex(ValueError, 'daily equity'):
            audit(*args)

    def test_rejects_latch_reset(self):
        args = fixture(True); args[1][-1]['hard_latched'] = False
        with self.assertRaisesRegex(ValueError, 'Hard latch state'):
            audit(*args)

    def test_rejects_execution_without_decision(self):
        args = fixture(); args[1][1]['decision_target_spy'] = ''; args[1][1]['decision_target_qqq'] = ''
        with self.assertRaisesRegex(ValueError, 'previous-close decision'):
            audit(*args)

    def test_rejects_invented_soft_state(self):
        args = fixture(); args[1][1]['soft_active'] = True
        with self.assertRaisesRegex(ValueError, 'Soft hysteresis'):
            audit(*args)

    def test_rejects_soft_target_above_cap(self):
        args = fixture()
        args[0]['2020-02-03', 'SPY']['close'] = 93.
        args[1][2].update(equity_usd=9370., drawdown_percent=6.3, sp500_total_return_index=93., soft_active=True,
                          decision_target_spy=.9, decision_target_qqq=0.)
        with self.assertRaisesRegex(ValueError, 'Soft policy target'):
            audit(*args)


if __name__ == '__main__':
    unittest.main()
