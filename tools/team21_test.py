"""Synthetic-only tests; no downloads, credentials, broker, or application imports."""
import copy
import csv
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from team21_research import SYMBOLS, simulate, validate, indicators, benchmark_result, load_data


def fixture(count=250):
    dates = []
    day = date(2020, 1, 1)
    while len(dates) < count:
        if day.weekday() < 5:
            dates.append(day.isoformat())
        day += timedelta(days=1)
    return {s: [dict(date=d, open=100 + i / 10, close=100 + i / 10, adj_close=100 + i / 10,
                      trade_open=100 + i / 10, trade_close=100 + i / 10,
                      trade_dividend=0., split_numerator=1., split_denominator=1.)
                for i, d in enumerate(dates)] for s in SYMBOLS}


def run(data, candidate='spy_trend', cost=10):
    return simulate(data, data['SPY'][200]['date'], data['SPY'][-1]['date'], candidate, cost)


class ResearchTests(unittest.TestCase):
    def test_first_signal_precedes_fill_and_cash_is_nonnegative(self):
        result = run(fixture())
        self.assertGreater(result['trade_count'], 0)
        self.assertEqual(result['history'][0]['trade_count'], 0)
        for t in result['trades']:
            self.assertLess(t['signal_date'], t['date'])
            self.assertIsInstance(t['qty'], int)
        for r in result['history']:
            self.assertGreaterEqual(r['cash'], 0)
            self.assertLessEqual(sum(r['next_target']['weights'].values()), .8 + 1e-12)

    def test_future_prices_do_not_change_past_orders(self):
        a = fixture()
        b = copy.deepcopy(a)
        for rows in b.values():
            for r in rows[230:]:
                for k in ('open', 'close', 'adj_close', 'trade_open', 'trade_close'):
                    r[k] *= 8
        cutoff = a['SPY'][230]['date']
        self.assertEqual([t for t in run(a)['trades'] if t['date'] < cutoff],
                         [t for t in run(b)['trades'] if t['date'] < cutoff])

    def test_sell_settlement_not_immediately_spendable(self):
        data = fixture()
        for r in data['SPY'][210:]:
            for k in ('open', 'close', 'trade_open', 'trade_close'):
                r[k] = 80
        result = run(data)
        sell = next(t for t in result['trades'] if t['side'] == 'SELL')
        j = next(i for i, h in enumerate(result['history']) if h['date'] == sell['date'])
        row = result['history'][j]
        self.assertGreater(row['unsettled'], 0)
        self.assertAlmostEqual(row['cash'], row['cash_before_settlement'] + row['settled_today'])
        self.assertAlmostEqual(result['history'][j + 1]['settled_today'], row['unsettled'])

    def test_open_gap_latches_and_can_exceed_ten_percent(self):
        data = fixture()
        for r in data['SPY'][210:]:
            for k in ('open', 'close', 'trade_open', 'trade_close'):
                r[k] = 1
        result = run(data)
        self.assertEqual(result['hard_latch']['phase'], 'open')
        self.assertGreater(result['mdd_pct'], 10)
        latch = result['hard_latch']['date']
        self.assertTrue(all(t['side'] == 'SELL' for t in result['trades'] if t['date'] >= latch))
        self.assertTrue(all(r['hard'] for r in result['history'] if r['date'] >= latch))

    def test_close_breach_liquidates_next_open(self):
        data = fixture()
        data['SPY'][210]['trade_close'] = 1
        data['SPY'][210]['close'] = 1
        result = run(data)
        self.assertEqual(result['hard_latch']['phase'], 'close')
        sell = next(t for t in result['trades'] if t['reason'] == 'HARD')
        self.assertEqual(sell['date'], data['SPY'][211]['date'])

    def test_dividend_receivable_not_spendable(self):
        data = fixture()
        data['SPY'][210]['trade_dividend'] = 1
        result = run(data)
        before, row = result['history'][9:11]
        self.assertEqual(row['dividend_today'], before['qty']['SPY'])
        self.assertEqual(result['history'][-1]['dividend_receivable'], row['dividend_today'])

    def test_split_preserves_equity_and_whole_shares(self):
        data = fixture()
        for r in data['SPY'][:210]:
            r['trade_open'] *= 2
            r['trade_close'] *= 2
        data['SPY'][210]['split_numerator'] = 2
        result = run(data)
        self.assertEqual(result['history'][10]['splits'], {'SPY': 2.0})
        self.assertLess(result['mdd_pct'], 1)

    def test_ties_deterministic_and_three_equal_weights(self):
        result = run(fixture(), 'sector_top3')
        weights = result['history'][0]['next_target']['weights']
        self.assertEqual(list(weights), ['XLB', 'XLE', 'XLI'])
        self.assertEqual(len(set(weights.values())), 1)

    def test_missing_top_three_slots_stay_cash(self):
        data = fixture()
        for s in SYMBOLS:
            if s not in ('SPY', 'XLB'):
                for r in data[s]:
                    r['adj_close'] = 100
        result = run(data, 'sector_top3')
        target = result['history'][0]['next_target']
        self.assertEqual(list(target['weights']), ['XLB'])
        self.assertAlmostEqual(target['weights']['XLB'], target['gross'] / 3)

    def test_nominal_loader_undoes_only_future_splits(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'daily.csv'
            keys = ('date', 'symbol', 'open', 'close', 'adj_close', 'dividend', 'split_numerator', 'split_denominator')
            with path.open('w', newline='') as stream:
                writer = csv.DictWriter(stream, keys)
                writer.writeheader()
                for day in ('2020-01-01', '2020-01-02'):
                    for s in SYMBOLS + ('^SP500TR',):
                        writer.writerow(dict(date=day, symbol=s, open=50, close=50, adj_close=40, dividend=.5,
                                             split_numerator=2 if day == '2020-01-02' and s == 'XLB' else 1,
                                             split_denominator=1))
            data, _ = load_data(folder)
            self.assertEqual(data['XLB'][0]['trade_open'], 100)
            self.assertEqual(data['XLB'][0]['trade_dividend'], 1)
            self.assertEqual(data['XLB'][1]['trade_open'], 50)
            self.assertEqual(data['XLB'][1]['trade_dividend'], .5)

    def test_calendar_warmup_and_invalid_prices_rejected(self):
        data = fixture()
        data['XLB'].pop()
        with self.assertRaises(ValueError):
            validate(data)
        data = fixture()
        with self.assertRaises(ValueError):
            simulate(data, data['SPY'][100]['date'], data['SPY'][-1]['date'], 'spy_trend')
        data['XLE'][220]['trade_open'] = float('nan')
        with self.assertRaises(ValueError):
            validate(data)

    def test_equity_identity_and_trade_cash_identity(self):
        for candidate in ('spy_trend', 'sector_top1', 'sector_top3'):
            result = run(fixture(), candidate, 25)
            for r in result['history']:
                calculated = r['cash'] + r['unsettled'] + r['dividend_receivable'] + sum(q * r['close'][s] for s, q in r['qty'].items())
                self.assertAlmostEqual(calculated, r['equity'])
            for t in result['trades']:
                self.assertAlmostEqual(t['fee'], t['qty'] * t['price'] * .00125)

    def test_benchmark_total_return_close_alignment(self):
        result = benchmark_result({'2020-01-01': 100, '2020-01-02': 90, '2020-01-03': 110}, '2020-01-01', '2020-01-03')
        self.assertAlmostEqual(result['return_pct'], 10)
        self.assertAlmostEqual(result['mdd_pct'], 10)


if __name__ == '__main__':
    unittest.main()
