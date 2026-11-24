"""Check full-period annualization and recovery/cash reporting independently."""
import unittest
from team23_report import metrics


def row(day, equity, peak, held=0):
    return dict(date=day, equity=equity, peak=peak, qty={'SPY': held},
                exposure=.5 if held else 0, next_target={'gross': .5})


class MetricsTests(unittest.TestCase):
    def test_full_calendar_includes_idle_year(self):
        result = {'initial': 100, 'history': [row('2020-01-01', 100, 100),
                  row('2020-01-02', 110, 110, 1), row('2021-01-01', 110, 110)]}
        m = metrics(result)
        self.assertAlmostEqual(m['annualized_pct'], (1.1 ** (365.2425 / 366) - 1) * 100)
        self.assertEqual(m['held_days'], 1)
        self.assertEqual(m['cash_only_days'], 2)
        self.assertEqual(m['monthly_positive_fraction'], .5)
        self.assertEqual(m['monthly'][-1]['return_pct'], 0)

    def test_underwater_recovered_and_ongoing(self):
        result = {'initial': 100, 'history': [row('2020-01-01', 100, 100),
                  row('2020-01-10', 90, 100), row('2020-02-01', 100, 100),
                  row('2020-02-10', 95, 100), row('2020-03-20', 96, 100)]}
        m = metrics(result)
        self.assertEqual(m['underwater_max_calendar_days'], 48)
        self.assertTrue(m['underwater_ongoing'])
        self.assertAlmostEqual(m['worst_month_pct'], -10)
        self.assertAlmostEqual(m['monthly_positive_fraction'], 2/3)

    def test_zero_activity_not_reported_as_winning_months(self):
        m = metrics({'initial': 100, 'history': [row('2020-01-15', 100, 100),
                     row('2020-02-17', 100, 100), row('2020-03-01', 100, 100)]})
        self.assertEqual(m['annualized_pct'], 0)
        self.assertEqual(m['monthly_positive_fraction'], 0)
        self.assertEqual(m['underwater_max_calendar_days'], 0)
        self.assertTrue(m['monthly'][0]['boundary_month_may_be_partial'])
        self.assertTrue(m['monthly'][-1]['boundary_month_may_be_partial'])


if __name__ == '__main__':
    unittest.main()
