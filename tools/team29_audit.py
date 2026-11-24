"""Independent stock and five-minute ledger audit. No research/live imports."""
import argparse
import csv
import hashlib
import importlib.util
import json
import math
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from team23_audit import check, near

STOCKS = ('AAPL','MSFT','AMZN','GOOGL','NVDA','JPM','XOM','PG')
SYMBOLS = ('SPY',)+STOCKS
DAILY_STOCKS=('AAPL','MSFT','AMZN','JPM','XOM','PG')
DAILY_SYMBOLS=('SPY',)+DAILY_STOCKS
DAILY = ('stock_rank_monthly','stock_pullback5')
INTRADAY = ('orb30','opening_pullback')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def daily_module():
    spec=importlib.util.spec_from_file_location('team29_independent_daily',Path(__file__).with_name('team28_audit.py'))
    mod=importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    mod.BROAD_SYMBOLS=DAILY_SYMBOLS
    mod.RESEARCH_CANDIDATES=DAILY
    mod.choose=choose_daily
    return mod


def choose_daily(candidate, previous, qty, ages, signals, data, day, monthly):
    check(candidate in DAILY,'daily candidate')
    if candidate=='stock_rank_monthly':
        eligible=[s for s in DAILY_STOCKS if signals[s][day]['eligible'] and signals[s][day]['momentum63']>0]
        return sorted(eligible,key=lambda s:(-signals[s][day]['momentum63'],s))[:3] if monthly else [s for s in previous if s in eligible]
    keep=[]; enter=[]
    for s in DAILY_STOCKS:
        v=signals[s][day]; close=data[s][day]['adj_close']
        if qty[s]:
            if not (ages[s]>=5 or close>v['sma5'] or close<v['sma200']): keep.append(s)
        elif v['eligible'] and v['rsi2']<10: enter.append(s)
    return sorted(keep)+sorted(enter,key=lambda s:(signals[s][day]['rsi2'],s))[:max(0,3-len(keep))]


def read_intraday(path):
    days={}
    with Path(path).open(encoding='utf-8',newline='') as f:
        for r in csv.DictReader(f):
            symbol=r['symbol']; day=r['date']; slot=int(r['slot'])
            check(symbol in SYMBOLS and 0<=slot<78,'symbol/slot')
            values={k:float(r[k]) for k in ('open','high','low','close','volume')}
            check(all(math.isfinite(v) for v in values.values()),'finite bars')
            check(all(values[k]>0 for k in ('open','high','low','close')) and values['volume']>=0,'positive bars')
            check(values['low']<=min(values['open'],values['close'])<=max(values['open'],values['close'])<=values['high'],'OHLC')
            bars=days.setdefault(day,{}).setdefault(symbol,{})
            check(slot not in bars,'duplicate intraday slot')
            year=int(day[:4]); march=date(year,3,1); november=date(year,11,1)
            check(2024<=year<=2026,'bounded modern DST audit')
            dst_start=march+timedelta(days=(6-march.weekday())%7+7)
            dst_end=november+timedelta(days=(6-november.weekday())%7)
            local_day=date.fromisoformat(day)
            hour=13 if dst_start<=local_day<dst_end else 14
            opening=datetime(year,local_day.month,local_day.day,hour,30,tzinfo=timezone.utc)
            expected=int(opening.timestamp())+slot*300
            check(int(r['timestamp_utc'])==expected,'UTC/session/DST timestamp')
            bars[slot]=values|{'timestamp_utc':int(r['timestamp_utc'])}
    return days


def full_sessions(days):
    return sorted(d for d,syms in days.items() if set(syms)==set(SYMBOLS) and all(set(syms[s])==set(range(78)) for s in SYMBOLS))


