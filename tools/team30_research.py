"""Predeclared filter ablations, unchanged TEAM24 accounting and final10% latch."""
import argparse
import hashlib
import importlib.util
import json
from datetime import date, datetime, timezone
from pathlib import Path

TOOLS=Path(__file__).resolve().parent
RULES={
 'baseline':'Unchanged balanced_trend/recovery_vol.',
 'remove_sma':'Remove individual SMA200 eligibility only; all four assets eligible.',
 'market_entry':'Monthly new entries require SPY>SMA200 and SMA200>SMA200 from20sessions earlier. Existing eligible basket survives.',
 'vol_entry':'Monthly new entries require SPY vol20<=25% annualized. Existing eligible basket survives.',
 'monthly_exit':'Individual trend exits only on monthly selection; daily risk reduction and hard latch unchanged.',
 'daily_entry':'Allow daily eligible additions; FULL only for additions or normal monthly cycle, otherwise original mode.',
 'cap40':'Cap recovery_vol gross at40%, all other rules unchanged.',
 'pause6':'Composite rest policy: DD>=6% when armed pauses, minimum20sessions and5consecutive market_up closes resume. Re-arm only DD<3%; peak and10%latch never reset.'}

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def engine():
 spec=importlib.util.spec_from_file_location('team30_private',TOOLS/'team24_research.py')
 m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def indicators(data):
 out=engine().indicators(data)
 for s in out:
  for i,row in enumerate(out[s]):
   row['sma200_lag20']=out[s][i-20]['sma200'] if i>=219 else None
   row['market_up']=bool(row['eligible'] and row['sma200_lag20'] is not None and row['sma200']>row['sma200_lag20'])
 return out

class FilterPolicy:
 def __init__(self,variant,module):
  if variant not in RULES:raise ValueError('Unknown filter variant')
  self.variant=variant;self.base_select=module.select_basket;self.base_risk=module.risk_target;self.base_order=module.order_mode
  self.snapshot=None;self.basket=[];self.paused=False;self.armed=True;self.pause_day=None;self.day=-1;self.up_streak=0;self.transition=False;self.trace=[]

 def select(self,candidate,prior,snapshot,monthly):
  self.snapshot=snapshot;v=self.variant
  if v=='remove_sma':basket=list(engine_symbols())
  elif v=='monthly_exit' and not monthly:basket=list(prior)
  elif v in ('market_entry','vol_entry') and monthly:
   allowed=snapshot['SPY']['market_up'] if v=='market_entry' else snapshot['SPY']['vol20']<=.25
   basket=self.base_select(candidate,prior,snapshot,True) if allowed else [s for s in prior if snapshot[s]['eligible']]
  else:basket=self.base_select(candidate,prior,snapshot,monthly or v=='daily_entry')
  self.basket=basket
  return basket

 def risk(self,value,peak,hard,policy,volatility):
  self.day+=1;self.transition=False
  gross=self.base_risk(value,peak,hard,policy,volatility)
  dd=1-value/peak;self.up_streak=self.up_streak+1 if self.snapshot['SPY']['market_up'] else 0
  if self.variant=='cap40':gross=min(.4,gross)
  if self.variant=='pause6':
   if not self.paused and dd<.03:self.armed=True
   if hard:
    gross=0.;self.basket.clear()
   elif self.paused:
    if self.day-self.pause_day>=20 and self.up_streak>=5:
     self.paused=False;self.transition=True
     self.basket[:]=[s for s in engine_symbols() if self.snapshot[s]['eligible']]
    else:gross=0.;self.basket.clear()
   elif self.armed and dd>=.06:
    self.paused=True;self.armed=False;self.pause_day=self.day;self.transition=True;gross=0.;self.basket.clear()
  self.trace.append({'paused':self.paused,'armed':self.armed,'pause_day':self.pause_day,'day':self.day,'up_streak':self.up_streak,'transition':self.transition})
  return gross

 def order(self,monthly,gross,anchor,previous,basket,hard):
  if hard:return 'HOLD'
  if self.variant=='pause6' and self.transition:return 'FULL'
  if self.variant=='daily_entry' and set(basket)-set(previous):return 'FULL'
  return self.base_order(monthly,gross,anchor,previous,basket,hard)

def engine_symbols():return ('SPY','QQQ','GLD','IEF')

