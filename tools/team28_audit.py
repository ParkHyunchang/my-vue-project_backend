"""Independent TEAM28 audit: direct-window signals and reconstructed cash/holdings.
No imports of the research implementation, broker or account configuration.
Accounting reconstruction is preserved from TEAM23; selection independently added.
"""
import argparse
import csv
import hashlib
import json
import math
from datetime import date
from pathlib import Path
from team23_audit import check, near, risk_budget
from team24_audit import verify_manifest

SECTORS = ('XLB','XLE','XLI','XLK','XLP','XLU','XLV','XLY')
BASE_SYMBOLS = ('SPY',)+SECTORS
BROAD_SYMBOLS = ('SPY','QQQ','GLD','IEF','IWM','EFA','EEM','TLT','DBC','VNQ','XLB','XLE','XLI','XLK','XLP','XLU','XLV','XLY')
POLICIES = ('cushion80','recovery_vol')
CANDIDATES = ('sector_breakout',)
RESEARCH_CANDIDATES = ('broad_rank_monthly','broad_pullback5','broad_breakout10','sector_pullback3','baseline_sector_breakout')


def indicator_cache(data, symbols, candidate):
    dates = sorted(data['SPY'])
    out = {s:{} for s in symbols}
    for s in symbols:
        p = [data[s][d]['adj_close'] for d in dates]
        for i in range(199,len(dates)):
            mean200 = sum(p[i-199:i+1])/200
            changes = (p[i]-p[i-1],p[i-1]-p[i-2])
            gains = sum(max(0,x) for x in changes)
            losses = sum(max(0,-x) for x in changes)
            returns = [p[j]/p[j-1]-1 for j in range(i-19,i+1)]
            mean = sum(returns)/20
            row = {'eligible':p[i]>mean200,'momentum':p[i]/p[i-126]-1,
                   'sma200':mean200,'sma5':sum(p[i-4:i+1])/5,
                   'prior20_high':max(p[i-20:i]),'prior10_low':min(p[i-10:i]),
                   'rsi2':50. if gains+losses==0 else 100*gains/(gains+losses),
                   'vol20':math.sqrt(sum((r-mean)**2 for r in returns)/19*252)}
            if candidate != 'baseline_sector_breakout':
                row['momentum63']=p[i]/p[i-63]-1
            out[s][dates[i]]=row
    return out


def choose(candidate, previous, qty, ages, signals, data, day, monthly):
    check(candidate in RESEARCH_CANDIDATES, 'candidate identity')
    if candidate == 'broad_rank_monthly':
        eligible = [s for s in BROAD_SYMBOLS if signals[s][day]['eligible'] and signals[s][day]['momentum63']>0]
        return sorted(eligible,key=lambda s:(-signals[s][day]['momentum63'],s))[:3] if monthly else [s for s in previous if s in eligible]
    pool = SECTORS if candidate in ('sector_pullback3','baseline_sector_breakout') else BROAD_SYMBOLS
    breakout = candidate in ('broad_breakout10','baseline_sector_breakout')
    survivors=[]
    entries=[]
    for s in sorted(pool):
        ind=signals[s][day]
        close=data[s][day]['adj_close']
        if qty[s]:
            exit_now=(close<ind['prior10_low'] or ages[s]>=10) if breakout else (close>ind['sma5'] or ages[s]>=(3 if candidate=='sector_pullback3' else 5) or close<ind['sma200'])
            if not exit_now:
                survivors.append(s)
        elif ind['eligible'] and (close>ind['prior20_high'] if breakout else ind['rsi2']<10):
            entries.append(s)
    entries.sort(key=lambda s:(-signals[s][day]['momentum'],s) if breakout else (signals[s][day]['rsi2'],s))
    return survivors+entries[:max(0,3-len(survivors))]


def read_data(folder):
    data={}
    with (Path(folder)/'daily.csv').open(encoding='utf-8',newline='') as f:
        for r in csv.DictReader(f):
            data.setdefault(r['symbol'],{})[r['date']]={k:v if k in ('date','symbol') else float(v) for k,v in r.items()}
    for s in BROAD_SYMBOLS:
        days=sorted(data[s])
        events=[(d,data[s][d]['split_numerator']/data[s][d]['split_denominator']) for d in days if data[s][d]['split_numerator'] != data[s][d]['split_denominator']]
        for d in days:
            factor=math.prod(r for when,r in events if when>d)
            for target,source in [('trade_open','open'),('trade_close','close'),('trade_dividend','dividend')]:
                data[s][d][target]=data[s][d][source]*factor
    return data


