import unittest
from replay_daily import replay, load_prices
from pathlib import Path
from tempfile import TemporaryDirectory

SETTINGS = {"maxOrderPercent": 10, "riskPerTradePercent": .5, "maxPositions": 3,
            "dailyMaxBuys": 2, "maxHoldingTradingDays": 5, "trailingStopAtrMultiplier": 2,
            "trailingActivationR": 1, "symbolCooldownDays": 5}
DAYS = ["2024-01-02", "2024-01-03", "2024-01-04", "2024-01-05", "2024-01-08"]

def fixture(settings=None):
    prices = {(d,s): {"open":100.,"high":101.,"low":99.,"close":100.} for d in DAYS for s in ["SPY","QQQ","A"]}
    exported = {"settings": dict(settings or SETTINGS), "signals": [{"date": DAYS[0], "dataDate":"2023-12-29", "symbol":"A", "accepted":True,"relativeStrengthPercent":5,"atr":1,"stopPercent":2}]}
    return prices,exported

class ReplayTests(unittest.TestCase):
    def test_allocation_override_is_explicit_and_preserves_exported_settings(self):
        prices,signals=fixture()
        _,_,report=replay(prices,signals,DAYS[0],DAYS[-1],overrides={"maxOrderPercent":25,"maxPositions":4})
        self.assertEqual(25,report["applied_settings"]["maxOrderPercent"])
        self.assertEqual(10,report["exported_settings"]["maxOrderPercent"])
        self.assertEqual(3,signals["settings"]["maxPositions"])
        with self.assertRaises(ValueError):replay(prices,signals,DAYS[0],DAYS[-1],overrides={"maxPositions":0})

    def test_only_tiny_ohlc_roundoff_is_repaired(self):
        with TemporaryDirectory() as directory:
            path=Path(directory)/"bars.csv"
            path.write_text("date,symbol,open,high,low,close\n2024-01-02,SPY,100,100,99,100.000000000001\n")
            prices=load_prices(path)
            self.assertEqual(1,prices.repair_count)
            self.assertEqual(prices[DAYS[0],"SPY"]["close"],prices[DAYS[0],"SPY"]["high"])
            path.write_text("date,symbol,open,high,low,close\n2024-01-02,SPY,100,100,99,100.01\n")
            with self.assertRaises(ValueError):load_prices(path)

    def test_next_open_not_same_close_and_fees_reduce_cash(self):
        prices,signals=fixture();prices[DAYS[1],"A"]["open"]=100.5
        _,trades,report=replay(prices,signals,DAYS[0],DAYS[-1])
        buy=trades[0]
        self.assertEqual(DAYS[1],buy["date"]);self.assertEqual(DAYS[0],buy["signal_date"])
        self.assertAlmostEqual(100.5*1.0005,buy["price"])
        self.assertLessEqual(buy["quantity"]*buy["price"]+buy["fee"],1000+1e-8)
        self.assertGreaterEqual(report["ending_cash"],0)

    def test_gap_stop_uses_worse_open(self):
        prices,signals=fixture();prices[DAYS[2],"A"]={"open":90,"high":93,"low":88,"close":91}
        _,trades,_=replay(prices,signals,DAYS[0],DAYS[-1],0,0)
        self.assertEqual("STOP",trades[1]["reason"]);self.assertEqual(90,trades[1]["price"])

    def test_intraday_high_does_not_tighten_same_bar_stop(self):
        prices,signals=fixture();prices[DAYS[1],"A"]["high"]=200
        _,trades,_=replay(prices,signals,DAYS[0],DAYS[-1],0,0)
        self.assertEqual(1,len(trades))

    def test_permanent_ten_percent_latch_liquidates_next_open(self):
        config={**SETTINGS,"maxOrderPercent":100,"riskPerTradePercent":100,"maxPositions":1}
        prices,signals=fixture(config);signals["signals"][0]["stopPercent"]=50
        prices[DAYS[1],"A"]={"open":100,"high":100,"low":89,"close":89}
        prices[DAYS[2],"A"]={"open":80,"high":85,"low":79,"close":84}
        signals["signals"].append({**signals["signals"][0],"date":DAYS[2],"dataDate":DAYS[1]})
        _,trades,result=replay(prices,signals,DAYS[0],DAYS[-1],0,0)
        self.assertTrue(result["latch_triggered"]);self.assertEqual(DAYS[1],result["latch_date"])
        self.assertEqual("LATCH",trades[1]["reason"]);self.assertEqual(80,trades[1]["price"])
        self.assertEqual(1,result["buy_count"])

    def test_configured_holding_session_limit(self):
        prices,signals=fixture({**SETTINGS,"maxHoldingTradingDays":2})
        _,trades,_=replay(prices,signals,DAYS[0],DAYS[-1],0,0)
        self.assertEqual(DAYS[3],trades[1]["date"]);self.assertEqual("MAX_HOLD",trades[1]["reason"])

    def test_missing_prices_and_future_signal_are_rejected(self):
        prices,signals=fixture();del prices[DAYS[2],"A"]
        with self.assertRaises(ValueError):replay(prices,signals,DAYS[0],DAYS[-1])
        prices,signals=fixture();signals["signals"][0]["dataDate"]=DAYS[0]
        with self.assertRaises(ValueError):replay(prices,signals,DAYS[0],DAYS[-1])

    def test_future_price_change_cannot_change_prior_fill(self):
        prices,signals=fixture();_,trades,_=replay(prices,signals,DAYS[0],DAYS[-1])
        prices[DAYS[-1],"A"]={"open":200,"high":220,"low":190,"close":210}
        _,changed,_=replay(prices,signals,DAYS[0],DAYS[-1])
        self.assertEqual(trades[0],changed[0])

if __name__=="__main__":unittest.main()
