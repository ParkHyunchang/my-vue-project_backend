"""Frozen stock/daily and short-sample intraday experiments, no live imports.

Intraday bars are not quotes. Signals finish before next-bar-open fills. Account
cash/peak/latch persist across sessions, including low-observed risk breaches.
"""
import argparse
import csv
import hashlib
import importlib.util
import json
import math
from datetime import date, datetime, timezone
from pathlib import Path

TOOLS=Path(__file__).resolve().parent
STOCKS=('AAPL','MSFT','AMZN','GOOGL','NVDA','JPM','XOM','PG')
SYMBOLS=('SPY',)+STOCKS
DAILY_STOCKS=('AAPL','MSFT','AMZN','JPM','XOM','PG')
DAILY_SYMBOLS=('SPY',)+DAILY_STOCKS
DAILY_RULES={
 'stock_rank_monthly':'6 surviving stocks, positive63 momentum and aboveSMA200, monthly top3 by momentum/symbol, daily exits only; daily quantity rebalance.',
 'stock_pullback5':'6 stocks, aboveSMA200/simple two-changeRSI<10, lowestRSI/symbol up to3; exit close>SMA5 or close<SMA200 or actual holding age>=5, next open.'}
INTRADAY_RULES={
 'orb30':'After first6 bars, close above their high; highest close/openingHigh-1, symbol tie break.',
 'opening_pullback':'Close<=99% firstbaropen and close>previousbarhigh; most negative close/firstopen-1, symbol tie break.'}

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def private_engine():
 spec=importlib.util.spec_from_file_location('team29_private',TOOLS/'team23_research.py')
 m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
 m.SYMBOLS=DAILY_SYMBOLS; m.SECTORS=DAILY_STOCKS
 return m

def daily_select(strategy,prior,qty,ages,snapshot,closes,monthly):
 if strategy=='stock_rank_monthly':
  good=lambda s:snapshot[s]['eligible'] and snapshot[s]['momentum63']>0
  return sorted((s for s in DAILY_STOCKS if good(s)),key=lambda s:(-snapshot[s]['momentum63'],s))[:3] if monthly else [s for s in prior if good(s)]
 if strategy!='stock_pullback5':raise ValueError('Unknown strategy')
 held={s for s in DAILY_STOCKS if qty[s]>0}
 survivors=sorted(s for s in held if not(closes[s]>snapshot[s]['sma5'] or closes[s]<snapshot[s]['sma200'] or ages[s]>=5))
 entries=sorted((s for s in DAILY_STOCKS if s not in held and snapshot[s]['eligible'] and snapshot[s]['rsi2']<10),key=lambda s:(snapshot[s]['rsi2'],s))
 return survivors+entries[:max(0,3-len(survivors))]

def daily_signals(data):
 out=private_engine().indicators(data)
 for s in DAILY_SYMBOLS:
  for i,row in enumerate(out[s]):row['momentum63']=data[s][i]['adj_close']/data[s][i-63]['adj_close']-1 if i>=63 else None
 return out

def daily_run(data,start,end,strategy,cost,policy,signals=None):
 m=private_engine();m.select_basket=lambda c,p,q,a,s,cl,mo:daily_select(strategy,p,q,a,s,cl,mo)
 r=m.simulate(data,start,end,'sector_breakout',cost,signals=signals or daily_signals(data),risk_policy=policy)
 r.update(research_candidate=strategy,universe=list(DAILY_SYMBOLS),approval_eligible=False)
 days=(date.fromisoformat(r['end'])-date.fromisoformat(r['start'])).days
 r['annualized_pct']=((r['final_equity']/r['initial'])**(365.2425/max(1,days))-1)*100
 r['historical_cell_pass']=r['annualized_pct']>=10 and r['mdd_pct']<=10 and not r['hard_latch'] and not r['pending_liquidation']
 return r

