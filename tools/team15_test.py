import unittest
from datetime import date, timedelta
from team15_dca import simulate


def bars(prices):
    return [{'date': (date(2020, 1, 1) + timedelta(days=i)).isoformat(), 'open': p, 'close': p, 'dividend': 0} for i,p in enumerate(prices)]


class DcaTests(unittest.TestCase):
    def test_cash_shortfall_skips_instead_of_borrowing(self):
        result = simulate(bars([700] * 40), 'daily_share')
        self.assertEqual(result['bought_shares'], 14)
        self.assertEqual(result['cash_skip_days'], 26)
        self.assertTrue(all(x['cash'] >= 0 for x in result['history']))

    def test_budget_accumulates_but_never_buys_fractional_share(self):
        result = simulate(bars([700] * 40), 'daily_budget')
        self.assertEqual(result['bought_shares'], 14)
        self.assertEqual(result['history'][0]['quantity'], 0)
        for trade in result['trades']:
            self.assertIsInstance(trade['qty'], int)
            self.assertLessEqual(trade['qty'] * trade['price'] + trade['fee'], trade['cash_before'] * .99 + 1e-8)

    def test_gap_hard_precedes_scheduled_buy_and_never_resets(self):
        result = simulate(bars([100] * 30 + [50, 200, 200]), 'daily_share')
        self.assertIsNotNone(result['hard_date'])
        self.assertEqual(result['trades'][-1]['side'], 'SELL')
        self.assertGreater(result['mdd_pct'], 10)
        self.assertEqual(result['history'][-1]['quantity'], 0)

    def test_close_breach_sells_next_open_not_same_close(self):
        rows = bars([100, 90, 80])
        rows[1]['close'] = 80
        result = simulate(rows, 'lump_sum')
        self.assertEqual(result['hard_date'], rows[1]['date'])
        self.assertEqual(result['trades'][-1]['date'], rows[2]['date'])
        self.assertAlmostEqual(result['trades'][-1]['price'], 80 * .9995)

    def test_dividend_only_for_pre_exdate_shares_and_not_spendable(self):
        rows = bars([100, 100])
        rows[1]['dividend'] = 2
        result = simulate(rows, 'daily_share')
        self.assertEqual(result['history'][-1]['dividend_receivable'], 2)
        self.assertAlmostEqual(result['history'][-1]['cash'], 10000 - 2 * 100 * 1.0005 * 1.0005)

    def test_future_changes_cannot_change_earlier_orders(self):
        a = simulate(bars([100] * 40), 'daily_budget')
        b = simulate(bars([100] * 20 + [200] * 20), 'daily_budget')
        self.assertEqual(a['history'][:20], b['history'][:20])

    def test_separate_ledger_reconstruction(self):
        result = simulate(bars([100] * 30 + [50, 200]), 'daily_share')
        cash, qty = 10000, 0
        for trade in result['trades']:
            if trade['side'] == 'BUY':
                cash -= trade['qty'] * trade['price'] + trade['fee']
                qty += trade['qty']
            else:
                cash += trade['qty'] * trade['price'] - trade['fee']
                qty -= trade['qty']
            self.assertAlmostEqual(cash, trade['cash_after'])
        self.assertAlmostEqual(cash + qty * 200, result['final_equity'])


if __name__ == '__main__':
    unittest.main()
