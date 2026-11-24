"""Isolated SPY/QQQ research engine. No live application, credentials, account or orders."""
import argparse
import csv
import hashlib
import json
import math
import statistics
from dataclasses import asdict, dataclass
from datetime import date
from pathlib import Path
from replay_daily import load_prices
from us_performance import metrics

SYMBOLS = ("SPY", "QQQ")

@dataclass(frozen=True)
class Parameters:
    sma_sessions: int = 200
    momentum_sessions: int = 252
    volatility_sessions: int = 20
    volatility_target: float = .12
    max_weight: float = .99
    soft_threshold: float = .06
    soft_recovery: float = .03
    soft_cap: float = .25
    hard_threshold: float = .10


def validate_parameters(p):
    if min(p.sma_sessions,p.momentum_sessions,p.volatility_sessions) < 2:
        raise ValueError("At least two observations per lookback")
    if not 0 < p.soft_recovery < p.soft_threshold < p.hard_threshold < 1:
        raise ValueError("Invalid risk thresholds")
    if not 0 < p.soft_cap < p.max_weight <= .99 or p.volatility_target <= 0:
        raise ValueError("Invalid exposure constraints")


def signal_target(candidate, history, p=Parameters()):
    """Completed closes through today only. Inputs never include tomorrow's prices."""
    if candidate not in ("A","B","C"):
        raise ValueError("Unknown predeclared candidate")
    need = p.sma_sessions if candidate == "A" else max(p.sma_sessions,p.momentum_sessions+1,p.volatility_sessions+1)
    if any(len(history[symbol]) < need for symbol in SYMBOLS):
        raise ValueError("Insufficient pre-period warmup")
    eligible = []
    for symbol in SYMBOLS:
        closes=history[symbol]
        if closes[-1] <= statistics.fmean(closes[-p.sma_sessions:]):
            continue
        if candidate == "A":
            if symbol == "SPY": return {"SPY":p.max_weight,"QQQ":0.0}
            continue
        momentum=closes[-1]/closes[-p.momentum_sessions-1]-1
        if momentum > 0: eligible.append((momentum,1 if symbol=="SPY" else 0,symbol))
    if not eligible: return {"SPY":0.0,"QQQ":0.0}
    symbol=max(eligible)[2]
    weight=p.max_weight
    if candidate=="C":
        closes=history[symbol]
        returns=[math.log(closes[i]/closes[i-1]) for i in range(len(closes)-p.volatility_sessions,len(closes))]
        volatility=statistics.stdev(returns)*math.sqrt(252)
        weight=min(weight,p.volatility_target/volatility) if volatility>0 else weight
    return {key:weight if key==symbol else 0.0 for key in SYMBOLS}


