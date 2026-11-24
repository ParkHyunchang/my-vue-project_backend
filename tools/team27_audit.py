"""Independent TEAM27 signals and approval audit over the existing ledger auditor.

Does not import TEAM27 implementation, broker, account, or runtime configuration.
"""
import argparse
import hashlib
import json
from datetime import date
from pathlib import Path

import team24_audit as ledger

CANDIDATES = {'fast63': 'rotation1', 'multi_horizon': 'rotation1',
              'dual_canary': 'rotation1', 'breadth_defensive': 'riskrank2',
              'balanced_trend': 'balanced_trend'}


def indicator_cache(data, candidate):
    ledger.check(candidate in CANDIDATES, 'unknown research candidate')
    out = ledger.indicator_cache(data)
    if candidate == 'balanced_trend':
        return out
    dates = sorted(data['SPY'])
    prices = {s: [data[s][d]['adj_close'] for d in dates] for s in ledger.SYMBOLS}
    for i in range(199, len(dates)):
        d = dates[i]
        metrics = {}
        for s in ledger.SYMBOLS:
            p = prices[s]
            if i < 252:
                metrics[s] = (0., 0., 0.)
            else:
                m = {n: p[i] / p[i-n] - 1 for n in (21, 63, 126, 252)}
                metrics[s] = (m[63], (12*m[21]+4*m[63]+2*m[126]+m[252])/19,
                              (m[63]+m[126]+m[252])/3)
        canary = metrics['SPY'][1] > 0 and metrics['IEF'][1] > 0
        breadth = metrics['SPY'][1] > 0 and metrics['QQQ'][1] > 0
        for s in ledger.SYMBOLS:
            original = out[s][d]
            p = prices[s]
            m63, fast, slow = metrics[s]
            if candidate == 'fast63':
                score = m63
                eligible = p[i] > sum(p[i-62:i+1])/63 and score > 0
            elif candidate == 'multi_horizon':
                score = fast
                eligible = original['eligible'] and score > 0
            elif candidate == 'dual_canary':
                score = slow if canary else fast
                eligible = s in (('SPY', 'QQQ') if canary else ('GLD', 'IEF')) and score > 0
            else:
                score = fast
                eligible = (breadth or s in ('GLD', 'IEF')) and score > 0
            original.update(eligible=bool(i >= 252 and eligible), momentum=score,
                            riskrank=score/max(.05, original['vol63']))
    return out


def audit(result, data, signals=None):
    candidate = result['research_candidate']
    ledger.check(candidate in CANDIDATES, 'research identity')
    ledger.check(result['candidate'] == CANDIDATES[candidate], 'base engine identity')
    return ledger.audit(result, data, indicator_cache(data, candidate) if signals is None else signals)


def annualized(result):
    days = (date.fromisoformat(result['end'])-date.fromisoformat(result['start'])).days
    return ((result['final_equity']/result['initial'])**(365.2425/max(1, days))-1)*100


