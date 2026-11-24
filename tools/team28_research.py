"""Predeclared expanded ETF and short swing research, never a live strategy.

An isolated instance of the unchanged TEAM23 ledger handles every transaction.
Only universe and completed-close selection functions are replaced. RSI2 is a
simple two-change ratio, not Wilder RSI. Daily target rebalancing costs remain.
"""
import argparse
import hashlib
import importlib.util
import json
from datetime import date, datetime, timezone
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
SYMBOLS = ('SPY','QQQ','GLD','IEF','IWM','EFA','EEM','TLT','DBC','VNQ',
           'XLB','XLE','XLI','XLK','XLP','XLU','XLV','XLY')
SECTORS = ('XLB','XLE','XLI','XLK','XLP','XLU','XLV','XLY')
RULES = {
    'broad_rank_monthly': '18 ETFs: positive63 momentum above SMA200, monthly top3 by momentum63/symbol; daily remove ineligible, no daily new entries.',
    'broad_pullback5': '18 ETFs: above SMA200 and simple2changeRSI<10, lowestRSI/symbol into empty3slots. Exit close>SMA5, age>=5 or close<SMA200.',
    'broad_breakout10': '18 ETFs: close>prior20closeHigh and aboveSMA200, top126momentum/symbol into empty3slots. Exit close<prior10closeLow or age>=10.',
    'sector_pullback3': '8 sectors only: same pullback rule, age>=3 exit. All entries/exits use completed close then next open.',
}
BASELINE = 'baseline_sector_breakout'


