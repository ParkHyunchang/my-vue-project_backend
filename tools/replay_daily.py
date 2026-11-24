"""Offline daily-bar proxy replay, NOT the full live strategy. No network or broker code."""
import argparse
import csv
import json
import math
import hashlib
from datetime import date
from pathlib import Path
from us_performance import metrics


class PriceSeries(dict):
    repair_count = 0


def load_prices(path):
    prices = PriceSeries()
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            key = (row["date"], row["symbol"])
            if key in prices:
                raise ValueError("Duplicate price row")
            date.fromisoformat(key[0])
            bar = {name: float(row[name]) for name in ("open", "high", "low", "close")}
            if any(not math.isfinite(n) or n <= 0 for n in bar.values()):
                raise ValueError("Invalid price")
            corrected_high=max(bar["high"],bar["open"],bar["close"])
            corrected_low=min(bar["low"],bar["open"],bar["close"])
            if max(abs(corrected_high-bar["high"]),abs(corrected_low-bar["low"])) > max(bar.values())*1e-10:
                raise ValueError("Invalid OHLC ordering")
            if corrected_high!=bar["high"] or corrected_low!=bar["low"]:
                prices.repair_count += 1
            bar["high"],bar["low"]=corrected_high,corrected_low
            prices[key] = bar
    return prices


