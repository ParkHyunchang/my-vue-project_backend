"""Synthetic-only tests for TEAM23: no broker, database, or market downloads."""
import copy
import math
import statistics
import unittest
from team21_test import fixture
from team22_research import simulate as previous
from team23_research import (SYMBOLS, SECTORS, CANDIDATES, RISK_POLICIES,
    indicators, risk_target, select_basket, simulate)


def run(data, candidate='spy_trend', policy='recovery_vol', cost=10):
    return simulate(data, data['SPY'][200]['date'], data['SPY'][-1]['date'],
                    candidate, cost, risk_policy=policy)


def set_price(row, price):
    for key in ('open', 'close', 'adj_close', 'trade_open', 'trade_close'):
        row[key] = price


class StrategyTests(unittest.TestCase):
    def test_spy_cushion_control_matches_previous(self):
        data = fixture()
        for cost in (10, 25):
            before = previous(data, data['SPY'][200]['date'], data['SPY'][-1]['date'],
                              'spy_trend', cost, risk_policy='cushion80')
            after = run(data, 'spy_trend', 'cushion80', cost)
            self.assertEqual(before['trades'], after['trades'])
            for a, b in zip(before['history'], after['history']):
                self.assertEqual(a, {k: v for k, v in b.items() if k not in ('holding_age','signal_snapshot','market_vol20')})
            for key in ('return_pct','mdd_pct','hard_latch','mean_exposure','fees'):
                self.assertEqual(before[key], after[key])

    def test_indicator_windows_and_flat_rsi(self):
        data = fixture()
        signal = indicators(data)['SPY'][200]
        close = [r['adj_close'] for r in data['SPY']]
        self.assertEqual(signal['prior20_high'], max(close[180:200]))
        self.assertEqual(signal['prior10_low'], min(close[190:200]))
        expected = statistics.stdev([close[j]/close[j-1]-1 for j in range(181,201)]) * math.sqrt(252)
        self.assertAlmostEqual(signal['vol20'], expected)
        self.assertEqual(signal['rsi2'], 100)
        for row in data['SPY']:
            row['adj_close'] = 100
        self.assertEqual(indicators(data)['SPY'][200]['rsi2'],50)
        self.assertEqual(indicators(data)['SPY'][200]['vol20'],0)

    def test_recovery_floor_caps_and_volatility_recovery(self):
        for equity, expected in ((100,.8),(95,.4),(92,.2),(90.001,.2)):
            self.assertEqual(risk_target(equity,100,False,'recovery_vol',.10),expected)
        self.assertAlmostEqual(risk_target(90.001,100,False,'cushion80',.10),8*.001/90.001)
        self.assertEqual(risk_target(97,100,False,'recovery_vol',.5),.2)
        self.assertEqual(risk_target(97,100,False,'recovery_vol',.1),.8)
        for policy in RISK_POLICIES:
            self.assertEqual(risk_target(90,100,True,policy,.1),0)
        with self.assertRaises(ValueError):
            risk_target(100,100,False,'recovery_vol',float('nan'))

    def test_breakout_ages_and_no_same_close_reentry(self):
        data=fixture()
        result=run(data,'sector_breakout')
        first=result['history'][0]
        self.assertEqual(first['next_target']['basket'],['XLB','XLE','XLI'])
        self.assertTrue(all(q==0 for q in first['qty'].values()))
        self.assertEqual(result['history'][1]['holding_age']['XLB'],1)
        tenth=result['history'][10]
        self.assertEqual(tenth['holding_age']['XLB'],10)
        self.assertNotIn('XLB',tenth['next_target']['basket'])
        self.assertEqual(result['history'][11]['holding_age']['XLB'],0)
        self.assertTrue(all(t['signal_date']<t['date'] for t in result['trades']))

    def test_shortterm_signal_exits_and_partial_holding_age(self):
        data=fixture()
        snapshot={s: indicators(data)[s][200] for s in SYMBOLS}
        qty={s:0 for s in SYMBOLS}; qty['XLB']=3
        ages={s:0 for s in SYMBOLS}; ages['XLB']=4
        closes={s:data[s][200]['adj_close'] for s in SYMBOLS}
        closes['XLB']=snapshot['XLB']['prior10_low']-1
        basket=select_basket('sector_breakout',['XLB'],qty,ages,snapshot,closes,False)
        self.assertNotIn('XLB',basket)
        self.assertEqual(len(basket),3)
        closes['XLB']=snapshot['XLB']['prior20_high']+1
        basket=select_basket('sector_breakout',['XLB'],qty,ages,snapshot,closes,False)
        self.assertEqual(basket[0],'XLB')

    def test_pullback_five_actual_holding_closes(self):
        data=fixture()
        for i in range(200,len(data['SPY'])):
            set_price(data['SPY'][i],119.5-.1*(i-200) if i>201 else 119.5-.5*(i-200))
        # Make post-entry closes descend without crossing the long trend.
        for i in range(202,len(data['SPY'])):
            set_price(data['SPY'][i],118.9-.1*(i-202))
        result=run(data,'spy_pullback')
        firstbuy=next(t for t in result['trades'] if t['side']=='BUY')
        self.assertEqual(firstbuy['date'],data['SPY'][202]['date'])
        h=next(h for h in result['history'] if h['date']==data['SPY'][206]['date'])
        self.assertEqual(h['holding_age']['SPY'],5)
        self.assertEqual(h['next_target']['basket'],[])
        sold=next(t for t in result['trades'] if t['date']==data['SPY'][207]['date'] and t['side']=='SELL')
        self.assertGreater(sold['qty'],0)

    def test_pullback_profit_and_trend_exit(self):
        data=fixture(); signals=indicators(data)
        snapshot={s:signals[s][200] for s in SYMBOLS}
        qty={s:0 for s in SYMBOLS}; qty['SPY']=1
        ages={s:0 for s in SYMBOLS}; ages['SPY']=1
        closes={s:data[s][200]['adj_close'] for s in SYMBOLS}
        self.assertEqual(select_basket('spy_pullback',['SPY'],qty,ages,snapshot,closes,False),[])
        closes['SPY']=snapshot['SPY']['sma200']-1
        self.assertEqual(select_basket('spy_pullback',['SPY'],qty,ages,snapshot,closes,False),[])

    def test_unfilled_signal_has_no_age_or_survivor(self):
        data=fixture(); snapshot={s:indicators(data)[s][200] for s in SYMBOLS}
        qty={s:0 for s in SYMBOLS}; ages=dict(qty)
        closes={s:90 for s in SYMBOLS}
        self.assertEqual(select_basket('sector_breakout',['XLB'],qty,ages,snapshot,closes,False),[])

    def test_future_changes_cannot_change_past_decisions(self):
        for strategy in CANDIDATES:
            a=fixture(); b=copy.deepcopy(a)
            cutoff=b['SPY'][230]['date']
            for rows in b.values():
                for row in rows[230:]:
                    set_price(row,row['adj_close']*7)
            ar,br=run(a,strategy),run(b,strategy)
            self.assertEqual([h for h in ar['history'] if h['date']<cutoff],
                             [h for h in br['history'] if h['date']<cutoff])

    def test_cash_integer_and_equity_invariants(self):
        for policy in RISK_POLICIES:
            for candidate in CANDIDATES:
                result=run(fixture(),candidate,policy,25)
                for h in result['history']:
                    self.assertGreaterEqual(h['cash'],0)
                    self.assertTrue(all(isinstance(q,int) and q>=0 for q in h['qty'].values()))
                    buys=[t for t in result['trades'] if t['date']==h['date'] and t['side']=='BUY']
                    self.assertLessEqual(sum(t['net'] for t in buys),.99*(h['cash_before_settlement']+h['settled_today'])+1e-7)
                    self.assertAlmostEqual(h['equity'],h['cash']+h['unsettled']+h['dividend_receivable']+sum(h['qty'][s]*h['close'][s] for s in SYMBOLS))

    def test_gap_stop_is_permanent_despite_recovery_floor(self):
        data=fixture()
        for row in data['SPY'][210:]:
            row['trade_open']=row['trade_close']=1
        result=run(data)
        self.assertGreater(result['mdd_pct'],10)
        self.assertEqual(result['hard_latch'],{'date':data['SPY'][210]['date'],'phase':'open'})
        after=[h for h in result['history'] if h['date']>=data['SPY'][210]['date']]
        self.assertTrue(all(h['hard'] and h['next_target']['gross']==0 for h in after))
        self.assertTrue(all(not any(h['qty'].values()) for h in after))
        self.assertEqual(after[1]['settled_today'],after[0]['unsettled'])

    def test_close_stop_liquidates_next_open(self):
        data=fixture(); data['SPY'][210]['trade_close']=1
        result=run(data)
        self.assertEqual(result['hard_latch']['phase'],'close')
        sell=next(t for t in result['trades'] if t['reason']=='HARD')
        self.assertEqual(sell['date'],data['SPY'][211]['date'])


if __name__=='__main__':
    unittest.main()
