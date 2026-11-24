"""Independent replay auditor; never imports the strategy engine or broker code."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path

SECTORS = ('XLB', 'XLE', 'XLI', 'XLK', 'XLP', 'XLU', 'XLV', 'XLY')
SYMBOLS = ('SPY',) + SECTORS
POLICIES = ('cushion80', 'recovery_vol')
CANDIDATES = ('spy_trend', 'sector_breakout', 'spy_pullback')


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
    """Independent direct windows; no engine imports or shared calculations."""
    dates = sorted(data['SPY'])
    out = {s: {} for s in SYMBOLS}
    for s in SYMBOLS:
        prices = [data[s][d]['adj_close'] for d in dates]
        for i in range(199, len(dates)):
            close = prices[i]
            sma200 = sum(prices[i-199:i+1]) / 200
            differences = [prices[j] - prices[j-1] for j in (i-1, i)]
            gain = sum(max(0, v) for v in differences)
            loss = sum(max(0, -v) for v in differences)
            rsi = 50. if gain == loss == 0 else 100. if loss == 0 else 100 * gain / (gain + loss)
            returns = [prices[j] / prices[j-1] - 1 for j in range(i-19, i+1)]
            average = sum(returns) / 20
            vol = math.sqrt(sum((r-average)**2 for r in returns) / 19 * 252)
            out[s][dates[i]] = {'eligible': close > sma200, 'momentum': close / prices[i-126] - 1,
                'sma200': sma200, 'sma5': sum(prices[i-4:i+1]) / 5,
                'prior20_high': max(prices[i-20:i]), 'prior10_low': min(prices[i-10:i]),
                'rsi2': rsi, 'vol20': vol}
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
    for entry in manifest['files']:
        verify(entry['raw_file'], entry['raw_sha256'])
        verify(entry['csv_file'], entry['csv_sha256'])
    verify(manifest['daily_file'], manifest['daily_sha256'])
    return 1 + 2 * len(manifest['files'])


def audit(result, data, signals=None):
    """Reconstruct each balance and decision from CSV, with separate direct-window indicators."""
    dates = sorted(data['SPY'])
    signals = indicator_cache(data) if signals is None else signals
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
        strategy = result['candidate']
        if strategy == 'spy_trend':
            if n == 0 or d[:7] != expected_dates[n-1][:7]:
                basket = ['SPY'] if signals['SPY'][d]['eligible'] else []
            basket = [s for s in basket if signals[s][d]['eligible']]
        else:
            pool = SECTORS if strategy == 'sector_breakout' else ('SPY',)
            exited = set()
            survivors = []
            for s in sorted(pool):
                if not positions[s]:
                    continue
                ind = signals[s][d]
                close = data[s][d]['adj_close']
                should_exit = (close < ind['prior10_low'] or ages[s] >= 10) if strategy == 'sector_breakout' else (close > ind['sma5'] or ages[s] >= 5 or close < ind['sma200'])
                if should_exit:
                    exited.add(s)
                else:
                    survivors.append(s)
            candidates = []
            for s in pool:
                ind = signals[s][d]
                entry = ind['eligible'] and (data[s][d]['adj_close'] > ind['prior20_high'] if strategy == 'sector_breakout' else ind['rsi2'] < 10)
                if entry and not positions[s] and s not in exited:
                    candidates.append(s)
            candidates.sort(key=lambda s: (-signals[s][d]['momentum'], s))
            slots = 3 if strategy == 'sector_breakout' else 1
            basket = survivors + candidates[:slots-len(survivors)]
        gross = risk_budget(result['risk_policy'], value, peak, hard, vol)
        max_target_gross = max(max_target_gross, gross)
        divisor = 3 if strategy == 'sector_breakout' else 1
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
