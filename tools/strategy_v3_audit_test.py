import copy
import unittest
from datetime import date, timedelta
from strategy_v3_audit import audit, SYMBOLS


def fixture(gap=False):
    days = ['2020-01-02', '2020-01-03', '2020-01-06', '2020-01-07']
    prices = {(day, symbol): {'open': 100., 'close': 100.} for day in days for symbol in SYMBOLS}
    trades = [{'date': days[1], 'signal_date': days[0], 'side': 'BUY', 'symbol': 'SPY', 'quantity': 80., 'price': 100., 'fee': 0., 'reason': 'DAILY_CPPI'}]
    if gap:
        prices[days[2], 'SPY'] = {'open': 80., 'close': 80.}
        prices[days[3], 'SPY'] = {'open': 200., 'close': 200.}
        trades.append({'date': days[2], 'signal_date': days[2], 'side': 'SELL', 'symbol': 'SPY', 'quantity': 80., 'price': 80., 'fee': 0., 'reason': 'HARD_OPEN_GAP'})
    for day in days:
        prices[day, '^SP500TR'] = copy.deepcopy(prices[day, 'SPY'])
    rows = []
    for i, day in enumerate(days):
        latched = gap and i >= 2
        equity = 8400. if latched else 10000.
        row = {'date': day, 'equity_usd': equity, 'cash_usd': equity if i == 0 or latched else 2000., 'peak_equity_usd': 10000.,
               'drawdown_percent': (1-equity/10000)*100, 'external_flow_usd': 0., 'fees_usd': 0., 'hard_latched': latched,
               'cppi_floor_usd': 9000., 'cppi_cushion_usd': max(0,equity-9000), 'cppi_budget_weight': 0. if latched else .8,
               'sp500_total_return_index': prices[day, 'SPY']['close']}
        for symbol in SYMBOLS:
            target = .8 if symbol == 'SPY' and not latched else 0.
            row['decision_target_'+symbol.lower()] = target
            row['executed_target_'+symbol.lower()] = target if i else ''
        rows.append(row)
    change = rows[-1]['equity_usd']/10000-1
    years = (date.fromisoformat(days[-1])-date.fromisoformat(days[0])).days/365.25
    benchmark = prices[days[-1], 'SPY']['close']/100-1
    report = {'candidate_id': 'D', 'start': days[0], 'end': days[-1], 'initial_cash_usd': 10000., 'fee_bps_per_side': 0., 'slippage_bps_per_side': 0.,
              'benchmark_symbol': '^SP500TR', 'latch_date': days[2] if gap else None,
              'metrics': {'strategy_twr': change, 'strategy_cagr': (1+change)**(1/years)-1, 'strategy_max_drawdown': -change,
                          'benchmark_total_return': benchmark, 'excess_return_percentage_points': (change-benchmark)*100}}
    return prices, rows, trades, report


class AuditTest(unittest.TestCase):
    def test_initial_cpp_budget_is_eighty_percent(self):
        self.assertEqual(audit(*fixture())['status'], 'PASS')

    def test_gap_liquidation_stays_latched_after_price_recovers(self):
        self.assertEqual(audit(*fixture(True))['latch_date'], '2020-01-06')

    def test_missing_trade_cannot_fake_cash_performance(self):
        args = fixture(); args[2].clear()
        with self.assertRaisesRegex(ValueError, 'Missing/extra execution'):
            audit(*args)

    def test_cpp_target_cannot_exceed_cushion(self):
        args = fixture(); args[1][0]['decision_target_spy'] = .99
        with self.assertRaisesRegex(ValueError, 'CPPI close target'):
            audit(*args)

    def test_next_open_target_cannot_increase(self):
        args = fixture(); args[1][1]['executed_target_spy'] = .99
        with self.assertRaisesRegex(ValueError, 'open downside-only cap'):
            audit(*args)

    def test_position_size_must_match_plan_not_just_remain_cash_positive(self):
        args = fixture(); args[2][0]['quantity'] = 79.
        with self.assertRaisesRegex(ValueError, 'independent target quantity'):
            audit(*args)

    def test_same_day_close_cannot_generate_same_open_buy(self):
        args = fixture(); args[2][0]['signal_date'] = args[2][0]['date']
        with self.assertRaisesRegex(ValueError, 'next session'):
            audit(*args)

    def test_high_water_cannot_reset_after_gap(self):
        args = fixture(True); args[1][-1]['peak_equity_usd'] = 8400.
        with self.assertRaisesRegex(ValueError, 'peak_equity_usd'):
            audit(*args)

    def test_fees_cannot_be_omitted_from_ledger(self):
        args = fixture(); args[2][0]['fee'] = 1.
        with self.assertRaisesRegex(ValueError, 'commission'):
            audit(*args)

    def test_all_candidates_independent_audit_and_future_price_invariance(self):
        # Engine is imported only by this synthetic cross-check, never by auditor.
        from strategy_v3 import simulate
        prices = {}
        day = date(2018, 1, 1)
        while day <= date(2019, 3, 11):
            if day.weekday() < 5:
                elapsed = (day - date(2018, 1, 1)).days
                for symbol, slope in [('SPY', .15), ('QQQ', .2), ('GLD', .1), ('IEF', .12), ('^SP500TR', .15)]:
                    value = 100 + elapsed * slope
                    if symbol == 'QQQ' and '2019-02-11' <= day.isoformat() <= '2019-02-13':
                        value *= .8
                    prices[day.isoformat(), symbol] = dict(open=value, high=value, low=value, close=value)
            day += timedelta(days=1)
        altered = copy.deepcopy(prices)
        for (day, _), bar in altered.items():
            if day > '2019-02-15':
                for field in bar:
                    bar[field] *= 5
        for candidate in ('D', 'E', 'F'):
            original = simulate(prices, candidate, '2019-01-02', '2019-03-08')
            self.assertEqual(audit(prices, *original)['status'], 'PASS', candidate)
            changed = simulate(altered, candidate, '2019-01-02', '2019-03-08')
            for index in (0, 1):
                self.assertEqual([row for row in original[index] if row['date'] <= '2019-02-15'],
                                 [row for row in changed[index] if row['date'] <= '2019-02-15'], candidate)


if __name__ == '__main__':
    unittest.main()
