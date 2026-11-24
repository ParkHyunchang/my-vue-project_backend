"""Frozen 2x2 SPY trend study. Offline only; not a live-account strategy replay."""
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean
from team15_dca import load_data

ROOT = Path(__file__).resolve().parent
PERIODS = [('crisis','2008-01-01','2017-12-31'), ('middle','2018-01-01','2023-12-31'),
           ('recent','2024-01-01','2025-12-31'), ('latest','2026-01-01','2026-11-17'),
           ('continuous','2008-01-01','2026-11-17')]
CANDIDATES = ('full_five','staged_five','full_trend','staged_trend')


def simulate(all_rows, start, end, candidate, fee_bps=5, slip_bps=5, initial=10000):
    if candidate not in CANDIDATES or initial <= 0 or min(fee_bps, slip_bps)<0:
        raise ValueError('Invalid experiment input')
    selected=[(i,r) for i,r in enumerate(all_rows) if start<=r['date']<=end]
    if not selected:
        raise ValueError('Empty evaluation period')
    staged=candidate.startswith('staged')
    five=candidate.endswith('five')
    cash, qty, receivable, peak = float(initial), 0, 0.0, float(initial)
    hard=False
    hard_date=None
    pending=None
    entry_idx=None
    added=False
    fees=0.0
    mdd=0.0
    trades=[]
    history=[]
    fee,slip=fee_bps/10000,slip_bps/10000

    def observe(price):
        nonlocal peak,mdd
        value=cash+qty*price+receivable
        peak=max(peak,value)
        dd=max(0,1-value/peak)
        mdd=max(mdd,dd)
        return value,dd

    for day_idx,(source_idx,row) in enumerate(selected):
        date=row['date']
        receivable+=qty*row['dividend']
        opening,dd=observe(row['open'])
        if not hard and dd>=.10-1e-12:
            hard,hard_date=True,date
        action=pending
        pending=None
        if hard and qty:
            action={'side':'SELL','reason':'HARD','signal_date':date,'weight':0}
        if action and action['side']=='SELL' and qty:
            price=row['open']*(1-slip)
            commission=qty*price*fee
            cash+=qty*price-commission
            fees+=commission
            trades.append(dict(action,date=date,qty=qty,price=price,fee=commission,cash_after=cash))
            qty=0
            entry_idx=None
            added=False
            observe(row['open'])
        elif action and action['side']=='BUY' and not hard:
            price=row['open']*(1+slip)
            unit=price*(1+fee)
            target=math.floor(action['weight']*opening/unit)
            wanted=max(0,target-qty)
            count=min(wanted,math.floor(cash*.99/unit))
            if action['reason']=='ADD':
                added=True
            if count:
                before=cash
                commission=count*price*fee
                cash-=count*unit
                fees+=commission
                if cash < -1e-7 or count*unit > before*.99+1e-7:
                    raise AssertionError('Borrowed cash')
                if qty==0:
                    entry_idx=day_idx
                qty+=count
                trades.append(dict(action,date=date,qty=count,price=price,fee=commission,cash_before=before,cash_after=cash))
        equity,dd=observe(row['close'])
        if not hard and dd>=.10-1e-12:
            hard,hard_date=True,date
        closes=[r['close'] for r in all_rows[max(0,source_idx-199):source_idx+1]]
        trend=len(closes)==200 and row['close']>mean(closes)
        held=0 if entry_idx is None else day_idx-entry_idx+1
        if hard:
            if qty:
                pending={'side':'SELL','reason':'HARD','signal_date':date,'weight':0}
        elif qty:
            if not trend or (five and held>=5):
                pending={'side':'SELL','reason':'TREND_EXIT' if not trend else 'FIVE_DAY_EXIT','signal_date':date,'weight':0}
            elif staged and not added and held>=2:
                pending={'side':'BUY','reason':'ADD','signal_date':date,'weight':.8}
        elif trend:
            pending={'side':'BUY','reason':'ENTRY','signal_date':date,'weight':.4 if staged else .8}
        history.append({'date':date,'cash':cash,'quantity':qty,'dividend_receivable':receivable,
                        'equity':equity,'peak':peak,'drawdown':dd,'hard':hard,'trend':trend,
                        'held_sessions':held,'added':added,'pending':pending,
                        'exposure':qty*row['close']/equity if equity else 0})
    return {'candidate':candidate,'start':selected[0][1]['date'],'end':selected[-1][1]['date'],
            'return_pct':(equity/initial-1)*100,'final_equity':equity,'mdd_pct':mdd*100,
            'hard_date':hard_date,'pending_liquidation':hard and qty>0,'fees':fees,
            'trade_count':len(trades),'add_count':sum(t['reason']=='ADD' for t in trades),
            'mean_exposure_pct':mean(r['exposure'] for r in history)*100,
            'hard_session_pct':mean(r['hard'] for r in history)*100,'history':history,'trades':trades}