def simulate(prices, candidate, start, end, fee_bps=5, slippage_bps=5, initial=10000, parameters=Parameters()):
    p=parameters;validate_parameters(p)
    if candidate not in ("A","B","C") or not all(math.isfinite(v) for v in (fee_bps,slippage_bps,initial)) or min(fee_bps,slippage_bps)<0 or max(fee_bps,slippage_bps)>=10000 or initial<=0:
        raise ValueError("Invalid execution assumptions")
    date.fromisoformat(start);date.fromisoformat(end)
    all_dates=sorted(day for day,symbol in prices if symbol=="SPY")
    if sorted(day for day,symbol in prices if symbol=="QQQ") != all_dates:
        raise ValueError("SPY/QQQ trading calendars must match exactly")
    selected=[day for day in all_dates if start<=day<=end]
    benchmark_symbol="^SP500TR" if any(symbol=="^SP500TR" for _,symbol in prices) else "SPY"
    if any((day,benchmark_symbol) not in prices for day in selected):
        raise ValueError("Missing benchmark session")
    if len(selected)<2: raise ValueError("At least two evaluation sessions")
    for day in all_dates:
        for symbol in SYMBOLS:
            bar=prices[day,symbol]
            if any(not math.isfinite(bar[name]) or bar[name]<=0 for name in ("open","high","low","close")):
                raise ValueError("Missing or invalid OHLC")
    history={symbol:[] for symbol in SYMBOLS}
    first_index=all_dates.index(selected[0])
    for day in all_dates[:first_index]:
        for symbol in SYMBOLS: history[symbol].append(prices[day,symbol]["close"])
    # Equal evaluation starts: reject missing warmup rather than silently begin later.
    signal_target(candidate,{s:history[s]+[prices[selected[0],s]["close"]] for s in SYMBOLS},p)
    next_calendar={day:all_dates[i+1] if i+1<len(all_dates) else None for i,day in enumerate(all_dates)}
    cash=float(initial);quantities={s:0.0 for s in SYMBOLS};peak=float(initial)
    fee=fee_bps/10000;slip=slippage_bps/10000
    pending=None;soft=False;hard=False;latch_date=None;latch_source=None
    ledger=[];rows=[];transitions=[];daily_fees=0

    def execute(target,day,signal_date,reason):
        nonlocal cash,daily_fees
        equity_open=cash+sum(quantities[s]*prices[day,s]["open"] for s in SYMBOLS)
        # Target is a notional budget INCLUDING execution fee. Slippage is in fills.
        desired={s:equity_open*target[s]/(prices[day,s]["open"]*(1+slip)*(1+fee)) for s in SYMBOLS}
        for symbol in SYMBOLS:
            sell_quantity=max(0.0,quantities[symbol]-desired[symbol])
            if sell_quantity*prices[day,symbol]["open"]<=1e-8: continue
            fill=prices[day,symbol]["open"]*(1-slip);commission=sell_quantity*fill*fee
            quantities[symbol]-=sell_quantity;cash+=sell_quantity*fill-commission;daily_fees+=commission
            ledger.append({"date":day,"signal_date":signal_date,"side":"SELL","symbol":symbol,"quantity":sell_quantity,"price":fill,"fee":commission,"reason":reason})
        # Emergency transitions only reduce shares, even if an overnight gap lowers
        # the existing weight below the previously requested exposure cap.
        if reason != "REBALANCE": return
        for symbol in SYMBOLS:
            wanted=max(0.0,desired[symbol]-quantities[symbol])
            fill=prices[day,symbol]["open"]*(1+slip)
            # Cash-only order budget: retain at least 1% of available cash each buy.
            buy_quantity=min(wanted,max(0.0,cash)*p.max_weight/(fill*(1+fee)))
            if buy_quantity*fill<=1e-8: continue
            commission=buy_quantity*fill*fee
            cash-=buy_quantity*fill+commission;quantities[symbol]+=buy_quantity;daily_fees+=commission
            if cash < -1e-7: raise AssertionError("Borrowed cash would be required")
            ledger.append({"date":day,"signal_date":signal_date,"side":"BUY","symbol":symbol,"quantity":buy_quantity,"price":fill,"fee":commission,"reason":reason})

    for index,day in enumerate(selected):
        daily_fees=0;executed=None;executed_reason=""
        equity_open=cash+sum(quantities[s]*prices[day,s]["open"] for s in SYMBOLS)
        if index and not hard and equity_open<=peak*(1-p.hard_threshold):
            hard=True;latch_date=day;latch_source="OPEN_GAP"
            transitions.append({"date":day,"type":"HARD_OPEN_GAP","equity":equity_open,"previous_close_peak":peak})
            pending=({s:0.0 for s in SYMBOLS},day,"HARD_OPEN_GAP")
        if index and pending is not None:
            target,signal_date,reason=pending
            if hard: target={s:0.0 for s in SYMBOLS}
            execute(target,day,signal_date,reason)
            executed=target;executed_reason=reason;pending=None
        for symbol in SYMBOLS:history[symbol].append(prices[day,symbol]["close"])
        equity=cash+sum(quantities[s]*prices[day,s]["close"] for s in SYMBOLS)
        peak=max(peak,equity)
        drawdown=max(0.0,1-equity/peak)
        previous_soft=soft
        if not hard and drawdown>=p.hard_threshold-1e-12:
            hard=True;latch_date=day;latch_source="CLOSE"
            transitions.append({"date":day,"type":"HARD_CLOSE","equity":equity,"peak":peak})
        if not hard:
            if drawdown>=p.soft_threshold-1e-12:soft=True
            elif drawdown<=p.soft_recovery+1e-12:soft=False
            if soft!=previous_soft:transitions.append({"date":day,"type":"SOFT_ON" if soft else "SOFT_OFF","drawdown_percent":drawdown*100})
        next_day=next_calendar[day]
        # Calendar dates may be known in advance; no next-day OHLC enters decisions.
        month_end=next_day is not None and day[:7]!=next_day[:7]
        current={s:quantities[s]*prices[day,s]["close"]/equity for s in SYMBOLS}
        decision=None;reason=""
        if hard:
            if sum(quantities.values())>1e-12:decision={s:0.0 for s in SYMBOLS};reason="HARD_LATCH"
        elif month_end:
            decision=signal_target(candidate,history,p);reason="REBALANCE"
            if soft:
                total=sum(decision.values())
                if total>p.soft_cap:decision={s:w*p.soft_cap/total for s,w in decision.items()}
        else:
            decision=dict(current)
            changed=False
            for symbol in SYMBOLS:
                if quantities[symbol]>1e-12 and history[symbol][-1]<=statistics.fmean(history[symbol][-p.sma_sessions:]):
                    decision[symbol]=0.0;changed=True;reason="SMA_OFF"
            total=sum(decision.values())
            if soft and not previous_soft and total>p.soft_cap+1e-12:
                decision={s:w*p.soft_cap/total for s,w in decision.items()};changed=True;reason="SOFT_REDUCE"
            if not changed:decision=None
        if decision is not None:pending=(decision,day,reason)
        rows.append({"date":day,"equity_usd":equity,"external_flow_usd":0,"fees_usd":daily_fees,
          "sp500_total_return_index":prices[day,benchmark_symbol]["close"],"spy_total_return_index":prices[day,"SPY"]["close"],"cash_usd":cash,"peak_equity_usd":peak,
          "drawdown_percent":drawdown*100,"soft_active":soft,"hard_latched":hard,
          "decision_target_spy":"" if decision is None else decision["SPY"],"decision_target_qqq":"" if decision is None else decision["QQQ"],
          "executed_target_spy":"" if executed is None else executed["SPY"],"executed_target_qqq":"" if executed is None else executed["QQQ"],
          "decision_reason":reason,"executed_reason":executed_reason})
    metric_rows=[{**row,"date":date.fromisoformat(row["date"])} for row in rows]
    report={"status":"ISOLATED_RESEARCH_NOT_PRODUCTION","profitability_proven":False,"candidate_id":candidate,
      "start":selected[0],"end":selected[-1],"initial_cash_usd":initial,"fee_bps_per_side":fee_bps,"slippage_bps_per_side":slippage_bps,
      "parameters":asdict(p),"soft_enabled":True,"hard_drawdown_percent":p.hard_threshold*100,
      "soft_threshold_percent":p.soft_threshold*100,"soft_recovery_percent":p.soft_recovery*100,"soft_cap":p.soft_cap*100,
      "latch_date":latch_date,"latch_source":latch_source,"latch_triggered":hard,"risk_transitions":transitions,
      "buy_count":sum(t["side"]=="BUY" for t in ledger),"sell_count":sum(t["side"]=="SELL" for t in ledger),
      "ending_cash":cash,"ending_positions":{s:q for s,q in quantities.items() if q>1e-12},"metrics":metrics(metric_rows),
      "benchmark_symbol":benchmark_symbol,"spy_comparison_metrics":metrics([{**row,"sp500_total_return_index":row["spy_total_return_index"]} for row in metric_rows]),
      "average_invested_percent":statistics.fmean((row["equity_usd"]-row["cash_usd"])/row["equity_usd"] for row in rows)*100,
      "average_cash_percent":statistics.fmean(row["cash_usd"]/row["equity_usd"] for row in rows)*100,
      "annualized_gross_turnover":sum(t["quantity"]*t["price"] for t in ledger)/statistics.fmean(row["equity_usd"] for row in rows)/((date.fromisoformat(selected[-1])-date.fromisoformat(selected[0])).days/365.25),
      "limitations":["Dividend/split-adjusted synthetic fractional units, not integer shares or dividend cash accounting.",
       "SPY/QQQ fixed ETFs; no claim of production strategy equivalence or optimized parameters.",
       "Monthly close decisions fill next open; daily SMA-off/soft reductions and hard risk override the schedule.",
       "First entry only after first month-end; every evaluation period resets to cash with no previous holdings.",
       "Close-only high-water equity; open prices detect downside gaps but never raise the peak.",
       "10% is a trigger, not a loss guarantee: intraday gaps and close-to-open execution may exceed it.",
       "All orders fill completely at modeled open plus cost; no partial fills, halts or order-book model.",
       "No flows, leverage, cash interest, taxes or forced final liquidation. ^SP500TR uses provided total-return index; SPY adjusted close is auxiliary.",
       "Low drawdown may reflect cash exposure; retrospective periods do not prove future performance."]}
    return rows,ledger,report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prices",type=Path);parser.add_argument("--candidate",choices=["A","B","C"],required=True)
    parser.add_argument("--start",required=True);parser.add_argument("--end",required=True)
    parser.add_argument("--fee-bps",type=float,default=5);parser.add_argument("--slippage-bps",type=float,default=5)
    parser.add_argument("--output-prefix",type=Path,required=True)
    parser.add_argument("--lock-file",type=Path,required=True);parser.add_argument("--lock-sha256",required=True)
    args=parser.parse_args()
    try:
        lock_hash=hashlib.sha256(args.lock_file.read_bytes()).hexdigest()
        if lock_hash!=args.lock_sha256.lower():raise ValueError("Locked protocol SHA256 does not match")
        rows,trades,report=simulate(load_prices(args.prices),args.candidate,args.start,args.end,args.fee_bps,args.slippage_bps)
        report["protocol_lock"]={"path":str(args.lock_file),"sha256":lock_hash}
        report["input_sha256"]={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in (args.prices,Path(__file__))}
        prefix=args.output_prefix;prefix.parent.mkdir(parents=True,exist_ok=True)
        for suffix,data in [(".json",report),("-trades.json",trades)]:Path(str(prefix)+suffix).write_text(json.dumps(data,ensure_ascii=False,indent=2,allow_nan=False)+"\n",encoding="utf-8")
        with Path(str(prefix)+"-equity.csv").open("w",encoding="utf-8",newline="") as stream:
            writer=csv.DictWriter(stream,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
        print(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False))
    except (ValueError,KeyError,OSError,statistics.StatisticsError) as error:parser.error(str(error))

if __name__=="__main__":main()
