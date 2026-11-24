"""Audited synthetic selection-to-ledger diagnostics, never market performance approval.

Usage: python -B tools/team20_analysis.py <selection-report.json>
No downloaded prices, operating account, network, or production configuration is read.
"""
import json
import sys
from decimal import Decimal
from pathlib import Path

from team19_audit import audit_ledger, equal, integer, num, require

VARIANTS = {"BASE": (0, 1), "TIGHT_EXTENSION": (0, "0.25"), "STRONG_RELATIVE": (8, 1)}
PATHS = ("CONTINUATION", "REVERSAL")


def analyze_scenario(scenario):
    require(scenario.get("dataset_kind") == "synthetic", "Only declared synthetic scenarios supported")
    require(scenario.get("performance_verified") is False, "Market-performance assertion prohibited")
    require(scenario.get("execution_mode") == "OFFLINE_CAUSAL_ACTUAL_SERVICES", "Actual-service causal replay required")
    require(scenario.get("real_order_count") == 0, "Real-order evidence prohibited")
    ledger = scenario["ledger"]
    audit = audit_ledger(ledger)
    events = ledger["events"]
    require(events and events[0]["type"] == "MARK", "Initial mark must precede orders")
    marks = [e for e in events if e["type"] == "MARK"]
    initial = num(ledger["initial_cash_usd"])
    for h in ledger["initial_holdings"]:
        quote = marks[0]["quotes"][h["symbol"]]
        initial += num(quote["bid"]) * integer(h["quantity"])
    require(initial > 0, "Positive initial equity required")
    equal(ledger["initial_equity_usd"], initial, "initial equity from initial holdings and cash")
    equal(marks[0]["equity_usd"], initial, "initial mark equity")
    ending = num(ledger["equity_usd"])
    peak = initial
    max_drawdown = Decimal(0)
    for equity in [num(m["equity_usd"]) for m in marks] + [ending]:
        require(equity >= 0, "Negative equity")
        peak = max(peak, equity)
        max_drawdown = max(max_drawdown, (peak - equity) / peak * 100)
    fills = [e for e in events if e["type"] == "FILL"]
    buys = [e for e in fills if e["side"] == "BUY"]
    sells = [e for e in fills if e["side"] == "SELL"]
    ordered = [e["symbol"] for e in events if e["type"] == "ORDER" and e["side"] == "BUY"]
    return {
        "scenario": scenario["scenario"],
        "selection_variant": scenario.get("selection_variant", "UNSPECIFIED"),
        "market_path": scenario.get("market_path", "UNSPECIFIED"),
        "dataset_kind": "synthetic",
        "audit": audit,
        "initial_equity_usd": str(initial),
        "ending_equity_usd": str(ending),
        "synthetic_return_pct": str((ending / initial - 1) * 100),
        "synthetic_observed_max_drawdown_pct": str(max_drawdown),
        "fees_usd": str(sum((num(e["fee"]) for e in fills), Decimal(0))),
        "buy_order_symbols": ordered,
        "buy_fill_count": len(buys),
        "sell_fill_count": len(sells),
        "buy_filled_shares": sum(integer(e["quantity"]) for e in buys),
        "sell_filled_shares": sum(integer(e["quantity"]) for e in sells),
        "remaining_holdings": ledger["holdings"],
        "unsettled_cash_usd": str(num(ledger["unsettled_cash_usd"])),
        "first_observation": marks[0]["at"],
        "last_observation": marks[-1]["at"],
        "performance_verified": False,
        "benchmark_comparison": None,
    }


def validate_matrix(scenarios):
    matrix = {}
    for scenario in scenarios:
        variant = scenario.get("selection_variant")
        if variant not in VARIANTS:
            require(variant in (None, "REGRESSION"), "Unknown selection variant")
            continue
        key = (variant, scenario.get("market_path"))
        require(key[1] in PATHS and key not in matrix, "Duplicate or unknown comparison case")
        settings = scenario["settings"]
        relative, extension = VARIANTS[variant]
        equal(settings["minRelativeStrengthPercent"], num(relative), "predeclared relative strength")
        equal(settings["maxEntryExtensionAtr"], num(extension), "predeclared extension")
        equal(settings["maxOrderPercent"], num(10), "predeclared position allocation")
        ledger = scenario["ledger"]
        equal(ledger["initial_cash_usd"], num(10000), "predeclared initial cash")
        equal(ledger["fee_bps"], num(10), "predeclared fee")
        require(ledger["initial_holdings"] == [], "Predeclared initial holdings differ")
        matrix[key] = scenario
    require(set(matrix) == {(v, p) for v in VARIANTS for p in PATHS}, "All six predeclared comparison cases required")
    for path in PATHS:
        reference = matrix[("BASE", path)]
        baseline = {k: v for k, v in reference["settings"].items()
                    if k not in ("minRelativeStrengthPercent", "maxEntryExtensionAtr")}
        observations = [(e["at"], e["quotes"]) for e in reference["ledger"]["events"] if e["type"] == "MARK"]
        for variant in VARIANTS:
            case = matrix[(variant, path)]
            require({k: v for k, v in case["settings"].items()
                     if k not in ("minRelativeStrengthPercent", "maxEntryExtensionAtr")} == baseline,
                    "Non-selection setting differs between comparisons")
            require([(e["at"], e["quotes"]) for e in case["ledger"]["events"] if e["type"] == "MARK"] == observations,
                    "Market observations differ between comparisons")
    return len(matrix)


def analyze(report, require_matrix=True):
    require(report.get("performance_verified") is False, "Cannot certify market performance")
    scenarios = report["scenarios"]
    require(isinstance(scenarios, list) and scenarios, "Scenarios required")
    rows = [analyze_scenario(s) for s in scenarios]
    matrix_count = validate_matrix(scenarios) if require_matrix else None
    return {
        "status": "PASS_SYNTHETIC_DIAGNOSTICS_ONLY",
        "performance_verified": False,
        "adoption_decision": "NOT_ESTABLISHED_SYNTHETIC_ONLY",
        "benchmark_comparison": None,
        "predeclared_comparison_count": matrix_count,
        "limitations": [
            "Synthetic paths and candidate populations do not establish predictive skill or real returns.",
            "Drawdown uses supplied observations only; no unseen intraframe prices are inferred.",
            "Full account includes cash, unsettled net sale proceeds and bid-valued holdings after fees.",
            "Unsettled sale proceeds are assets, not immediately available purchase cash.",
            "No strategy recommendation, S&P 500 outperformance claim, or operating deployment follows.",
        ],
        "scenarios": rows,
    }


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("Provide exactly one selection replay report")
        data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"), parse_float=Decimal)
        print(json.dumps(analyze(data), indent=2, default=str))
    except (ValueError, KeyError, TypeError, OSError, ArithmeticError) as error:
        print(json.dumps({"status": "FAIL", "reason": str(error), "performance_verified": False}))
        raise SystemExit(1)