def intraday_signal(strategy, bars, slot):
    check(strategy in INTRADAY,'intraday candidate')
    if not 6<=slot<=74: return None
    candidates=[]
    for s in STOCKS:
        b=bars[s][slot]
        if strategy=='orb30':
            opening_high=max(bars[s][k]['high'] for k in range(6))
            if b['close']>opening_high: candidates.append((-(b['close']/opening_high-1),s))
        else:
            change=b['close']/bars[s][0]['open']-1
            if change<=-.01 and b['close']>bars[s][slot-1]['high']:
                candidates.append((change,s))
    return min(candidates)[1] if candidates else None


def annualized(result):
    days=(date.fromisoformat(result['end'])-date.fromisoformat(result['start'])).days
    return ((result['final_equity']/result['initial'])**(365.2425/max(1,days))-1)*100


def gate(runs):
    check(len(runs)==6,'six daily cells')
    check({(r['period'],r['cost_bps']) for r in runs}=={(p,c) for p in ('recent','latest','continuous') for c in (10,25)},'six unique daily cells')
    return all(annualized(r)>=10 and r['mdd_pct']<=10 and not r['hard_latch'] and not r['pending_liquidation'] for r in runs)


def compare(actual, expected, label):
    if isinstance(expected,dict):
        check(set(actual)==set(expected),label+' keys')
        for k,v in expected.items(): compare(actual[k],v,label+'.'+k)
    elif isinstance(expected,int) and not isinstance(expected,bool): check(type(actual) is int and actual==expected,label)
    elif isinstance(expected,float): near(actual,expected,label)
    else: check(actual==expected,label)


