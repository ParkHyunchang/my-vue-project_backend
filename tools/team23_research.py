"""Offline daily-bar research. No broker imports, orders, or application configuration.

Signals use completed closes; execution uses following opens. This is a new daily
execution model, not a replay of the production intraday strategy. A 10% latch is
an observed drawdown rule, not a guaranteed maximum loss across gaps.
"""
import csv
import hashlib
import json
import math
import statistics
from pathlib import Path

SECTORS = ('XLB', 'XLE', 'XLI', 'XLK', 'XLP', 'XLU', 'XLV', 'XLY')
SYMBOLS = ('SPY',) + SECTORS
CANDIDATES = ('spy_trend', 'sector_breakout', 'spy_pullback')
COSTS = (10, 25)
RISK_POLICIES = ('cushion80', 'recovery_vol')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate(data):
    if any(s not in data for s in SYMBOLS):
        raise ValueError('Missing required ETF')
    calendar = [r['date'] for r in data['SPY']]
    if not calendar or calendar != sorted(set(calendar)):
        raise ValueError('Duplicate, empty, or unsorted calendar')
    for symbol in SYMBOLS:
        if [r['date'] for r in data[symbol]] != calendar:
            raise ValueError('ETF calendars differ; forward filling prohibited')
        for r in data[symbol]:
            for key in ('open', 'close', 'adj_close', 'trade_open', 'trade_close'):
                if not isinstance(r.get(key), (int, float)) or not math.isfinite(r[key]) or r[key] <= 0:
                    raise ValueError('Invalid price: ' + symbol + '/' + key)
            for key in ('trade_dividend', 'split_numerator', 'split_denominator'):
                if not math.isfinite(r[key]) or r[key] < 0:
                    raise ValueError('Invalid corporate action')
            if r['split_numerator'] <= 0 or r['split_denominator'] <= 0:
                raise ValueError('Invalid split ratio')
    return calendar


def load_data(folder):
    """Load collector's combined CSV; callers freeze and verify its manifest."""
    folder = Path(folder)
    data = {s: [] for s in SYMBOLS}
    benchmark = {}
    with (folder / 'daily.csv').open(newline='', encoding='utf-8') as stream:
        for raw in csv.DictReader(stream):
            symbol = raw['symbol']
            if symbol == '^SP500TR':
                benchmark[raw['date']] = float(raw['close'])
            elif symbol in data:
                row = {'date': raw['date']}
                for key in ('open', 'close', 'adj_close', 'dividend', 'split_numerator', 'split_denominator'):
                    row[key] = float(raw[key])
                data[symbol].append(row)
    # Yahoo quotes/dividends are retrospectively split-adjusted. Undo only splits
    # strictly AFTER each row to reconstruct the whole-share nominal trade basis.
    # XLF is excluded before results: 2016 spinoff entitlement is not a stock split.
    for rows in data.values():
        future_factor = 1.0
        for row in reversed(rows):
            row['trade_open'] = row['open'] * future_factor
            row['trade_close'] = row['close'] * future_factor
            row['trade_dividend'] = row['dividend'] * future_factor
            future_factor *= row['split_numerator'] / row['split_denominator']
    calendar = validate(data)
    if set(benchmark) != set(calendar) or any(not math.isfinite(v) or v <= 0 for v in benchmark.values()):
        raise ValueError('Benchmark calendar or values invalid')
    return data, benchmark