def read_daily(folder):
 data={s:[] for s in DAILY_SYMBOLS}
 with (Path(folder)/'daily.csv').open(encoding='utf-8',newline='') as f:
  for raw in csv.DictReader(f):
   if raw['symbol'] in data:data[raw['symbol']].append({k:v if k=='date' else float(v) for k,v in raw.items() if k in ('date','open','close','adj_close','dividend','split_numerator','split_denominator')})
 for rows in data.values():
  factor=1.
  for row in reversed(rows):
   ratio=row['split_numerator']/row['split_denominator']
   if ratio<1 or abs(ratio-round(ratio))>1e-9:raise ValueError('Unsupported fractional corporate action')
   for target,source in [('trade_open','open'),('trade_close','close'),('trade_dividend','dividend')]:row[target]=row[source]*factor
   factor*=ratio
 private_engine().validate(data)
 return data

def read_intraday(folder):
 groups={}
 with (Path(folder)/'intraday.csv').open(encoding='utf-8',newline='') as f:
  for raw in csv.DictReader(f):
   if raw['symbol'] not in SYMBOLS:raise ValueError('Unexpected symbol')
   row={k:raw[k] for k in ('date','symbol')}
   row.update({k:int(raw[k]) for k in ('timestamp_utc','slot')})
   row.update({k:float(raw[k]) for k in ('open','high','low','close','volume')})
   groups.setdefault(row['date'],{}).setdefault(row['symbol'],[]).append(row)
 eligible={};excluded={}
 for day,symbols in sorted(groups.items()):
  valid=set(symbols)==set(SYMBOLS) and all([r['slot'] for r in symbols[s]]==list(range(78)) for s in SYMBOLS)
  if valid:
   stamps=[r['timestamp_utc'] for r in symbols['SPY']]
   valid=all([r['timestamp_utc'] for r in symbols[s]]==stamps for s in SYMBOLS) and all(b-a==300 for a,b in zip(stamps,stamps[1:]))
  if not valid:excluded[day]='Not all9 symbols have aligned complete78 bars';continue
  for rows in symbols.values():
   for row in rows:
    if any(not math.isfinite(row[k]) or row[k]<=0 for k in ('open','high','low','close')) or row['low']>min(row['open'],row['close']) or row['high']<max(row['open'],row['close']) or row['volume']<0:raise ValueError('Invalid bar')
  eligible[day]=symbols
 return eligible,excluded

def entry_signal(daybars,slot,strategy):
 if strategy not in INTRADAY_RULES:raise ValueError('Unknown intraday strategy')
 if not 6<=slot<=74:return None
 candidates=[]
 for s in STOCKS:
  rows=daybars[s];bar=rows[slot]
  if strategy=='orb30':
   threshold=max(r['high'] for r in rows[:6]);score=bar['close']/threshold-1
   if score>0:candidates.append((-score,s))
  else:
   ret=bar['close']/rows[0]['open']-1
   if ret<=-.01 and bar['close']>rows[slot-1]['high']:candidates.append((ret,s))
 return min(candidates)[1] if candidates else None

