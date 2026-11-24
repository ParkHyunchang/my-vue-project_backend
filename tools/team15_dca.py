"""Offline, raw-dollar SPY whole-share DCA study; never imports application code."""
import csv
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean, median

ROOT = Path(__file__).resolve().parent
MODES = ('daily_share', 'daily_budget', 'lump_sum')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_data():
    folder = ROOT / 'strategy_v3_data'
    manifest = json.loads((folder / 'manifest.json').read_text())
    for name in ('SPY', '^SP500TR'):
        item = next(x for x in manifest['files'] if x['symbol'] == name)
        for kind in ('raw', 'csv'):
            if digest(folder / item[kind + '_file']) != item[kind + '_sha256']:
                raise ValueError('Original market snapshot was changed')
    raw = json.loads((folder / 'SPY.raw.json').read_text())['chart']['result'][0]
    if raw.get('events', {}).get('splits'):
        raise ValueError('Split processing is not implemented; fail closed')
    def day(t):
        return datetime.fromtimestamp(t, timezone.utc).date().isoformat()
    dividends = {day(x['date']): x['amount'] for x in raw.get('events', {}).get('dividends', {}).values()}
    quote = raw['indicators']['quote'][0]
    rows = []
    for i, stamp in enumerate(raw['timestamp']):
        row = {'date': day(stamp), 'open': quote['open'][i], 'close': quote['close'][i], 'dividend': dividends.get(day(stamp), 0)}
        if any(not isinstance(row[k], (int, float)) or not math.isfinite(row[k]) or row[k] <= 0 for k in ('open', 'close')):
            raise ValueError('Invalid raw quote')
        rows.append(row)
    with (folder / 'SP500TR.csv').open(newline='') as stream:
        index = {r['date']: float(r['close']) for r in csv.DictReader(stream)}
    if len({r['date'] for r in rows}) != len(rows) or any(rows[i]['date'] >= rows[i+1]['date'] for i in range(len(rows)-1)):
        raise ValueError('Duplicate/unsorted dates')
    if set(index) != {r['date'] for r in rows}:
        raise ValueError('Benchmark trading dates differ')
    return rows, index


def simulate(rows, mode, initial=10000.0, fee_bps=5, slip_bps=5):
    if mode not in MODES or not rows or initial <= 0 or fee_bps < 0 or slip_bps < 0:
        raise ValueError('Invalid simulation inputs')
    cash, receivable, qty = initial, 0.0, 0
    peak, mdd, hard, pending = initial, 0.0, False, False
    spent, fees, skipped = 0.0, 0.0, 0
    latch = None
    trades, history = [], []
    fee, slip = fee_bps / 10000, slip_bps / 10000
    def observe(price):
        nonlocal peak, mdd
        value = cash + qty * price + receivable
        peak = max(peak, value)
        dd = max(0.0, 1 - value / peak)
        mdd = max(mdd, dd)
        return value, dd
    for i, row in enumerate(rows):
        # Only shares held across ex-date earn this dividend. Not spendable yet.
        receivable += qty * row['dividend']
        _, dd = observe(row['open'])
        if not hard and dd >= .10 - 1e-12:
            hard, latch = True, row['date']
        if qty and (hard or pending):
            price = row['open'] * (1 - slip)
            commission = qty * price * fee
            cash += qty * price - commission
            fees += commission
            trades.append({'date': row['date'], 'side': 'SELL', 'qty': qty, 'price': price, 'fee': commission, 'cash_after': cash, 'reason': 'HARD'})
            qty, pending = 0, False
            observe(row['open'])
        if not hard and i < 40:
            price = row['open'] * (1 + slip)
            unit = price * (1 + fee)
            cap = max(0, math.floor(cash * .99 / unit))
            if mode == 'daily_share':
                wanted = 1
            elif mode == 'daily_budget':
                wanted = max(0, math.floor((initial * (i + 1) / 40 - spent + 1e-9) / unit))
            else:
                wanted = cap if i == 0 else 0
            buy = min(wanted, cap)
            if mode == 'daily_share' and buy == 0:
                skipped += 1
            if buy:
                before = cash
                cost = buy * unit
                commission = buy * price * fee
                cash -= cost
                spent += cost
                fees += commission
                qty += buy
                if cash < -1e-8 or cost > before * .99 + 1e-8:
                    raise AssertionError('Cash-only constraint violated')
                trades.append({'date': row['date'], 'side': 'BUY', 'qty': buy, 'price': price, 'fee': commission, 'cash_before': before, 'cash_after': cash, 'reason': mode})
        equity, dd = observe(row['close'])
        if not hard and dd >= .10 - 1e-12:
            hard, pending, latch = True, True, row['date']
        history.append({'date': row['date'], 'cash': cash, 'quantity': qty, 'dividend_receivable': receivable, 'equity': equity, 'peak': peak, 'drawdown': dd, 'hard': hard})
    return {'mode': mode, 'start': rows[0]['date'], 'end': rows[-1]['date'], 'initial': initial,
            'final_equity': equity, 'return_pct': (equity / initial - 1) * 100, 'mdd_pct': mdd * 100,
            'hard_date': latch, 'pending_liquidation': pending and qty > 0, 'fees': fees,
            'bought_shares': sum(t['qty'] for t in trades if t['side'] == 'BUY'),
            'cash_skip_days': skipped, 'trades': trades, 'history': history}


