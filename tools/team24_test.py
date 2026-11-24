"""Synthetic low-turnover execution and accounting tests; no market-performance run."""
import copy
import math
import statistics
import unittest
from datetime import date, timedelta
from team24_research import SYMBOLS, indicators, simulate, select_basket, weights_for, order_mode, risk_target


def fixture(count=275):
    days=[]
    day=date(2020,1,1)
    while len(days)<count:
        if day.weekday()<5:
            days.append(day.isoformat())
        day+=timedelta(days=1)
    return {s:[dict(date=d,open=100+i/10,close=100+i/10,adj_close=100+i/10,
                      trade_open=100+i/10,trade_close=100+i/10,trade_dividend=0.,
                      split_numerator=1.,split_denominator=1.) for i,d in enumerate(days)] for s in SYMBOLS}


def run(data,candidate='rotation1',policy='recovery_vol',signals=None):
    return simulate(data,data['SPY'][200]['date'],data['SPY'][-1]['date'],candidate,
                    risk_policy=policy,signals=signals)


class LowTurnoverTests(unittest.TestCase):
    def test_completed_signal_and_no_daily_topups(self):
        result=run(fixture())
        self.assertEqual(result['history'][0]['trade_count'],0)
        self.assertTrue(result['trades'])
        for t in result['trades']:
            self.assertLess(t['signal_date'],t['date'])
            self.assertEqual(t['reason'],'FULL')
        holds=[h for h in result['history'] if h['execution_mode']=='HOLD']
        self.assertTrue(holds)
        self.assertTrue(all(h['trade_count']==0 for h in holds))
        self.assertLess(result['trade_count'],15)

    def test_selection_rank_ties_and_missing_slots(self):
        snap={s:dict(eligible=True,momentum=.1,riskrank=1.) for s in SYMBOLS}
        self.assertEqual(select_basket('rotation1',[],snap,True),['GLD'])
        snap['QQQ']['riskrank']=2
        self.assertEqual(select_basket('riskrank2',[],snap,True),['QQQ','GLD'])
        snap['GLD']['eligible']=False
        self.assertEqual(select_basket('riskrank2',['QQQ','GLD'],snap,False),['QQQ'])
        self.assertEqual(weights_for('riskrank2',['QQQ'],.8),{'QQQ':.4})
        weights=weights_for('balanced_trend',['SPY','IEF'],.8)
        self.assertAlmostEqual(weights['SPY'],.32)
        self.assertAlmostEqual(weights['IEF'],.16)

    def test_modes_anchor_and_five_point_boundary(self):
        self.assertEqual(order_mode(False,.751,.8,['SPY'],['SPY'],False),'HOLD')
        self.assertEqual(order_mode(False,.75,.8,['SPY'],['SPY'],False),'REDUCE')
        self.assertEqual(order_mode(False,.8,.8,['SPY','QQQ'],['SPY'],False),'EXIT')
        self.assertEqual(order_mode(True,.3,.8,['SPY'],['QQQ'],False),'FULL')
        self.assertEqual(order_mode(True,.3,.8,['SPY'],['QQQ'],True),'HOLD')
        result=run(fixture())
        self.assertIsNone(result['history'][0]['rebalance_anchor_gross'])
        for i,h in enumerate(result['history'][1:],1):
            previous=result['history'][i-1]
            expected=h['executed_target']['gross'] if h['execution_mode'] in ('FULL','REDUCE') else previous['rebalance_anchor_gross']
            self.assertEqual(h['rebalance_anchor_gross'],expected)

    def test_eligibility_exit_keeps_survivor_quantities(self):
        data=fixture(225)
        signals=indicators(data)
        for x in signals['GLD'][210:]:
            x['eligible']=False
        result=run(data,'balanced_trend',signals=signals)
        j=next(i for i,h in enumerate(result['history']) if h['execution_mode']=='EXIT')
        before,after=result['history'][j-1:j+1]
        self.assertGreater(before['qty']['GLD'],0)
        self.assertEqual(after['qty']['GLD'],0)
        for s in ('SPY','QQQ','IEF'):
            self.assertEqual(before['qty'][s],after['qty'][s])
        self.assertEqual(after['rebalance_anchor_gross'],before['rebalance_anchor_gross'])

    def test_reduce_never_adds_and_includes_exits(self):
        data=fixture(225)
        for s in SYMBOLS:
            for r in data[s][210:]:
                r['trade_open']*=.95
                r['trade_close']*=.95
        result=run(data,'balanced_trend',policy='cushion80')
        reductions={h['date'] for h in result['history'] if h['execution_mode']=='REDUCE'}
        self.assertTrue(reductions)
        self.assertTrue(all(t['side']=='SELL' for t in result['trades'] if t['date'] in reductions))

    def test_sale_receivable_funds_one_completion_not_daily_topups(self):
        data=fixture()
        signals=indicators(data)
        dates=[r['date'] for r in data['SPY']]
        switch=next(i for i in range(202,len(dates)-3) if dates[i][:7]!=dates[i-1][:7])
        for r in signals['QQQ'][switch:]:
            r['momentum']=1
        result=run(data,signals=signals)
        j=next(i for i,h in enumerate(result['history']) if h['date']==dates[switch+1])
        h=result['history'][j]
        self.assertGreater(h['unsettled'],0)
        self.assertGreater(h['qty']['QQQ'],0)
        self.assertLess(h['qty']['QQQ']*h['close']['QQQ'],h['equity']*.4)
        self.assertEqual(result['history'][j+1]['settled_today'],h['unsettled'])
        self.assertEqual(result['history'][j+1]['execution_mode'],'FUND')
        self.assertGreater(result['history'][j+1]['qty']['QQQ'],h['qty']['QQQ'])
        self.assertLessEqual(result['history'][j+1]['qty']['QQQ']-h['qty']['QQQ'],h['next_target']['fund_shortfall']['QQQ'])
        self.assertEqual(result['history'][j+2]['execution_mode'],'HOLD')
        self.assertEqual(result['history'][j+2]['qty']['QQQ'],result['history'][j+1]['qty']['QQQ'])
        self.assertTrue(all(t['side']=='BUY' for t in result['trades'] if t['date']==result['history'][j+1]['date']))

    def test_fund_shortfall_split_cap_and_no_retry(self):
        data=fixture()
        signals=indicators(data)
        dates=[r['date'] for r in data['SPY']]
        switch=next(i for i in range(202,len(dates)-3) if dates[i][:7]!=dates[i-1][:7])
        for r in signals['QQQ'][switch:]:
            r['momentum']=1
        data['QQQ'][switch+2]['split_numerator']=2
        for r in data['QQQ'][switch+2:]:
            r['trade_open']/=2
            r['trade_close']/=2
        result=run(data,signals=signals)
        j=next(i for i,h in enumerate(result['history']) if h['date']==dates[switch+1])
        full,fund,hold=result['history'][j:j+3]
        self.assertEqual(fund['execution_mode'],'FUND')
        added=fund['qty']['QQQ']-2*full['qty']['QQQ']
        self.assertGreater(added,0)
        self.assertLessEqual(added,2*full['next_target']['fund_shortfall']['QQQ'])
        self.assertEqual(fund['rebalance_anchor_gross'],full['rebalance_anchor_gross'])
        self.assertEqual(hold['execution_mode'],'HOLD')

    def test_exit_or_reduce_cancels_full_shortfall(self):
        for cancellation in ('EXIT','REDUCE'):
            data=fixture()
            signals=indicators(data)
            dates=[r['date'] for r in data['SPY']]
            switch=next(i for i in range(202,len(dates)-3) if dates[i][:7]!=dates[i-1][:7])
            for r in signals['QQQ'][switch:]:
                r['momentum']=1
            if cancellation=='EXIT':
                for r in signals['QQQ'][switch+1:]:
                    r['eligible']=False
            else:
                data['QQQ'][switch+1]['trade_close']*=.8
            result=run(data,policy='cushion80',signals=signals)
            h=next(h for h in result['history'] if h['date']==dates[switch+1])
            self.assertEqual(h['next_target']['mode'],cancellation)
            self.assertEqual(h['next_target']['fund_shortfall'],{})

    def test_cash_integer_and_full_accounting(self):
        result=run(fixture(),'balanced_trend')
        for h in result['history']:
            self.assertGreaterEqual(h['cash'],0)
            self.assertTrue(all(isinstance(q,int) and q>=0 for q in h['qty'].values()))
            expected=h['cash']+h['unsettled']+h['dividend_receivable']+sum(h['qty'][s]*h['close'][s] for s in SYMBOLS)
            self.assertAlmostEqual(h['equity'],expected)
            debit=sum(t['net'] for t in result['trades'] if t['date']==h['date'] and t['side']=='BUY')
            self.assertLessEqual(debit,.99*(h['cash_before_settlement']+h['settled_today'])+1e-7)

    def test_gap_hard_stop_overrides_hold_and_never_resumes(self):
        data=fixture()
        for s in SYMBOLS:
            for i,r in enumerate(data[s][210:],210):
                r['trade_open']=r['trade_close']=50 if i<220 else 500
        result=run(data)
        self.assertGreater(result['mdd_pct'],10)
        self.assertEqual(result['hard_latch']['phase'],'open')
        d=result['hard_latch']['date']
        self.assertTrue(all(t['side']=='SELL' for t in result['trades'] if t['date']>=d))
        self.assertTrue(all(h['hard'] and h['next_target']['gross']==0 for h in result['history'] if h['date']>=d))

    def test_close_hard_stop_next_open(self):
        data=fixture(225)
        for s in SYMBOLS:
            data[s][210]['trade_close']=50
        result=run(data)
        self.assertEqual(result['hard_latch']['phase'],'close')
        latch=result['hard_latch']['date']
        exits=[t for t in result['trades'] if t['reason']=='HARD']
        self.assertTrue(exits)
        self.assertTrue(all(t['date']>latch for t in exits))

    def test_volatility_and_risk_floor(self):
        data=fixture()
        sig=indicators(data)['SPY'][220]
        p=[r['adj_close'] for r in data['SPY']]
        vals=[p[j]/p[j-1]-1 for j in range(158,221)]
        self.assertAlmostEqual(sig['vol63'],statistics.stdev(vals)*math.sqrt(252))
        self.assertAlmostEqual(sig['riskrank'],sig['momentum']/max(.05,sig['vol63']))
        self.assertEqual(risk_target(9100,10000,False,'recovery_vol',.1),.2)
        self.assertEqual(risk_target(9100,10000,True,'recovery_vol',.1),0)

    def test_future_data_cannot_change_past_orders(self):
        a=fixture();b=copy.deepcopy(a)
        for s in SYMBOLS:
            for r in b[s][240:]:
                for k in ('open','close','adj_close','trade_open','trade_close'):
                    r[k]*=3
        cutoff=a['SPY'][240]['date']
        for candidate in ('rotation1','riskrank2','balanced_trend'):
            first,second=run(a,candidate),run(b,candidate)
            self.assertEqual([t for t in first['trades'] if t['date']<cutoff],[t for t in second['trades'] if t['date']<cutoff])

    def test_dividend_receivable_and_split_preserve_accounting(self):
        data=fixture(225)
        data['GLD'][210]['trade_dividend']=1
        data['GLD'][215]['split_numerator']=2
        for r in data['GLD'][215:]:
            r['trade_open']/=2
            r['trade_close']/=2
        result=run(data)
        h=result['history'][10]
        self.assertGreater(h['dividend_receivable'],0)
        self.assertEqual(h['dividend_receivable'],h['qty']['GLD'])
        self.assertEqual(result['history'][15]['qty']['GLD'],2*result['history'][14]['qty']['GLD'])


if __name__=='__main__':
    unittest.main()