def simulate(data,start,end,variant,cost=25,signals=None):
 m=engine();p=FilterPolicy(variant,m)
 m.select_basket=p.select;m.risk_target=p.risk;m.order_mode=p.order
 r=m.simulate(data,start,end,'balanced_trend',cost,signals=signals or indicators(data),risk_policy='recovery_vol')
 for h,t in zip(r['history'],p.trace):h['filter_state']=t
 r['research_variant']=variant
 days=(date.fromisoformat(r['end'])-date.fromisoformat(r['start'])).days
 r['annualized_pct']=((r['final_equity']/r['initial'])**(365.2425/max(1,days))-1)*100
 r['historical_cell_pass']=r['annualized_pct']>=10 and r['mdd_pct']<=10 and not r['hard_latch'] and not r['pending_liquidation']
 h=r['history']
 r['diagnostics']={'flat_days':sum(not any(x['qty'].values()) for x in h),'hard_days':sum(x['hard'] for x in h),
  'paused_days':sum(x['filter_state']['paused'] for x in h),'empty_target_days':sum(not x['next_target']['basket'] for x in h),
  'market_gate_closed_days':sum(not x['signal_snapshot']['SPY']['market_up'] for x in h),
  'vol_gate_closed_days':sum(x['market_vol20']>.25 for x in h),'total_days':len(h),
  'fees_and_slippage':r['fees']+r['slippage'],'first_hard_date':r['hard_latch']['date'] if r['hard_latch'] else None}
 r['approval_eligible']=False
 return r

def freeze(data,output):
 from research_lab.data import load_snapshot
 snapshot=load_snapshot(data);last=snapshot['dates'][-1];year=date.fromisoformat(last).year
 manifest={'registeredAt':datetime.now(timezone.utc).isoformat(),'data':str(Path(data).resolve()),
  'dataThrough':last,'dailySha256':snapshot['snapshot_hash'],'manifestSha256':snapshot['manifest_hash'],
  'sources':{p.name:sha(p) for p in (Path(__file__),TOOLS/'team24_research.py')},'rules':RULES,
  'periods':[['recent',f'{year-2}-01-01',f'{year-1}-12-31'],['latest',f'{year}-01-01',last],['continuous','2008-01-01',last]],
  'costBps':[10,25],'candidate':'balanced_trend','riskPolicy':'recovery_vol','initialCash':10000,
  'historicalGate':{'annualizedPctMin':10,'maxDrawdownPctMax':10,'hardLatched':False,'pendingLiquidation':False},
  'forwardGate':{'newTradingDaysMin':60,'fillsMin':20,'positiveReturn':True,'baselineExcessPpMin':2,'maxDrawdownPctMax':10,'baselineDrawdownExcessPpMax':1,'hardLatched':False},
  'eligible':False,'forwardDays':0,'forwardFills':0,'notes':[
   'All48 planned cells, no result-driven optimization; known history is not unseen holdout.',
   'One policy family changed at a time; pause6 is a composite rest/re-entry policy.',
   'Market/vol gates block new entries only, preserve prior eligible basket on monthly decision.',
   'Pause6 re-entry rebuilds eligible basket and FULL at next open even off month; rearm DD<3%, final10%always independent.',
   'Final10%latch/cash/whole shares/T+1/fee/slippage/account peak unchanged.',
   'No operational configuration/source/allowlist change, no forward backfill or deployment.']}
 output=Path(output);output.mkdir(parents=True,exist_ok=False)
 (output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8');return manifest

def execute(output):
 from research_lab.data import load_snapshot
 output=Path(output);manifest=json.loads((output/'manifest.json').read_text())
 for name,h in manifest['sources'].items():
  if sha(TOOLS/name)!=h:raise ValueError('Frozen source changed')
 snapshot=load_snapshot(manifest['data'])
 if snapshot['snapshot_hash']!=manifest['dailySha256'] or snapshot['manifest_hash']!=manifest['manifestSha256']:raise ValueError('Frozen data changed')
 data=snapshot['data'];signals=indicators(data);runs=output/'runs';runs.mkdir(exist_ok=False);summary=[]
 for label,start,end in manifest['periods']:
  for cost in manifest['costBps']:
   for variant in RULES:
    r=simulate(data,start,end,variant,cost,signals);r['period']=label
    file=f'{label}__{variant}__{cost}bps.json'
    (runs/file).write_text(json.dumps(r,separators=(',',':'),allow_nan=False),encoding='utf-8')
    summary.append({k:v for k,v in r.items() if k not in ('history','trades')}|{'file':file})
 judgments=[]
 for variant in RULES:
  cells=[r for r in summary if r['research_variant']==variant]
  judgments.append({'variant':variant,'historicalPassed':len(cells)==6 and all(r['historical_cell_pass'] for r in cells),
   'passedCells':sum(r['historical_cell_pass'] for r in cells),'totalCells':len(cells),'forwardDays':0,'forwardFills':0,'eligible':False})
 for name,value in [('summary',summary),('judgments',judgments)]:
  (output/(name+'.json')).write_text(json.dumps(value,indent=2),encoding='utf-8')
 return judgments

if __name__=='__main__':
 import sys
 sys.path.insert(0,str(TOOLS))
 p=argparse.ArgumentParser();p.add_argument('action',choices=('freeze','run'));p.add_argument('--data');p.add_argument('--output',required=True);a=p.parse_args()
 print(json.dumps(freeze(a.data,a.output) if a.action=='freeze' else execute(a.output),indent=2))
