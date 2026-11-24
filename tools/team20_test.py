"""Independent calculation and fail-closed tests for synthetic selection diagnostics."""
import copy
import unittest
from decimal import Decimal

from team19_test import sample
from team20_analysis import analyze as full_analysis, validate_matrix


def analyze(data):
    return full_analysis(data, require_matrix=False)


def report():
    ledger = sample()
    ledger["initial_equity_usd"] = 1000
    ledger["events"].insert(0, {"type": "MARK", "at": "2026-11-17T17:00:00Z",
        "cash_usd": 1000, "unsettled_cash_usd": 0, "reserved_cash_usd": 0,
        "equity_usd": 1000, "holdings": [], "quotes": {}})
    return {"performance_verified": False, "scenarios": [{
        "scenario": "sample", "selection_variant": "BASE", "market_path": "CONTINUATION",
        "dataset_kind": "synthetic", "execution_mode": "OFFLINE_CAUSAL_ACTUAL_SERVICES",
        "real_order_count": 0, "performance_verified": False, "ledger": ledger}]}


class SelectionAnalysisTests(unittest.TestCase):
    def test_equity_return_and_observed_drawdown_after_fees(self):
        result = analyze(report())
        row = result["scenarios"][0]
        self.assertEqual(Decimal(row["synthetic_return_pct"]), Decimal("1.892"))
        self.assertEqual(Decimal(row["synthetic_observed_max_drawdown_pct"]), Decimal("0.398"))
        self.assertEqual(Decimal(row["fees_usd"]), Decimal("3.08"))
        self.assertEqual(row["buy_filled_shares"], 2)
        self.assertEqual(row["sell_filled_shares"], 1)
        self.assertFalse(result["performance_verified"])
        self.assertIsNone(result["benchmark_comparison"])
        self.assertEqual(result["adoption_decision"], "NOT_ESTABLISHED_SYNTHETIC_ONLY")

    def test_source_not_mutated(self):
        original = report()
        before = copy.deepcopy(original)
        analyze(original)
        self.assertEqual(original, before)

    def test_final_equity_tampering_rejected_before_metrics(self):
        data = report()
        data["scenarios"][0]["ledger"]["equity_usd"] = 99999
        with self.assertRaises(ValueError): analyze(data)

    def test_initial_baseline_tampering_rejected(self):
        data = report()
        data["scenarios"][0]["ledger"]["initial_equity_usd"] = 900
        with self.assertRaises(ValueError): analyze(data)

    def test_real_data_relabel_does_not_enable_performance(self):
        data = report()
        data["scenarios"][0]["dataset_kind"] = "real_point_in_time"
        with self.assertRaises(ValueError): analyze(data)

    def test_market_claim_rejected(self):
        for key in ("top", "scenario"):
            data = report()
            (data if key == "top" else data["scenarios"][0])["performance_verified"] = True
            with self.assertRaises(ValueError): analyze(data)

    def test_legacy_noncausal_mode_rejected(self):
        data = report()
        data["scenarios"][0]["execution_mode"] = "OFFLINE_ACTUAL_SERVICES"
        with self.assertRaises(ValueError): analyze(data)

    def test_missing_initial_mark_rejected(self):
        data = report()
        data["scenarios"][0]["ledger"]["events"].pop(0)
        with self.assertRaises(ValueError): analyze(data)

    def test_empty_scenarios_rejected(self):
        with self.assertRaises(ValueError): analyze({"performance_verified": False, "scenarios": []})

    def test_unsettled_sales_count_in_equity_without_becoming_cash(self):
        data = report()
        ledger = data["scenarios"][0]["ledger"]
        ledger["events"] = ledger["events"][:-2]
        mark = ledger["events"][-1]
        for key in ("cash_usd", "unsettled_cash_usd", "reserved_cash_usd", "holdings", "equity_usd"):
            ledger[key] = copy.deepcopy(mark[key])
        row = analyze(data)["scenarios"][0]
        self.assertEqual(Decimal(row["synthetic_return_pct"]), Decimal("1.892"))
        self.assertEqual(Decimal(row["unsettled_cash_usd"]), Decimal("108.9"))

    def test_incomplete_matrix_rejected(self):
        with self.assertRaises((KeyError, ValueError)):
            full_analysis(report())

    def test_comparison_design_and_market_input_match(self):
        cases = []
        for variant, relative, extension in (("BASE", 0, 1), ("TIGHT_EXTENSION", 0, .25), ("STRONG_RELATIVE", 8, 1)):
            for path in ("CONTINUATION", "REVERSAL"):
                case = report()["scenarios"][0]
                case.update(selection_variant=variant, market_path=path,
                            settings={"minRelativeStrengthPercent": relative, "maxEntryExtensionAtr": extension,
                                      "maxOrderPercent": 10, "riskPerTradePercent": .5})
                case["ledger"].update(initial_cash_usd=10000, fee_bps=10)
                cases.append(case)
        self.assertEqual(validate_matrix(cases), 6)
        with self.assertRaises(ValueError): validate_matrix(cases + [copy.deepcopy(cases[0])])
        changed = copy.deepcopy(cases)
        changed[2]["settings"]["riskPerTradePercent"] = 1
        with self.assertRaises(ValueError): validate_matrix(changed)
        changed = copy.deepcopy(cases)
        changed[2]["ledger"]["events"][3]["at"] = "2026-11-17T18:00:00Z"
        with self.assertRaises(ValueError): validate_matrix(changed)

    def test_idle_cash_is_part_of_account_return(self):
        data = report()
        ledger = self.holding_only_ledger([10, 11])
        data["scenarios"][0]["ledger"] = ledger
        row = analyze(data)["scenarios"][0]
        self.assertEqual(Decimal(row["synthetic_return_pct"]), Decimal("5"))
        self.assertEqual(row["buy_fill_count"], 0)

    def test_drawdown_is_from_running_peak_even_with_positive_return(self):
        data = report()
        data["scenarios"][0]["ledger"] = self.holding_only_ledger([10, 15, "12.5"])
        row = analyze(data)["scenarios"][0]
        self.assertEqual(Decimal(row["synthetic_return_pct"]), Decimal("12.5"))
        self.assertEqual(Decimal(row["synthetic_observed_max_drawdown_pct"]), Decimal("10"))

    @staticmethod
    def holding_only_ledger(prices):
        events = []
        for index, price in enumerate(prices):
            at = f"2026-11-17T17:0{index}:00Z"
            events.append({"type": "MARK", "at": at, "cash_usd": 100,
                "unsettled_cash_usd": 0, "reserved_cash_usd": 0,
                "equity_usd": str(100 + Decimal(str(price)) * 10),
                "holdings": [{"symbol": "TEST", "quantity": 10, "price": price}],
                "quotes": {"TEST": {"bid": price, "ask": price, "available_at": at,
                    "bid_size": 100, "ask_size": 100}}})
        return {"initial_cash_usd": 100, "initial_equity_usd": 200,
                "initial_holdings": [{"symbol": "TEST", "quantity": 10}], "fee_bps": 0,
                **{k: v for k, v in events[-1].items() if k not in ("type", "at", "quotes")},
                "events": events}


if __name__ == "__main__":
    unittest.main()
