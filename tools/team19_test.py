"""Adversarial checks for independent causal ledger accounting audit."""
import copy
import unittest

from team19_audit import audit_report


def sample():
    def state(at, cash, unsettled, reserve, quantity, price, equity):
        return {"type": "MARK", "at": at, "cash_usd": cash,
                "unsettled_cash_usd": unsettled, "reserved_cash_usd": reserve,
                "holdings": [{"symbol": "TEST", "quantity": quantity, "price": price}],
                "equity_usd": equity}
    final = state("2026-11-18T14:30:00Z", "908.92", 0, 0, 1, 110, "1018.92")
    result = {"initial_cash_usd": 1000, "initial_holdings": [], "fee_bps": 100,
            **{k: v for k, v in final.items() if k not in ("type", "at")},
            "events": [
                {"type": "ORDER", "at": "2026-11-17T17:00:00Z", "order_id": "B1",
                 "side": "BUY", "symbol": "TEST", "quantity": 5, "limit_price": 100, "market": False},
                {"type": "FILL", "at": "2026-11-17T17:00:30Z", "order_id": "B1",
                 "side": "BUY", "symbol": "TEST", "quantity": 2, "price": 99, "fee": "1.98",
                 "quote_at": "2026-11-17T17:00:30Z", "bid": 98, "ask": 99, "bid_size": 2, "ask_size": 2},
                state("2026-11-17T17:00:30Z", "800.02", 0, 303, 2, 98, "996.02"),
                {"type": "CANCEL", "at": "2026-11-17T17:00:30Z", "order_id": "B1", "quantity": 3},
                {"type": "ORDER", "at": "2026-11-17T17:01:00Z", "order_id": "S1",
                 "side": "SELL", "symbol": "TEST", "quantity": 1, "limit_price": 110, "market": False},
                {"type": "FILL", "at": "2026-11-17T17:01:30Z", "order_id": "S1",
                 "side": "SELL", "symbol": "TEST", "quantity": 1, "price": 110, "fee": "1.1",
                 "quote_at": "2026-11-17T17:01:30Z", "bid": 110, "ask": 111, "bid_size": 1, "ask_size": 1,
                 "settles_at": "2026-11-18T14:30:00Z"},
                state("2026-11-17T17:01:30Z", "800.02", "108.9", 0, 1, 110, "1018.92"),
                {"type": "SETTLEMENT", "at": "2026-11-18T14:30:00Z", "amount": "108.9", "order_id": "S1"},
                final]}
    for event in result["events"]:
        if event["type"] == "MARK":
            price = event["holdings"][0]["price"]
            event["quotes"] = {"TEST": {"bid": price, "ask": price + 1,
                "available_at": event["at"], "bid_size": 2 if price == 98 else 1,
                "ask_size": 2 if price == 98 else 1}}
    return result


class AuditTests(unittest.TestCase):
    def rejected(self, mutation):
        data = sample()
        mutation(data)
        with self.assertRaises((ValueError, KeyError)):
            audit_report(data)

    def test_full_causal_sequence(self):
        result = audit_report(sample())
        self.assertEqual(result["status"], "PASS")
        self.assertFalse(result["performance_verified"])
        self.assertEqual(result["ledgers"][0]["fills"], 2)

    def test_outer_report(self):
        self.assertEqual(audit_report({"scenarios": [{"ledger": sample()}]})["status"], "PASS")

    def test_same_instant_fill(self):
        self.rejected(lambda d: d["events"][1].update(quote_at=d["events"][0]["at"]))

    def test_unfunded_order(self):
        self.rejected(lambda d: d["events"][0].update(quantity=11))

    def test_unknown_order_fill(self):
        self.rejected(lambda d: d["events"][1].update(order_id="invented"))

    def test_fee_tampering(self):
        self.rejected(lambda d: d["events"][1].update(fee=0))

    def test_spread_tampering(self):
        self.rejected(lambda d: d["events"][1].update(price=98))

    def test_capacity_tampering(self):
        self.rejected(lambda d: d["events"][1].update(ask_size=1))

    def test_stale_quote(self):
        self.rejected(lambda d: d["events"][1].update(at="2026-11-17T17:03:00Z"))

    def test_premature_settlement(self):
        self.rejected(lambda d: d["events"][7].update(at="2026-11-17T17:02:00Z"))

    def test_overcancel(self):
        self.rejected(lambda d: d["events"][3].update(quantity=4))

    def test_equity_or_cash_fabrication(self):
        self.rejected(lambda d: d.update(equity_usd=99999))
        self.rejected(lambda d: d.update(cash_usd=99999))

    def test_holdings_fabrication(self):
        self.rejected(lambda d: d["holdings"][0].update(quantity=2))

    def test_duplicate_order(self):
        self.rejected(lambda d: d["events"].insert(1, copy.deepcopy(d["events"][0])))

    def test_future_quote(self):
        self.rejected(lambda d: d["events"][1].update(quote_at="2026-11-17T17:01:00Z"))

    def test_mark_price_must_match_frame_quote(self):
        self.rejected(lambda d: d["events"][6]["holdings"][0].update(price=100))

    def test_duplicate_holding_not_collapsed(self):
        self.rejected(lambda d: d["holdings"].append(copy.deepcopy(d["holdings"][0])))


if __name__ == "__main__":
    unittest.main()
