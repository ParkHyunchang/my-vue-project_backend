"""Offline, predeclared four-ETF adaptations; not replications of published systems.

Uses the unchanged TEAM24 cash/settlement/10% latch ledger. No live imports.
"""
import argparse
import hashlib
import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import team24_research as engine
from research_lab.data import load_snapshot

ALIASES = {'fast63': 'rotation1', 'multi_horizon': 'rotation1',
           'dual_canary': 'rotation1', 'breadth_defensive': 'riskrank2'}
RULES = {
    'fast63': 'Top1 positive 63-session momentum above SMA63.',
    'multi_horizon': 'Top1 fast momentum above SMA200; fast=(12*r21+4*r63+2*r126+r252)/19.',
    'dual_canary': 'If SPY and IEF fast>0, top1 SPY/QQQ slow=(r63+r126+r252)/3>0; else top1 GLD/IEF fast>0. No SMA filter.',
    'breadth_defensive': 'If SPY and QQQ fast>0, top2 positive fast/vol63 of all4; else GLD/IEF only. Empty slots stay cash.',
}


def signals_for(data, candidate, base=None):
    if candidate not in ALIASES:
        raise ValueError('Unknown predeclared candidate')
    base = base or engine.indicators(data)
    prices = {s: [r['adj_close'] for r in data[s]] for s in engine.SYMBOLS}
    output = {s: [] for s in engine.SYMBOLS}
    for i in range(len(data['SPY'])):
        fast, slow, m63 = {}, {}, {}
        for s in engine.SYMBOLS:
            p = prices[s]
            returns = {n: p[i]/p[i-n]-1 if i >= n else 0.0 for n in (21,63,126,252)}
            fast[s] = sum(w*returns[n] for n,w in ((21,12),(63,4),(126,2),(252,1)))/19
            slow[s] = sum(returns[n] for n in (63,126,252))/3
            m63[s] = returns[63]
        canary = fast['SPY'] > 0 and fast['IEF'] > 0
        breadth = fast['SPY'] > 0 and fast['QQQ'] > 0
        for s in engine.SYMBOLS:
            row = dict(base[s][i])
            p = prices[s]
            if candidate == 'fast63':
                score, allowed = m63[s], i >= 62 and p[i] > sum(p[i-62:i+1])/63
            elif candidate == 'multi_horizon':
                score, allowed = fast[s], row['eligible']
            elif candidate == 'dual_canary':
                score = slow[s] if canary else fast[s]
                allowed = s in (('SPY','QQQ') if canary else ('GLD','IEF'))
            else:
                score = fast[s]
                allowed = breadth or s in ('GLD','IEF')
            row['eligible'] = bool(i >= 252 and allowed and score > 0)
            row['momentum'] = score if i >= 252 else 0.0
            row['riskrank'] = row['momentum']/max(.05,row['vol63'] or .05)
            output[s].append(row)
    return output


def run(data, candidate, start, end, cost, policy, signals=None):
    result = engine.simulate(data, start, end, ALIASES[candidate], cost,
                             signals=signals or signals_for(data,candidate), risk_policy=policy)
    result['research_candidate'] = candidate
    result['engine_alias'] = ALIASES[candidate]
    return result


