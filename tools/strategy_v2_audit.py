"""Independent offline accounting/policy audit. Does not import the strategy engine."""
import argparse
import csv
import hashlib
import json
import math
from datetime import date
from pathlib import Path


def equal(actual, expected, label):
    if not math.isfinite(float(actual)) or not math.isclose(float(actual), float(expected), rel_tol=1e-9, abs_tol=1e-6):
        raise ValueError(f"{label}: {actual} != {expected}")


def boolean(value):
    if value is True or value == 'True' or value == 'true':
        return True
    if value is False or value == 'False' or value == 'false':
        return False
    raise ValueError(f"Invalid boolean {value}")


def audit(prices, rows, trades, report):
    if len(rows) < 2:
        raise ValueError('At least two valuation sessions required')
    dates = [row['date'] for row in rows]
    expected = sorted(day for day, symbol in prices if symbol == 'SPY' and dates[0] <= day <= dates[-1])
    if dates != expected or dates != sorted(set(dates)):
        raise ValueError('Valuation sessions missing, duplicated or unordered')
    if report['start'] != dates[0] or report['end'] != dates[-1]:
        raise ValueError('Report window mismatch')
    benchmark = report.get('benchmark_symbol', 'SPY')
    if benchmark not in ('SPY', '^SP500TR'):
        raise ValueError('Undeclared benchmark')
    by_day = {}
    for trade in trades:
        if trade['date'] not in dates or trade['symbol'] not in ('SPY', 'QQQ'):
            raise ValueError('Trade outside declared universe/window')
        by_day.setdefault(trade['date'], []).append(trade)
    cash = peak = float(report['initial_cash_usd'])
    fee_rate, slip = report['fee_bps_per_side'] / 10000, report['slippage_bps_per_side'] / 10000
    if cash <= 0 or fee_rate < 0 or not 0 <= slip < 1:
        raise ValueError('Invalid declared assumptions')
    positions, hard, soft, trigger, mdd, exposures = {}, False, False, None, 0, []
    previous = None
    for index, row in enumerate(rows):
        day, fees = row['date'], 0
        open_equity = cash + sum(qty * prices[day, symbol]['open'] for symbol, qty in positions.items())
        open_gap = not hard and open_equity <= peak * .90 + 1e-8
        if open_gap:
            hard, trigger = True, day
        for trade in by_day.get(day, []):
            same_open_gap = open_gap and trade.get('reason') == 'HARD_OPEN_GAP' and trade['side'] == 'SELL' and trade['signal_date'] == day
            if not index or (not same_open_gap and trade['signal_date'] != dates[index - 1]):
                raise ValueError('Trade is not next session after signal')
            side, symbol = trade['side'], trade['symbol']
            quantity, price, fee = (float(trade[key]) for key in ('quantity', 'price', 'fee'))
            if not all(math.isfinite(value) for value in (quantity, price, fee)) or quantity <= 0 or price <= 0 or fee < 0:
                raise ValueError('Invalid fill')
            if side not in ('BUY', 'SELL'):
                raise ValueError('Unknown side')
            equal(price, prices[day, symbol]['open'] * (1 + slip if side == 'BUY' else 1 - slip), 'next-open fill')
            equal(fee, quantity * price * fee_rate, 'commission')
            if side == 'BUY':
                if hard:
                    raise ValueError('Buy after permanent hard latch')
                if trade.get('reason') != 'REBALANCE':
                    raise ValueError('Emergency decision increased exposure')
                if trade['signal_date'][:7] == day[:7]:
                    raise ValueError('Exposure increase outside month-end signal')
                if quantity * price + fee > cash * .99 + 1e-6:
                    raise ValueError('Buy exceeds 99% available-cash ceiling')
                cash -= quantity * price + fee
                positions[symbol] = positions.get(symbol, 0) + quantity
            else:
                if quantity > positions.get(symbol, 0) + 1e-7:
                    raise ValueError('Oversold position')
                cash += quantity * price - fee
                positions[symbol] -= quantity
                if abs(positions[symbol]) < 1e-7:
                    del positions[symbol]
            if cash < -1e-6:
                raise ValueError('Borrowing/cash limit violation')
            fees += fee
        if hard and positions:
            raise ValueError('Hard latch not fully liquidated at next open')
        equity = cash + sum(qty * prices[day, symbol]['close'] for symbol, qty in positions.items())
        peak = max(peak, equity)
        drawdown = (1 - equity / peak) * 100
        if drawdown >= 10 - 1e-9 and not hard:
            hard, trigger = True, day
        previous_soft = soft
        if not hard:
            if drawdown >= 6 - 1e-9:
                soft = True
            elif drawdown <= 3 + 1e-9:
                soft = False
        mdd = max(mdd, drawdown / 100)
        equal(row['equity_usd'], equity, 'daily equity')
        equal(row['cash_usd'], cash, 'cash balance')
        equal(row['peak_equity_usd'], peak, 'high-water mark')
        equal(row['drawdown_percent'], drawdown, 'drawdown')
        equal(row['fees_usd'], fees, 'daily fees')
        equal(row['external_flow_usd'], 0, 'external flow')
        equal(row['sp500_total_return_index'], prices[day, benchmark]['close'], 'declared benchmark level')
        if boolean(row['hard_latched']) != hard:
            raise ValueError('Hard latch state inconsistent with equity path')
        if boolean(row['soft_active']) != soft:
            raise ValueError('Soft hysteresis state inconsistent with equity path')
        if row.get('decision_reason') == 'SOFT_REDUCE' and (not soft or previous_soft):
            raise ValueError('Repeated soft rebalance without new activation')
        for symbol in ('spy', 'qqq'):
            executed = row.get(f'executed_target_{symbol}')
            if executed not in (None, ''):
                max_units = open_equity * float(executed) / (prices[day, symbol.upper()]['open'] * (1 + slip) * (1 + fee_rate))
                if positions.get(symbol.upper(), 0) > max_units + 1e-6:
                    raise ValueError('Actual holding exceeds executed notional target')
                if open_gap:
                    equal(executed, 0, 'open-gap zero target')
                elif previous is None or previous.get(f'decision_target_{symbol}') in (None, ''):
                    raise ValueError('Execution without previous-close decision')
                else:
                    equal(executed, previous[f'decision_target_{symbol}'], 'lagged decision target')
        if hard:
            for symbol in ('spy', 'qqq'):
                target = row.get(f'decision_target_{symbol}')
                if target not in (None, ''):
                    equal(target, 0, 'latched decision target')
        targets = [row.get(f'decision_target_{symbol}') for symbol in ('spy', 'qqq')]
        if any(target not in (None, '') for target in targets):
            if any(target in (None, '') for target in targets):
                raise ValueError('Incomplete target pair')
            total_target = sum(float(target) for target in targets)
            if any(float(target) < 0 for target in targets) or total_target > .99 + 1e-9:
                raise ValueError('Invalid leverage/negative target')
            if boolean(row['soft_active']) and total_target > .25 + 1e-9:
                raise ValueError('Soft policy target exceeds 25% cap')
        exposures.append((equity - cash) / equity)
        previous = row
    equal(report['metrics']['strategy_twr'], float(rows[-1]['equity_usd']) / float(rows[0]['equity_usd']) - 1, 'total return')
    years = (date.fromisoformat(dates[-1]) - date.fromisoformat(dates[0])).days / 365.25
    equal(report['metrics']['strategy_cagr'], (float(rows[-1]['equity_usd']) / float(rows[0]['equity_usd'])) ** (1 / years) - 1, 'CAGR')
    equal(report['metrics']['strategy_max_drawdown'], mdd, 'maximum drawdown')
    benchmark_return = prices[dates[-1], benchmark]['close'] / prices[dates[0], benchmark]['close'] - 1
    if 'benchmark_total_return' in report['metrics']:
        equal(report['metrics']['benchmark_total_return'], benchmark_return, 'same-window benchmark return')
        equal(report['metrics']['excess_return_percentage_points'], (float(rows[-1]['equity_usd']) / float(rows[0]['equity_usd']) - 1 - benchmark_return) * 100, 'benchmark excess return')
    if report['latch_date'] != trigger:
        raise ValueError('Latch date mismatch')
    return {'status': 'PASS', 'sessions': len(rows), 'trades': len(trades), 'latch_date': trigger,
            'average_close_exposure_percent': sum(exposures) / len(exposures) * 100,
            'maximum_close_exposure_percent': max(exposures) * 100,
            'limits': 'Independent accounting, fill timing and hard-latch audit; signal formula, soft policy and benchmark comparability require separate source/prefix-invariance review.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('prefix', type=Path)
    parser.add_argument('--market', type=Path, required=True)
    args = parser.parse_args()
    paths = [args.market, Path(str(args.prefix) + '.json'), Path(str(args.prefix) + '-trades.json'), Path(str(args.prefix) + '-equity.csv')]
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