def audit_intraday(result, days):
    sessions=full_sessions(days)
    check(sessions,'full sessions required')
    candidate=result['research_candidate']; check(candidate in INTRADAY,'candidate')
    check(result['cost_bps'] in (10,25),'cost')
    check(result['risk_policy']=='fixed80_intraday','risk policy')
    check(result['annualized_pct'] is None and result['approval_eligible'] is False,'short sample not approval or annualization')
    check(result['sessions']==len(sessions) and result['start']==sessions[0] and result['end']==sessions[-1],'session coverage')
    check(len(result['history'])==len(sessions)*78,'complete bar history')
    cash=peak=float(result['initial']); check(cash==10000,'initial cash')
    due=fees=slippage=mdd=0.; quantity=0; symbol=entry=latch=None; hard=False
    fee=slip=result['cost_bps']/20000.; trade_index=history_index=0

    def mark(price, stamp, phase):
        nonlocal peak,mdd,hard,latch
        value=cash+due+quantity*price
        peak=max(peak,value); drawdown=max(0,1-value/peak);mdd=max(mdd,drawdown)
        if not hard and drawdown>=.1-1e-12:
            hard=True; latch={'timestamp_utc':stamp,'phase':phase}
        return value

    for day in sessions:
        check(quantity==0,'no overnight positions')
        cash+=due; settled=due; due=0.; pending=None; bought=False
        bars=days[day]
        for slot in range(78):
            stamp=bars['SPY'][slot]['timestamp_utc']; executed=pending
            opening=bars[symbol][slot]['open'] if quantity else 0.
            mark(opening,stamp,'open')
            action=None; reason=None
            if quantity and (hard or slot==77 or pending and pending['side']=='SELL'):
                action='SELL'; reason='HARD' if hard else 'EOD' if slot==77 else pending['reason']
                count=quantity; price=opening*(1-slip)
            elif not quantity and not hard and not bought and pending and pending['side']=='BUY' and slot<=75:
                selected=pending['symbol']; opening=bars[selected][slot]['open']; price=opening*(1+slip)
                count=math.floor(min(.99*cash,.8*(cash+due))/(price*(1+fee)))
                if count: action='BUY';reason='SIGNAL';symbol=selected
            if action:
                charge=count*price*fee; net=count*price+charge if action=='BUY' else count*price-charge
                if action=='BUY':
                    check(not hard and net<=cash*.99+1e-7,'cash-only or latch')
                    quantity=count;cash-=net;bought=True;entry=price
                else:
                    check(count==quantity,'full sale, no short');due+=net
                fees+=charge;slippage+=count*abs(price-opening)
                expected={'date':day,'slot':slot,'timestamp_utc':stamp,'symbol':symbol,'side':action,'qty':count,
                          'open':opening,'price':price,'fee':charge,'net':net,'cash_after':cash,'unsettled_after':due,
                          'reason':reason,'signal_timestamp':pending['timestamp_utc'] if pending else None,
                          'signal_available_timestamp':pending['timestamp_utc']+300 if pending else None}
                check(trade_index<len(result['trades']),'missing trade')
                compare(result['trades'][trade_index],expected,'trade')
                trade_index+=1
                if action=='SELL': quantity=0;symbol=entry=None;mark(0,stamp,'after_sell')
                else:mark(opening,stamp,'after_buy')
            low=bars[symbol][slot]['low'] if quantity else 0.
            close=bars[symbol][slot]['close'] if quantity else 0.
            mark(low,stamp,'low');equity=mark(close,stamp,'close');pending=None
            if quantity:
                reason='HARD' if hard else 'STOP' if close<=.98*entry else 'PROFIT' if close>=1.03*entry else None
                if reason:pending={'side':'SELL','reason':reason,'timestamp_utc':stamp}
            elif not bought and not hard:
                selected=intraday_signal(candidate,bars,slot)
                if selected:pending={'side':'BUY','symbol':selected,'timestamp_utc':stamp}
            expected={'date':day,'slot':slot,'timestamp_utc':stamp,'settled_today':settled if slot==0 else 0.,
                      'cash':cash,'unsettled':due,'symbol':symbol,'qty':quantity,'entry':entry,'equity':equity,
                      'peak':peak,'mdd':mdd,'hard':hard,'bought_today':bought,'executed_target':executed,
                      'next_target':pending,'fees_cumulative':fees,'slippage_cumulative':slippage}
            compare(result['history'][history_index],expected,'bar');history_index+=1
            check(cash>=-1e-8 and isinstance(quantity,int) and quantity>=0,'cash/position invariant')
        check(quantity==0,'EOD must flatten')
    check(trade_index==len(result['trades'])==result['trade_count'],'trade count')
    compare(result['hard_latch'],latch,'latch')
    check(result['pending_liquidation'] is False,'EOD pending liquidation')
    for key,value in {'final_equity':cash+due,'return_pct':((cash+due)/result['initial']-1)*100,
                      'mdd_pct':mdd*100,'fees':fees,'slippage':slippage}.items():near(result[key],value,key)
    return {'status':'PASS','bars':history_index,'trades':trade_index,'sessions':len(sessions)}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('data');parser.add_argument('results');args=parser.parse_args()
    folder=Path(args.results); source=Path(args.data)
    manifest=json.loads((folder/'manifest.json').read_text())
    check(Path(manifest['data']).resolve()==source.resolve(),'frozen input path')
    for name,expected in manifest['dataFiles'].items():check(sha(source/name)==expected,'frozen data hash')
    for name,expected in manifest['sources'].items():check(sha(Path(__file__).parent/name)==expected,'frozen source hash')
    check(manifest['dailyUniverse']==list(DAILY_SYMBOLS),'daily corporate-action exclusions')
    check(manifest['historicalGate']=={'annualizedPctMin':10,'maxDrawdownPctMax':10,'hardLatched':False,'pendingLiquidation':False},'historical gate unchanged')
    check(manifest['forwardGate']=={'newTradingDaysMin':60,'fillsMin':20,'positiveReturn':True,'baselineExcessPpMin':2,'maxDrawdownPctMax':10,'baselineDrawdownExcessPpMax':1,'hardLatched':False},'forward gate unchanged')
    check(manifest['eligible'] is False and manifest['forwardDays']==0,'no paper backfill')
    module=daily_module(); daily=module.read_data(source/'daily')
    signals=module.indicator_cache(daily,DAILY_SYMBOLS,'stock_rank_monthly')
    bars=read_intraday(source/'5m'/'intraday.csv')
    complete=full_sessions(bars)
    excluded=json.loads((folder/'excluded-sessions.json').read_text())
    check(set(excluded)==set(bars)-set(complete),'excluded session coverage')
    reports={};groups={};identities=set();intraday_id=set()
    for path in sorted((folder/'runs').glob('*.json')):
        result=json.loads(path.read_text()); candidate=result['research_candidate']
        if path.name.startswith('daily__'):
            key=(result['period'],candidate,result['risk_policy'],result['cost_bps'])
            check(key not in identities,'duplicate daily run');identities.add(key)
            reports[path.name]=module.audit(result,daily,signals)|{'sha256':sha(path)}
            near(result['annualized_pct'],annualized(result),'daily CAGR')
            check(result['historical_cell_pass']==(annualized(result)>=10 and result['mdd_pct']<=10 and not result['hard_latch'] and not result['pending_liquidation']),'daily cell gate')
            check(result['approval_eligible'] is False,'biased sample not approval')
            groups.setdefault((candidate,result['risk_policy']),[]).append(result)
        else:
            key=(candidate,result['cost_bps']);check(key not in intraday_id,'duplicate intraday run');intraday_id.add(key)
            reports[path.name]=audit_intraday(result,bars)|{'sha256':sha(path)}
    check(identities=={(p,c,r,b) for p in ('recent','latest','continuous') for c in DAILY for r in ('cushion80','recovery_vol') for b in (10,25)},'exact24 daily matrix')
    check(intraday_id=={(c,b) for c in INTRADAY for b in (10,25)},'exact4 intraday matrix')
    summaries=json.loads((folder/'summary.json').read_text());check(len(summaries)==28,'summary count');seen=set()
    for summary in summaries:
        name=summary['file'];check(name in reports and name not in seen,'summary identity');seen.add(name)
        original=json.loads((folder/'runs'/name).read_text())
        expected={k:v for k,v in original.items() if k not in ('history','trades')}|{'file':name,'mode':'daily' if name.startswith('daily__') else 'intraday'}
        check(summary==expected,'summary exact')
    check(json.loads((folder/'blocked.json').read_text())==[],'unexpected incomplete execution')
    judgments=json.loads((folder/'judgments.json').read_text());check(len(judgments)==6,'judgment count')
    seen=set()
    for j in judgments:
        key=(j['candidate'],j['risk_policy']);check(key not in seen,'duplicate judgment');seen.add(key)
        check(j['forward_days']==0 and j['forward_fills']==0 and j['approval_eligible'] is False,'forward/approval judgment')
        if key in groups:
            check(j['historical_passed']==gate(groups[key]) and j['required_cells']==6 and j['passed_cells']==sum(r['historical_cell_pass'] for r in groups[key]),'daily final judgment')
        else:
            check(key in {(c,'fixed80_intraday') for c in INTRADAY},'intraday judgment identity')
            check(j['historical_passed'] is False and j['required_periods_complete'] is False,'intraday insufficient history')
    output={'status':'PASS','runs':len(reports),'daily_states':sum(r.get('daily_states',0) for r in reports.values()),
            'intraday_bars':sum(r.get('bars',0) for r in reports.values()),'trades':sum(r['trades'] for r in reports.values()),
            'historical_gate':{c+':'+p:gate(rs) for (c,p),rs in groups.items()},'approval_eligible_count':0,
            'sessions':len(complete),'excluded_sessions':len(excluded),'reports':reports,'audit_source_sha256':sha(__file__)}
    (folder/'independent-audit.json').write_text(json.dumps(output,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in output.items() if k!='reports'}))


if __name__=='__main__':main()
