import unittest
from datetime import date,timedelta
from dataclasses import replace
from unittest.mock import patch
from strategy_v4 import Parameters,recovery_gate,simulate
from strategy_v3 import SYMBOLS

P=Parameters(sma_sessions=20,momentum_sessions=3,breakout_sessions=2,wait_sessions=2,stage_sessions=2)

def fixture():
    prices={};day=date(2017,10,2)
    while day<=date(2018,4,15):
        if day.weekday()<5:
            value=100. if day>=date(2018,1,25) else 60.
            for symbol in (*SYMBOLS,"^SP500TR"):
                prices[day.isoformat(),symbol]=dict(open=value,high=value+1,low=value-1,close=value)
        day+=timedelta(days=1)
    return prices


def mark(prices,day,opening,closing,symbol="SPY"):
    prices[day,symbol]=dict(open=opening,high=max(opening,closing)+1,low=min(opening,closing)-1,close=closing)


def recovery_prices():
    prices=fixture()
    values={"2018-02-02":(100,91),"2018-02-05":(91,91),"2018-02-06":(91,102),
        "2018-02-07":(102,103),"2018-02-08":(103,104),"2018-02-09":(104,105),
        "2018-02-12":(105,106),"2018-02-13":(106,107),"2018-02-14":(107,110),"2018-02-15":(110,115)}
    for day,(opening,closing) in values.items():mark(prices,day,opening,closing)
    return prices

