"""Independent recovery-state and accounting audit. Never imports a strategy engine."""
import argparse
import csv
import hashlib
import json
import math
from datetime import date
from pathlib import Path
from strategy_v3_audit import equal, boolean, target_values

SYMBOLS = ('GLD', 'IEF', 'QQQ', 'SPY')
CAPS = {'NORMAL': .8, 'WAIT': 0., 'PROBE25': .25, 'PROBE50': .5, 'RECOVERY80': .8, 'HARD': 0.}
RECOVERY = ('PROBE25', 'PROBE50', 'RECOVERY80')


def optional_equal(actual, expected, label):
    if expected is None:
        if actual not in (None, ''):
            raise ValueError(f'{label}: inactive reference must be empty')
    else:
        equal(actual, expected, label)


def expected_execution(cash, holdings, target, opening, fee, slip):
    equity = cash + math.fsum(holdings.get(symbol, 0.) * opening[symbol] for symbol in SYMBOLS)
    positions = {symbol: holdings.get(symbol, 0.) for symbol in SYMBOLS}
    desired = {symbol: equity * target[symbol] / (opening[symbol] * (1 + slip) * (1 + fee)) for symbol in SYMBOLS}
    fills = []
    for side in ('SELL', 'BUY'):
        for symbol in SYMBOLS:
            price = opening[symbol] * (1 - slip if side == 'SELL' else 1 + slip)
            quantity = max(0., positions[symbol] - desired[symbol]) if side == 'SELL' else min(max(0., desired[symbol] - positions[symbol]), max(0., cash) * .99 / (price * (1 + fee)))
            if quantity * (opening[symbol] if side == 'SELL' else price) <= 1e-8:
                continue
            commission = quantity * price * fee
            fills.append((side, symbol, quantity, price, commission))
            cash += quantity * price - commission if side == 'SELL' else -quantity * price - commission
            positions[symbol] += -quantity if side == 'SELL' else quantity
    return cash, positions, fills


