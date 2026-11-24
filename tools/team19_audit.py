"""Independent event-ledger audit; no market-return or live-execution certification.

Usage: python -B tools/team19_audit.py <causal-replay-report.json>
Reconstructs balances from initial state and events rather than accepting final totals.
"""
import json
import sys
from datetime import datetime
from decimal import Decimal, getcontext
from pathlib import Path

ZERO = Decimal(0)
getcontext().prec = 80
QUANTUM = Decimal("0.000000000001")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def num(value):
    require(not isinstance(value, bool), "boolean amount")
    n = Decimal(str(value))
    require(n.is_finite(), "nonfinite amount")
    return n


def integer(value):
    n = num(value)
    require(n == n.to_integral_value() and n >= 0, "invalid quantity")
    return int(n)


def timestamp(value):
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(result.tzinfo is not None, "timezone missing")
    return result


def equal(actual, expected, field):
    require(abs(num(actual) - expected) <= QUANTUM, "mismatch: " + field)


def audit_ledger(report):
    cash = num(report["initial_cash_usd"])
    require(cash >= 0, "negative initial cash")
    fee_bps = num(report["fee_bps"])
    require(fee_bps >= 0, "negative fee")
    positions = {}
    for holding in report["initial_holdings"]:
        symbol = holding["symbol"]
        require(symbol not in positions, "duplicate initial holding")
        positions[symbol] = integer(holding["quantity"])
    orders, unsettled, capacities = {}, [], {}
    fill_ids = set()
    market_marks = {}
    for event in report["events"]:
        if event["type"] == "MARK":
            key = timestamp(event["at"])
            require(key not in market_marks, "duplicate frame mark")
            market_marks[key] = event["quotes"]
    last_quotes = None
    previous_at = None
    fills = marks = 0

    def fee(notional):
        return notional * fee_bps / 10000

    def reserved():
        return sum((o["limit"] * o["remaining"] + fee(o["limit"] * o["remaining"])
                    for o in orders.values() if o["side"] == "BUY" and o["remaining"]), ZERO)

    def pending_cash():
        return sum((row["amount"] for row in unsettled), ZERO)

    def snapshot(state, quotes, at):
        equal(state["cash_usd"], cash, "settled cash")
        equal(state["unsettled_cash_usd"], pending_cash(), "unsettled cash")
        equal(state["reserved_cash_usd"], reserved(), "reserved cash")
        if "available_cash_usd" in state:
            equal(state["available_cash_usd"], cash - reserved(), "available cash")
        actual = {h["symbol"]: integer(h["quantity"]) for h in state["holdings"]}
        require(len(actual) == len(state["holdings"]), "duplicate holding snapshot")
        require({s: q for s, q in actual.items() if q} == {s: q for s, q in positions.items() if q},
                "holding quantity mismatch")
        valuation = ZERO
        for holding in state["holdings"]:
            price = num(holding["price"])
            require(price > 0, "nonpositive mark")
            quote = quotes[holding["symbol"]]
            equal(price, num(quote["bid"]), "mark price versus bid")
            quote_at = timestamp(quote["available_at"])
            require(0 <= (at - quote_at).total_seconds() <= 90, "stale or future mark")
            valuation += price * integer(holding["quantity"])
        equal(state["equity_usd"], cash + pending_cash() + valuation, "equity")

    for sequence, event in enumerate(report["events"], 1):
        if "event_seq" in event:
            require(integer(event["event_seq"]) == sequence, "event sequence mismatch")
        at = timestamp(event["at"])
        require(previous_at is None or at >= previous_at, "event time reversed")
        previous_at = at
        kind = event["type"]
        if kind == "ORDER":
            key = event["order_id"]
            require(key not in orders, "duplicate order")
            side = event["side"]
            require(side in ("BUY", "SELL"), "invalid side")
            qty = integer(event["quantity"])
            require(qty > 0, "zero order quantity")
            limit = num(event["limit_price"])
            market = event.get("market", False)
            require(isinstance(market, bool), "invalid market flag")
            require((market and side == "SELL") or limit > 0, "invalid order price")
            symbol = event["symbol"]
            if side == "BUY":
                require(not market, "market buy unsupported")
                require(limit * qty <= (cash - reserved()) * Decimal("0.99"), "99-percent cash guard breached")
                require(limit * qty + fee(limit * qty) <= cash - reserved(), "unfunded order")
            else:
                committed = sum(o["remaining"] for o in orders.values()
                                if o["side"] == "SELL" and o["symbol"] == symbol)
                require(qty <= positions.get(symbol, 0) - committed, "oversell order")
            orders[key] = {"side": side, "symbol": symbol, "remaining": qty,
                           "limit": limit, "market": market, "at": at}
        elif kind == "FILL":
            key = event["order_id"]
            require(key in orders, "fill without order")
            order = orders[key]
            require(event["side"] == order["side"] and event["symbol"] == order["symbol"],
                    "fill identity mismatch")
            qty = integer(event["quantity"])
            require(0 < qty <= order["remaining"], "overfill or zero fill")
            quote_at = timestamp(event["quote_at"])
            require(order["at"] < quote_at <= at and at > order["at"], "noncausal fill")
            require((at - quote_at).total_seconds() <= 90, "stale fill")
            bid, ask, price = num(event["bid"]), num(event["ask"]), num(event["price"])
            require(0 < bid <= ask, "invalid fill quote")
            source_quote = market_marks[at][order["symbol"]]
            require(timestamp(source_quote["available_at"]) == quote_at, "fill quote time differs from frame")
            for field in ("bid", "ask", "bid_size", "ask_size"):
                equal(event[field], num(source_quote[field]), "fill versus market frame " + field)
            fill_id = event.get("fill_id", str(sequence))
            require(fill_id not in fill_ids, "duplicate fill id")
            fill_ids.add(fill_id)
            buying = order["side"] == "BUY"
            equal(price, ask if buying else bid, "fill price versus quote")
            require(order["market"] or (price <= order["limit"] if buying else price >= order["limit"]),
                    "limit breached")
            cap = integer(event["ask_size"] if buying else event["bid_size"])
            capacity_key = (quote_at, order["symbol"], order["side"])
            used = capacities.get(capacity_key, 0) + qty
            require(used <= cap, "quote liquidity reused")
            capacities[capacity_key] = used
            notional = price * qty
            charge = fee(notional)
            equal(event["fee"], charge, "fill fee")
            order["remaining"] -= qty
            symbol = order["symbol"]
            if buying:
                cash -= notional + charge
                positions[symbol] = positions.get(symbol, 0) + qty
            else:
                require(positions.get(symbol, 0) >= qty, "oversell fill")
                positions[symbol] -= qty
                settles = timestamp(event["settles_at"])
                require(settles > at, "instant sell settlement")
                unsettled.append({"order_id": key, "fill_id": fill_id, "amount": notional - charge, "at": settles})
            fills += 1
        elif kind == "CANCEL":
            order = orders[event["order_id"]]
            qty = integer(event["quantity"])
            require(0 < qty <= order["remaining"], "overcancel")
            order["remaining"] -= qty
        elif kind == "SETTLEMENT":
            amount = num(event["amount"])
            require(amount > 0, "nonpositive settlement")
            # All due receivables may be settled together or as individual events.
            remaining = amount
            for row in unsettled:
                if (row["at"] <= at
                        and ("order_id" not in event or event["order_id"] == row["order_id"])
                        and ("fill_id" not in event or event["fill_id"] == row["fill_id"])):
                    taken = min(row["amount"], remaining)
                    row["amount"] -= taken
                    remaining -= taken
            require(remaining == 0, "unearned or premature settlement")
            unsettled = [row for row in unsettled if row["amount"]]
            cash += amount
        elif kind == "MARK":
            last_quotes = event["quotes"]
            snapshot(event, last_quotes, at)
            marks += 1
        elif kind == "REJECT":
            pass  # Explicit rejected attempts have no balance effect.
        else:
            raise ValueError("unknown ledger event")
        require(cash >= 0 and reserved() <= cash, "negative cash or overreserved cash")
    require(marks > 0, "no mark evidence")
    snapshot(report, last_quotes, previous_at)
    return {"status": "PASS", "events": len(report["events"]), "orders": len(orders),
            "fills": fills, "marks": marks, "performance_verified": False}


def audit_report(report):
    if "scenarios" in report:
        results = [audit_ledger(s["ledger"]) for s in report["scenarios"]]
        require(bool(results), "empty scenarios")
    else:
        results = [audit_ledger(report.get("ledger", report))]
    return {"status": "PASS", "ledgers": results, "performance_verified": False,
            "scope": "Independent event accounting audit; not market provenance or profitability."}


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("Provide exactly one report file")
        value = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"), parse_float=Decimal)
        print(json.dumps(audit_report(value), indent=2))
    except (ValueError, KeyError, TypeError, OSError, ArithmeticError) as error:
        print(json.dumps({"status": "FAIL", "reason": str(error), "performance_verified": False}))
        raise SystemExit(1)