def gate(runs):
    ledger.check(len(runs) == 6, 'exact six gate runs without duplicates')
    required = {'recent', 'latest', 'continuous'}
    aliases = {'2024_2025': 'recent', '2026_ytd': 'latest'}
    selected = {(aliases.get(r['period'], r['period']), r['cost_bps']): r
                for r in runs if aliases.get(r['period'], r['period']) in required}
    ledger.check(set(selected) == {(p,c) for p in required for c in (10,25)}, 'exact six gate cells')
    return all(annualized(r) >= 10 and r['mdd_pct'] <= 10 and not r['hard_latch']
               and not r['pending_liquidation'] for r in selected.values())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('data')
    parser.add_argument('results')
    args = parser.parse_args()
    verified = ledger.verify_manifest(args.data)
    data = ledger.read_data(args.data)
    folder = Path(args.results)
    manifest = json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    ledger.check(Path(manifest['snapshot']).resolve() == Path(args.data).resolve(), 'frozen snapshot path')
    ledger.check(manifest['dailySha256'] == hashlib.sha256((Path(args.data)/'daily.csv').read_bytes()).hexdigest(), 'frozen daily hash')
    ledger.check(manifest['manifestSha256'] == hashlib.sha256((Path(args.data)/'manifest.json').read_bytes()).hexdigest(), 'frozen manifest hash')
    for name, expected in manifest['sources'].items():
        ledger.check(Path(name).name == name, 'source filename')
        ledger.check(hashlib.sha256((Path(__file__).parent/name).read_bytes()).hexdigest() == expected, 'frozen source hash')
    ledger.check(set(manifest['candidates']) == set(CANDIDATES)-{'balanced_trend'}, 'frozen candidate list')
    ledger.check(manifest['historicalGate'] == {'annualizedPctMin':10,'maxDrawdownPctMax':10,'hardLatched':False,'pendingLiquidation':False}, 'historical thresholds unchanged')
    ledger.check(manifest['forwardGate'] == {'newTradingDaysMin':60,'fillsMin':20,'returnPositive':True,'baselineExcessPpMin':2,'maxDrawdownPctMax':10,'baselineDrawdownExcessPpMax':1,'hardLatched':False}, 'forward thresholds unchanged')
    reports, grouped, identities = {}, {}, set()
    caches = {c: indicator_cache(data, c) for c in CANDIDATES}
    for path in sorted((folder/'runs').glob('*bps.json')):
        result = json.loads(path.read_text(encoding='utf-8'))
        c = result['research_candidate']
        key = (result['period'], c, result['risk_policy'], result['cost_bps'])
        ledger.check(key not in identities, 'duplicate run')
        identities.add(key)
        reports[path.name] = audit(result, data, caches[c]) | {'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        ledger.near(result['annualized_pct'], annualized(result), 'annualized return')
        cell_pass = annualized(result) >= 10 and result['mdd_pct'] <= 10 and not result['hard_latch'] and not result['pending_liquidation']
        ledger.check(result['historical_cell_pass'] == cell_pass, 'historical cell gate')
        grouped.setdefault((c, result['risk_policy']), []).append(result)
    ledger.check(len(grouped) == 10, 'all ten candidate/policy combinations required')
    periods = {'recent','latest','continuous'}
    ledger.check(identities == {(p,c,r,b) for p in periods for c in CANDIDATES
                               for r in ledger.POLICIES for b in (10,25)}, 'exact 60 run matrix')
    gates = {c+':'+p: gate(rs) for (c,p),rs in grouped.items()}
    summaries = json.loads((folder/'summary.json').read_text(encoding='utf-8'))
    ledger.check(len(summaries) == len(reports), 'summary count')
    seen = set()
    for summary in summaries:
        filename = summary['file']
        ledger.check(filename in reports and filename not in seen, 'summary identity')
        seen.add(filename)
        source = json.loads((folder/'runs'/filename).read_text(encoding='utf-8'))
        ledger.check(summary == {k:v for k,v in source.items() if k not in ('history','trades')} | {'file':filename}, 'summary mismatch')
    judgments = json.loads((folder/'judgments.json').read_text(encoding='utf-8'))
    ledger.check(len(judgments) == 10, 'judgment count')
    seen = set()
    for j in judgments:
        key = j['candidate']+':'+j['policy']
        ledger.check(key in gates and key not in seen, 'judgment identity')
        seen.add(key)
        rs = grouped[(j['candidate'],j['policy'])]
        ledger.check(j['historicalPassed'] == gates[key] and j['totalCells'] == 6 and j['passedCells'] == sum(r['historical_cell_pass'] for r in rs), 'judgment gate')
        ledger.check(j['forwardDays'] == 0 and j['forwardFills'] == 0 and j['eligible'] is False, 'forward evidence cannot be backfilled')
    output = {'status':'PASS', 'run_count':len(reports), 'verified_source_files':verified,
              'historical_gate':gates, 'approval_eligible_count':0,
              'forward_observation':'Not collected; historical runs cannot satisfy forward requirements.',
              'daily_states':sum(r['daily_states'] for r in reports.values()),
              'trades':sum(r['trades'] for r in reports.values()), 'runs':reports,
              'audit_source_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (folder/'independent-audit.json').write_text(json.dumps(output, indent=2), encoding='utf-8')
    print(json.dumps({k:v for k,v in output.items() if k != 'runs'}))


if __name__ == '__main__':
    main()