def audit(result, data, signals=None):
    """Reconstruct each balance and decision from CSV, with separate direct-window indicators."""
    candidate = result['research_candidate']
    SYMBOLS = BASE_SYMBOLS if candidate == 'baseline_sector_breakout' else BROAD_SYMBOLS
    check(result['candidate'] == 'sector_breakout', 'engine alias')
    check(result.get('universe') == list(SYMBOLS), 'universe and processing order')
    dates = sorted(data['SPY'])
    signals = indicator_cache(data, SYMBOLS, candidate) if signals is None else signals
    indices = {d: i for i, d in enumerate(dates)}
    ages = {s: 0 for s in SYMBOLS}
    expected_dates = [d for d in dates if result['start'] <= d <= result['end']]
    check([h['date'] for h in result['history']] == expected_dates, 'history calendar')
    cash = peak = float(result['initial'])
    dividend = unsettled = fee_total = slip_total = mdd = 0.0
    positions = {s: 0 for s in SYMBOLS}
    hard = False
    latch = pending = None
    basket = []
    ptr = 0
    fee = slip = result['cost_bps'] / 20000
    check(result['cost_bps'] in (10, 25), 'cost configuration')
    exposure_sum = max_target_gross = 0.0
    check(result['risk_policy'] in POLICIES, 'risk policy')
    check(result['candidate'] in CANDIDATES, 'candidate')

    def observe(prices, date, phase):
        nonlocal peak, mdd, hard, latch
        value = cash + unsettled + dividend + sum(positions[s] * prices[s] for s in SYMBOLS)
        peak = max(peak, value)
        dd = max(0, 1 - value / peak)
        mdd = max(mdd, dd)
        if not hard and dd >= .1 - 1e-12:
            hard, latch = True, {'date': date, 'phase': phase}
        return value, dd

    for n, h in enumerate(result['history']):
        d = h['date']
        i = indices[d]
        near(h['cash_before_settlement'], cash, d + ' beginning cash')
        near(h['settled_today'], unsettled, d + ' settlement')
        cash += unsettled
        unsettled = 0.0
        distribution = 0.0
        splits = {}
        op, cl = {}, {}
        for s in SYMBOLS:
            r = data[s][d]
            ratio = r['split_numerator'] / r['split_denominator']
            changed = positions[s] * ratio
            near(changed, round(changed), 'integer split entitlement')
            positions[s] = round(changed)
            if ratio != 1:
                splits[s] = ratio
            distribution += positions[s] * r['trade_dividend']
            op[s], cl[s] = r['trade_open'], r['trade_close']
            near(h['open'][s], op[s], 'source open')
            near(h['close'][s], cl[s], 'source close')
        check(h['splits'] == splits, 'split events')
        dividend += distribution
        value_open, dd_open = observe(op, d, 'open')
        near(h['open_equity'], value_open, 'opening equity')
        near(h['open_drawdown'], dd_open, 'opening drawdown')
        check(h['executed_target'] == pending, 'previous close target causality')
        weights = {} if hard else (pending['weights'] if pending else None)
        desired = {s: math.floor(weights.get(s, 0) * value_open / (op[s] * (1 + slip) * (1 + fee))) for s in SYMBOLS} if weights is not None else None
        expected_trades = []

        def transact(s, side, q):
            nonlocal cash, unsettled, fee_total, slip_total, ptr
            check(ptr < len(result['trades']), 'missing trade')
            t = result['trades'][ptr]
            ptr += 1
            check((t['date'], t['symbol'], t['side'], t['qty']) == (d, s, side, q), 'trade identity/order/quantity')
            check(isinstance(q, int) and q > 0, 'positive integer trade')
            price = op[s] * (1 + slip if side == 'BUY' else 1 - slip)
            charge = q * price * fee
            net = q * price + charge if side == 'BUY' else q * price - charge
            check(t['signal_date'] == (pending['date'] if pending else None), 'signal date')
            check(t['reason'] == ('HARD' if side == 'SELL' and hard else 'CLOSE_TARGET'), 'trade reason')
            if side == 'BUY':
                check(not hard and cash + 1e-7 >= net, 'cash or hard latch violated')
                cash -= net
                positions[s] += q
            else:
                check(positions[s] >= q, 'short sale')
                positions[s] -= q
                unsettled += net
            fee_total += charge
            slip_total += q * abs(price - op[s])
            for key, val in [('open', op[s]), ('price', price), ('fee', charge), ('net', net), ('cash_after', cash), ('unsettled_after', unsettled)]:
                near(t[key], val, 'trade ' + key)
            expected_trades.append(t)
            return net

        if desired is not None:
            for s in SYMBOLS:
                amount = max(0, positions[s] - desired[s])
                if amount:
                    transact(s, 'SELL', amount)
            observe(op, d, 'after_sells')
            budget = .99 * cash
            for s in sorted(SYMBOLS):
                if hard:
                    break
                amount = min(max(0, desired[s] - positions[s]), max(0, math.floor(budget / (op[s] * (1 + slip) * (1 + fee)))))
                if amount:
                    budget -= transact(s, 'BUY', amount)
                    observe(op, d, 'after_buy')
        value, dd = observe(cl, d, 'close')
        for s in SYMBOLS:
            ages[s] = ages[s] + 1 if positions[s] else 0
        check(h['holding_age'] == ages, 'actual holding age')
        for s in SYMBOLS:
            for key, expected in signals[s][d].items():
                actual = h['signal_snapshot'][s][key]
                if isinstance(expected, bool):
                    check(actual == expected, 'indicator ' + key)
                else:
                    near(actual, expected, 'indicator ' + key)
        vol = signals['SPY'][d]['vol20']
        near(h['market_vol20'], vol, 'market volatility')
        basket = choose(candidate, basket, positions, ages, signals, data, d, n == 0 or d[:7] != expected_dates[n-1][:7])
        gross = risk_budget(result['risk_policy'], value, peak, hard, vol)
        max_target_gross = max(max_target_gross, gross)
        divisor = 3
        pending = {'date': d, 'weights': {s: gross / divisor for s in basket}, 'gross': gross, 'basket': list(basket)}
        nt = h['next_target']
        check(nt['date'] == d and nt['basket'] == basket and set(nt['weights']) == set(pending['weights']), 'selection causality')
        near(nt['gross'], gross, 'risk gross')
        for s in basket:
            near(nt['weights'][s], pending['weights'][s], 'target weight')
        # Preserve JSON exact float for following target equality; independently checked above.
        pending = nt
        check(h['qty'] == positions and h['hard'] == hard, 'positions or hard latch')
        near(h['trade_count'], len(expected_trades), 'daily trade count')
        exposure = sum(positions[s] * cl[s] for s in SYMBOLS) / value
        exposure_sum += exposure
        for key, val in [('cash', cash), ('unsettled', unsettled), ('dividend_today', distribution), ('dividend_receivable', dividend), ('equity', value), ('peak', peak), ('drawdown', dd), ('fees_cumulative', fee_total), ('slippage_cumulative', slip_total), ('exposure', exposure)]:
            near(h[key], val, d + ' ' + key)
    check(ptr == len(result['trades']) == result['trade_count'], 'extra trades')
    check(result['hard_latch'] == latch, 'latch timestamp')
    check(result['pending_liquidation'] == (hard and any(positions.values())), 'pending liquidation')
    for key, val in [('final_equity', value), ('return_pct', (value / result['initial'] - 1) * 100), ('mdd_pct', mdd * 100), ('fees', fee_total), ('slippage', slip_total), ('mean_exposure', exposure_sum / len(expected_dates))]:
        near(result[key], val, 'summary ' + key)
    near(result['risk_formula_max_target_gross'], .8, 'formula cap')
    near(result['max_target_gross'], max_target_gross, 'observed max target')
    if 'benchmark' in result:
        levels = [data['^SP500TR'][d]['close'] for d in expected_dates]
        benchmark_return = (levels[-1] / levels[0] - 1) * 100
        near(result['benchmark']['return_pct'], benchmark_return, 'benchmark total return')
        near(result['excess_return_pp'], result['return_pct'] - benchmark_return, 'excess return')
        running_peak = levels[0]
        benchmark_dd = 0.0
        for level in levels:
            running_peak = max(running_peak, level)
            benchmark_dd = max(benchmark_dd, 1 - level / running_peak)
        near(result['benchmark']['mdd_pct'], benchmark_dd * 100, 'benchmark drawdown')
    return {'status': 'PASS', 'daily_states': len(expected_dates), 'trades': ptr}



