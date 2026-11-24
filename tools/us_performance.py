"""Offline, paired USD performance evaluation. No broker or network integration."""

import argparse
import csv
import json
import math
from datetime import date
from pathlib import Path


FIELDS = {"date", "equity_usd", "external_flow_usd", "fees_usd", "sp500_total_return_index"}


def read_rows(path):
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not FIELDS.issubset(reader.fieldnames or []):
            raise ValueError("CSV requires: " + ", ".join(sorted(FIELDS)))
        rows = []
        for number, row in enumerate(reader, 2):
            try:
                value = {key: float(row[key]) for key in FIELDS - {"date"}}
                value["date"] = date.fromisoformat(row["date"])
            except (TypeError, ValueError) as error:
                raise ValueError(f"Invalid CSV row {number}") from error
            if any(not math.isfinite(value[key]) for key in FIELDS - {"date"}):
                raise ValueError(f"Non-finite value at row {number}")
            if value["equity_usd"] <= 0 or value["sp500_total_return_index"] <= 0:
                raise ValueError(f"Equity and benchmark must be positive at row {number}")
            if value["fees_usd"] < 0:
                raise ValueError(f"Fees must be nonnegative at row {number}")
            if rows and value["date"] <= rows[-1]["date"]:
                raise ValueError("Dates must be unique and strictly increasing")
            rows.append(value)
    if len(rows) < 2:
        raise ValueError("At least two paired valuation dates are required")
    return rows


def metrics(rows):
    """Flows occur AFTER the day's valuation return; fees are ALREADY in equity."""
    nav, peak, drawdown = 1.0, 1.0, 0.0
    for previous, current in zip(rows, rows[1:]):
        factor = (current["equity_usd"] - current["external_flow_usd"]) / previous["equity_usd"]
        if factor <= 0:
            raise ValueError("Nonpositive pre-flow equity; cannot calculate chained returns")
        nav *= factor
        peak = max(peak, nav)
        drawdown = max(drawdown, 1 - nav / peak)
    benchmark_nav = [row["sp500_total_return_index"] / rows[0]["sp500_total_return_index"] for row in rows]
    benchmark_peak, benchmark_drawdown = 1.0, 0.0
    for value in benchmark_nav:
        benchmark_peak = max(benchmark_peak, value)
        benchmark_drawdown = max(benchmark_drawdown, 1 - value / benchmark_peak)
    days = (rows[-1]["date"] - rows[0]["date"]).days
    benchmark = benchmark_nav[-1]
    return {
        "start": rows[0]["date"].isoformat(), "end": rows[-1]["date"].isoformat(),
        "observations": len(rows), "calendar_days": days,
        "strategy_twr": nav - 1, "benchmark_total_return": benchmark - 1,
        "excess_return_percentage_points": (nav - benchmark) * 100,
        "strategy_cagr": nav ** (365.25 / days) - 1,
        "benchmark_cagr": benchmark ** (365.25 / days) - 1,
        "strategy_max_drawdown": drawdown, "benchmark_max_drawdown": benchmark_drawdown,
        "external_flows_usd": sum(row["external_flow_usd"] for row in rows[1:]),
        "fees_usd_already_in_equity": sum(row["fees_usd"] for row in rows[1:]),
    }


def evaluate(rows, kind="synthetic", holdout_start=None, min_days=365):
    if min_days < 1:
        raise ValueError("min-days must be positive")
    full = metrics(rows)
    holdout = None
    if holdout_start is not None:
        # Exact baseline avoids silently changing the declared holdout window.
        if holdout_start not in [row["date"] for row in rows]:
            raise ValueError("holdout-start must match an input valuation date")
        selected = [row for row in rows if row["date"] >= holdout_start]
        if len(selected) < 2:
            raise ValueError("Holdout requires at least two observations")
        holdout = metrics(selected)
    assessed = holdout or full
    gates = {
        "positive_excess_return": assessed["excess_return_percentage_points"] > 0,
        "drawdown_within_10_percent": assessed["strategy_max_drawdown"] <= 0.10 + 1e-12,
        "minimum_calendar_days": assessed["calendar_days"] >= min_days,
        "holdout_provided": holdout is not None,
        "non_synthetic_input": kind != "synthetic",
    }
    status = "CRITERIA_MET_ON_SUPPLIED_DATA" if all(gates.values()) else "CRITERIA_NOT_MET"
    if kind == "synthetic":
        status = "UNVERIFIED_SYNTHETIC_DATA"
    return {
        "status": status, "input_kind_declared": kind,
        "data_authenticity_verified": False, "profitability_proven": False,
        "minimum_calendar_days": min_days, "gates": gates,
        "full_period": full, "holdout": holdout,
        "limitations": [
            "Input provenance and total-return/dividend treatment are caller assertions, not verified.",
            "End-of-day external flows only; intraday flows require separate valuations.",
            "Whole-account USD equity must include all holdings, cash, fees and accrued liabilities.",
            "Drawdown is sampled at supplied dates; intraday losses and gaps can exceed 10%.",
            "Missing sessions, survivorship, lookahead and overfitting are not detected automatically.",
            "This is a performance evaluator, not a strategy replay or a live/paper order engine.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    parser.add_argument("--kind", choices=["synthetic", "historical", "paper", "live"], default="synthetic")
    parser.add_argument("--holdout-start", type=date.fromisoformat)
    parser.add_argument("--min-days", type=int, default=365)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.output and args.output.resolve() == args.csv.resolve():
            raise ValueError("Output must not overwrite the input CSV")
        report = evaluate(read_rows(args.csv), args.kind, args.holdout_start, args.min_days)
        encoded = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
        if args.output:
            args.output.write_text(encoded + "\n", encoding="utf-8")
        print(encoded)
    except (ValueError, OSError, OverflowError) as error:
        parser.error(str(error))
    return 0 if report["status"] == "CRITERIA_MET_ON_SUPPLIED_DATA" else 2


if __name__ == "__main__":
    raise SystemExit(main())