class StrategyV4Tests(unittest.TestCase):
    def test_actual_twenty_session_wait_and_twenty_exposed_close_stage_boundary(self):
        prices=fixture();mark(prices,"2018-02-02",100,91);mark(prices,"2018-02-05",91,91)
        future=sorted(d for d,s in prices if s=="SPY" and d>="2018-02-06")
        previous=91
        for i,day in enumerate(future):
            close=102+i*.2;mark(prices,day,previous,close);previous=close
        params=replace(P,wait_sessions=20,stage_sessions=20)
        rows,_,report=self.run_g(prices,"2018-04-12",params)
        days=[r["date"] for r in rows]
        entry=report["recovery_entries"][0]["date"]
        day0=days.index("2018-02-05");entry_index=days.index(entry)
        self.assertEqual(21,entry_index-day0)
        self.assertEqual("PROBE25",rows[entry_index-1]["state_close"])
        self.assertEqual(20,rows[entry_index-1]["wait_elapsed"])
        promotion=next(i for i,r in enumerate(rows) if r["state_close"]=="PROBE50")
        self.assertEqual(19,promotion-entry_index)
        self.assertEqual(0,rows[promotion]["stage_exposed_sessions"])
        self.assertEqual(1,rows[promotion+1]["stage_exposed_sessions"])

    def test_h_empty_basket_preserves_anchor_and_pauses_exposed_session_counter(self):
        prices=fixture();mark(prices,"2018-02-02",100,91,"GLD")
        future=sorted(d for d,s in prices if s=="SPY" and d>="2018-02-05")
        previous=91
        for i,day in enumerate(future):
            close=91.5+i*.6;mark(prices,day,previous,close,"GLD");previous=close
            spy=101+i*.3;mark(prices,day,spy,spy)
        params=replace(P,stage_sessions=100)
        with patch("strategy_v4.select_f",side_effect=[["GLD"],[]]):
            rows,_,report=simulate(prices,"H","2018-01-30","2018-03-05",0,0,parameters=params)
        by={r["date"]:r for r in rows}
        self.assertEqual(1,report["wait_reentry_count"])
        prior=by["2018-02-28"]["stage_exposed_sessions"]
        anchor=by["2018-02-28"]["trial_anchor"]
        self.assertGreater(prior,0)
        for day in ("2018-03-01","2018-03-02","2018-03-05"):
            self.assertEqual(0,by[day]["slot_count"])
            self.assertEqual(prior,by[day]["stage_exposed_sessions"])
            self.assertEqual(anchor,by[day]["trial_anchor"])
            self.assertAlmostEqual(by[day]["cash_usd"],by[day]["equity_usd"])

    def run_g(self,prices,end="2018-02-15",p=P,fee=0,slip=0):
        return simulate(prices,"G","2018-02-01",end,fee,slip,parameters=p)

    def test_gate_uses_prior_high_excludes_current_and_requires_strict_breakout(self):
        p=replace(P,sma_sessions=3,breakout_sessions=2)
        self.assertFalse(recovery_gate([98,99,100,100],p))
        self.assertTrue(recovery_gate([98,99,100,101],p))

    def test_wait_day_zero_executes_once_and_excludes_liquidation_day(self):
        rows,trades,report=self.run_g(recovery_prices())
        by={r["date"]:r for r in rows}
        self.assertEqual("WAIT",by["2018-02-02"]["state_close"])
        self.assertEqual("",by["2018-02-02"]["wait_day0"])
        self.assertEqual("2018-02-05",by["2018-02-05"]["wait_day0"])
        self.assertEqual(0,by["2018-02-05"]["wait_elapsed"])
        self.assertEqual(1,by["2018-02-06"]["wait_elapsed"])
        self.assertEqual(2,by["2018-02-07"]["wait_elapsed"])
        self.assertEqual("PROBE25",by["2018-02-07"]["state_close"])
        self.assertEqual("",by["2018-02-07"]["trial_anchor"])
        self.assertEqual("2018-02-08",report["recovery_entries"][0]["date"])
        self.assertFalse(any(t["side"]=="BUY" and "2018-02-05"<=t["date"]<"2018-02-08" for t in trades))

    def test_reentry_anchor_is_pretrade_equity_and_global_six_does_not_repause(self):
        rows,_,report=self.run_g(recovery_prices())
        by={r["date"]:r for r in rows};anchor=by["2018-02-08"]["trial_anchor_open"]
        self.assertAlmostEqual(9280,anchor)
        self.assertGreater(by["2018-02-08"]["drawdown_percent"],6)
        self.assertEqual("PROBE25",by["2018-02-08"]["state_close"])
        self.assertEqual(1,report["wait_reentry_count"])
        self.assertEqual(4,report["sessions_first_soft_to_reentry"])

    def test_stage_counts_execution_close_and_never_skips_stages(self):
        rows,_,_=self.run_g(recovery_prices());by={r["date"]:r for r in rows}
        self.assertEqual(1,by["2018-02-08"]["stage_exposed_sessions"])
        self.assertEqual("PROBE50",by["2018-02-09"]["state_close"])
        self.assertEqual(0,by["2018-02-09"]["stage_exposed_sessions"])
        self.assertEqual("PROBE50",by["2018-02-12"]["state_open"])
        self.assertEqual(1,by["2018-02-12"]["stage_exposed_sessions"])
        self.assertEqual("RECOVERY80",by["2018-02-13"]["state_close"])
        self.assertEqual("NORMAL",by["2018-02-15"]["state_close"])
        self.assertEqual("",by["2018-02-15"]["trial_anchor"])
        anchors=[r["trial_anchor_open"] for r in rows if r["trial_anchor_open"]!=""]
        self.assertTrue(all(a==anchors[0] for a in anchors))

    def test_trial_open_stop_precedes_stage_buy_and_starts_new_wait_day_zero(self):
        prices=recovery_prices();mark(prices,"2018-02-09",98,104)
        rows,trades,_=self.run_g(prices,"2018-02-09");last=rows[-1]
        self.assertEqual("WAIT",last["state_open"])
        self.assertEqual("TRIAL_OPEN",last["executed_reason"])
        self.assertEqual(0,last["wait_elapsed"]);self.assertEqual("",last["trial_anchor"])
        self.assertFalse(any(t["date"]=="2018-02-09" and t["side"]=="BUY" for t in trades))

    def test_trial_close_stop_liquidates_next_open_and_does_not_reset_peak(self):
        prices=recovery_prices();mark(prices,"2018-02-08",103,98)
        rows,trades,_=self.run_g(prices,"2018-02-09")
        self.assertEqual("WAIT",rows[-2]["state_close"])
        self.assertEqual("TRIAL_CLOSE",rows[-1]["executed_reason"])
        self.assertEqual(10000,rows[-1]["peak_equity_usd"])
        self.assertTrue(any(t["date"]=="2018-02-09" and t["side"]=="SELL" for t in trades))

    def test_recovery_trend_off_returns_wait_without_trial_loss(self):
        prices=recovery_prices();mark(prices,"2018-02-09",104,102)
        rows,_,_=self.run_g(prices,"2018-02-09",replace(P,sma_sessions=3))
        self.assertEqual("RECOVERY_TREND_OFF",rows[-1]["decision_reason"])
        self.assertEqual("WAIT",rows[-1]["state_close"])

    def test_hard_has_priority_over_soft_and_trial_and_is_permanent(self):
        prices=fixture();mark(prices,"2018-02-02",100,85)
        rows,trades,report=self.run_g(prices)
        self.assertEqual("HARD",rows[1]["state_close"])
        self.assertTrue(report["latch_triggered"])
        self.assertEqual(0,report["wait_reentry_count"])
        self.assertFalse(any(t["date"]>"2018-02-02" and t["side"]=="BUY" for t in trades))
        prices=recovery_prices();mark(prices,"2018-02-09",80,104)
        rows,_,report=self.run_g(prices,"2018-02-09")
        self.assertEqual("HARD_OPEN_GAP",rows[-1]["executed_reason"])
        self.assertEqual("OPEN_GAP",report["latch_source"])

    def test_normal_soft_open_cancels_buy_and_records_actual_liquidation_day(self):
        prices=fixture();mark(prices,"2018-02-05",91,100)
        rows,trades,_=self.run_g(prices,"2018-02-06")
        row=next(r for r in rows if r["date"]=="2018-02-05")
        self.assertEqual("SOFT_OPEN",row["executed_reason"])
        self.assertEqual("2018-02-05",row["wait_day0"])
        self.assertFalse(any(t["side"]=="BUY" and t["date"]=="2018-02-05" for t in trades))

    def test_f_basket_requires_month_end_and_actual_data_all_five_calendars(self):
        prices=fixture()
        rows,trades,_=simulate(prices,"H","2018-01-29","2018-02-01",0,0,parameters=P)
        self.assertFalse(trades) # flat prices fail positive momentum, so no basket
        self.assertEqual(0,rows[-1]["slot_count"])
        del prices["2018-02-01","^SP500TR"]
        with self.assertRaises(ValueError):self.run_g(prices)

    def test_cost_cash_limits_and_future_prices_cannot_change_prior_fill(self):
        prices=recovery_prices();rows,trades,_=self.run_g(prices,"2018-02-09",fee=5,slip=20)
        cash=10000.
        for t in trades:
            gross=t["quantity"]*t["price"]
            if t["side"]=="BUY":self.assertLessEqual(gross+t["fee"],cash*.99+1e-7);cash-=gross+t["fee"]
            else:cash+=gross-t["fee"]
        self.assertGreaterEqual(cash,0)
        mark(prices,"2018-02-09",200,220)
        _,changed,_=self.run_g(prices,"2018-02-09",fee=5,slip=20)
        self.assertEqual(trades[0],changed[0])

if __name__=="__main__":unittest.main()