def indicators(data):
    result = {}
    for symbol in SYMBOLS:
        closes = [r['adj_close'] for r in data[symbol]]
        total, values = 0.0, []
        for i, close in enumerate(closes):
            total += close
            if i >= 200:
                total -= closes[i - 200]
            changes = [closes[j] - closes[j-1] for j in range(max(1, i-1), i+1)]
            gain = sum(max(0, x) for x in changes)
            loss = sum(max(0, -x) for x in changes)
            rsi = 100 * gain / (gain + loss) if gain + loss else 50.0
            returns = [closes[j] / closes[j-1] - 1 for j in range(i-19, i+1)] if i >= 20 else []
            values.append({'eligible': i >= 199 and close > total / 200,
                           'momentum': close / closes[i - 126] - 1 if i >= 126 else None,
                           'sma200': total / 200 if i >= 199 else None,
                           'sma5': sum(closes[i-4:i+1]) / 5 if i >= 4 else None,
                           'prior20_high': max(closes[i-20:i]) if i >= 20 else None,
                           'prior10_low': min(closes[i-10:i]) if i >= 10 else None,
                           'rsi2': rsi if i >= 2 else None,
                           'vol20': statistics.stdev(returns) * math.sqrt(252) if returns else None})
        result[symbol] = values
    return result


def risk_target(value, peak, hard, policy, volatility):
    """Completed-close risk target. A 20% recovery floor is not a loss guarantee."""
    if policy not in RISK_POLICIES or not math.isfinite(value) or not math.isfinite(peak) or value <= 0 or peak < value:
        raise ValueError('Invalid risk configuration')
    if not isinstance(volatility, (int, float)) or not math.isfinite(volatility) or volatility < 0:
        raise ValueError('Invalid volatility')
    if hard:
        return 0.0
    if policy == 'cushion80':
        return min(.8, 8 * max(0, value - .9 * peak) / value)
    gross = min(.8, max(.2, .10 / max(.05, volatility)))
    if value <= .92 * peak:
        gross = min(gross, .2)
    elif value <= .95 * peak:
        gross = min(gross, .4)
    return gross


def select_basket(candidate, prior_basket, qty, ages, snapshot, closes, monthly):
    """Signals use this completed close, actual holdings, and no future frames."""
    if candidate == 'spy_trend':
        basket = ['SPY'] if monthly and snapshot['SPY']['eligible'] else ([] if monthly else list(prior_basket))
        return [s for s in basket if snapshot[s]['eligible']]
    if candidate == 'spy_pullback':
        s, signal = 'SPY', snapshot['SPY']
        if qty[s] > 0:
            exit_now = closes[s] > signal['sma5'] or ages[s] >= 5 or closes[s] < signal['sma200']
            return [] if exit_now else [s]
        return [s] if signal['eligible'] and signal['rsi2'] < 10 else []
    if candidate != 'sector_breakout':
        raise ValueError('Invalid candidate')
    held = {s for s in SECTORS if qty[s] > 0}
    survivors = sorted(s for s in held if not (closes[s] < snapshot[s]['prior10_low'] or ages[s] >= 10))
    entries = sorted((s for s in SECTORS if s not in held and snapshot[s]['eligible']
                      and closes[s] > snapshot[s]['prior20_high']), key=lambda s: (-snapshot[s]['momentum'], s))
    return survivors + entries[:max(0, 3 - len(survivors))]


