import copy
import unittest
from datetime import date,timedelta
import team30_research as research
import team30_audit as audit


def fixture(count=330,drop=None):
    days=[];day=date(2020,1,1)
    while len(days)<count:
        if day.weekday()<5:days.append(day.isoformat())
        day+=timedelta(days=1)
    data={}
    for symbol in audit.SYMBOLS:
        rows=[]
        for i,d in enumerate(days):
            p=100+i*.1
            if drop and 232<=i<265:p*=drop
            rows.append(dict(date=d,open=p,close=p,adj_close=p,trade_open=p,trade_close=p,
                             trade_dividend=0.,split_numerator=1.,split_denominator=1.))
        data[symbol]=rows
    return data


def run(data,variant='baseline',cost=25):
    return research.simulate(data,data['SPY'][220]['date'],data['SPY'][-1]['date'],variant,cost)


def mapping(data):return {s:{x['date']:x for x in rows} for s,rows in data.items()}


class AuditTests(unittest.TestCase):
    def test_all_variants_and_costs(self):
        data=fixture()
        for v in audit.VARIANTS:
            for cost in (10,25):self.assertEqual(audit.audit(run(data,v,cost),mapping(data))['status'],'PASS')

    def test_mutations_and_future_signal(self):
        data=fixture();result=run(data)
        changes=[lambda r:r['trades'][0].update(signal_date=r['trades'][0]['date']),
                 lambda r:r['history'][1].update(cash=r['history'][1]['cash']+1),
                 lambda r:r['trades'][0].update(qty=r['trades'][0]['qty']+1),
                 lambda r:r['history'][2]['filter_state'].update(day=999),
                 lambda r:r['history'][2]['signal_snapshot']['SPY'].update(market_up=False),
                 lambda r:r['diagnostics'].update(flat_days=999)]
        for change in changes:
            changed=copy.deepcopy(result);change(changed)
            with self.assertRaises(AssertionError):audit.audit(changed,mapping(data))

    def test_pause_resume_preserves_peak_and_rearm(self):
        data=fixture(drop=.88);result=run(data,'pause6')
        audit.audit(result,mapping(data))
        paused=[h for h in result['history'] if h['filter_state']['paused']]
        resumed=[h for h in result['history'] if h['filter_state']['transition'] and not h['filter_state']['paused']]
        self.assertTrue(paused);self.assertTrue(resumed)
        self.assertGreaterEqual(resumed[0]['filter_state']['day']-paused[0]['filter_state']['pause_day'],20)
        self.assertFalse(resumed[0]['filter_state']['armed'])
        changed=copy.deepcopy(result);idx=next(i for i,h in enumerate(result['history']) if h['filter_state']['paused']);changed['history'][idx]['peak']=changed['history'][idx]['equity']
        with self.assertRaises(AssertionError):audit.audit(changed,mapping(data))

    def test_final_latch_cannot_resume(self):
        data=fixture(drop=.6);result=run(data,'pause6');audit.audit(result,mapping(data))
        self.assertIsNotNone(result['hard_latch'])
        first=next(i for i,h in enumerate(result['history']) if h['hard'])
        self.assertTrue(all(h['hard'] for h in result['history'][first:]))
        self.assertFalse(any(t['side']=='BUY' and t['date']>=result['history'][first]['date'] for t in result['trades']))
        changed=copy.deepcopy(result);changed['history'][first+1]['hard']=False
        with self.assertRaises(AssertionError):audit.audit(changed,mapping(data))

    def test_signal_prefix_and_gate_matrix(self):
        data=fixture();prefix={s:rs[:280] for s,rs in data.items()}
        short=run(prefix,'daily_entry');long=run(data,'daily_entry')
        self.assertEqual(short['history'],long['history'][:len(short['history'])])
        rows=[]
        for period in ('recent','latest','continuous'):
            for cost in (10,25):rows.append(dict(period=period,cost_bps=cost,start='2020-01-01',end='2021-01-01',initial=100,final_equity=120,mdd_pct=9,hard_latch=None,pending_liquidation=False))
        self.assertTrue(audit.gate(rows))
        with self.assertRaises(AssertionError):audit.gate(rows+[rows[0]])
        rows[0]['hard_latch']={'date':'2020-02-01'};self.assertFalse(audit.gate(rows))

if __name__=='__main__':unittest.main()
