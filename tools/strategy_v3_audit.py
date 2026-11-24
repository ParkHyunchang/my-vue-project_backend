"""Independent CPPI ledger and decision audit; no strategy-engine imports or network."""
import argparse
import csv
import hashlib
import json
import math
from datetime import date
from pathlib import Path

SYMBOLS = ('SPY', 'QQQ', 'GLD', 'IEF')


def equal(actual, expected, label):
    if not math.isfinite(float(actual)) or not math.isclose(float(actual), float(expected), rel_tol=1e-9, abs_tol=1e-6):
        raise ValueError(f'{label}: {actual} != {expected}')


def boolean(value):
    if value is True or value in ('True', 'true'):
        return True
    if value is False or value in ('False', 'false'):
        return False
    raise ValueError(f'Invalid boolean {value}')


def budget(equity, peak):
    return min(.99, 8 * max(0., equity - .9 * peak) / equity)


def target_values(row, prefix):
    values = [row.get(prefix + symbol.lower()) for symbol in SYMBOLS]
    if all(value in ('', None) for value in values):
        return None
    if any(value in ('', None) for value in values):
        raise ValueError('Incomplete target vector')
    result = {symbol: float(value) for symbol, value in zip(SYMBOLS, values)}
    if any(not math.isfinite(value) or value < 0 for value in result.values()):
        raise ValueError('Invalid target weight')
    return result