def replay(prices, exported, start, end, fee_bps=5, slippage_bps=5, initial=10000, overrides=None):
    if fee_bps < 0 or slippage_bps < 0 or slippage_bps >= 10000 or initial <= 0:
        raise ValueError("Invalid execution assumptions")
    settings = dict(exported["settings"])
    overrides = overrides or {}
    if set(overrides) - {"maxOrderPercent", "maxPositions"}:
        raise ValueError("Only declared allocation overrides are supported")
    settings.update(overrides)
    if not math.isfinite(settings["maxOrderPercent"]) or not 0 < settings["maxOrderPercent"] <= 100 or not isinstance(settings["maxPositions"], int) or settings["maxPositions"] < 1:
        raise ValueError("Invalid allocation override")
    dates = sorted(d for d, symbol in prices if symbol == "SPY" and start <= d <= end)
    if len(dates) < 2:
        raise ValueError("At least two sessions required")
    signals = {}
    for signal in exported["signals"]:
        if signal["accepted"]:
            if signal.get("dataDate") is None or signal["dataDate"] >= signal["date"]:
                raise ValueError("Signal uses unfinished/future bars")
            signals.setdefault(signal["date"], []).append(signal)
    symbols = {symbol for _, symbol in prices}
    for day in dates:
        for symbol in symbols:
            if (day, symbol) not in prices:
                raise ValueError(f"Missing session {day} {symbol}")
    cash, peak = float(initial), float(initial)
    positions, ledger, rows, cooldown = {}, [], [], {}
    latch, latch_date = False, None
    fee, slip = fee_bps / 10000, slippage_bps / 10000
    daily_fees = 0

    def sell(symbol, price, day, reason):
        nonlocal cash, daily_fees
        p = positions.pop(symbol)
        fill = price * (1 - slip)
        gross = p["quantity"] * fill
        commission = gross * fee
        cash += gross - commission
        daily_fees += commission
        ledger.append({"date": day, "symbol": symbol, "side": "SELL", "quantity": p["quantity"], "price": fill, "fee": commission, "reason": reason})

    for index, day in enumerate(dates):
        daily_fees = 0
        # First session is a cash-only close baseline, identical to the benchmark baseline.
        if index:
            for symbol in list(positions):
                if latch or index - positions[symbol]["entry_index"] >= settings["maxHoldingTradingDays"]:
                    sell(symbol, prices[day, symbol]["open"], day, "LATCH" if latch else "MAX_HOLD")
            if not latch:
                candidates = sorted(signals.get(dates[index-1], []), key=lambda s: (-s["relativeStrengthPercent"], s["symbol"]))
                bought = 0
                for signal in candidates:
                    symbol = signal["symbol"]
                    if symbol in positions or len(positions) >= settings["maxPositions"] or bought >= settings["dailyMaxBuys"]:
                        continue
                    if symbol in cooldown and (date.fromisoformat(day)-date.fromisoformat(cooldown[symbol])).days < settings["symbolCooldownDays"]:
                        continue
                    equity_open = cash + sum(p["quantity"] * prices[day, name]["open"] for name, p in positions.items())
                    price = prices[day, symbol]["open"] * (1 + slip)
                    risk_fraction = signal["stopPercent"] / 100
                    if not 0 < risk_fraction <= 1 or signal["atr"] <= 0:
                        raise ValueError("Invalid accepted risk signal")
                    budget = min(cash * .99, equity_open * settings["maxOrderPercent"] / 100,
                                 equity_open * settings["riskPerTradePercent"] / 100 / risk_fraction)
                    if budget <= 0:
                        continue
                    quantity = budget / (price * (1 + fee))
                    commission = quantity * price * fee
                    cash -= quantity * price + commission
                    daily_fees += commission
                    if cash < -1e-7:
                        raise AssertionError("Cash cap violated")
                    positions[symbol] = {"quantity": quantity, "entry": price, "entry_index": index,
                                         "risk": price * risk_fraction, "atr": signal["atr"],
                                         "high_water": price, "stop": price * (1 - risk_fraction)}
                    cooldown[symbol] = day
                    bought += 1
                    ledger.append({"date": day, "signal_date": dates[index-1], "symbol": symbol, "side": "BUY", "quantity": quantity, "price": price, "fee": commission, "reason": "PRIOR_CLOSE_SIGNAL"})
            # Stop was known before this daily low. Gaps fill at the worse opening price.
            # No same-day high is used to tighten the stop before testing today's low.
            for symbol in list(positions):
                p, bar = positions[symbol], prices[day, symbol]
                if bar["low"] <= p["stop"]:
                    sell(symbol, min(bar["open"], p["stop"]), day, "STOP")
                    continue
                p["high_water"] = max(p["high_water"], bar["close"])
                if p["high_water"] >= p["entry"] + p["risk"] * settings["trailingActivationR"]:
                    p["stop"] = max(p["stop"], p["high_water"] - p["atr"] * settings["trailingStopAtrMultiplier"])
            equity = cash + sum(p["quantity"] * prices[day, symbol]["close"] for symbol, p in positions.items())
            peak = max(peak, equity)
            if equity <= peak * .90 and not latch:
                latch, latch_date = True, day
        else:
            equity = cash
        rows.append({"date": day, "equity_usd": equity, "external_flow_usd": 0, "fees_usd": daily_fees,
                     "sp500_total_return_index": prices[day, "SPY"]["close"]})
    metric_rows = [{**row, "date": date.fromisoformat(row["date"])} for row in rows]
    report = {"status": "LIMITED_DAILY_PROXY_NOT_LIVE_STRATEGY", "profitability_proven": False,
              "start": dates[0], "end": dates[-1], "initial_cash_usd": initial,
              "fee_bps_per_side": fee_bps, "slippage_bps_per_side": slippage_bps,
              "floating_point_repairs": getattr(prices,"repair_count",0), "roundoff_tolerance_relative": 1e-10, "settings": settings, "applied_settings": settings, "exported_settings": exported["settings"], "declared_overrides": overrides, "signal_adapter": exported.get("adapter"), "signal_source_sha256": exported.get("sha256", {}), "latch_triggered": latch, "latch_date": latch_date,
              "buy_count": sum(t["side"] == "BUY" for t in ledger), "sell_count": sum(t["side"] == "SELL" for t in ledger),
              "ending_cash": cash, "ending_positions": list(positions), "metrics": metrics(metric_rows),
              "limitations": ["Fixed surviving stock sample; not point-in-time index membership or full universe.",
              "Production technical signal reused; no liquidity/rank/fundamental/intraday volume/spread screen.",
              "Candidates ranked by relative strength only, not live trendCandidateScore including extension/spread.",
              "Live daily 3% new-buy stop is not replayed; orders occur only once at session open.",
              "Prior 51 bars + current close zero-spread proxy; fills next session open without live revalidation.",
              "Dividend/split adjusted OHLC and fractional synthetic units; not live integer shares or actual dividend cash timing.",
              "All daily fills assumed complete; no rejection, queue, partial fills, or halt model.",
              "ATR stop uses known previous-close level and daily low; favorable intraday highs never tighten same-bar stop.",
              "Max holding exits at session open after configured sessions; no take-profit target in TREND proxy.",
              "10% latch evaluated at closes, liquidation next open, never reset; gaps can exceed 10%.",
              "Constant 1% cash buffer, no borrowing, max positions/daily buys/risk sizing, calendar-day cooldown.",
              "Period resets are retrospective comparisons, not untouched out-of-sample validation.",
              "SPY adjusted-close total-return proxy includes ETF expenses/tracking; not official S&P500 total-return index.",
              "Ending positions marked to close, not forcibly liquidated; unrealized exit costs excluded."]}
    return rows, ledger, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prices", type=Path)
    parser.add_argument("signals", type=Path)
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--output-prefix", required=True, type=Path)
    parser.add_argument("--fee-bps", type=float, default=5)
    parser.add_argument("--slippage-bps", type=float, default=5)
    parser.add_argument("--max-order-percent", type=float)
    parser.add_argument("--max-positions", type=int)
    args = parser.parse_args()
    try:
        date.fromisoformat(args.start); date.fromisoformat(args.end)
        overrides = {}
        if args.max_order_percent is not None: overrides["maxOrderPercent"] = args.max_order_percent
        if args.max_positions is not None: overrides["maxPositions"] = args.max_positions
        rows, trades, report = replay(load_prices(args.prices), json.loads(args.signals.read_text(encoding="utf-8")), args.start, args.end, args.fee_bps, args.slippage_bps, overrides=overrides)
        report["input_sha256"] = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in (args.prices,args.signals,Path(__file__))}
        prefix = args.output_prefix
        prefix.parent.mkdir(parents=True, exist_ok=True)
        for suffix, data in [(".json", report), ("-trades.json", trades)]:
            Path(str(prefix)+suffix).write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False)+"\n", encoding="utf-8")
        with Path(str(prefix)+"-equity.csv").open("w", encoding="utf-8", newline="") as output:
            writer=csv.DictWriter(output,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
        print(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False))
    except (ValueError, KeyError, OSError) as error:
        parser.error(str(error))

if __name__ == "__main__":
    main()
