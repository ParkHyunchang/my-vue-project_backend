"""Independently reconstruct a replay ledger and performance, without importing replay code."""

import argparse
import csv
import json
import math
from datetime import date
from pathlib import Path


def close_enough(actual, expected, context):
    if not math.isclose(actual, expected, rel_tol=1e-10, abs_tol=1e-7):
        raise ValueError(f"{context}: {actual} != {expected}")


def audit(prefix, market):
    with market.open(newline="", encoding="utf-8") as stream:
        prices = {(row["date"], row["symbol"]): float(row["close"]) for row in csv.DictReader(stream)}
    report = json.loads(Path(str(prefix) + ".json").read_text(encoding="utf-8"))
    trades = json.loads(Path(str(prefix) + "-trades.json").read_text(encoding="utf-8"))
    with Path(str(prefix) + "-equity.csv").open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    cash, positions, peak, mdd = report["initial_cash_usd"], {}, report["initial_cash_usd"], 0
    by_day = {}
    for trade in trades:
        by_day.setdefault(trade["date"], []).append(trade)
    if set(by_day) - {row["date"] for row in rows}:
        raise ValueError("Trade outside equity window")
    for index, row in enumerate(rows):
        day, fees = row["date"], 0
        for trade in by_day.get(day, []):
            symbol, quantity, price, fee = (trade[key] for key in ("symbol", "quantity", "price", "fee"))
            close_enough(fee, quantity * price * report["fee_bps_per_side"] / 10000, "fee")
            if trade["side"] == "BUY":
                if index == 0 or trade["signal_date"] != rows[index - 1]["date"]:
                    raise ValueError("Buy is not the next valuation session after its signal")
                cash -= quantity * price + fee
                positions[symbol] = positions.get(symbol, 0) + quantity
            elif trade["side"] == "SELL":
                if symbol not in positions or quantity > positions[symbol] + 1e-7:
                    raise ValueError("Oversold holding")
                cash += quantity * price - fee
                positions[symbol] -= quantity
                if abs(positions[symbol]) < 1e-7:
                    del positions[symbol]
            else:
                raise ValueError("Unknown trade side")
            if cash < -1e-7:
                raise ValueError("Negative cash")
            fees += fee
        equity = cash + sum(quantity * prices[(day, symbol)] for symbol, quantity in positions.items())
        close_enough(float(row["external_flow_usd"]), 0, "unexpected flow")
        close_enough(float(row["fees_usd"]), fees, "daily fee")
        close_enough(float(row["equity_usd"]), equity, "daily equity")
        close_enough(float(row["sp500_total_return_index"]), prices[(day, "SPY")], "SPY proxy")
        peak = max(peak, equity)
        mdd = max(mdd, 1 - equity / peak)
    initial = float(rows[0]["equity_usd"])
    final = float(rows[-1]["equity_usd"])
    benchmark = prices[(rows[-1]["date"], "SPY")] / prices[(rows[0]["date"], "SPY")]
    days = (date.fromisoformat(rows[-1]["date"]) - date.fromisoformat(rows[0]["date"])).days
    result = {"strategy_twr": final / initial - 1, "strategy_cagr": (final / initial) ** (365.25 / days) - 1,
              "strategy_max_drawdown": mdd, "benchmark_total_return": benchmark - 1,
              "excess_return_percentage_points": (final / initial - benchmark) * 100}
    for name, value in result.items():
        close_enough(report["metrics"][name], value, name)
    close_enough(report["ending_cash"], cash, "ending cash")
    if set(report["ending_positions"]) != set(positions):
        raise ValueError("Ending positions mismatch")
    for side in ("BUY", "SELL"):
        if report[side.lower() + "_count"] != sum(trade["side"] == side for trade in trades):
            raise ValueError("Trade count mismatch")
    return {"status": "PASS", "prefix": str(prefix), "days": len(rows), "trades": len(trades),
            "independently_recomputed_metrics": result,
            "limits": "Ledger accounting and metric audit only; does not validate the live-strategy approximation or historic fills."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prefix", type=Path, nargs="+")
    parser.add_argument("--market", type=Path, default=Path(__file__).parent / "market_data" / "daily.csv")
    args = parser.parse_args()
    for prefix in args.prefix:
        result = audit(prefix, args.market)
        Path(str(prefix) + "-audit.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, indent=2))
