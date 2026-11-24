"""Synthetic adversarial checks for independent TEAM28 auditor."""
import copy
import unittest
from team21_test import fixture
import team28_research as subject
import team28_audit as auditor


def example():
    original=fixture()
    return {s:copy.deepcopy(original.get(s,original['SPY'])) for s in auditor.BROAD_SYMBOLS}


def run(rows,strategy='broad_breakout10',cost=25,policy='recovery_vol'):
    return subject.simulate(rows,rows['SPY'][200]['date'],rows['SPY'][-1]['date'],strategy,cost,policy)


def dictionary(rows):
    return {s:{r['date']:r for r in rs} for s,rs in rows.items()}


class AuditTests(unittest.TestCase):
    def test_all_fixed_combinations(self):
        rows=example(); data=dictionary(rows)
        for c in auditor.RESEARCH_CANDIDATES:
            for p in auditor.POLICIES:
                for b in (10,25):
                    self.assertEqual(auditor.audit(run(rows,c,b,p),data)['status'],'PASS')

    def test_tampering_rejected(self):
        rows=example(); data=dictionary(rows); original=run(rows)
        mutations=[lambda r:r['history'][0].update(cash=0),
                   lambda r:r['history'][2]['holding_age'].update(DBC=999),
                   lambda r:r['history'][0]['signal_snapshot']['SPY'].update(momentum63=9),
                   lambda r:r['trades'][0].update(fee=0),
                   lambda r:r['trades'][0].update(qty=99999),
                   lambda r:r['trades'][0].update(signal_date=r['trades'][0]['date']),
                   lambda r:r['history'][1]['next_target'].update(gross=9),
                   lambda r:r.update(universe=list(reversed(r['universe'])))]
        for mutate in mutations:
            result=copy.deepcopy(original); mutate(result)
            with self.assertRaises(AssertionError):auditor.audit(result,data)

    def test_age_exit_and_no_same_signal_reentry(self):
        rows=example(); data=dictionary(rows)
        signals=auditor.indicator_cache(data,auditor.BROAD_SYMBOLS,'broad_pullback5')
        day=rows['SPY'][210]['date']; qty={s:0 for s in auditor.BROAD_SYMBOLS}; ages=dict(qty)
        for s in auditor.BROAD_SYMBOLS:
            signals[s][day].update(eligible=True,rsi2=0,sma5=1000,sma200=1)
        for candidate,symbol,limit in [('broad_pullback5','DBC',5),('sector_pullback3','XLB',3),('broad_breakout10','DBC',10)]:
            qty[symbol]=1; ages[symbol]=limit
            basket=auditor.choose(candidate,[symbol],qty,ages,signals,data,day,False)
            self.assertNotIn(symbol,basket)
            ages[symbol]=limit-1
            self.assertIn(symbol,auditor.choose(candidate,[symbol],qty,ages,signals,data,day,False))
            qty[symbol]=0; ages[symbol]=0

    def test_latch_survives_recovery(self):
        rows=example()
        for series in rows.values():
            for i,r in enumerate(series):
                if i>=210:
                    for key in ('open','close','adj_close','trade_open','trade_close'):r[key]*=.5 if i<215 else 2
        result=run(rows); data=dictionary(rows)
        self.assertIsNotNone(result['hard_latch'])
        self.assertEqual(auditor.audit(result,data)['status'],'PASS')
        changed=copy.deepcopy(result)
        next(h for h in changed['history'] if h['hard'])['hard']=False
        with self.assertRaises(AssertionError):auditor.audit(changed,data)

    def test_exact_gate_matrix(self):
        rows=example(); original=run(rows)
        cells=[]
        for period in ('recent','latest','continuous'):
            for cost in (10,25):cells.append(dict(original,period=period,cost_bps=cost))
        self.assertIsInstance(auditor.gate(cells),bool)
        with self.assertRaises(AssertionError):auditor.gate(cells+[cells[0]])
        with self.assertRaises(AssertionError):auditor.gate(cells[:-1]+[cells[0]])


if __name__=='__main__':unittest.main()
