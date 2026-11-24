import unittest
from datetime import date,timedelta
from strategy_v3 import Account,Parameters,SYMBOLS,cppi_weight,rebalance,select_f,simulate

SMALL=Parameters(sma_sessions=3,momentum_sessions=3)

def fixture():
    prices={};day=date(2007,12,1);index=0
    while day<=date(2008,3,5):
        if day.weekday()<5:
            close=100+index*.1
            for symbol in (*SYMBOLS,"^SP500TR"):
                prices[day.isoformat(),symbol]=dict(open=close,high=close+1,low=close-1,close=close)
            index+=1
        day+=timedelta(days=1)
    return prices


def flat_fixture():
    prices=fixture()
    for key in prices:prices[key]=dict(open=100.,high=101.,low=99.,close=100.)
    return prices


def mark(prices,day,symbol,opening,closing):
    prices[day,symbol]=dict(open=opening,high=max(opening,closing)+1,low=min(opening,closing)-1,close=closing)

class StrategyV3Tests(unittest.TestCase):
    def test_cppi_initial_drawdown_and_floor_budgets(self):
        self.assertAlmostEqual(.8,cppi_weight(10000,10000))
        self.assertAlmostEqual(8*400/9400,cppi_weight(9400,10000))
        self.assertEqual(0,cppi_weight(9000,10000))
        self.assertEqual(0,cppi_weight(8900,10000))
        with self.assertRaises(ValueError):cppi_weight(0,10000)

    def test_accounting_sells_then_buys_alphabetically_and_each_buy_cash_99(self):
        account=Account(1000);account.quantities["SPY"]=5;account.quantities["QQQ"]=5
        target={"GLD":.495,"IEF":.495,"QQQ":0.,"SPY":0.}
        rebalance(account,target,{s:100. for s in SYMBOLS},.0005,.002,"2008-01-03","2008-01-02","CPPI_REBALANCE")
        self.assertEqual([("SELL","QQQ"),("SELL","SPY"),("BUY","GLD"),("BUY","IEF")],[(t["side"],t["symbol"]) for t in account.ledger])
        cash=1000.
        for trade in account.ledger:
            amount=trade["quantity"]*trade["price"]
            if trade["side"]=="BUY":
                self.assertLessEqual(amount+trade["fee"],cash*.99+1e-8);cash-=amount+trade["fee"]
            else:cash+=amount-trade["fee"]
        self.assertAlmostEqual(cash,account.cash);self.assertGreaterEqual(cash,0)

    def test_candidate_d_first_close_next_open_and_daily_rebalance(self):
        rows,trades,_=simulate(flat_fixture(),"D","2008-01-02","2008-01-08",0,0,parameters=SMALL)
        self.assertEqual(10000,rows[0]["equity_usd"])
        self.assertEqual("2008-01-03",trades[0]["date"]);self.assertEqual("2008-01-02",trades[0]["signal_date"])
        self.assertAlmostEqual(80,trades[0]["quantity"])
        self.assertTrue(all(t["symbol"]=="SPY" for t in trades))

    def test_e_daily_trend_exit_and_reentry_do_not_wait_for_month_end(self):
        prices=fixture();mark(prices,"2008-01-04","QQQ",102.4,101.8)
        mark(prices,"2008-01-07","QQQ",101.8,104)
        _,trades,_=simulate(prices,"E","2008-01-02","2008-01-09",0,0,parameters=SMALL)
        self.assertTrue(any(t["date"]=="2008-01-07" and t["side"]=="SELL" for t in trades))
        self.assertTrue(any(t["date"]=="2008-01-08" and t["side"]=="BUY" for t in trades))

    def test_f_ties_alphabetical_and_single_eligible_gets_one_slot(self):
        history={s:[100,101,102,103] for s in SYMBOLS}
        self.assertEqual(["GLD","IEF"],select_f(history,SMALL))
        for symbol in ("IEF","QQQ","SPY"):history[symbol]=[103,102,101,100]
        self.assertEqual(["GLD"],select_f(history,SMALL))

    def test_f_first_month_end_and_dropped_slot_never_redistributes_or_reenters(self):
        prices=fixture();mark(prices,"2008-02-04","GLD",104.5,103.5)
        mark(prices,"2008-02-05","GLD",103.5,110)
        rows,trades,_=simulate(prices,"F","2008-01-28","2008-02-08",0,0,parameters=SMALL)
        self.assertEqual("2008-02-01",trades[0]["date"])
        by={r["date"]:r for r in rows}
        dropped=by["2008-02-04"]
        self.assertEqual(2,dropped["slot_count"]);self.assertEqual("IEF",dropped["selected_symbols"])
        self.assertEqual(0,dropped["decision_target_gld"])
        self.assertAlmostEqual(dropped["cppi_budget_weight"]/2,dropped["decision_target_ief"])
        self.assertEqual("IEF",by["2008-02-05"]["selected_symbols"])
        self.assertFalse(any(t["symbol"]=="GLD" and t["side"]=="BUY" and t["date"]>"2008-02-04" for t in trades))

    def test_f_one_eligible_uses_entire_budget(self):
        prices=fixture()
        for day,symbol in list(prices):
            if symbol in ("IEF","QQQ","SPY"):prices[day,symbol]=dict(open=100,high=101,low=99,close=100)
        rows,_,_=simulate(prices,"F","2008-01-28","2008-02-04",0,0,parameters=SMALL)
        month_end=next(r for r in rows if r["date"]=="2008-01-31")
        self.assertEqual(1,month_end["slot_count"])
        self.assertAlmostEqual(month_end["cppi_budget_weight"],month_end["decision_target_gld"])

    def test_open_cushion_can_only_shrink_previous_target(self):
        prices=flat_fixture();mark(prices,"2008-01-04","SPY",95,95)
        rows,_,_=simulate(prices,"D","2008-01-02","2008-01-07",0,0,parameters=SMALL)
        row=next(r for r in rows if r["date"]=="2008-01-04")
        self.assertEqual("CPPI_OPEN_CAP",row["executed_reason"])
        self.assertAlmostEqual(8*(9600-9000)/9600,row["executed_target_spy"])
        self.assertLess(row["executed_target_spy"],.8)
        prices=flat_fixture();mark(prices,"2008-01-04","SPY",120,100)
        rows,_,_=simulate(prices,"D","2008-01-02","2008-01-07",0,0,parameters=SMALL)
        row=next(r for r in rows if r["date"]=="2008-01-04")
        self.assertAlmostEqual(.8,row["executed_target_spy"])
        # Selling the excess at the elevated opening price realizes some gain;
        # that closing equity may raise peak, but the 11600 opening mark may not.
        self.assertAlmostEqual(row["equity_usd"],row["peak_equity_usd"])
        self.assertLess(row["peak_equity_usd"],11600)

    def test_hard_open_gap_cancels_buy_and_never_resets(self):
        prices=flat_fixture();mark(prices,"2008-01-04","SPY",85,100)
        rows,trades,report=simulate(prices,"D","2008-01-02","2008-03-05",0,0,parameters=SMALL)
        self.assertEqual("OPEN_GAP",report["latch_source"]);self.assertEqual("2008-01-04",report["latch_date"])
        closing=next(t for t in trades if t["reason"]=="HARD_OPEN_GAP")
        self.assertEqual(closing["date"],closing["signal_date"]);self.assertEqual(85,closing["price"])
        self.assertFalse(any(t["side"]=="BUY" and t["date"]>="2008-01-04" for t in trades))
        self.assertTrue(all(r["hard_latched"] for r in rows if r["date"]>="2008-01-04"))

    def test_hard_close_liquidates_next_open_even_with_price_recovery(self):
        prices=flat_fixture();mark(prices,"2008-01-03","SPY",100,85)
        mark(prices,"2008-01-04","SPY",95,110)
        _,trades,report=simulate(prices,"D","2008-01-02","2008-01-08",0,0,parameters=SMALL)
        self.assertEqual("CLOSE",report["latch_source"])
        self.assertEqual("HARD_LATCH",trades[1]["reason"]);self.assertEqual(95,trades[1]["price"])
        self.assertEqual(1,report["buy_count"])

    def test_missing_data_warmup_and_future_price_mutation(self):
        prices=fixture()
        with self.assertRaises(ValueError):simulate(prices,"F","2008-01-02","2008-01-08")
        bad=dict(prices);del bad["2008-01-03","^SP500TR"]
        with self.assertRaises(ValueError):simulate(bad,"D","2008-01-02","2008-01-08",parameters=SMALL)
        _,trades,_=simulate(prices,"D","2008-01-02","2008-01-08",parameters=SMALL)
        mark(prices,"2008-01-08","SPY",200,220)
        _,changed,_=simulate(prices,"D","2008-01-02","2008-01-08",parameters=SMALL)
        self.assertEqual(trades[0],changed[0])

if __name__=="__main__":unittest.main()
