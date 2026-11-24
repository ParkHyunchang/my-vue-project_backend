import csv
import importlib.util
import tempfile
import unittest
from datetime import date
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("us_performance", Path(__file__).parents[1] / "us_performance.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def row(day, equity, flow=0, benchmark=100, fees=0):
    return {"date": date.fromisoformat(day), "equity_usd": equity,
            "external_flow_usd": flow, "fees_usd": fees, "sp500_total_return_index": benchmark}


class PerformanceTest(unittest.TestCase):
    def test_deposit_and_withdrawal_do_not_create_returns(self):
        values = [row("2024-01-01", 100), row("2024-06-01", 200, 100), row("2025-01-01", 50, -150)]
        actual = MODULE.metrics(values)
        self.assertEqual(actual["strategy_twr"], 0)
        self.assertEqual(actual["strategy_max_drawdown"], 0)

    def test_fees_are_not_deducted_twice(self):
        actual = MODULE.metrics([row("2024-01-01", 100), row("2025-01-01", 99, fees=1)])
        self.assertAlmostEqual(actual["strategy_twr"], -0.01)
        self.assertEqual(actual["fees_usd_already_in_equity"], 1)

    def test_drawdown_uses_peak_and_flow_adjusted_nav(self):
        actual = MODULE.metrics([row("2024-01-01", 100), row("2024-06-01", 220, 100), row("2025-01-01", 198)])
        self.assertAlmostEqual(actual["strategy_twr"], 0.08)
        self.assertAlmostEqual(actual["strategy_max_drawdown"], 0.10)

    def test_synthetic_never_proves_profitability(self):
        values = [row("2024-01-01", 100), row("2025-01-01", 120)]
        result = MODULE.evaluate(values, holdout_start=date(2024, 1, 1))
        self.assertEqual(result["status"], "UNVERIFIED_SYNTHETIC_DATA")
        self.assertFalse(result["profitability_proven"])

    def test_holdout_is_assessed_separately(self):
        values = [row("2023-01-01", 100), row("2024-01-01", 200), row("2025-01-01", 190, benchmark=110)]
        result = MODULE.evaluate(values, "historical", date(2024, 1, 1))
        self.assertGreater(result["full_period"]["strategy_twr"], 0)
        self.assertFalse(result["gates"]["positive_excess_return"])
        self.assertEqual(result["status"], "CRITERIA_NOT_MET")

    def test_pass_requires_holdout_duration_and_drawdown(self):
        values = [row("2024-01-01", 100), row("2025-01-01", 120, benchmark=110)]
        self.assertEqual(MODULE.evaluate(values, "paper")["status"], "CRITERIA_NOT_MET")
        result = MODULE.evaluate(values, "paper", date(2024, 1, 1))
        self.assertEqual(result["status"], "CRITERIA_MET_ON_SUPPLIED_DATA")
        self.assertFalse(result["data_authenticity_verified"])
        self.assertAlmostEqual(result["holdout"]["excess_return_percentage_points"], 10)

    def test_reader_rejects_duplicate_date_nonfinite_and_missing_benchmark(self):
        for second in [("2024-01-01", "100", "100"), ("2025-01-01", "NaN", "100"), ("2025-01-01", "100", "")]:
            with self.subTest(second=second), tempfile.TemporaryDirectory() as folder:
                path = Path(folder) / "input.csv"
                with path.open("w", newline="") as stream:
                    writer = csv.writer(stream)
                    writer.writerow(["date", "equity_usd", "external_flow_usd", "fees_usd", "sp500_total_return_index"])
                    writer.writerow(["2024-01-01", 100, 0, 0, 100])
                    writer.writerow([second[0], second[1], 0, 0, second[2]])
                with self.assertRaises(ValueError):
                    MODULE.read_rows(path)

    def test_ten_percent_boundary_and_excess_are_independent(self):
        for terminal, allowed in [(90, True), (89.99, False)]:
            values = [row("2024-01-01", 100), row("2025-01-01", terminal, benchmark=80)]
            result = MODULE.evaluate(values, "historical", date(2024, 1, 1))
            self.assertTrue(result["gates"]["positive_excess_return"])
            self.assertEqual(result["gates"]["drawdown_within_10_percent"], allowed)

    def test_cagr_uses_elapsed_days(self):
        result = MODULE.metrics([row("2024-01-01", 100), row("2025-01-01", 121)])
        self.assertAlmostEqual(result["strategy_cagr"], 1.21 ** (365.25 / 366) - 1)

    def test_unmatched_holdout_and_invalid_flow_fail(self):
        values = [row("2024-01-01", 100), row("2025-01-01", 100, 100)]
        with self.assertRaises(ValueError):
            MODULE.metrics(values)
        with self.assertRaises(ValueError):
            MODULE.evaluate([row("2024-01-01", 100), row("2025-01-01", 110)], holdout_start=date(2024, 2, 1))


if __name__ == "__main__":
    unittest.main()
