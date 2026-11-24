"""Independent filter ablation audit; cash ledger reconstruction retained from TEAM24.
Never imports TEAM30 strategy or live code.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
from datetime import datetime, timezone, date

SYMBOLS = ('SPY', 'QQQ', 'GLD', 'IEF')
BASE_WEIGHTS = {'SPY': .4, 'QQQ': .2, 'GLD': .2, 'IEF': .2}
POLICIES = ('cushion80', 'recovery_vol')
CANDIDATES = ('balanced_trend',)
VARIANTS = ('baseline','remove_sma','market_entry','vol_entry','monthly_exit','daily_entry','cap40','pause6')


def risk_budget(policy, value, peak, hard, vol):
    check(policy in POLICIES, 'risk policy')
    if hard:
        return 0.0
    if policy == 'cushion80':
        return min(.8, 8 * max(0, value - .9 * peak) / value)
    check(math.isfinite(vol) and vol >= 0, 'volatility')
    target = min(.8, max(.2, .10 / max(.05, vol)))
    if value <= .92 * peak:
        target = min(target, .2)
    elif value <= .95 * peak:
        target = min(target, .4)
    return target


def indicator_cache(data):
    """Independent direct windows and sample variance, no engine import."""
    dates = sorted(data['SPY'])
    out = {s: {} for s in SYMBOLS}
    for s in SYMBOLS:
        prices = [data[s][d]['adj_close'] for d in dates]
        for i in range(199, len(dates)):
            sma = sum(prices[i-199:i+1]) / 200
            vols = {}
            for window in (20, 63):
                rs = [prices[j]/prices[j-1]-1 for j in range(i-window+1,i+1)]
                mean = sum(rs)/window
                vols[window] = math.sqrt(sum((r-mean)**2 for r in rs)/(window-1)*252)
            out[s][dates[i]] = {'eligible': prices[i] > sma,
                'momentum': prices[i]/prices[i-126]-1, 'sma200': sma,
                'vol20': vols[20], 'vol63': vols[63], 'riskrank': (prices[i]/prices[i-126]-1)/max(.05,vols[63]),
                'sma200_lag20':sum(prices[i-219:i-19])/200 if i>=219 else None}
            out[s][dates[i]]['market_up']=bool(i>=219 and prices[i]>sma and sma>out[s][dates[i]]['sma200_lag20'])
    return out


def check(ok, message):
    if not ok:
        raise AssertionError(message)


def near(actual, expected, name):
    check(isinstance(actual, (int, float)) and math.isfinite(actual), name + ' nonfinite')
    check(abs(actual - expected) <= 1e-6 * max(1, abs(expected)), f'{name}: {actual} != {expected}')


def read_data(folder):
    data = {}
    with (Path(folder) / 'daily.csv').open(encoding='utf-8', newline='') as f:
        for r in csv.DictReader(f):
            data.setdefault(r['symbol'], {})[r['date']] = {k: (v if k in ('date', 'symbol') else float(v)) for k, v in r.items()}
    for symbol in SYMBOLS:
        days = sorted(data[symbol])
        actions = [(d, data[symbol][d]['split_numerator'] / data[symbol][d]['split_denominator']) for d in days if data[symbol][d]['split_numerator'] != data[symbol][d]['split_denominator']]
        for d in days:
            factor = math.prod(ratio for action_date, ratio in actions if action_date > d)
            for target, source in [('trade_open', 'open'), ('trade_close', 'close'), ('trade_dividend', 'dividend')]:
                data[symbol][d][target] = data[symbol][d][source] * factor
    return data


def verify_manifest(folder):
    folder = Path(folder)
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    def verify(name, expected):
        actual = hashlib.sha256((folder / name).read_bytes()).hexdigest()
        check(actual == expected, 'data hash mismatch: ' + name)
    normalized = {}
    normalized_count = 0
    for entry in manifest['files']:
        verify(entry['raw_file'], entry['raw_sha256'])
        verify(entry['csv_file'], entry['csv_sha256'])
        # Independently verify normalization, not only the converter's own hashes.
        raw = json.loads((folder / entry['raw_file']).read_text(encoding='utf-8'))['chart']['result'][0]
        check(raw['meta']['symbol'] == entry['symbol'], 'raw symbol')
        quote = raw['indicators']['quote'][0]
        adjusted = raw['indicators']['adjclose'][0]['adjclose']
        events = raw.get('events', {})
        day = lambda stamp: datetime.fromtimestamp(stamp, timezone.utc).date().isoformat()
        dividends = {day(v['date']): v['amount'] for v in events.get('dividends', {}).values()}
        splits = {day(v['date']): (v['numerator'], v['denominator']) for v in events.get('splits', {}).values()}
        with (folder / entry['csv_file']).open(encoding='utf-8', newline='') as stream:
            rows = list(csv.DictReader(stream))
        check(len(rows) == len(raw['timestamp']), 'raw/normalized row count')
        normalized_count += len(rows)
        normalized.update({(r['date'], r['symbol']): r for r in rows})
        for i, row in enumerate(rows):
            date_key = day(raw['timestamp'][i])
            check(row['date'] == date_key and row['symbol'] == entry['symbol'], 'raw row identity')
            for key in ('open', 'high', 'low', 'close', 'volume'):
                check(float(row[key]) == quote[key][i], 'raw normalization ' + key)
            check(float(row['adj_close']) == adjusted[i], 'raw adjusted close')
            check(float(row['adjustment_factor']) == adjusted[i]/quote['close'][i], 'raw adjustment factor')
            check(float(row['dividend']) == dividends.get(date_key, 0), 'raw dividend')
            split = splits.get(date_key, (1, 1))
            check(float(row['split_numerator']) == split[0] and float(row['split_denominator']) == split[1], 'raw split')
    verify(manifest['daily_file'], manifest['daily_sha256'])
    with (folder / manifest['daily_file']).open(encoding='utf-8', newline='') as stream:
        combined = list(csv.DictReader(stream))
    check(len(combined) == normalized_count == len(normalized), 'combined row count')
    check({(r['date'], r['symbol']): r for r in combined} == normalized, 'combined normalization mismatch')
    return 1 + 2 * len(manifest['files'])


def audit(result, data, signals=None):
    """Reconstruct each balance and decision from CSV, with separate direct-window indicators."""
    dates = sorted(data['SPY'])
    signals = indicator_cache(data) if signals is None else signals
    indices = {d: i for i, d in enumerate(dates)}
    reference = None
    variant=result['research_variant'];check(variant in VARIANTS,'variant')
    paused=False;armed=True;pause_day=None;up_streak=0
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
        mode = 'HARD' if hard else (pending['mode'] if pending else 'HOLD')
        check(h['execution_mode'] == mode, 'execution mode')
        weights = pending['weights'] if pending else {}
        desired = dict(positions)
        if mode == 'HARD':
            desired = {s: 0 for s in SYMBOLS}
        elif mode in ('FULL', 'REDUCE'):
            planned = {s: math.floor(weights.get(s, 0)*value_open/(op[s]*(1+slip)*(1+fee))) for s in SYMBOLS}
            desired = planned if mode == 'FULL' else {s: min(positions[s], planned[s]) for s in SYMBOLS}
            reference = pending['gross']
        elif mode == 'FUND':
            planned = {s: math.floor(weights.get(s, 0)*value_open/(op[s]*(1+slip)*(1+fee))) for s in SYMBOLS}
            for s in SYMBOLS:
                split = data[s][d]['split_numerator']/data[s][d]['split_denominator']
                cap = pending['fund_shortfall'].get(s,0)*split
                near(cap, round(cap), 'integer funding entitlement')
                desired[s] = positions[s]+min(round(cap),max(0,planned[s]-positions[s]))
        elif mode == 'EXIT':
            desired = {s: positions[s] if s in pending['basket'] else 0 for s in SYMBOLS}
        elif mode != 'HOLD':
            raise AssertionError('Invalid execution mode')
        expected_trades = []
        shortfall = {}

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
            check(t['reason'] == ('HARD' if side == 'SELL' and hard else mode), 'trade reason')
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

        if mode != 'HOLD':
            for s in SYMBOLS:
                amount = max(0, positions[s] - desired[s])
                if amount:
                    transact(s, 'SELL', amount)
            observe(op, d, 'after_sells')
            budget = .99 * cash
            for s in sorted(SYMBOLS):
                if hard or mode not in ('FULL', 'FUND'):
                    break
                amount = min(max(0, desired[s] - positions[s]), max(0, math.floor(budget / (op[s] * (1 + slip) * (1 + fee)))))
                if amount:
                    budget -= transact(s, 'BUY', amount)
                    observe(op, d, 'after_buy')
        if mode == 'FULL' and not hard:
            shortfall = {s: desired[s]-positions[s] for s in SYMBOLS if desired[s]>positions[s]}
        value, dd = observe(cl, d, 'close')
        for s in SYMBOLS:
            for key, expected in signals[s][d].items():
                actual = h['signal_snapshot'][s][key]
                if isinstance(expected, bool) or expected is None:
                    check(actual == expected, 'indicator ' + key)
                else:
                    near(actual, expected, 'indicator ' + key)
        vol = signals['SPY'][d]['vol20']
        near(h['market_vol20'], vol, 'market volatility')
        if reference is None:
            check(h['rebalance_anchor_gross'] is None, 'initial reference')
        else:
            near(h['rebalance_anchor_gross'], reference, 'executed gross reference')
        monthly = n == 0 or d[:7] != expected_dates[n-1][:7]
        check(result['candidate']=='balanced_trend' and result['risk_policy']=='recovery_vol','fixed engine policy')
        eligible = {sym: signals[sym][d]['eligible'] for sym in SYMBOLS}
        previous_basket=list(basket)
        market_up=signals['SPY'][d]['market_up']
        if variant=='remove_sma':
            basket=list(SYMBOLS)
        elif variant=='monthly_exit' and not monthly:
            basket=list(previous_basket)
        elif monthly and variant in ('market_entry','vol_entry'):
            allowed=market_up if variant=='market_entry' else vol<=.25
            basket=[sym for sym in SYMBOLS if eligible[sym]] if allowed else [sym for sym in previous_basket if eligible[sym]]
        elif monthly or variant=='daily_entry':
            basket=[sym for sym in SYMBOLS if eligible[sym]]
        else:
            basket=[sym for sym in previous_basket if eligible[sym]]
        gross=risk_budget('recovery_vol',value,peak,hard,vol)
        up_streak=up_streak+1 if market_up else 0
        transition=False
        if variant=='cap40':gross=min(.4,gross)
        if variant=='pause6':
            if not paused and dd<.03:armed=True
            if hard:
                gross=0.;basket=[]
            elif paused:
                if n-pause_day>=20 and up_streak>=5:
                    paused=False;transition=True
                    basket=[sym for sym in SYMBOLS if eligible[sym]]
                else:
                    gross=0.;basket=[]
            elif armed and dd>=.06:
                paused=True;armed=False;pause_day=n;transition=True;gross=0.;basket=[]
        check(h['filter_state']=={'paused':paused,'armed':armed,'pause_day':pause_day,'day':n,'up_streak':up_streak,'transition':transition},'filter state and pause/resume')
        max_target_gross=max(max_target_gross,gross)
        target_mode='HOLD' if hard else 'FULL' if (monthly or variant=='pause6' and transition or variant=='daily_entry' and set(basket)-set(previous_basket)) else 'REDUCE' if reference is not None and gross<=reference-.05+1e-12 else 'EXIT' if set(previous_basket)-set(basket) else 'HOLD'
        weights={sym:gross*BASE_WEIGHTS[sym] for sym in basket}
        fund_shortfall = {}
        if target_mode == 'HOLD' and not hard and shortfall:
            target_mode = 'FUND'
            factor = min(1.0,gross/pending['gross']) if pending['gross']>0 else 0.0
            weights = {s:w*factor for s,w in pending['weights'].items()}
            fund_shortfall = shortfall
        expected = {'date': d, 'weights': weights, 'gross': gross, 'basket': list(basket), 'mode': target_mode, 'fund_shortfall': fund_shortfall}
        nt = h['next_target']
        check(nt['date'] == d and nt['basket'] == basket and nt['mode'] == target_mode and set(nt['weights']) == set(weights), 'selection or mode causality')
        check(nt['fund_shortfall'] == fund_shortfall, 'one-shot funding cap')
        near(nt['gross'], gross, 'risk gross')
        for s in basket:
            near(nt['weights'][s], weights[s], 'target weight')
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
    history=result['history']
    diagnostics={'flat_days':sum(not any(x['qty'].values()) for x in history),
        'hard_days':sum(x['hard'] for x in history),'paused_days':sum(x['filter_state']['paused'] for x in history),
        'empty_target_days':sum(not x['next_target']['basket'] for x in history),
        'market_gate_closed_days':sum(not signals['SPY'][x['date']]['market_up'] for x in history),
        'vol_gate_closed_days':sum(signals['SPY'][x['date']]['vol20']>.25 for x in history),
        'total_days':len(history),'fees_and_slippage':fee_total+slip_total,
        'first_hard_date':latch['date'] if latch else None}
    check(set(result['diagnostics'])==set(diagnostics),'diagnostics fields')
    for key,expected in diagnostics.items():
        if key=='fees_and_slippage':near(result['diagnostics'][key],expected,'diagnostic '+key)
        else:check(result['diagnostics'][key]==expected,'diagnostic '+key)
    return {'status': 'PASS', 'daily_states': len(expected_dates), 'trades': ptr}


def annualized(r):
    days=(date.fromisoformat(r['end'])-date.fromisoformat(r['start'])).days
    return ((r['final_equity']/r['initial'])**(365.2425/max(1,days))-1)*100


def gate(rows):
    check(len(rows)==6,'exact six cells')
    check({(r['period'],r['cost_bps']) for r in rows}=={(p,c) for p in ('recent','latest','continuous') for c in (10,25)},'unique six cells')
    return all(annualized(r)>=10 and r['mdd_pct']<=10 and not r['hard_latch'] and not r['pending_liquidation'] for r in rows)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('data');parser.add_argument('results');args=parser.parse_args()
    source,folder=Path(args.data),Path(args.results)
    verified=verify_manifest(source);data=read_data(source);signals=indicator_cache(data)
    sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
    manifest=json.loads((folder/'manifest.json').read_text())
    check(Path(manifest['data']).resolve()==source.resolve(),'frozen data path')
    check(manifest['dailySha256']==sha(source/'daily.csv') and manifest['manifestSha256']==sha(source/'manifest.json'),'frozen data')
    for name,h in manifest['sources'].items():check(Path(name).name==name and sha(Path(__file__).parent/name)==h,'frozen source')
    check(set(manifest['rules'])==set(VARIANTS),'all variants frozen')
    check(manifest['historicalGate']=={'annualizedPctMin':10,'maxDrawdownPctMax':10,'hardLatched':False,'pendingLiquidation':False},'historical gate')
    check(manifest['forwardGate']=={'newTradingDaysMin':60,'fillsMin':20,'positiveReturn':True,'baselineExcessPpMin':2,'maxDrawdownPctMax':10,'baselineDrawdownExcessPpMax':1,'hardLatched':False},'forward gate')
    reports={};groups={};identities=set()
    for path in sorted((folder/'runs').glob('*bps.json')):
        r=json.loads(path.read_text(encoding='utf-8'))
        key=(r['period'],r['research_variant'],r['cost_bps']);check(key not in identities,'duplicate run');identities.add(key)
        reports[path.name]=audit(r,data,signals)|{'sha256':sha(path)}
        near(r['annualized_pct'],annualized(r),'CAGR')
        check(r['approval_eligible'] is False,'no approval')
        check(r['historical_cell_pass']==(annualized(r)>=10 and r['mdd_pct']<=10 and not r['hard_latch'] and not r['pending_liquidation']),'cell pass')
        groups.setdefault(r['research_variant'],[]).append(r)
    check(identities=={(p,v,c) for p in ('recent','latest','continuous') for v in VARIANTS for c in (10,25)},'exact48 matrix')
    summaries=json.loads((folder/'summary.json').read_text());check(len(summaries)==48,'summary count')
    seen=set()
    for s in summaries:
        name=s['file'];check(name in reports and name not in seen,'summary identity');seen.add(name)
        r=json.loads((folder/'runs'/name).read_text())
        check(s=={k:v for k,v in r.items() if k not in ('history','trades')}|{'file':name},'summary exact')
    judgments=json.loads((folder/'judgments.json').read_text());check(len(judgments)==8,'judgment count')
    seen=set()
    for j in judgments:
        v=j['variant'];check(v in groups and v not in seen,'judgment identity');seen.add(v)
        check(j['historicalPassed']==gate(groups[v]) and j['totalCells']==6 and j['passedCells']==sum(r['historical_cell_pass'] for r in groups[v]),'judgment match')
        check(j['forwardDays']==0 and j['forwardFills']==0 and j['eligible'] is False,'no synthetic forward approval')
    output={'status':'PASS','run_count':len(reports),'verified_source_files':verified,'historical_gate':{v:gate(rs) for v,rs in groups.items()},'daily_states':sum(x['daily_states'] for x in reports.values()),'trades':sum(x['trades'] for x in reports.values()),'runs':reports,'audit_source_sha256':sha(__file__),'approval_eligible_count':0}
    (folder/'independent-audit.json').write_text(json.dumps(output,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in output.items() if k!='runs'}))


if __name__=='__main__':main()
