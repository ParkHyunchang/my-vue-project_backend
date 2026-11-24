import unittest
from team24_report import metrics, TARGET_ANNUAL_PCT


def row(day, equity, peak):
    return dict(date=day, equity=equity, peak=peak, qty={'SPY': 0},
                exposure=0, next_target={'gross': 0})


class MetricsTests(unittest.TestCase):
    def test_actual_annual_returns_include_cash_year(self):
        r = {'initial': 100, 'history': [row('2018-01-02', 100, 100),
             row('2018-12-31', 110, 110), row('2019-12-31', 99, 110), row('2020-11-17', 99, 110)]}
        m = metrics(r)
        self.assertEqual(TARGET_ANNUAL_PCT, 10)
        self.assertAlmostEqual(m['annual'][0]['return_pct'], 10)
        self.assertAlmostEqual(m['annual'][1]['return_pct'], -10)
        self.assertEqual(m['annual'][2]['return_pct'], 0)
        self.assertAlmostEqual(m['annual_positive_fraction'], 1/3)
        self.assertAlmostEqual(m['worst_year_pct'], -10)
        self.assertLess(m['annualized_pct'], 0)

    def test_ytd_observed_return_not_annualized(self):
        m = metrics({'initial': 100, 'history': [row('2026-01-02', 100, 100), row('2026-11-17', 105, 105)]})
        self.assertAlmostEqual(m['annual'][0]['return_pct'], 5)
        self.assertGreater(m['annualized_pct'], 5)
        self.assertTrue(m['annual'][0]['boundary_year_may_be_partial'])


if __name__ == '__main__':
    unittest.main()