def simulate(data, start, end, candidate, cost_bps=10, initial=10000.0, signals=None, risk_policy='cushion80'):
    calendar = validate(data)
    if candidate not in CANDIDATES or cost_bps not in COSTS or initial <= 0 or risk_policy not in RISK_POLICIES:
        raise ValueError('Invalid fixed research configuration')
    selected = [i for i, d in enumerate(calendar) if start <= d <= end]
    if not selected or selected[0] < 199:
        raise ValueError('No evaluation rows or insufficient 200-session warmup')
    signals = signals or indicators(data)
    cash, dividends, unsettled = float(initial), 0.0, 0.0
    qty = {s: 0 for s in SYMBOLS}
    ages = {s: 0 for s in SYMBOLS}
    peak, mdd, fees, slippage = float(initial), 0.0, 0.0, 0.0
    hard, latch, pending, basket = False, None, None, []
    trades, history, events = [], [], []
    fee = slip = cost_bps / 20000.0

    def equity(prices):
        return cash + dividends + unsettled + sum(qty[s] * prices[s] for s in SYMBOLS)

    def observe(prices, date, phase):
        nonlocal peak, mdd, hard, latch
        value = equity(prices)
        peak = max(peak, value)
        dd = max(0.0, 1 - value / peak)
        mdd = max(mdd, dd)
        if not hard and dd >= .10 - 1e-12:
            hard, latch = True, {'date': date, 'phase': phase}
        return value, dd

    for n, i in enumerate(selected):
        date = calendar[i]
        opening = {s: data[s][i]['trade_open'] for s in SYMBOLS}
        closing = {s: data[s][i]['trade_close'] for s in SYMBOLS}
        cash_before_settlement = cash
        settled_today = unsettled
        cash += unsettled
        unsettled = 0.0
        dividend_today = 0.0
        splits = {}
        for s in SYMBOLS:
            row = data[s][i]
            ratio = row['split_numerator'] / row['split_denominator']
            adjusted = qty[s] * ratio
            if abs(adjusted - round(adjusted)) > 1e-8:
                raise ValueError('Fractional split shares require verified cash-in-lieu data')
            qty[s] = round(adjusted)
            if ratio != 1:
                splits[s] = ratio
            dividend_today += qty[s] * row['trade_dividend']
        dividends += dividend_today
        open_equity, open_dd = observe(opening, date, 'open')
        executed = pending
        targets = {s: 0.0 for s in SYMBOLS} if hard else (pending['weights'] if pending else None)
        day_trades = []
        if targets is not None:
            desired = {s: math.floor(targets.get(s, 0) * open_equity / (opening[s] * (1 + slip) * (1 + fee))) for s in SYMBOLS}
            # Opening sells settle next observed trading day; cannot finance buys today.
            for s in SYMBOLS:
                amount = max(0, qty[s] - desired[s])
                if amount:
                    price = opening[s] * (1 - slip)
                    charge = amount * price * fee
                    proceeds = amount * price - charge
                    qty[s] -= amount
                    unsettled += proceeds
                    fees += charge
                    slippage += amount * (opening[s] - price)
                    trade = {'date': date, 'symbol': s, 'side': 'SELL', 'qty': amount, 'open': opening[s], 'price': price, 'fee': charge, 'net': proceeds,
                             'cash_after': cash, 'unsettled_after': unsettled, 'signal_date': pending['date'] if pending else None,
                             'reason': 'HARD' if hard else 'CLOSE_TARGET'}
                    trades.append(trade)
                    day_trades.append(trade)
            observe(opening, date, 'after_sells')
            budget = cash * .99
            for s in sorted(SYMBOLS):
                if hard:
                    break
                price = opening[s] * (1 + slip)
                unit = price * (1 + fee)
                amount = min(max(0, desired[s] - qty[s]), max(0, math.floor(budget / unit)))
                if amount:
                    charge = amount * price * fee
                    debit = amount * price + charge
                    cash -= debit
                    budget -= debit
                    qty[s] += amount
                    fees += charge
                    slippage += amount * (price - opening[s])
                    trade = {'date': date, 'symbol': s, 'side': 'BUY', 'qty': amount, 'open': opening[s], 'price': price, 'fee': charge, 'net': debit,
                             'cash_after': cash, 'unsettled_after': unsettled, 'signal_date': pending['date'], 'reason': 'CLOSE_TARGET'}
                    trades.append(trade)
                    day_trades.append(trade)
                    observe(opening, date, 'after_buy')
        value, dd = observe(closing, date, 'close')
        ages = {s: ages[s] + 1 if qty[s] > 0 else 0 for s in SYMBOLS}
        snapshot = {s: dict(signals[s][i]) for s in SYMBOLS}
        monthly = n == 0 or date[:7] != calendar[selected[n - 1]][:7]
        basket = select_basket(candidate, basket, qty, ages, snapshot,
                               {s: data[s][i]['adj_close'] for s in SYMBOLS}, monthly)
        volatility = snapshot['SPY']['vol20']
        gross = risk_target(value, peak, hard, risk_policy, volatility)
        slots = 3 if candidate == 'sector_breakout' else 1
        weights = {s: gross / slots for s in basket} if basket else {}
        pending = {'date': date, 'weights': weights, 'gross': gross, 'basket': list(basket)}
        if cash < -1e-7 or unsettled < -1e-7 or any(q < 0 or not isinstance(q, int) for q in qty.values()):
            raise AssertionError('Cash/position invariant violated')
        history.append({'date': date, 'cash_before_settlement': cash_before_settlement, 'settled_today': settled_today,
                        'cash': cash, 'unsettled': unsettled, 'dividend_today': dividend_today, 'dividend_receivable': dividends,
                        'holding_age': dict(ages), 'signal_snapshot': snapshot, 'market_vol20': volatility,
                        'splits': splits, 'qty': dict(qty), 'open': opening, 'close': closing, 'equity': value, 'peak': peak,
                        'drawdown': dd, 'open_equity': open_equity, 'open_drawdown': open_dd, 'hard': hard,
                        'fees_cumulative': fees, 'slippage_cumulative': slippage, 'executed_target': executed,
                        'next_target': pending, 'trade_count': len(day_trades), 'exposure': sum(qty[s] * closing[s] for s in SYMBOLS) / value})
    return {'candidate': candidate, 'cost_bps': cost_bps, 'initial': initial, 'start': history[0]['date'], 'end': history[-1]['date'],
            'return_pct': (history[-1]['equity'] / initial - 1) * 100, 'final_equity': history[-1]['equity'], 'mdd_pct': mdd * 100,
            'hard_latch': latch, 'pending_liquidation': hard and any(qty.values()), 'fees': fees, 'slippage': slippage,
            'trade_count': len(trades), 'mean_exposure': sum(r['exposure'] for r in history) / len(history),
            'risk_policy': risk_policy, 'risk_formula_max_target_gross': .80,
            'max_target_gross': max(h['next_target']['gross'] for h in history), 'history': history, 'trades': trades}