def isolated_engine():
    spec = importlib.util.spec_from_file_location('team28_private_ledger', TOOLS/'team23_research.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def selection(strategy, prior, qty, ages, signals, closes, monthly):
    if strategy not in RULES:
        raise ValueError('Unknown frozen strategy')
    universe = SECTORS if strategy == 'sector_pullback3' else SYMBOLS
    if strategy == 'broad_rank_monthly':
        eligible = lambda s: signals[s]['eligible'] and signals[s]['momentum63'] > 0
        if monthly:
            return sorted((s for s in universe if eligible(s)),
                          key=lambda s: (-signals[s]['momentum63'],s))[:3]
        return [s for s in prior if eligible(s)]
    held = {s for s in universe if qty[s] > 0}
    if strategy == 'broad_breakout10':
        survivors = sorted(s for s in held if not (closes[s] < signals[s]['prior10_low'] or ages[s] >= 10))
        entries = sorted((s for s in universe if s not in held and signals[s]['eligible']
                          and closes[s] > signals[s]['prior20_high']),
                         key=lambda s: (-signals[s]['momentum'],s))
    else:
        limit = 3 if strategy == 'sector_pullback3' else 5
        survivors = sorted(s for s in held if not (closes[s] > signals[s]['sma5']
                           or ages[s] >= limit or closes[s] < signals[s]['sma200']))
        entries = sorted((s for s in universe if s not in held and signals[s]['eligible']
                          and signals[s]['rsi2'] < 10), key=lambda s:(signals[s]['rsi2'],s))
    return survivors + entries[:max(0,3-len(survivors))]


def engine_for(strategy):
    module = isolated_engine()
    if strategy == BASELINE:
        return module
    if strategy not in RULES:
        raise ValueError('Unknown frozen strategy')
    module.SYMBOLS = SYMBOLS
    module.SECTORS = SYMBOLS
    module.select_basket = lambda candidate,prior,qty,ages,signals,closes,monthly: selection(
        strategy,prior,qty,ages,signals,closes,monthly)
    return module


def indicators(data, module):
    output = module.indicators(data)
    if tuple(module.SYMBOLS) != SYMBOLS:
        return output
    for s in module.SYMBOLS:
        prices = [r['adj_close'] for r in data[s]]
        for i,row in enumerate(output[s]):
            row['momentum63'] = prices[i]/prices[i-63]-1 if i>=63 else None
    return output


def annualized(result):
    days=(date.fromisoformat(result['end'])-date.fromisoformat(result['start'])).days
    return ((result['final_equity']/result['initial'])**(365.2425/max(1,days))-1)*100


def simulate(data, start, end, strategy, cost=25, policy='recovery_vol', signals=None):
    module=engine_for(strategy)
    result=module.simulate(data,start,end,'sector_breakout',cost,signals=signals or indicators(data,module),risk_policy=policy)
    result.update(research_candidate=strategy,engine_alias='sector_breakout',universe=list(module.SYMBOLS))
    result['annualized_pct']=annualized(result)
    result['historical_cell_pass']=(result['annualized_pct']>=10 and result['mdd_pct']<=10
                                   and not result['hard_latch'] and not result['pending_liquidation'])
    return result


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_data(folder):
    """Verify every pinned file before reading; no account, key or network input."""
    folder=Path(folder)
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('status')!='complete':
        raise ValueError('Incomplete data manifest')
    for entry in manifest['files']:
        for key in ('raw','csv'):
            name=entry[key+'_file']
            if Path(name).name!=name or (folder/name).is_symlink() or sha(folder/name)!=entry[key+'_sha256']:
                raise ValueError('Invalid source data hash/path')
    if sha(folder/'daily.csv')!=manifest['daily_sha256']:
        raise ValueError('Invalid combined data hash')
    module=engine_for('broad_rank_monthly')
    data,benchmark=module.load_data(folder)
    if data['SPY'][-1]['date'] >= datetime.now(timezone.utc).date().isoformat():
        raise ValueError('Unfinished UTC day')
    return data,benchmark,manifest


def freeze(folder, output):
    data,_,market=load_data(folder)
    latest=data['SPY'][-1]['date']; year=date.fromisoformat(latest).year
    manifest={'registeredAt':datetime.now(timezone.utc).isoformat(),
              'snapshot':str(Path(folder).resolve()),'dataThrough':latest,
              'dataSha256':sha(Path(folder)/'daily.csv'),'dataManifestSha256':sha(Path(folder)/'manifest.json'),
              'sources':{p.name:sha(p) for p in (Path(__file__),TOOLS/'team23_research.py')},
              'universe':list(SYMBOLS),'rules':RULES,'baseline':BASELINE,
              'periods':[['recent',f'{year-2}-01-01',f'{year-1}-12-31'],['latest',f'{year}-01-01',latest],['continuous','2008-01-01',latest]],
              'policies':['cushion80','recovery_vol'],'costBps':[10,25],'initialCash':10000,
              'historicalGate':{'annualizedPctMin':10,'maxDrawdownPctMax':10,'hardLatched':False,'pendingLiquidation':False},
              'forwardGate':{'newTradingDaysMin':60,'fillsMin':20,'positiveReturn':True,'baselineExcessPpMin':2,'maxDrawdownPctMax':10,'baselineDrawdownExcessPpMax':1,'hardLatched':False},
              'forwardDays':0,'forwardFills':0,'eligible':False,
              'notes':['Rules frozen before results; all48 new and12 baseline runs reported.',
                       'Simple two-change RSI, not Wilder. Holding age starts1 on actual buy day close; exits next open.',
                       'Every day target quantities are recomputed; all rebalancing transaction costs included.',
                       'An exit cannot re-enter the same symbol at the same signal close.',
                       'Daily-bar swing research, not intraday quote/fill verification.',
                       'Historical selection of surviving ETFs, not point-in-time constituents or an untouched holdout.',
                       'Cash-only whole shares, prior-close signals, next-open orders, T+1 proceeds, permanent10% latch.',
                       'No live/pinned source change, approval, forward backfill, key or deployment.']}
    output=Path(output); output.mkdir(parents=True,exist_ok=False)
    (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    return manifest


def execute(output):
    output=Path(output); manifest=json.loads((output/'manifest.json').read_text())
    for p in (Path(__file__),TOOLS/'team23_research.py'):
        if sha(p)!=manifest['sources'][p.name]: raise ValueError('Frozen source changed')
    folder=Path(manifest['snapshot'])
    if sha(folder/'daily.csv')!=manifest['dataSha256'] or sha(folder/'manifest.json')!=manifest['dataManifestSha256']:
        raise ValueError('Frozen data changed')
    data,index,_=load_data(folder)
    runs=output/'runs'; runs.mkdir(exist_ok=False)
    cached=indicators(data,engine_for('broad_rank_monthly'))
    baseline_signals=indicators(data,engine_for(BASELINE))
    summary=[]
    for period,start,end in manifest['periods']:
        for policy in manifest['policies']:
            for cost in manifest['costBps']:
                for strategy in list(RULES)+[BASELINE]:
                    result=simulate(data,start,end,strategy,cost,policy,baseline_signals if strategy==BASELINE else cached)
                    result['period']=period
                    result['benchmark']=isolated_engine().benchmark_result(index,start,end)
                    result['excess_return_pp']=result['return_pct']-result['benchmark']['return_pct']
                    filename=f'{period}__{strategy}__{policy}__{cost}bps.json'
                    (runs/filename).write_text(json.dumps(result,separators=(',',':'),allow_nan=False),encoding='utf-8')
                    summary.append({k:v for k,v in result.items() if k not in ('history','trades')}|{'file':filename})
    judgments=[]
    for strategy in list(RULES)+[BASELINE]:
        for policy in manifest['policies']:
            cells=[r for r in summary if r['research_candidate']==strategy and r['risk_policy']==policy]
            judgments.append({'candidate':strategy,'policy':policy,'historicalPassed':len(cells)==6 and all(r['historical_cell_pass'] for r in cells),
                              'passedCells':sum(r['historical_cell_pass'] for r in cells),'totalCells':len(cells),
                              'forwardDays':0,'forwardFills':0,'eligible':False})
    for name,value in [('summary',summary),('judgments',judgments)]:
        (output/(name+'.json')).write_text(json.dumps(value,indent=2,allow_nan=False),encoding='utf-8')
    return judgments


if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('action',choices=('freeze','run'))
    parser.add_argument('--output',required=True); parser.add_argument('--data')
    args=parser.parse_args()
    if args.action=='freeze' and not args.data: parser.error('--data required')
    print(json.dumps(freeze(args.data,args.output) if args.action=='freeze' else execute(args.output),indent=2))
