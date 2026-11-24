"""Incremental, durable-compatible virtual ledger; no broker or network calls.

Copies the validated TEAM24 daily execution transition. A human-approved switch
changes the next completed-close decision, never prior trades or the risk peak.
"""
import math
from copy import deepcopy
from team24_research import (SYMBOLS, CANDIDATES, RISK_POLICIES, risk_target,
                             select_basket, weights_for, order_mode)


def new_account(initial, strategy_id):
    if not math.isfinite(initial) or not 100 <= initial <= 100000000:
        raise ValueError('가상 시작 금액은 100~100000000 USD여야 합니다.')
    return dict(initial=float(initial), cash=float(initial), dividends=0., unsettled=0.,
                qty={s:0 for s in SYMBOLS}, peak=float(initial), mdd=0., fees=0., slippage=0.,
                hard=False,latch=None,pending=None,basket=[],anchor=None,date=None,
                strategy_id=strategy_id,history=[],trades=[],switch_to=None)


def advance(account, data, signals, index, cost_bps=25):
    state = deepcopy(account)
    calendar = [r['date'] for r in data['SPY']]
    if index < 199 or state['date'] and calendar[index] <= state['date']:
        raise ValueError('새 완료 거래일만 처리할 수 있습니다.')
    strategy_id = state['strategy_id']
    candidate, risk_policy = strategy_id.split(':')
    if candidate not in CANDIDATES or risk_policy not in RISK_POLICIES or cost_bps not in (10,25):
        raise ValueError('등록되지 않은 전략입니다.')
    cash,dividends,unsettled=state['cash'],state['dividends'],state['unsettled']
    qty=state['qty']; peak,mdd,fees,slippage=state['peak'],state['mdd'],state['fees'],state['slippage']
    hard,latch,pending,basket=state['hard'],state['latch'],state['pending'],state['basket']
    anchor=state['anchor']; trades=[];history=[]
    fee=slip=cost_bps/20000.
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

    for n, i in enumerate([index]):
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
        mode = 'HARD' if hard else pending['mode'] if pending else 'HOLD'
        if not hard and pending and mode in ('FULL', 'REDUCE'):
            # Reference is the prior-close target implemented this open, not actual exposure.
            anchor = pending['gross']
        day_trades = []
        full_shortfall = {}
        if mode != 'HOLD':
            if mode == 'HARD':
                desired = {s: 0 for s in SYMBOLS}
            elif mode == 'EXIT':
                desired = {s: qty[s] if s in pending['basket'] else 0 for s in SYMBOLS}
            else:
                desired = {s: math.floor(pending['weights'].get(s, 0) * open_equity / (opening[s] * (1 + slip) * (1 + fee))) for s in SYMBOLS}
                if mode == 'REDUCE':
                    desired = {s: min(qty[s], desired[s]) for s in SYMBOLS}
                elif mode == 'FUND':
                    # One T+1 completion only; never exceed original unfilled shares.
                    caps = {}
                    for s in SYMBOLS:
                        ratio = data[s][i]['split_numerator'] / data[s][i]['split_denominator']
                        cap = pending['fund_shortfall'].get(s, 0) * ratio
                        if abs(cap - round(cap)) > 1e-8:
                            raise ValueError('Fractional split funding cap')
                        caps[s] = round(cap)
                    desired = {s: qty[s] + min(caps[s], max(0, desired[s] - qty[s])) for s in SYMBOLS}
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
                             'reason': 'HARD' if hard else mode}
                    trades.append(trade)
                    day_trades.append(trade)
            observe(opening, date, 'after_sells')
            budget = cash * .99
            for s in sorted(SYMBOLS):
                if hard or mode not in ('FULL', 'FUND'):
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
                             'cash_after': cash, 'unsettled_after': unsettled, 'signal_date': pending['date'], 'reason': mode}
                    trades.append(trade)
                    day_trades.append(trade)
                    observe(opening, date, 'after_buy')
            if mode == 'FULL' and not hard:
                full_shortfall = {s: desired[s] - qty[s] for s in SYMBOLS if desired[s] > qty[s]}
        value, dd = observe(closing, date, 'close')
        snapshot = {s: dict(signals[s][i]) for s in SYMBOLS}
        switched = bool(state.get('switch_to')) and not hard
        if switched:
            strategy_id = state['switch_to']
            candidate, risk_policy = strategy_id.split(':')
        monthly = switched or not state.get('date') or date[:7] != state['date'][:7]
        previous_basket = list(basket)
        basket = select_basket(candidate, basket, snapshot, monthly)
        volatility = snapshot['SPY']['vol20']
        gross = risk_target(value, peak, hard, risk_policy, volatility)
        weights = weights_for(candidate, basket, gross)
        next_mode = order_mode(monthly, gross, anchor, previous_basket, basket, hard)
        fund_shortfall = {}
        if next_mode == 'HOLD' and not hard and full_shortfall:
            next_mode = 'FUND'
            factor = min(1.0, gross / executed['gross']) if executed['gross'] > 0 else 0.0
            weights = {s: w * factor for s, w in executed['weights'].items()}
            fund_shortfall = full_shortfall
        pending = {'date': date, 'weights': weights, 'gross': gross, 'basket': list(basket), 'mode': next_mode,
                   'fund_shortfall': fund_shortfall}
        if cash < -1e-7 or unsettled < -1e-7 or any(q < 0 or not isinstance(q, int) for q in qty.values()):
            raise AssertionError('Cash/position invariant violated')
        history.append({'date': date, 'cash_before_settlement': cash_before_settlement, 'settled_today': settled_today,
                        'cash': cash, 'unsettled': unsettled, 'dividend_today': dividend_today, 'dividend_receivable': dividends,
                        'rebalance_anchor_gross': anchor, 'execution_mode': mode, 'signal_snapshot': snapshot, 'market_vol20': volatility,
                        'splits': splits, 'qty': dict(qty), 'open': opening, 'close': closing, 'equity': value, 'peak': peak,
                        'drawdown': dd, 'open_equity': open_equity, 'open_drawdown': open_dd, 'hard': hard,
                        'fees_cumulative': fees, 'slippage_cumulative': slippage, 'executed_target': executed,
                        'next_target': pending, 'trade_count': len(day_trades), 'exposure': sum(qty[s] * closing[s] for s in SYMBOLS) / value})

    state.update(cash=cash,dividends=dividends,unsettled=unsettled,qty=qty,peak=peak,mdd=mdd,
                 fees=fees,slippage=slippage,hard=hard,latch=latch,pending=pending,basket=basket,
                 anchor=anchor,date=calendar[index],strategy_id=strategy_id,switch_to=None)
    state['history'].extend(history)
    state['trades'].extend(trades)
    return state


def summary(account):
    value = account['history'][-1]['equity'] if account['history'] else account['initial']
    return {'cashUsd':account['cash'],'equityUsd':value,'peakUsd':account['peak'],
            'drawdownPct':max(0,1-value/account['peak'])*100,'maxDrawdownPct':account['mdd']*100,
            'hardLatched':account['hard'],'holdings':[{'symbol':s,'quantity':q} for s,q in account['qty'].items() if q],
            'unsettledUsd':account['unsettled'],'dividendReceivableUsd':account['dividends'],
            'returnPct':(value/account['initial']-1)*100,'days':len(account['history']),
            'fillCount':len(account['trades']),'feesUsd':account['fees'],'slippageUsd':account['slippage']}
