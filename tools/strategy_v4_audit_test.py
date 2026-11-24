import copy
import unittest
from datetime import date, timedelta
from strategy_v4_audit import audit, expected_execution, SYMBOLS


def market_fixture():
    prices, dates = {}, []
    day = date(2018, 1, 1)
    while len(dates) < 330:
        if day.weekday() < 5:
            dates.append(day.isoformat())
        day += timedelta(days=1)
    previous = 80.
    for i, day in enumerate(dates):
        value = 80. if i < 220 else 100. if i < 222 else 92. if i == 222 else 92.+(i-223)*.15
        opening = value if i <= 221 else previous
        for symbol in (*SYMBOLS, '^SP500TR'):
            prices[day, symbol] = dict(open=opening, high=max(opening,value), low=min(opening,value), close=value)
        previous = value
    return prices, dates[220:]


class RecoveryAuditTest(unittest.TestCase):
    def test_fee_inclusive_sequential_cash_cap_and_alphabetical_execution(self):
        holdings = {symbol: 0. for symbol in SYMBOLS}
        opening = {symbol: 100. for symbol in SYMBOLS}
        target = {symbol: .4 if symbol in ('SPY', 'GLD') else 0. for symbol in SYMBOLS}
        cash, positions, fills = expected_execution(10000., holdings, target, opening, .0005, .002)
        self.assertEqual([fill[1] for fill in fills], ['GLD', 'SPY'])
        self.assertAlmostEqual(cash, 2000.)
        self.assertGreater(positions['GLD'], 0.)

    def test_recovery_engine_matches_independent_state_machine(self):
        from strategy_v4 import simulate
        prices, dates = market_fixture()
        for candidate in ('G', 'H'):
            result = simulate(prices, candidate, dates[0], dates[-1], fee_bps=0, slippage_bps=0)
            self.assertEqual(audit(prices, *result)['status'], 'PASS')
        rows, trades, report = simulate(prices, 'G', dates[0], dates[-1], fee_bps=0, slippage_bps=0)
        waiting = [row for row in rows if row['state_close'] == 'WAIT']
        self.assertGreaterEqual(len(waiting), 21)
        first_signal = next(i for i,row in enumerate(rows) if row['state_close'] == 'PROBE25')
        self.assertEqual(first_signal, 23)
        self.assertIn(rows[first_signal]['trial_anchor'], (None, ''))
        self.assertEqual(rows[first_signal+1]['stage_exposed_sessions'], 1)
        anchor = rows[first_signal+1]['trial_anchor']
        first_upgrade = next(i for i,row in enumerate(rows) if row['state_close'] == 'PROBE50')
        self.assertEqual(first_upgrade, first_signal+20)
        self.assertEqual(rows[first_upgrade]['stage_exposed_sessions'], 0)
        self.assertEqual(rows[first_upgrade]['trial_anchor'], anchor)
        self.assertEqual(rows[first_upgrade+1]['stage_exposed_sessions'], 1)
        self.assertTrue(any(trade['date'] == rows[first_signal+1]['date'] and trade['side'] == 'BUY' for trade in trades))

    def test_future_prices_cannot_change_prior_decisions(self):
        from strategy_v4 import simulate
        prices, dates = market_fixture(); altered = copy.deepcopy(prices)
        cutoff = dates[60]
        for (day, _), bar in altered.items():
            if day > cutoff:
                for field in bar:
                    bar[field] *= 5
        for candidate in ('G', 'H'):
            before = simulate(prices, candidate, dates[0], dates[-1])
            after = simulate(altered, candidate, dates[0], dates[-1])
            for index in (0, 1):
                self.assertEqual([row for row in before[index] if row['date'] <= cutoff], [row for row in after[index] if row['date'] <= cutoff])

    def test_corrupted_promotion_anchor_is_rejected(self):
        from strategy_v4 import simulate
        prices, dates = market_fixture()
        rows, trades, report = simulate(prices, 'G', dates[0], dates[-1], fee_bps=0, slippage_bps=0)
        upgraded = next(row for row in rows if row['state_close'] == 'PROBE50')
        upgraded['trial_anchor'] += 10
        with self.assertRaisesRegex(ValueError, 'anchor must not reset'):
            audit(prices, rows, trades, report)

    def test_corrupted_stage_counter_is_rejected(self):
        from strategy_v4 import simulate
        prices, dates = market_fixture()
        rows, trades, report = simulate(prices, 'G', dates[0], dates[-1], fee_bps=0, slippage_bps=0)
        next(row for row in rows if row['stage_exposed_sessions'] == 1)['stage_exposed_sessions'] = 0
        with self.assertRaisesRegex(ValueError, 'session counter'):
            audit(prices, rows, trades, report)

    def test_wait_day0_cannot_roll_forward_each_cash_only_session(self):
        from strategy_v4 import simulate
        prices, dates = market_fixture()
        rows, trades, report = simulate(prices, 'G', dates[0], dates[-1], fee_bps=0, slippage_bps=0)
        second_wait = next(row for row in rows if row['wait_elapsed'] == 1)
        second_wait['wait_day0'] = second_wait['date']
        with self.assertRaisesRegex(ValueError, 'day0 reset'):
            audit(prices, rows, trades, report)

    def test_hard_gap_overrides_planned_increase_and_never_resets(self):
        from strategy_v4 import simulate
        prices, dates = market_fixture()
        for symbol in SYMBOLS:
            prices[dates[44], symbol] = dict(open=50., high=50., low=50., close=50.)
        rows, trades, report = simulate(prices, 'G', dates[0], dates[-1], fee_bps=0, slippage_bps=0)
        self.assertEqual(audit(prices, rows, trades, report)['status'], 'PASS')
        self.assertEqual(rows[43]['state_close'], 'PROBE50')
        self.assertEqual(rows[44]['state_open'], 'HARD')
        self.assertTrue(all(row['state_close'] == 'HARD' for row in rows[44:]))
        self.assertFalse(any(trade['side'] == 'BUY' and trade['date'] >= dates[44] for trade in trades))


if __name__ == '__main__':
    unittest.main()
