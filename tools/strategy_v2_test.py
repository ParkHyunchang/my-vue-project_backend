import math
import statistics
import unittest
from datetime import date,timedelta
from strategy_v2 import Parameters,signal_target,simulate


def fixture(long_history=False):
    days=[];day=date(2017,1,2) if long_history else date(2018,1,2)
    while day<=date(2018,3,5):
        if day.weekday()<5:days.append(day.isoformat())
        day+=timedelta(days=1)
    prices={}
    for index,day in enumerate(days):
        close=(100 if day>="2018-01-25" else 40) if long_history else 100+index*.2
        for symbol in ("SPY","QQQ"):
            prices[day,symbol]={"open":close,"high":close*1.01,"low":close*.99,"close":close}
    return prices


def bar(prices,day,opening,close):
    prices[day,"SPY"]={"open":opening,"high":max(opening,close)*1.01,"low":min(opening,close)*.99,"close":close}

SMALL=Parameters(sma_sessions=3,momentum_sessions=3,volatility_sessions=3)

class StrategyV2Tests(unittest.TestCase):
    def test_first_entry_only_after_month_end_and_cash_fee_ceiling(self):
        prices=fixture();rows,trades,report=simulate(prices,"A","2018-01-30","2018-02-08",parameters=SMALL)
        self.assertEqual("2018-02-01",trades[0]["date"])
        self.assertEqual("2018-01-31",trades[0]["signal_date"])
        self.assertEqual("REBALANCE",trades[0]["reason"])
        self.assertLessEqual(trades[0]["quantity"]*trades[0]["price"]+trades[0]["fee"],9900+1e-7)
        self.assertTrue(all(row["cash_usd"]>=0 for row in rows))
        self.assertEqual(10000,rows[0]["equity_usd"])

    def test_momentum_tie_chooses_spy_and_absolute_trend_requires_positive(self):
        history={"SPY":[100,101,102,103],"QQQ":[100,101,102,103]}
        self.assertEqual({"SPY":.99,"QQQ":0},signal_target("B",history,SMALL))
        history={"SPY":[104,103,102,103],"QQQ":[104,103,102,103]}
        self.assertEqual({"SPY":0,"QQQ":0},signal_target("B",history,SMALL))

    def test_volatility_uses_twenty_style_sample_log_returns(self):
        p=Parameters(sma_sessions=2,momentum_sessions=3,volatility_sessions=3)
        history={"SPY":[100,103,102,110],"QQQ":[100,100,100,101]}
        returns=[math.log(history["SPY"][i]/history["SPY"][i-1]) for i in range(1,4)]
        expected=min(.99,.12/(statistics.stdev(returns)*math.sqrt(252)))
        self.assertAlmostEqual(expected,signal_target("C",history,p)["SPY"])

    def test_sma_off_reduces_daily_but_cannot_reenter_midmonth(self):
        prices=fixture();bar(prices,"2018-02-01",104.4,103.5)
        _,trades,_=simulate(prices,"A","2018-01-30","2018-02-08",0,0,parameters=SMALL)
        self.assertEqual(2,len(trades));self.assertEqual("SMA_OFF",trades[1]["reason"])
        self.assertEqual("2018-02-02",trades[1]["date"])
        self.assertEqual("SELL",trades[1]["side"])

    def test_soft_shrink_once_and_price_drift_does_not_rebalance_daily(self):
        prices=fixture(True);bar(prices,"2018-02-01",100,93);bar(prices,"2018-02-02",93,93)
        rows,trades,_=simulate(prices,"A","2018-01-30","2018-02-08",0,0)
        self.assertEqual(2,len(trades));self.assertEqual("SOFT_REDUCE",trades[1]["reason"])
        self.assertEqual("2018-02-02",trades[1]["date"])
        self.assertTrue(rows[2]["soft_active"])
        self.assertTrue(any((r["equity_usd"]-r["cash_usd"])/r["equity_usd"]>.25 for r in rows[4:]))

    def test_soft_recovery_does_not_buy_until_month_end(self):
        prices=fixture(True);bar(prices,"2018-02-01",100,93);bar(prices,"2018-02-02",93,93)
        for day,_ in list(prices):
            if day>="2018-02-05":bar(prices,day,110,110)
        _,trades,report=simulate(prices,"A","2018-01-30","2018-02-08",0,0)
        self.assertTrue(any(t["type"]=="SOFT_OFF" for t in report["risk_transitions"]))
        self.assertEqual(1,sum(t["side"]=="BUY" for t in trades))

    def test_soft_emergency_cannot_buy_after_gap_lowers_weight_below_cap(self):
        prices=fixture(True);bar(prices,"2018-02-01",100,80);bar(prices,"2018-02-02",76,76)
        p=Parameters(max_weight=.30)
        rows,trades,_=simulate(prices,"A","2018-01-30","2018-02-02",0,0,parameters=p)
        self.assertTrue(rows[-2]["soft_active"])
        self.assertEqual(1,sum(t["side"]=="BUY" for t in trades))
        self.assertFalse(any(t["date"]=="2018-02-02" and t["side"]=="BUY" for t in trades))

    def test_close_hard_latch_liquidates_next_open_and_never_restarts(self):
        prices=fixture(True);bar(prices,"2018-02-01",100,89);bar(prices,"2018-02-02",80,85)
        _,trades,report=simulate(prices,"A","2018-01-30","2018-03-05",0,0)
        self.assertEqual("CLOSE",report["latch_source"])
        self.assertEqual("HARD_LATCH",trades[1]["reason"]);self.assertEqual(80,trades[1]["price"])
        self.assertEqual(1,report["buy_count"])

    def test_open_gap_precedes_any_buy_and_open_high_never_raises_peak(self):
        prices=fixture(True);bar(prices,"2018-02-02",150,100)
        rows,_,report=simulate(prices,"A","2018-01-30","2018-02-08",0,0)
        self.assertFalse(report["latch_triggered"])
        self.assertTrue(all(abs(row["peak_equity_usd"]-10000)<1e-8 for row in rows))
        bar(prices,"2018-02-02",85,110)
        _,trades,report=simulate(prices,"A","2018-01-30","2018-03-05",0,0)
        self.assertEqual("OPEN_GAP",report["latch_source"])
        self.assertEqual("HARD_OPEN_GAP",trades[1]["reason"])
        self.assertEqual(trades[1]["date"],trades[1]["signal_date"])
        self.assertEqual(85,trades[1]["price"])

    def test_future_prices_do_not_change_prior_orders(self):
        prices=fixture();_,trades,_=simulate(prices,"B","2018-01-30","2018-02-08",parameters=SMALL)
        bar(prices,"2018-02-08",200,250)
        _,modified,_=simulate(prices,"B","2018-01-30","2018-02-08",parameters=SMALL)
        self.assertEqual(trades[0],modified[0])

    def test_missing_warmup_and_partial_real_benchmark_are_rejected(self):
        prices=fixture()
        with self.assertRaises(ValueError):simulate(prices,"B","2018-01-30","2018-02-08")
        prices["2018-01-30","^SP500TR"]={"open":100,"high":100,"low":100,"close":100}
        with self.assertRaises(ValueError):simulate(prices,"A","2018-01-30","2018-02-08",parameters=SMALL)

if __name__=="__main__":unittest.main()