def run():
    output = ROOT / 'team15_results'
    output.mkdir(exist_ok=False)
    files = ['team15_plan.md', 'team15_dca.py', 'strategy_v3_data/SPY.raw.json', 'strategy_v3_data/SP500TR.csv']
    hashes = {f: digest(ROOT / f) for f in files}
    (output / 'pre-run-lock.json').write_text(json.dumps({'utc': datetime.now(timezone.utc).isoformat(), 'sha256': hashes}, indent=2) + '\n')
    rows, index = load_data()
    first = next(i for i, r in enumerate(rows) if r['date'] >= '2008-01-01')
    windows = [(f'block_{k//40:03}', k, False) for k in range(first, len(rows)-39, 40)]
    windows.append(('latest_40', len(rows)-40, True))
    results = []
    for name, start, example in windows:
        sample = rows[start:start+40]
        benchmark = (index[sample[-1]['date']] / index[rows[start-1]['date']] - 1) * 100
        for slip in (5, 20):
            for mode in MODES:
                result = simulate(sample, mode, slip_bps=slip)
                result.update(window=name, example_only=example, cost_bps=5+slip, benchmark_pct=benchmark,
                              excess_pp=result['return_pct']-benchmark)
                results.append(result)
    if any(digest(ROOT / f) != h for f, h in hashes.items()):
        raise ValueError('Inputs changed during run')
    (output / 'runs.json').write_text(json.dumps(results, indent=2, allow_nan=False) + '\n')
    aggregate = []
    for cost in (10, 25):
        for mode in MODES:
            sample = [x for x in results if not x['example_only'] and x['cost_bps'] == cost and x['mode'] == mode]
            lumps = {x['window']: x for x in results if x['cost_bps'] == cost and x['mode'] == 'lump_sum'}
            aggregate.append({'mode': mode, 'cost_bps': cost, 'windows': len(sample),
                'mean_return_pct': mean(x['return_pct'] for x in sample), 'median_return_pct': median(x['return_pct'] for x in sample),
                'mean_benchmark_pct': mean(x['benchmark_pct'] for x in sample),
                'beat_index_count': sum(x['excess_pp'] > 0 for x in sample),
                'beat_lump_count': sum(x['return_pct'] > lumps[x['window']]['return_pct'] + 1e-10 for x in sample),
                'hard_count': sum(x['hard_date'] is not None for x in sample),
                'mdd_over_10_count': sum(x['mdd_pct'] > 10 + 1e-8 for x in sample),
                'worst_mdd_pct': max(x['mdd_pct'] for x in sample),
                'cash_skip_windows': sum(x['cash_skip_days'] > 0 for x in sample)})
    latest = [{k:v for k,v in x.items() if k not in ('trades','history')} for x in results if x['example_only']]
    summary = {'aggregate': aggregate, 'latest': latest, 'runs': len(results), 'first': rows[first]['date'], 'last': rows[-1]['date']}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2, allow_nan=False) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    run()