def intraday_run(sessions,strategy,cost,initial=10000.):
 if cost not in (10,25) or initial<=0:raise ValueError('Invalid configuration')
 cash=float(initial);unsettled=0.;peak=float(initial);mdd=0.;hard=False;latch=None
 qty=0;symbol=None;entry=None;fees=slippage=0.;history=[];trades=[]
 fee=slip=cost/20000.
 def observe(price,stamp,phase):
  nonlocal peak,mdd,hard,latch
  equity=cash+unsettled+qty*price;peak=max(peak,equity)
  dd=max(0.,1-equity/peak);mdd=max(mdd,dd)
  if not hard and dd>=.10-1e-12:hard=True;latch={'timestamp_utc':stamp,'phase':phase}
  return equity
 for day,bars in sorted(sessions.items()):
  if qty:raise AssertionError('Overnight position')
  settled=unsettled;cash+=unsettled;unsettled=0.;pending=None;bought=False
  for i in range(78):
   stamp=bars['SPY'][i]['timestamp_utc'];executed=pending
   openprice=bars[symbol][i]['open'] if qty else 0.
   observe(openprice,stamp,'open')
   if qty and (hard or i==77 or pending and pending['side']=='SELL'):
    reason='HARD' if hard else 'EOD' if i==77 else pending['reason']
    price=openprice*(1-slip);charge=qty*price*fee;net=qty*price-charge
    cash_before=cash;unsettled+=net;fees+=charge;slippage+=qty*(openprice-price)
    trades.append({'date':day,'slot':i,'timestamp_utc':stamp,'symbol':symbol,'side':'SELL','qty':qty,'open':openprice,'price':price,'fee':charge,'net':net,'cash_after':cash,'unsettled_after':unsettled,'reason':reason,'signal_timestamp':pending['timestamp_utc'] if pending else None,'signal_available_timestamp':pending['timestamp_utc']+300 if pending else None})
    qty=0;symbol=None;entry=None;observe(0,stamp,'after_sell')
   elif not qty and not hard and not bought and pending and pending['side']=='BUY' and i<=75:
    symbol=pending['symbol'];openprice=bars[symbol][i]['open'];price=openprice*(1+slip);unit=price*(1+fee)
    qty=math.floor(min(cash*.99,(cash+unsettled)*.8)/unit)
    if qty:
     charge=qty*price*fee;net=qty*price+charge;cash-=net;fees+=charge;slippage+=qty*(price-openprice);bought=True;entry=price
     trades.append({'date':day,'slot':i,'timestamp_utc':stamp,'symbol':symbol,'side':'BUY','qty':qty,'open':openprice,'price':price,'fee':charge,'net':net,'cash_after':cash,'unsettled_after':unsettled,'reason':'SIGNAL','signal_timestamp':pending['timestamp_utc'],'signal_available_timestamp':pending['timestamp_utc']+300})
     observe(openprice,stamp,'after_buy')
    else:symbol=None
   low=bars[symbol][i]['low'] if qty else 0.;close=bars[symbol][i]['close'] if qty else 0.
   observe(low,stamp,'low');equity=observe(close,stamp,'close')
   pending=None
   if qty:
    reason='HARD' if hard else 'STOP' if close<=entry*.98 else 'PROFIT' if close>=entry*1.03 else None
    if reason:pending={'side':'SELL','reason':reason,'timestamp_utc':stamp}
   elif not bought and not hard:
    selected=entry_signal(bars,i,strategy)
    if selected:pending={'side':'BUY','symbol':selected,'timestamp_utc':stamp}
   if cash< -1e-8 or qty<0:raise AssertionError('Cash constraint')
   history.append({'date':day,'slot':i,'timestamp_utc':stamp,'settled_today':settled if i==0 else 0.,'cash':cash,'unsettled':unsettled,'symbol':symbol,'qty':qty,'entry':entry,'equity':equity,'peak':peak,'mdd':mdd,'hard':hard,'bought_today':bought,'executed_target':executed,'next_target':pending,'fees_cumulative':fees,'slippage_cumulative':slippage})
  if qty:raise AssertionError('EOD liquidation failed')
 return {'research_candidate':strategy,'cost_bps':cost,'initial':initial,'start':min(sessions),'end':max(sessions),'sessions':len(sessions),'final_equity':cash+unsettled,'return_pct':((cash+unsettled)/initial-1)*100,'mdd_pct':mdd*100,'hard_latch':latch,'pending_liquidation':bool(qty),'fees':fees,'slippage':slippage,'trade_count':len(trades),'annualized_pct':None,'approval_eligible':False,'risk_policy':'fixed80_intraday','history':history,'trades':trades}

