"""Synthetic tests only, no broker or network."""
import copy
import unittest

import team23_research as original
import team28_research as research
from team21_test import fixture


def expanded():
    base=fixture()
    return {s:copy.deepcopy(base.get(s,base['SPY'])) for s in research.SYMBOLS}


class ResearchTests(unittest.TestCase):
    def test_isolated_module_does_not_mutate_existing_ledger(self):
        old=(original.SYMBOLS,original.select_basket,original.SECTORS)
        a=research.engine_for('broad_pullback5'); b=research.engine_for('sector_pullback3')
        self.assertIsNot(a,b)
        self.assertEqual(old,(original.SYMBOLS,original.select_basket,original.SECTORS))

    def test_baseline_exact_same_ledger(self):
        data=expanded(); start=data['SPY'][200]['date']; end=data['SPY'][-1]['date']
        before=original.simulate(data,start,end,'sector_breakout',25,risk_policy='recovery_vol')
        after=research.simulate(data,start,end,research.BASELINE)
        for key in before:
            self.assertEqual(before[key],after[key],key)

    def test_rules_and_actual_holding_age_exit(self):
        data=expanded(); module=research.engine_for('broad_pullback5')
        sig={s:dict(r[200],eligible=True,rsi2=5,sma200=90,sma5=110,momentum63=1) for s,r in research.indicators(data,module).items()}
        qty={s:0 for s in research.SYMBOLS}; ages=dict(qty); closes={s:100 for s in qty}
        self.assertEqual(research.selection('broad_pullback5',[],qty,ages,sig,closes,False),['DBC','EEM','EFA'])
        qty['DBC']=1; ages['DBC']=5
        self.assertNotIn('DBC',research.selection('broad_pullback5',['DBC'],qty,ages,sig,closes,False))
        ages['DBC']=4
        self.assertIn('DBC',research.selection('broad_pullback5',['DBC'],qty,ages,sig,closes,False))
        qty['XLB']=1; ages['XLB']=3
        basket=research.selection('sector_pullback3',['XLB'],qty,ages,sig,closes,False)
        self.assertNotIn('XLB',basket); self.assertTrue(set(basket)<=set(research.SECTORS))

    def test_monthly_additions_only_and_tie_break(self):
        data=expanded(); sig={s:dict(r[200],eligible=True,momentum63=1) for s,r in research.indicators(data,research.engine_for('broad_rank_monthly')).items()}
        qty={s:0 for s in research.SYMBOLS}; closes={s:100 for s in qty}
        self.assertEqual(research.selection('broad_rank_monthly',[],qty,qty,sig,closes,True),['DBC','EEM','EFA'])
        self.assertEqual(research.selection('broad_rank_monthly',[],qty,qty,sig,closes,False),[])
        sig['DBC']['momentum63']=-1
        self.assertEqual(research.selection('broad_rank_monthly',['DBC','EEM'],qty,qty,sig,closes,False),['EEM'])

    def test_prefix_invariance_and_next_open_age(self):
        data=expanded(); mod=research.engine_for('broad_breakout10')
        before=research.indicators(data,mod)
        changed=copy.deepcopy(data)
        for rows in changed.values():
            for row in rows[220:]: row['adj_close']*=3
        after=research.indicators(changed,mod)
        for s in research.SYMBOLS:self.assertEqual(before[s][:220],after[s][:220])
        out=research.simulate(data,data['SPY'][200]['date'],data['SPY'][-1]['date'],'broad_breakout10')
        first=out['trades'][0]
        self.assertLess(first['signal_date'],first['date'])
        firstday=next(h for h in out['history'] if h['date']==first['date'])
        self.assertEqual(firstday['holding_age'][first['symbol']],1)
        for h in out['history']:
            self.assertGreaterEqual(h['cash'],0)
            self.assertTrue(all(q>=0 and isinstance(q,int) for q in h['qty'].values()))

    def test_simple_rsi_and_permanent_latch(self):
        data=expanded()
        for rows in data.values():
            for i,row in enumerate(rows):
                if i>=210:
                    for key in ('open','close','adj_close','trade_open','trade_close'):
                        row[key]*=.5 if i<215 else 2
        mod=research.engine_for('broad_breakout10')
        out=research.simulate(data,data['SPY'][200]['date'],data['SPY'][-1]['date'],'broad_breakout10')
        self.assertIsNotNone(out['hard_latch'])
        latch=out['hard_latch']['date']
        self.assertFalse(any(t['side']=='BUY' and t['date']>=latch for t in out['trades']))
        self.assertTrue(all(h['hard'] for h in out['history'] if h['date']>=latch))
        series=research.indicators(expanded(),mod)['SPY']
        self.assertEqual(series[200]['rsi2'],100)


if __name__=='__main__':unittest.main()
