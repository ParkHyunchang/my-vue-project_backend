import copy
import unittest
from datetime import datetime,timezone
import team29_audit as audit
import team29_research as subject
from team21_test import fixture


def daily_rows():
    source=fixture()['SPY']
    return {s:copy.deepcopy(source) for s in audit.DAILY_SYMBOLS}


def sessions(strategy='orb30',shock=False):
    result={}
    for day in ('2026-11-02','2026-11-03'):
        stamp=int(datetime.fromisoformat(day+'T14:30:00+00:00').timestamp())
        rows={}
        for s in audit.SYMBOLS:
            values=[]
            for slot in range(78):
                price=100+max(0,slot-5)*.04 if strategy=='orb30' else 100 if slot<6 else 98 if slot==6 else 98.5
                low=price-.02
                if shock and slot==10:low=70
                values.append({'date':day,'slot':slot,'timestamp_utc':stamp+slot*300,'symbol':s,
                               'open':price,'high':price+.02,'low':low,'close':price,'volume':1000})
            rows[s]=values
        result[day]=rows
    return result


def by_slot(data):
    return {d:{s:{r['slot']:r for r in rs} for s,rs in stocks.items()} for d,stocks in data.items()}


class AuditTests(unittest.TestCase):
    def test_daily_all_cells_and_tamper(self):
        rows=daily_rows();module=audit.daily_module();data={s:{r['date']:r for r in rs} for s,rs in rows.items()}
        for candidate in audit.DAILY:
            for policy in ('cushion80','recovery_vol'):
                for cost in (10,25):
                    r=subject.daily_run(rows,rows['SPY'][200]['date'],rows['SPY'][-1]['date'],candidate,cost,policy)
                    self.assertEqual(module.audit(r,data)['status'],'PASS')
        r['history'][0]['cash']=0
        with self.assertRaises(AssertionError):module.audit(r,data)

    def test_intraday_all_configs(self):
        for candidate in audit.INTRADAY:
            data=sessions(candidate)
            for cost in (10,25):
                r=subject.intraday_run(data,candidate,cost)
                self.assertGreater(r['trade_count'],0)
                self.assertEqual(audit.audit_intraday(r,by_slot(data))['status'],'PASS')

    def test_intraday_mutations(self):
        data=sessions();original=subject.intraday_run(data,'orb30',25)
        mutations=(lambda r:r['history'][0].update(cash=0),
                   lambda r:r['history'][80].update(peak=100),
                   lambda r:r['trades'][0].update(qty=999),
                   lambda r:r['trades'][0].update(signal_available_timestamp=r['trades'][0]['timestamp_utc']+300),
                   lambda r:r['trades'][0].update(fee=0),
                   lambda r:r.update(annualized_pct=99),
                   lambda r:r.update(approval_eligible=True),
                   lambda r:r['history'][1]['next_target'].update(symbol='SPY') if r['history'][1]['next_target'] else r['history'][1].update(qty=1))
        for mutate in mutations:
            r=copy.deepcopy(original);mutate(r)
            with self.assertRaises(AssertionError):audit.audit_intraday(r,by_slot(data))

    def test_low_latch_persists_and_no_intrabar_fill(self):
        data=sessions(shock=True);r=subject.intraday_run(data,'orb30',25)
        self.assertEqual(r['hard_latch']['phase'],'low')
        self.assertEqual(audit.audit_intraday(r,by_slot(data))['status'],'PASS')
        sells=[t for t in r['trades'] if t['side']=='SELL']
        self.assertEqual(sells[0]['slot'],11)
        self.assertEqual(sum(t['side']=='BUY' for t in r['trades']),1)
        altered=copy.deepcopy(r);next(h for h in altered['history'] if h['hard'])['hard']=False
        with self.assertRaises(AssertionError):audit.audit_intraday(altered,by_slot(data))

    def test_prefix_signal_no_future(self):
        data=by_slot(sessions());day=next(iter(data));bars=data[day]
        original=audit.intraday_signal('orb30',bars,7)
        changed=copy.deepcopy(bars)
        for rows in changed.values():
            for i in range(8,78):rows[i]['close']=99999
        self.assertEqual(original,audit.intraday_signal('orb30',changed,7))
        self.assertIsNone(audit.intraday_signal('orb30',bars,75))

    def test_full_session_and_gate_duplicates(self):
        data=by_slot(sessions());self.assertEqual(len(audit.full_sessions(data)),2)
        del data['2026-11-02']['PG'][4]
        self.assertEqual(audit.full_sessions(data),['2026-11-03'])
        row={'start':'2024-01-02','end':'2025-12-31','final_equity':13000,'initial':10000,'mdd_pct':5,'hard_latch':None,'pending_liquidation':False}
        cells=[dict(row,period=p,cost_bps=c) for p in ('recent','latest','continuous') for c in (10,25)]
        self.assertTrue(audit.gate(cells))
        with self.assertRaises(AssertionError):audit.gate(cells[:-1]+[cells[0]])


if __name__=='__main__':unittest.main()