def benchmark_result(index, start, end):
    dates = sorted(d for d in index if start <= d <= end)
    if not dates:
        raise ValueError('Empty benchmark')
    peak, mdd = index[dates[0]], 0.0
    for date in dates:
        peak = max(peak, index[date])
        mdd = max(mdd, 1 - index[date] / peak)
    return {'start': dates[0], 'end': dates[-1], 'return_pct': (index[dates[-1]] / index[dates[0]] - 1) * 100, 'mdd_pct': mdd * 100}


def periods(latest):
    return (('2008_2017', '2008-01-01', '2017-12-31'), ('2018_2023', '2018-01-01', '2023-12-31'),
            ('2024_2025', '2024-01-01', '2025-12-31'), ('2026_ytd', '2026-01-01', latest),
            ('continuous', '2008-01-01', latest))


def run_suite(data, index, output):
    """Parent calls only AFTER independent pre-run plan/data/source hash freeze."""
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    signal_cache = indicators(data)
    summary = []
    for period, start, end in periods(data['SPY'][-1]['date']):
        benchmark = benchmark_result(index, start, end)
        for cost in COSTS:
            for policy in RISK_POLICIES:
                for candidate in CANDIDATES:
                    result = simulate(data, start, end, candidate, cost, signals=signal_cache, risk_policy=policy)
                    result['benchmark'] = benchmark
                    result['excess_return_pp'] = result['return_pct'] - benchmark['return_pct']
                    result['period'] = period
                    name = f'{period}__{candidate}__{policy}__{cost}bps.json'
                    (output / name).write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
                    summary.append({k: v for k, v in result.items() if k not in ('history', 'trades')})
    (output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    return summary
