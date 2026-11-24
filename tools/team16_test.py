import unittest
from datetime import date,timedelta
from team16_research import simulate


def bars(tail, warmup=200):
    prices=[90.0]*warmup+tail
    return [{'date':(date(2020,1,1)+timedelta(days=i)).isoformat(),'open':p,'close':p,'dividend':0}
            for i,p in enumerate(prices)]


def run(rows,candidate='staged_trend',start=200):
    return simulate(rows,rows[start]['date'],rows[-1]['date'],candidate)


class TrendStudyTests(unittest.TestCase):
    def test_completed_signal_executes_next_open_and_adds_after_two_closes(self):
        rows=bars([100]*12)
        result=run(rows)
        self.assertEqual([t['reason'] for t in result['trades']],['ENTRY','ADD'])
        self.assertEqual(result['trades'][0]['signal_date'],rows[200]['date'])
        self.assertEqual(result['trades'][0]['date'],rows[201]['date'])
        self.assertEqual(result['trades'][1]['signal_date'],rows[202]['date'])
        self.assertEqual(result['trades'][1]['date'],rows[203]['date'])

    def test_five_day_exit_clock_not_reset_by_add(self):
        rows=bars([100]*10)
        result=run(rows,'staged_five')
        sell=next(t for t in result['trades'] if t['side']=='SELL')
        self.assertEqual(sell['signal_date'],rows[205]['date'])
        self.assertEqual(sell['date'],rows[206]['date'])
        self.assertFalse(any(t['side']=='BUY' and t['date']==sell['date'] for t in result['trades']))
        self.assertEqual(result['trades'][3]['date'],rows[207]['date'])

    def test_less_than_200_completed_closes_produces_no_signal(self):
        rows=bars([100]*30,warmup=0)
        result=run(rows,start=0)
        self.assertEqual(result['trades'],[])

    def test_gap_hard_cancels_scheduled_add_and_never_resets(self):
        rows=bars([100,100,100,60,200,200,200])
        result=run(rows)
        self.assertEqual([t['reason'] for t in result['trades']],['ENTRY','HARD'])
        self.assertGreater(result['mdd_pct'],10)
        self.assertTrue(result['history'][-1]['hard'])
        self.assertEqual(result['history'][-1]['quantity'],0)

    def test_trend_failure_precedes_add(self):
        rows=bars([100,100,89,89,89])
        result=run(rows)
        self.assertEqual([t['reason'] for t in result['trades']],['ENTRY','TREND_EXIT'])

    def test_later_prices_do_not_change_earlier_trades(self):
        a=run(bars([100]*30),'staged_five')
        b=run(bars([100]*15+[500]*15),'staged_five')
        self.assertEqual(a['history'][:15],b['history'][:15])

    def test_dividend_entitlement_survives_sale_without_becoming_cash(self):
        rows=bars([100]*10)
        rows[206]['dividend']=2
        result=run(rows,'full_five')
        shares=result['history'][5]['quantity']
        self.assertEqual(result['history'][6]['dividend_receivable'],shares*2)
        self.assertEqual(result['history'][6]['quantity'],0)
        self.assertAlmostEqual(result['history'][6]['equity'],result['history'][6]['cash']+shares*2)

    def test_costs_cash_integer_and_target_budget(self):
        result=run(bars([700]*12))
        for t in result['trades']:
            self.assertIsInstance(t['qty'],int)
            if t['side']=='BUY':
                self.assertLessEqual(t['qty']*t['price']+t['fee'],t['cash_before']*.99+1e-6)
                self.assertGreaterEqual(t['cash_after'],0)

    def test_full_entry_never_adds(self):
        result=run(bars([100]*20),'full_trend')
        self.assertEqual(len(result['trades']),1)
        self.assertEqual(result['add_count'],0)

    def test_last_close_hard_remains_pending_without_fictional_fill(self):
        rows=bars([100,100,100])
        rows[-1]['close']=50
        result=run(rows,'full_trend')
        self.assertTrue(result['pending_liquidation'])
        self.assertEqual(len(result['trades']),1)


if __name__=='__main__':
    unittest.main()
