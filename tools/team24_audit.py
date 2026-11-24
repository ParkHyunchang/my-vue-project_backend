"""Independent replay auditor; never imports the strategy engine or broker code."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
from datetime import datetime, timezone

SYMBOLS = ('SPY', 'QQQ', 'GLD', 'IEF')
BASE_WEIGHTS = {'SPY': .4, 'QQQ': .2, 'GLD': .2, 'IEF': .2}
POLICIES = ('cushion80', 'recovery_vol')
CANDIDATES = ('rotation1', 'riskrank2', 'balanced_trend')


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
                'vol20': vols[20], 'vol63': vols[63], 'riskrank': (prices[i]/prices[i-126]-1)/max(.05,vols[63])}
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
                if isinstance(expected, bool):
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
        strategy = result['candidate']
        eligible = {s: signals[s][d]['eligible'] and (strategy == 'balanced_trend' or signals[s][d]['momentum'] > 0) for s in SYMBOLS}
        previous_basket = list(basket)
        if monthly:
            if strategy == 'balanced_trend':
                basket = [s for s in SYMBOLS if eligible[s]]
            else:
                rank = lambda s: signals[s][d]['momentum'] / (max(.05, signals[s][d]['vol63']) if strategy == 'riskrank2' else 1)
                basket = sorted((s for s in SYMBOLS if eligible[s]), key=lambda s: (-rank(s), s))[:2 if strategy == 'riskrank2' else 1]
        else:
            basket = [s for s in basket if eligible[s]]
        gross = risk_budget(result['risk_policy'], value, peak, hard, vol)
        max_target_gross = max(max_target_gross, gross)
        target_mode = 'HOLD' if hard else 'FULL' if monthly else 'REDUCE' if reference is not None and gross <= reference-.05+1e-12 else 'EXIT' if basket != previous_basket else 'HOLD'
        weights = {s: gross*(BASE_WEIGHTS[s] if strategy == 'balanced_trend' else .5 if strategy == 'riskrank2' else 1) for s in basket}
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
    return {'status': 'PASS', 'daily_states': len(expected_dates), 'trades': ptr}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('data')
    parser.add_argument('results')
    args = parser.parse_args()
    verified_files = verify_manifest(args.data)
    data = read_data(args.data)
    folder = Path(args.results)
    reports = {}
    signals = indicator_cache(data)
    for path in sorted(folder.glob('*bps.json')):
        result = json.loads(path.read_text(encoding='utf-8'))
        reports[path.name] = audit(result, data, signals) | {'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    expected_names = {f'{period}__{candidate}__{policy}__{cost}bps.json'
                      for period in ('2008_2017', '2018_2023', '2024_2025', '2026_ytd', 'continuous')
                      for candidate in CANDIDATES
                      for policy in POLICIES for cost in (10, 25)}
    check(set(reports) == expected_names, 'expected exact 60-run matrix')
    summary = json.loads((folder / 'summary.json').read_text(encoding='utf-8'))
    check(len(summary) == 60, 'summary run count')
    identities = set()
    for entry in summary:
        name = f"{entry['period']}__{entry['candidate']}__{entry['risk_policy']}__{entry['cost_bps']}bps.json"
        check(name in reports and name not in identities, 'summary run identity')
        identities.add(name)
        source = json.loads((folder / name).read_text(encoding='utf-8'))
        check(entry == {k: v for k, v in source.items() if k not in ('history', 'trades')}, 'summary differs from audited ledger')
    output = {'status': 'PASS', 'runs': reports, 'verified_source_files': verified_files, 'daily_states': sum(r['daily_states'] for r in reports.values()), 'trades': sum(r['trades'] for r in reports.values()), 'audit_source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'daily_csv_sha256': hashlib.sha256((Path(args.data) / 'daily.csv').read_bytes()).hexdigest()}
    (folder / 'audit.json').write_text(json.dumps(output, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in output.items() if k != 'runs'}))


if __name__ == '__main__':
    main()