def source_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def freeze(folder, output):
    """Freeze before calculating any performance; output directory must be new."""
    market = load_snapshot(folder)
    latest = market['dates'][-1]
    year = date.fromisoformat(latest).year
    manifest = {'registeredAt': datetime.now(timezone.utc).isoformat(),
                'dataThrough': latest, 'snapshot': str(Path(folder).resolve()),
                'dailySha256': market['snapshot_hash'], 'manifestSha256': market['manifest_hash'],
                'sources': {p.name:source_hash(p) for p in (Path(__file__),Path(engine.__file__))},
                'candidates':RULES, 'aliases':ALIASES, 'policies':list(engine.RISK_POLICIES),
                'costBps':[10,25], 'initialCash':10000,
                'periods':[['recent',f'{year-2}-01-01',f'{year-1}-12-31'],
                           ['latest',f'{year}-01-01',latest],['continuous','2008-01-01',latest]],
                'historicalGate':{'annualizedPctMin':10,'maxDrawdownPctMax':10,'hardLatched':False,'pendingLiquidation':False},
                'forwardGate':{'newTradingDaysMin':60,'fillsMin':20,'returnPositive':True,'baselineExcessPpMin':2,'maxDrawdownPctMax':10,'baselineDrawdownExcessPpMax':1,'hardLatched':False},
                'forwardDays':0, 'forwardFills':0, 'eligible':False,
                'notes':['All runs reported; no parameter optimization or result-driven rule change.',
                         'Known historical periods are retrospective, not an untouched out-of-sample holdout.',
                         'No live catalog registration, source pin changes, signatures, approval or deployment.',
                         'Monthly entries, daily removals/reduction, one T+1 FUND follow unchanged engine.',
                         'Adaptations limited to four ETFs, not published VAA/BAA/PAA replications.']}
    output = Path(output)
    output.mkdir(parents=True,exist_ok=False)
    (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    return manifest


def execute(output):
    output = Path(output)
    manifest = json.loads((output/'manifest.json').read_text(encoding='utf-8'))
    for p in (Path(__file__),Path(engine.__file__)):
        if source_hash(p) != manifest['sources'][p.name]:
            raise ValueError('Frozen source changed')
    if (output/'runs').exists():
        raise ValueError('Existing results must not be overwritten')
    market = load_snapshot(manifest['snapshot'])
    if market['snapshot_hash'] != manifest['dailySha256'] or market['manifest_hash'] != manifest['manifestSha256']:
        raise ValueError('Frozen market changed')
    data = market['data']
    base = engine.indicators(data)
    signals = {c:signals_for(data,c,base) for c in ALIASES}
    runs = output/'runs'
    runs.mkdir()
    summary = []
    for label,start,end in manifest['periods']:
        for policy in manifest['policies']:
            for cost in manifest['costBps']:
                for candidate in list(ALIASES)+['balanced_trend']:
                    result = (run(data,candidate,start,end,cost,policy,signals[candidate]) if candidate in ALIASES
                              else engine.simulate(data,start,end,candidate,cost,signals=base,risk_policy=policy))
                    result['research_candidate'] = candidate
                    result['engine_alias'] = result['candidate']
                    result['period'] = label
                    days = (date.fromisoformat(result['end'])-date.fromisoformat(result['start'])).days
                    result['annualized_pct'] = ((result['final_equity']/result['initial'])**(365.2425/max(1,days))-1)*100
                    result['historical_cell_pass'] = result['annualized_pct']>=10 and result['mdd_pct']<=10 and not result['hard_latch'] and not result['pending_liquidation']
                    result['benchmark'] = engine.benchmark_result(market['benchmark'],start,end)
                    result['excess_return_pp'] = result['return_pct'] - result['benchmark']['return_pct']
                    filename = f'{label}__{candidate}__{policy}__{cost}bps.json'
                    (runs/filename).write_text(json.dumps(result,separators=(',',':'),allow_nan=False),encoding='utf-8')
                    summary.append({k:v for k,v in result.items() if k not in ('history','trades')} | {'file':filename})
    (output/'summary.json').write_text(json.dumps(summary,indent=2,allow_nan=False),encoding='utf-8')
    judgments = []
    for candidate in list(ALIASES)+['balanced_trend']:
        for policy in manifest['policies']:
            cells = [r for r in summary if r['research_candidate']==candidate and r['risk_policy']==policy]
            judgments.append({'candidate':candidate,'policy':policy,'historicalPassed':all(r['historical_cell_pass'] for r in cells),
                              'passedCells':sum(r['historical_cell_pass'] for r in cells),'totalCells':len(cells),
                              'forwardDays':0,'forwardFills':0,'eligible':False})
    (output/'judgments.json').write_text(json.dumps(judgments,indent=2),encoding='utf-8')
    return judgments


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action',choices=('freeze','run'))
    parser.add_argument('--output',required=True)
    parser.add_argument('--data')
    args = parser.parse_args()
    if args.action == 'freeze':
        if not args.data:
            parser.error('--data required for freeze')
        print(json.dumps(freeze(args.data,args.output),indent=2))
    else:
        print(json.dumps(execute(args.output),indent=2))
