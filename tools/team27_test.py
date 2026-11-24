"""Synthetic causal/safety checks; no strategy tuning or network."""
import copy
import unittest
from team24_test import fixture
from team27_research import ALIASES, signals_for, run


class Research27Tests(unittest.TestCase):
    def test_future_changes_do_not_change_past_signals(self):
        data = fixture(360)
        changed = copy.deepcopy(data)
        for rows in changed.values():
            for row in rows[310:]:
                row['adj_close'] *= .1
        for candidate in ALIASES:
            before, after = signals_for(data,candidate), signals_for(changed,candidate)
            for symbol in before:
                self.assertEqual(before[symbol][:310],after[symbol][:310])

    def test_warmup_and_positive_score_gate(self):
        data = fixture(320)
        for candidate in ALIASES:
            signals = signals_for(data,candidate)
            self.assertTrue(all(not s['eligible'] for rows in signals.values() for s in rows[:252]))
            self.assertTrue(all(s['momentum']>0 for rows in signals.values() for s in rows if s['eligible']))

    def test_cash_and_completed_signals(self):
        data = fixture(360)
        for candidate in ALIASES:
            result = run(data,candidate,data['SPY'][260]['date'],data['SPY'][-1]['date'],25,'recovery_vol')
            self.assertTrue(result['trades'])
            for trade in result['trades']:
                self.assertLess(trade['signal_date'],trade['date'])
                self.assertGreaterEqual(trade['cash_after'],0)
            self.assertTrue(all(h['cash']>=0 and all(q>=0 for q in h['qty'].values()) for h in result['history']))

    def test_gap_loss_latches_permanently_and_blocks_recovery_buys(self):
        data = fixture(380)
        for rows in data.values():
            for i,row in enumerate(rows):
                if i == 300:
                    for key in ('open','close','adj_close','trade_open','trade_close'):
                        row[key] *= .4
        for candidate in ALIASES:
            result = run(data,candidate,data['SPY'][260]['date'],data['SPY'][-1]['date'],25,'recovery_vol')
            self.assertIsNotNone(result['hard_latch'])
            latch = result['hard_latch']['date']
            self.assertFalse(any(t['side']=='BUY' and t['date']>=latch for t in result['trades']))
            self.assertTrue(all(h['hard'] for h in result['history'] if h['date']>=latch))

    def test_canary_negative_uses_only_defensive_assets(self):
        data = fixture(340)
        for row in data['SPY']:
            row['adj_close'] = 250-row['adj_close']
        for candidate in ('dual_canary','breadth_defensive'):
            signals = signals_for(data,candidate)
            self.assertFalse(signals['SPY'][-1]['eligible'])
            self.assertFalse(signals['QQQ'][-1]['eligible'])
            self.assertTrue(signals['GLD'][-1]['eligible'])


if __name__ == '__main__':
    unittest.main()