def audit(prices, rows, trades, report):
    if report['candidate_id'] not in ('G', 'H') or report['benchmark_symbol'] != '^SP500TR':
        raise ValueError('Undeclared candidate or benchmark')
    dates = [row['date'] for row in rows]
    calendar = sorted(day for day, symbol in prices if symbol == 'SPY')
    if len(dates) < 2 or dates != sorted(set(dates)) or dates != [day for day in calendar if dates[0] <= day <= dates[-1]]:
        raise ValueError('Invalid valuation calendar')
    if (report['start'], report['end']) != (dates[0], dates[-1]):
        raise ValueError('Report window mismatch')
    following = {day: calendar[i+1] if i+1 < len(calendar) else None for i, day in enumerate(calendar)}
    history = {symbol: [prices[day, symbol]['close'] for day in calendar if day < dates[0]] for symbol in SYMBOLS}
    if any(len(values) < 199 for values in history.values()):
        raise ValueError('Insufficient warmup')
    by_day = {}
    for trade in trades:
        if trade['date'] not in dates or trade['symbol'] not in SYMBOLS:
            raise ValueError('Trade outside window/universe')
        by_day.setdefault(trade['date'], []).append(trade)
    cash = peak = float(report['initial_cash_usd'])
    fee, slip = report['fee_bps_per_side']/10000, report['slippage_bps_per_side']/10000
    if not math.isfinite(cash) or cash <= 0 or not 0 <= fee < 1 or not 0 <= slip < 1:
        raise ValueError('Invalid cash/cost assumptions')
    holdings = {symbol: 0. for symbol in SYMBOLS}
    pending = None
    state, anchor, count, wait_day0 = 'NORMAL', None, 0, None
    active_slots, slot_count, latch_date = [], 0, None
    mdd, exposures, reentries = 0., [], []
    for index, row in enumerate(rows):
        day = row['date']
        opening = {symbol: prices[day, symbol]['open'] for symbol in SYMBOLS}
        open_equity = cash + math.fsum(holdings[symbol] * opening[symbol] for symbol in SYMBOLS)
        override, fills = False, []
        if index:
            if state != 'HARD' and open_equity <= peak*.90 + 1e-10:
                state, anchor, count, wait_day0 = 'HARD', None, 0, None
                latch_date, override = day, True
            elif state == 'NORMAL' and open_equity <= peak*.94 + 1e-10:
                state, anchor, count, wait_day0 = 'WAIT', None, 0, None
                override = True
            elif state in RECOVERY and anchor is not None and open_equity <= anchor*.99 + 1e-10:
                state, anchor, count, wait_day0 = 'WAIT', None, 0, None
                override = True
            target = {symbol: 0. for symbol in SYMBOLS} if override or state == 'HARD' else dict(pending)
            if state == 'WAIT' and wait_day0 is None:
                wait_day0 = index
            before_trade = open_equity
            cash, holdings, fills = expected_execution(cash, holdings, target, opening, fee, slip)
            if state == 'PROBE25' and anchor is None and any(side == 'BUY' for side, *_ in fills) and math.fsum(holdings[symbol]*opening[symbol] for symbol in SYMBOLS) > 0:
                anchor, count, wait_day0 = before_trade, 0, None
                reentries.append(day)
            actual_target = target_values(row, 'executed_target_')
            if actual_target is None:
                raise ValueError('Missing executed target')
            for symbol in SYMBOLS:
                equal(actual_target[symbol], target[symbol], 'open target/risk priority')
        elif target_values(row, 'executed_target_') is not None:
            raise ValueError('Execution before baseline')
        if row['state_open'] != state:
            raise ValueError('Open state/risk priority mismatch')
        optional_equal(row['trial_anchor_open'], anchor, 'pretrade entry anchor')
        equal(row['stage_exposed_sessions_open'], count, 'open stage counter')
        actual_fills = by_day.get(day, [])
        if len(actual_fills) != len(fills):
            raise ValueError('Missing/extra fill against fixed target')
        for actual, (side, symbol, quantity, price, commission) in zip(actual_fills, fills):
            if (actual['side'], actual['symbol']) != (side, symbol):
                raise ValueError('Not alphabetical sells then buys')
            if actual['signal_date'] != (day if override else dates[index-1]):
                raise ValueError('Signal/fill day mismatch')
            for field, value in [('quantity', quantity), ('price', price), ('fee', commission)]:
                equal(actual[field], value, field)
        if cash < -1e-6 or min(holdings.values()) < -1e-7:
            raise ValueError('Borrowing or shorting')
        closing = {symbol: prices[day, symbol]['close'] for symbol in SYMBOLS}
        equity = cash + math.fsum(holdings[symbol]*closing[symbol] for symbol in SYMBOLS)
        invested = math.fsum(holdings[symbol]*closing[symbol] for symbol in SYMBOLS)
        peak = max(peak, equity)
        dd = max(0., 1-equity/peak)
        mdd = max(mdd, dd)
        for symbol in SYMBOLS:
            history[symbol].append(closing[symbol])
        trend = {symbol: history[symbol][-1] > math.fsum(history[symbol][-200:])/200 for symbol in SYMBOLS}
        if report['candidate_id'] == 'G':
            active_slots, slot_count = ['SPY'], 1
        elif following[day] is not None and following[day][:7] != day[:7]:
            strength = {symbol: history[symbol][-1]/history[symbol][-127]-1 for symbol in SYMBOLS}
            active_slots = sorted([symbol for symbol in SYMBOLS if trend[symbol] and strength[symbol] > 0], key=lambda symbol: (-strength[symbol], symbol))[:2]
            slot_count = len(active_slots)
        else:
            active_slots = [symbol for symbol in active_slots if trend[symbol]]
        if state != 'HARD' and dd >= .10-1e-12:
            state, anchor, count, wait_day0 = 'HARD', None, 0, None
            latch_date = day
        elif state == 'NORMAL' and dd >= .06-1e-12:
            state, anchor, count, wait_day0 = 'WAIT', None, 0, None
        elif state in RECOVERY and anchor is not None:
            if equity <= anchor*.99 + 1e-10 or not trend['SPY']:
                state, anchor, count, wait_day0 = 'WAIT', None, 0, None
            else:
                if invested > 1e-10:
                    count += 1
                if state in ('PROBE25', 'PROBE50') and count >= 20 and equity >= anchor and trend['SPY']:
                    state, count = ('PROBE50' if state == 'PROBE25' else 'RECOVERY80'), 0
                elif state == 'RECOVERY80' and dd <= .03+1e-12:
                    state, anchor, count = 'NORMAL', None, 0
        elif state == 'WAIT' and wait_day0 is not None and index-wait_day0 >= 20:
            if trend['SPY'] and history['SPY'][-1] > max(history['SPY'][-21:-1]) and active_slots:
                state, anchor, count = 'PROBE25', None, 0
        pending = {symbol: CAPS[state]/slot_count if symbol in active_slots and slot_count else 0. for symbol in SYMBOLS}
        actual_close = target_values(row, 'decision_target_')
        if actual_close is None:
            raise ValueError('Missing next-session decision')
        for symbol in SYMBOLS:
            equal(actual_close[symbol], pending[symbol], 'independent basket/state target')
        if row['state_close'] != state:
            raise ValueError('Close state/wait/promotion mismatch')
        optional_equal(row['trial_anchor'], anchor, 'episode anchor must not reset on promotion')
        equal(row['stage_exposed_sessions'], count, 'stage actual-exposure session counter')
        expected_wait_day = '' if wait_day0 is None else dates[wait_day0]
        if row['wait_day0'] != expected_wait_day:
            raise ValueError('WAIT liquidation day0 reset/missing')
        optional_equal(row['wait_elapsed'], None if wait_day0 is None else index-wait_day0, 'completed WAIT sessions')
        for field, expected in [('equity_usd', equity), ('cash_usd', cash), ('peak_equity_usd', peak), ('drawdown_percent', dd*100),
                                ('fees_usd', sum(fill[4] for fill in fills)), ('external_flow_usd', 0),
                                ('sp500_total_return_index', prices[day, '^SP500TR']['close'])]:
            equal(row[field], expected, field)
        if boolean(row['hard_latched']) != (state == 'HARD'):
            raise ValueError('Permanent hard state mismatch')
        exposures.append(invested/equity)
    total = float(rows[-1]['equity_usd'])/float(rows[0]['equity_usd'])-1
    benchmark = prices[dates[-1], '^SP500TR']['close']/prices[dates[0], '^SP500TR']['close']-1
    years = (date.fromisoformat(dates[-1])-date.fromisoformat(dates[0])).days/365.25
    for field, expected in [('strategy_twr', total), ('strategy_cagr', (1+total)**(1/years)-1), ('strategy_max_drawdown', mdd),
                            ('benchmark_total_return', benchmark), ('excess_return_percentage_points', (total-benchmark)*100)]:
        equal(report['metrics'][field], expected, field)
    if report['latch_date'] != latch_date:
        raise ValueError('Latch date mismatch')
    if report['wait_reentry_count'] != len(reentries) or [entry['date'] for entry in report['recovery_entries']] != reentries:
        raise ValueError('Actual recovery entry count/dates mismatch')
    return {'status': 'PASS', 'sessions': len(rows), 'trades': len(trades), 'latch_date': latch_date,
            'reentry_dates': reentries, 'average_close_exposure_percent': math.fsum(exposures)/len(exposures)*100,
            'maximum_close_exposure_percent': max(exposures)*100,
            'limits': 'Independent offline recovery state/signal/accounting audit; hypothetical full fills do not prove live safety or profitability.'}


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