def audit(prices, rows, trades, report):
    candidate = report['candidate_id']
    if candidate not in ('D', 'E', 'F'):
        raise ValueError('Undeclared candidate')
    all_dates = sorted(day for day, symbol in prices if symbol == 'SPY')
    dates = [row['date'] for row in rows]
    if len(dates) < 2 or dates != sorted(set(dates)) or dates != [day for day in all_dates if dates[0] <= day <= dates[-1]]:
        raise ValueError('Missing/duplicate/unsorted valuation sessions')
    if (report['start'], report['end']) != (dates[0], dates[-1]):
        raise ValueError('Report window mismatch')
    benchmark = report['benchmark_symbol']
    if benchmark != '^SP500TR':
        raise ValueError('Undeclared benchmark')
    history = {symbol: [prices[day, symbol]['close'] for day in all_dates if day < dates[0]] for symbol in SYMBOLS}
    next_day = {day: all_dates[i + 1] if i + 1 < len(all_dates) else None for i, day in enumerate(all_dates)}
    by_day = {}
    for trade in trades:
        if trade['date'] not in dates or trade['symbol'] not in SYMBOLS:
            raise ValueError('Trade outside declared window/universe')
        by_day.setdefault(trade['date'], []).append(trade)
    cash = peak = float(report['initial_cash_usd'])
    fee_rate, slip = report['fee_bps_per_side'] / 10000, report['slippage_bps_per_side'] / 10000
    if not math.isfinite(cash) or cash <= 0 or not 0 <= fee_rate < 1 or not 0 <= slip < 1:
        raise ValueError('Invalid execution assumptions')
    holdings, hard, trigger, previous_target = {}, False, None, None
    active_slots, slot_count = [], 0
    exposure, mdd = [], 0.
    for index, row in enumerate(rows):
        day = row['date']
        open_equity = cash + sum(qty * prices[day, symbol]['open'] for symbol, qty in holdings.items())
        open_gap = index > 0 and not hard and open_equity <= .9 * peak + 1e-8
        if open_gap:
            hard, trigger = True, day
        expected_open = None
        if index:
            previous_total = sum(previous_target.values())
            ratio = min(1., budget(open_equity, peak) / previous_total) if previous_total else 0.
            expected_open = {symbol: 0. if hard else previous_target[symbol] * ratio for symbol in SYMBOLS}
        actual_open = target_values(row, 'executed_target_')
        if actual_open is not None:
            if expected_open is None:
                raise ValueError('Execution before initial cash baseline')
            for symbol in SYMBOLS:
                equal(actual_open[symbol], expected_open[symbol], 'open downside-only cap')
        expected_fills = []
        if expected_open is not None:
            simulated_cash = cash
            simulated_holdings = {symbol: holdings.get(symbol, 0.) for symbol in SYMBOLS}
            desired = {symbol: open_equity * expected_open[symbol] / (prices[day, symbol]['open'] * (1 + slip) * (1 + fee_rate)) for symbol in SYMBOLS}
            for side in ('SELL', 'BUY'):
                for symbol in sorted(SYMBOLS):
                    fill = prices[day, symbol]['open'] * (1 - slip if side == 'SELL' else 1 + slip)
                    if side == 'SELL':
                        quantity = max(0., simulated_holdings[symbol] - desired[symbol])
                    else:
                        quantity = min(max(0., desired[symbol] - simulated_holdings[symbol]), max(0., simulated_cash) * .99 / (fill * (1 + fee_rate)))
                    if quantity * fill <= 1e-8:
                        continue
                    commission = quantity * fill * fee_rate
                    expected_fills.append((side, symbol, quantity))
                    simulated_cash += quantity * fill - commission if side == 'SELL' else -quantity * fill - commission
                    simulated_holdings[symbol] += -quantity if side == 'SELL' else quantity
        actual_fills = by_day.get(day, [])
        if len(expected_fills) != len(actual_fills):
            raise ValueError('Missing/extra execution versus declared CPPI targets')
        for actual, (side, symbol, quantity) in zip(actual_fills, expected_fills):
            if (actual['side'], actual['symbol']) != (side, symbol):
                raise ValueError('Execution side/symbol ordering differs from locked plan')
            equal(actual['quantity'], quantity, 'independent target quantity with sequential cash cap')
        fees = 0.
        for trade in by_day.get(day, []):
            same_gap = open_gap and trade['side'] == 'SELL' and trade['reason'] == 'HARD_OPEN_GAP' and trade['signal_date'] == day
            if not index or (not same_gap and trade['signal_date'] != dates[index - 1]):
                raise ValueError('Fill not next session after signal')
            side, symbol = trade['side'], trade['symbol']
            quantity, price, fee = (float(trade[key]) for key in ('quantity', 'price', 'fee'))
            if side not in ('BUY', 'SELL') or not all(math.isfinite(x) for x in (quantity, price, fee)) or quantity <= 0 or price <= 0 or fee < 0:
                raise ValueError('Invalid fill')
            equal(price, prices[day, symbol]['open'] * (1 + slip if side == 'BUY' else 1 - slip), 'next-open fill')
            equal(fee, quantity * price * fee_rate, 'commission')
            if side == 'BUY':
                if hard:
                    raise ValueError('Buy after permanent hard latch')
                if quantity * price + fee > cash * .99 + 1e-6:
                    raise ValueError('Buy exceeds fee-inclusive 99% cash ceiling')
                cash -= quantity * price + fee
                holdings[symbol] = holdings.get(symbol, 0.) + quantity
            else:
                if quantity > holdings.get(symbol, 0.) + 1e-7:
                    raise ValueError('Oversold holding')
                cash += quantity * price - fee
                holdings[symbol] -= quantity
                if abs(holdings[symbol]) < 1e-7:
                    del holdings[symbol]
            if cash < -1e-6:
                raise ValueError('Borrowed cash')
            fees += fee
        if hard and holdings:
            raise ValueError('Hard latch not liquidated at open')
        if expected_open is not None:
            for symbol in SYMBOLS:
                maximum = open_equity * expected_open[symbol] / (prices[day, symbol]['open'] * (1 + slip) * (1 + fee_rate))
                if holdings.get(symbol, 0.) > maximum + 1e-6:
                    raise ValueError('Holding exceeds CPPI execution budget')
        equity = cash + sum(qty * prices[day, symbol]['close'] for symbol, qty in holdings.items())
        peak = max(peak, equity)
        dd = max(0., 1 - equity / peak)
        if dd >= .10 - 1e-12 and not hard:
            hard, trigger = True, day
        mdd = max(mdd, dd)
        for symbol in SYMBOLS:
            history[symbol].append(prices[day, symbol]['close'])
        if candidate != 'D' and any(len(history[symbol]) < 200 for symbol in SYMBOLS):
            raise ValueError('Insufficient signal warmup')
        above_sma = {symbol: history[symbol][-1] > math.fsum(history[symbol][-200:]) / 200 for symbol in SYMBOLS} if candidate != 'D' else {}
        if candidate == 'D':
            active_slots, slot_count = ['SPY'], 1
        elif candidate == 'E':
            active_slots, slot_count = (['QQQ'] if above_sma['QQQ'] else []), 1
        else:
            month_end = next_day[day] is not None and day[:7] != next_day[day][:7]
            if month_end:
                scores = {symbol: history[symbol][-1] / history[symbol][-127] - 1 for symbol in SYMBOLS}
                active_slots = sorted([symbol for symbol in SYMBOLS if above_sma[symbol] and scores[symbol] > 0], key=lambda symbol: (-scores[symbol], symbol))[:2]
                slot_count = len(active_slots)
            else:
                active_slots = [symbol for symbol in active_slots if above_sma[symbol]]
        cap = budget(equity, peak)
        expected_close = {symbol: cap / slot_count if symbol in active_slots and slot_count and not hard else 0. for symbol in SYMBOLS}
        actual_close = target_values(row, 'decision_target_')
        if actual_close is None:
            if not hard:
                raise ValueError('Missing daily CPPI decision')
            actual_close = {symbol: 0. for symbol in SYMBOLS}
        for symbol in SYMBOLS:
            equal(actual_close[symbol], expected_close[symbol], 'independent candidate/CPPI close target')
        previous_target = expected_close
        for field, expected in [('equity_usd', equity), ('cash_usd', cash), ('peak_equity_usd', peak), ('drawdown_percent', dd * 100),
                                ('fees_usd', fees), ('external_flow_usd', 0), ('cppi_floor_usd', .9 * peak),
                                ('cppi_cushion_usd', max(0., equity - .9 * peak)), ('cppi_budget_weight', 0. if hard else cap),
                                ('sp500_total_return_index', prices[day, benchmark]['close'])]:
            equal(row[field], expected, field)
        if boolean(row['hard_latched']) != hard:
            raise ValueError('Hard latch reset/missed trigger')
        exposure.append((equity - cash) / equity)
    initial_equity, final_equity = float(rows[0]['equity_usd']), float(rows[-1]['equity_usd'])
    years = (date.fromisoformat(dates[-1]) - date.fromisoformat(dates[0])).days / 365.25
    total_return = final_equity / initial_equity - 1
    benchmark_return = prices[dates[-1], benchmark]['close'] / prices[dates[0], benchmark]['close'] - 1
    for field, expected in [('strategy_twr', total_return), ('strategy_cagr', (1 + total_return) ** (1 / years) - 1),
                            ('strategy_max_drawdown', mdd), ('benchmark_total_return', benchmark_return),
                            ('excess_return_percentage_points', (total_return - benchmark_return) * 100)]:
        equal(report['metrics'][field], expected, field)
    if report['latch_date'] != trigger:
        raise ValueError('Latch date mismatch')
    return {'status': 'PASS', 'sessions': len(rows), 'trades': len(trades), 'latch_date': trigger,
            'average_close_exposure_percent': math.fsum(exposure) / len(exposure) * 100,
            'maximum_close_exposure_percent': max(exposure) * 100,
            'limits': 'Independent ledger/CPPI/signal audit of hypothetical adjusted fractional full fills; not live execution or future-profit evidence.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('prefix', type=Path)
    parser.add_argument('--market', type=Path, required=True)
    args = parser.parse_args()
    paths = [args.market, Path(str(args.prefix)+'.json'), Path(str(args.prefix)+'-trades.json'), Path(str(args.prefix)+'-equity.csv')]
    with paths[0].open(newline='', encoding='utf-8-sig') as stream:
        prices = {(row['date'], row['symbol']): {key: float(row[key]) for key in ('open', 'close')} for row in csv.DictReader(stream)}
    report, trades = (json.loads(path.read_text(encoding='utf-8')) for path in paths[1:3])
    with paths[3].open(newline='', encoding='utf-8') as stream:
        rows = list(csv.DictReader(stream))
    result = audit(prices, rows, trades, report)
    result['input_sha256'] = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