def freeze(data,output):
 data=Path(data);output=Path(output)
 files={str(p.relative_to(data)):sha(p) for p in data.rglob('*') if p.is_file() and p.suffix in ('.csv','.json')}
 if not files:raise ValueError('No data')
 manifest={'registeredAt':datetime.now(timezone.utc).isoformat(),'data':str(data.resolve()),'dataFiles':files,'sources':{p.name:sha(p) for p in (Path(__file__),TOOLS/'team23_research.py')},'dailyRules':DAILY_RULES,'intradayRules':INTRADAY_RULES,'symbols':list(SYMBOLS),'dailyUniverse':list(DAILY_SYMBOLS),'dailyExcluded':{'GOOGL':'2014 1998:1000 noninteger corporate action lacks verified cash-in-lieu','NVDA':'2007 3:2 split can generate fractional entitlement, cash-in-lieu unavailable'},'periods':[['recent','2024-01-01','2025-12-31'],['latest','2026-01-01','2026-11-20'],['continuous','2008-01-01','2026-11-20']],'costBps':[10,25],'riskPolicies':['cushion80','recovery_vol'],'historicalGate':{'annualizedPctMin':10,'maxDrawdownPctMax':10,'hardLatched':False,'pendingLiquidation':False},'forwardGate':{'newTradingDaysMin':60,'fillsMin':20,'positiveReturn':True,'baselineExcessPpMin':2,'maxDrawdownPctMax':10,'baselineDrawdownExcessPpMax':1,'hardLatched':False},'eligible':False,'forwardDays':0,'notes':['6 daily/8 intraday surviving stock examples are not historical constituents including delistings.','Intraday coverage short; no CAGR or full historical approval claim.','Intraday fixed80 is distinct from daily cushion/recovery; cash and permanent10% latch stay.','Full78bar common sessions only, list exclusions; signal closes then next open, last entry15:45, scheduled15:55open exit.','Signal timestamps label bar START; close/low information is available at timestamp+300. Next-open fills are never earlier. Low breach ordering within bar is unknown.', 'Low-price observation can trigger risk but never grants low-price liquidation; sell next bar open. Unknown within-bar order/quotes remain.','One buy per day; sale proceeds settle next observed session.','Price>=entry*1.03 or <=entry*.98 close triggers next-open exit.','SPY adjusted daily proxy is not official S&P500 total return.','No keys, live allowlist, signing, production, or existing pinned-source changes.']}
 output.mkdir(parents=True,exist_ok=False);(output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
 return manifest

def execute(output):
 output=Path(output);manifest=json.loads((output/'manifest.json').read_text())
 for name,h in manifest['sources'].items():
  if sha(TOOLS/name)!=h:raise ValueError('Frozen source changed')
 datafolder=Path(manifest['data'])
 for name,h in manifest['dataFiles'].items():
  if sha(datafolder/name)!=h:raise ValueError('Frozen data changed')
 runs=output/'runs';runs.mkdir(exist_ok=False);summaries=[];blocked=[]
 try:
  daily=read_daily(datafolder/'daily');signals=daily_signals(daily)
  for label,start,end in manifest['periods']:
   for strategy in DAILY_RULES:
    for policy in manifest['riskPolicies']:
     for cost in manifest['costBps']:
      r=daily_run(daily,start,end,strategy,cost,policy,signals);r['period']=label
      file=f'daily__{label}__{strategy}__{policy}__{cost}bps.json';(runs/file).write_text(json.dumps(r,separators=(',',':'),allow_nan=False))
      summaries.append({k:v for k,v in r.items() if k not in ('history','trades')}|{'file':file,'mode':'daily'})
 except ValueError as e:blocked.append({'mode':'daily','reason':str(e)})
 intraday,excluded=read_intraday(datafolder/'5m')
 (output/'excluded-sessions.json').write_text(json.dumps(excluded,indent=2))
 if not intraday:blocked.append({'mode':'intraday','reason':'No complete aligned78bar sessions'})
 else:
  for strategy in INTRADAY_RULES:
   for cost in manifest['costBps']:
    r=intraday_run(intraday,strategy,cost);file=f'intraday__{strategy}__{cost}bps.json'
    (runs/file).write_text(json.dumps(r,separators=(',',':'),allow_nan=False));summaries.append({k:v for k,v in r.items() if k not in ('history','trades')}|{'file':file,'mode':'intraday'})
 (output/'summary.json').write_text(json.dumps(summaries,indent=2));(output/'blocked.json').write_text(json.dumps(blocked,indent=2))
 return {'runs':len(summaries),'blocked':blocked,'approvalEligible':False}

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('action',choices=('freeze','run'));p.add_argument('--data');p.add_argument('--output',required=True);a=p.parse_args()
 print(json.dumps(freeze(a.data,a.output) if a.action=='freeze' else execute(a.output),indent=2))