def annualized(result):
    days=(date.fromisoformat(result['end'])-date.fromisoformat(result['start'])).days
    return ((result['final_equity']/result['initial'])**(365.2425/max(1,days))-1)*100


def gate(runs):
    check(len(runs)==6, 'six runs required without duplicates')
    check({(r['period'],r['cost_bps']) for r in runs}=={(p,c) for p in ('recent','latest','continuous') for c in (10,25)}, 'six unique cells required')
    return all(annualized(r)>=10 and r['mdd_pct']<=10 and not r['hard_latch'] and not r['pending_liquidation'] for r in runs)


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('data'); parser.add_argument('results')
    args=parser.parse_args(); folder=Path(args.results); source=Path(args.data)
    verified=verify_manifest(source)
    data=read_data(source)
    days=sorted(data['SPY'])
    check(all(sorted(data[s])==days for s in BROAD_SYMBOLS), 'same calendar, no fill')
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
    check(Path(manifest['snapshot']).resolve()==source.resolve(), 'frozen data path')
    check(manifest['dataSha256']==sha(source/'daily.csv'), 'frozen data hash')
    check(manifest['dataManifestSha256']==sha(source/'manifest.json'), 'frozen manifest hash')
    for name,expected in manifest['sources'].items():
        check(Path(name).name==name and sha(Path(__file__).parent/name)==expected, 'frozen implementation hash')
    check(manifest['universe']==list(BROAD_SYMBOLS), 'frozen universe')
    check(set(manifest['rules'])==set(RESEARCH_CANDIDATES)-{'baseline_sector_breakout'}, 'frozen candidates')
    check(manifest['historicalGate']=={'annualizedPctMin':10,'maxDrawdownPctMax':10,'hardLatched':False,'pendingLiquidation':False}, 'historical thresholds')
    check(manifest['forwardGate']=={'newTradingDaysMin':60,'fillsMin':20,'positiveReturn':True,'baselineExcessPpMin':2,'maxDrawdownPctMax':10,'baselineDrawdownExcessPpMax':1,'hardLatched':False}, 'forward thresholds')
    broad=indicator_cache(data,BROAD_SYMBOLS,'broad_rank_monthly')
    base=indicator_cache(data,BASE_SYMBOLS,'baseline_sector_breakout')
    reports={}; grouped={}; identities=set()
    for path in sorted((folder/'runs').glob('*bps.json')):
        result=json.loads(path.read_text())
        c=result['research_candidate']
        key=(result['period'],c,result['risk_policy'],result['cost_bps'])
        check(key not in identities, 'duplicate result'); identities.add(key)
        reports[path.name]=audit(result,data,base if c=='baseline_sector_breakout' else broad)|{'sha256':sha(path)}
        near(result['annualized_pct'],annualized(result),'CAGR')
        check(result['historical_cell_pass']==(annualized(result)>=10 and result['mdd_pct']<=10 and not result['hard_latch'] and not result['pending_liquidation']), 'cell gate')
        grouped.setdefault((c,result['risk_policy']),[]).append(result)
    check(identities=={(p,c,r,b) for p in ('recent','latest','continuous') for c in RESEARCH_CANDIDATES for r in POLICIES for b in (10,25)}, 'exact60 matrix')
    gates={c+':'+p:gate(rs) for (c,p),rs in grouped.items()}
    summaries=json.loads((folder/'summary.json').read_text())
    check(len(summaries)==60, 'summary count')
    seen=set()
    for summary in summaries:
        name=summary['file']; check(name in reports and name not in seen,'summary identity'); seen.add(name)
        source_result=json.loads((folder/'runs'/name).read_text())
        check(summary=={k:v for k,v in source_result.items() if k not in ('history','trades')}|{'file':name},'summary exact match')
    judgments=json.loads((folder/'judgments.json').read_text()); check(len(judgments)==10,'judgment count')
    seen=set()
    for j in judgments:
        key=j['candidate']+':'+j['policy']; check(key in gates and key not in seen,'judgment identity'); seen.add(key)
        rs=grouped[(j['candidate'],j['policy'])]
        check(j['historicalPassed']==gates[key] and j['totalCells']==6 and j['passedCells']==sum(r['historical_cell_pass'] for r in rs),'judgment accuracy')
        check(j['forwardDays']==0 and j['forwardFills']==0 and j['eligible'] is False,'forward cannot be backfilled')
    output={'status':'PASS','run_count':len(reports),'verified_source_files':verified,
            'daily_states':sum(r['daily_states'] for r in reports.values()),'trades':sum(r['trades'] for r in reports.values()),
            'historical_gate':gates,'approval_eligible_count':0,'forward_observation':'0; not retrospectively populated',
            'runs':reports,'audit_source_sha256':sha(__file__)}
    (folder/'independent-audit.json').write_text(json.dumps(output,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in output.items() if k!='runs'}))


if __name__=='__main__':
    main()