def buyhold(rows, fee_bps=5, slip_bps=5):
    cash,qty,dividend,peak,mdd=10000.0,0,0.0,10000.0,0.0
    for i,row in enumerate(rows):
        dividend+=qty*row['dividend']
        if i==1:
            unit=row['open']*(1+slip_bps/10000)*(1+fee_bps/10000)
            qty=math.floor(cash*.99/unit)
            cash-=qty*unit
        for price in (row['open'],row['close']):
            equity=cash+qty*price+dividend
            peak=max(peak,equity)
            mdd=max(mdd,1-equity/peak)
    return {'return_pct':(equity/10000-1)*100,'mdd_pct':mdd*100}


def run():
    output=ROOT/'team16_results'
    output.mkdir(exist_ok=False)
    files=['team16_plan.md','team16_research.py','team15_dca.py','strategy_v3_data/SPY.raw.json',
           'strategy_v3_data/SP500TR.csv','strategy_v3_data/manifest.json']
    hashes={f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in files}
    services=['KiwoomUsAccountRiskService.java','KiwoomUsTradeService.java','KiwoomUsAutoTradeService.java']
    guard={f'src/main/java/com/hyunchang/webapp/service/{name}':
           hashlib.sha256((ROOT.parent/'src/main/java/com/hyunchang/webapp/service'/name).read_bytes()).hexdigest()
           for name in services}
    (output/'pre-run-lock.json').write_text(json.dumps({'utc':datetime.now(timezone.utc).isoformat(),'sha256':hashes,
        'production_guard_sha256':guard},indent=2)+'\n')
    rows,index=load_data()
    summaries=[]
    for label,start,end in PERIODS:
        sample=[r for r in rows if start<=r['date']<=end]
        benchmark=(index[sample[-1]['date']]/index[sample[0]['date']]-1)*100
        for slip in (5,20):
            hold=buyhold(sample,slip_bps=slip)
            for candidate in CANDIDATES:
                result=simulate(rows,start,end,candidate,slip_bps=slip)
                result.update(period=label,cost_bps=5+slip,benchmark_pct=benchmark,
                              excess_pp=result['return_pct']-benchmark,buyhold=hold)
                name=f'{label}-{candidate}-{5+slip}bp'
                (output/(name+'.json')).write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
                summaries.append({k:v for k,v in result.items() if k not in ('trades','history')})
    if any(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()!=h for f,h in hashes.items()):
        raise ValueError('Frozen inputs changed')
    if any(hashlib.sha256((ROOT.parent/f).read_bytes()).hexdigest()!=h for f,h in guard.items()):
        raise ValueError('Live service changed during experiment')
    (output/'summary.json').write_text(json.dumps(summaries,indent=2,allow_nan=False)+'\n')
    eligible=[candidate for candidate in CANDIDATES if all(r['return_pct']>r['benchmark_pct'] and r['mdd_pct']<=10
        for r in summaries if r['candidate']==candidate and r['period'] in ('recent','latest'))]
    (output/'completion.json').write_text(json.dumps({'runs':len(summaries),'production_guard_unchanged':True,
        'recent_and_latest_both_costs_eligible_candidates':eligible,'all_retrospective':True},indent=2)+'\n')
    for row in summaries:
        print(json.dumps(row))


if __name__=='__main__':
    run()
