"""Isolated, predeclared CPPI ETF research. Never calls live APIs or production code."""
import argparse
import csv
import hashlib
import json
import math
import statistics
from dataclasses import asdict, dataclass, field
from datetime import date
from pathlib import Path
from replay_daily import load_prices
from us_performance import metrics

SYMBOLS=("GLD","IEF","QQQ","SPY")
BENCHMARK="^SP500TR"

@dataclass(frozen=True)
class Parameters:
    sma_sessions: int=200
    momentum_sessions: int=126
    multiplier: float=8.0
    floor_fraction: float=.90
    max_weight: float=.99
    hard_threshold: float=.10

@dataclass
class Account:
    cash: float=10000.0
    quantities: dict=field(default_factory=lambda:{s:0.0 for s in SYMBOLS})
    ledger: list=field(default_factory=list)

    def equity(self,marks):
        return self.cash+sum(self.quantities[s]*marks[s] for s in SYMBOLS)


def cppi_weight(equity,peak,p=Parameters()):
    if not math.isfinite(equity) or not math.isfinite(peak) or equity<=0 or peak<=0:
        raise ValueError("CPPI requires positive finite equity and peak")
    return min(p.max_weight,p.multiplier*max(0.0,equity-p.floor_fraction*peak)/equity)


def rebalance(account,target,opening,fee,slip,day,signal_date,reason,max_weight=.99):
    """Pure cash/quantity accounting; locked alphabetical sells, then cash-capped buys."""
    if set(target)!=set(SYMBOLS) or any(not math.isfinite(w) or w<0 for w in target.values()) or sum(target.values())>max_weight+1e-10:
        raise ValueError("Invalid cash-only target weights")
    equity=account.equity(opening)
    desired={s:equity*target[s]/(opening[s]*(1+slip)*(1+fee)) for s in SYMBOLS}
    fees=0.0
    for symbol in SYMBOLS:
        quantity=max(0.0,account.quantities[symbol]-desired[symbol])
        if quantity*opening[symbol]<=1e-8:continue
        price=opening[symbol]*(1-slip);commission=quantity*price*fee
        account.quantities[symbol]-=quantity;account.cash+=quantity*price-commission;fees+=commission
        account.ledger.append(dict(date=day,signal_date=signal_date,side="SELL",symbol=symbol,quantity=quantity,price=price,fee=commission,reason=reason))
    for symbol in SYMBOLS:
        wanted=max(0.0,desired[symbol]-account.quantities[symbol])
        price=opening[symbol]*(1+slip)
        quantity=min(wanted,max(0.0,account.cash)*max_weight/(price*(1+fee)))
        if quantity*price<=1e-8:continue
        commission=quantity*price*fee
        account.cash-=quantity*price+commission;account.quantities[symbol]+=quantity;fees+=commission
        account.ledger.append(dict(date=day,signal_date=signal_date,side="BUY",symbol=symbol,quantity=quantity,price=price,fee=commission,reason=reason))
    if account.cash<-1e-7 or any(q<-1e-10 for q in account.quantities.values()):raise AssertionError("Cash borrowing or short position")
    return fees


def trend_on(closes,p):
    return len(closes)>=p.sma_sessions and closes[-1]>statistics.fmean(closes[-p.sma_sessions:])


def select_f(history,p=Parameters()):
    eligible=[]
    for symbol in SYMBOLS:
        closes=history[symbol]
        if len(closes)<max(p.sma_sessions,p.momentum_sessions+1):raise ValueError("Missing F warmup")
        momentum=closes[-1]/closes[-p.momentum_sessions-1]-1
        if trend_on(closes,p) and momentum>0:eligible.append((symbol,momentum))
    return [symbol for symbol,_ in sorted(eligible,key=lambda item:(-item[1],item[0]))[:2]]


def simulate(prices,candidate,start,end,fee_bps=5,slippage_bps=5,initial=10000,parameters=Parameters()):
    p=parameters
    if candidate not in ("D","E","F"):raise ValueError("Unknown locked candidate")
    if min(p.sma_sessions,p.momentum_sessions)<2 or not 0<p.max_weight<=.99 or p.multiplier<=0 or abs(p.floor_fraction-(1-p.hard_threshold))>1e-12:
        raise ValueError("Invalid CPPI parameters")
    if not all(math.isfinite(v) for v in (fee_bps,slippage_bps,initial)) or min(fee_bps,slippage_bps)<0 or max(fee_bps,slippage_bps)>=10000 or initial<=0:
        raise ValueError("Invalid execution assumptions")
    date.fromisoformat(start);date.fromisoformat(end)
    calendar=sorted(day for day,symbol in prices if symbol=="SPY")
    for symbol in (*SYMBOLS,BENCHMARK):
        if sorted(day for day,name in prices if name==symbol)!=calendar:raise ValueError("All four ETF and total-return index calendars must match")
    selected=[day for day in calendar if start<=day<=end]
    if len(selected)<2:raise ValueError("At least two evaluation sessions")
    for day in calendar:
        for symbol in (*SYMBOLS,BENCHMARK):
            if any(not math.isfinite(prices[day,symbol][field]) or prices[day,symbol][field]<=0 for field in ("open","high","low","close")):
                raise ValueError("Invalid price")
    history={s:[] for s in SYMBOLS}
    for day in calendar[:calendar.index(selected[0])]:
        for symbol in SYMBOLS:history[symbol].append(prices[day,symbol]["close"])
    needed=1 if candidate=="D" else p.sma_sessions if candidate=="E" else max(p.sma_sessions,p.momentum_sessions+1)
    if any(len(history[s])+1<needed for s in SYMBOLS):raise ValueError("Insufficient warmup")
    next_day={day:calendar[i+1] if i+1<len(calendar) else None for i,day in enumerate(calendar)}
    account=Account(float(initial));peak=float(initial);fee=fee_bps/10000;slip=slippage_bps/10000
    hard=False;latch_date=None;latch_source=None;pending=None
    active_slots=[];slot_count=0;rows=[];transitions=[];selections=[]
    for index,day in enumerate(selected):
        opening={s:prices[day,s]["open"] for s in SYMBOLS}
        open_equity=account.equity(opening);fees=0.0;executed=None;executed_reason=""
        if index and not hard and open_equity<=peak*(1-p.hard_threshold)+1e-10:
            hard=True;latch_date=day;latch_source="OPEN_GAP"
            transitions.append(dict(date=day,type="HARD_OPEN_GAP",equity=open_equity,previous_close_peak=peak))
            pending=({s:0.0 for s in SYMBOLS},day,"HARD_OPEN_GAP")
        if index and pending is not None:
            target,signal_date,reason=pending
            target=dict(target)
            if hard:target={s:0.0 for s in SYMBOLS}
            else:
                # Opening prices may only SHRINK the prior close's budget. Selection
                # and peak cannot change here, and no future close enters this calculation.
                cap=cppi_weight(open_equity,peak,p);total=sum(target.values())
                if total>cap:
                    target={s:w*cap/total for s,w in target.items()}
                    reason="CPPI_OPEN_CAP"
            fees=rebalance(account,target,opening,fee,slip,day,signal_date,reason,p.max_weight)
            executed=target;executed_reason=reason;pending=None
        closing={s:prices[day,s]["close"] for s in SYMBOLS}
        for symbol in SYMBOLS:history[symbol].append(closing[symbol])
        equity=account.equity(closing);peak=max(peak,equity);drawdown=max(0.0,1-equity/peak)
        if not hard and drawdown>=p.hard_threshold-1e-12:
            hard=True;latch_date=day;latch_source="CLOSE"
            transitions.append(dict(date=day,type="HARD_CLOSE",equity=equity,peak=peak))
        budget=0.0 if hard else cppi_weight(equity,peak,p)
        decision={s:0.0 for s in SYMBOLS};reason="HARD_LATCH" if hard else "CPPI_REBALANCE"
        if not hard:
            if candidate=="D":decision["SPY"]=budget
            elif candidate=="E":decision["QQQ"]=budget if trend_on(history["QQQ"],p) else 0.0
            else:
                following=next_day[day]
                month_end=following is not None and day[:7]!=following[:7]
                if month_end:
                    active_slots=select_f(history,p);slot_count=len(active_slots)
                    selections.append(dict(date=day,selected=list(active_slots),slot_count=slot_count))
                else:
                    # Preserve the month-end denominator: dropping one slot must not
                    # redistribute its cash to the other, or permit midmonth re-entry.
                    removed=[s for s in active_slots if not trend_on(history[s],p)]
                    if removed:selections.append(dict(date=day,removed=removed,slot_count=slot_count))
                    active_slots=[s for s in active_slots if s not in removed]
                for symbol in active_slots:decision[symbol]=budget/slot_count
        pending=(decision,day,reason)
        row=dict(date=day,equity_usd=equity,external_flow_usd=0,fees_usd=fees,
          sp500_total_return_index=prices[day,BENCHMARK]["close"],spy_total_return_index=prices[day,"SPY"]["close"],
          cash_usd=account.cash,peak_equity_usd=peak,drawdown_percent=drawdown*100,hard_latched=hard,
          cppi_floor_usd=p.floor_fraction*peak,cppi_cushion_usd=max(0.0,equity-p.floor_fraction*peak),cppi_budget_weight=budget,
          selected_symbols=";".join(active_slots) if candidate=="F" else "SPY" if candidate=="D" else "QQQ" if decision["QQQ"] else "",
          slot_count=slot_count if candidate=="F" else 1,decision_reason=reason,executed_reason=executed_reason)
        for symbol in SYMBOLS:
            row["decision_target_"+symbol.lower()]=decision[symbol]
            row["executed_target_"+symbol.lower()]="" if executed is None else executed[symbol]
        rows.append(row)
    metric_rows=[{**r,"date":date.fromisoformat(r["date"])} for r in rows]
    report=dict(status="ISOLATED_CPPI_RESEARCH_NOT_PRODUCTION",profitability_proven=False,candidate_id=candidate,
      start=selected[0],end=selected[-1],initial_cash_usd=initial,fee_bps_per_side=fee_bps,slippage_bps_per_side=slippage_bps,
      parameters=asdict(p),hard_drawdown_percent=p.hard_threshold*100,soft_enabled=False,
      latch_date=latch_date,latch_source=latch_source,latch_triggered=hard,risk_transitions=transitions,selection_transitions=selections,
      buy_count=sum(t["side"]=="BUY" for t in account.ledger),sell_count=sum(t["side"]=="SELL" for t in account.ledger),
      ending_cash=account.cash,ending_positions={s:q for s,q in account.quantities.items() if q>1e-12},benchmark_symbol=BENCHMARK,
      metrics=metrics(metric_rows),spy_comparison_metrics=metrics([{**r,"sp500_total_return_index":r["spy_total_return_index"]} for r in metric_rows]),
      average_invested_percent=statistics.fmean((r["equity_usd"]-r["cash_usd"])/r["equity_usd"] for r in rows)*100,
      average_cash_percent=statistics.fmean(r["cash_usd"]/r["equity_usd"] for r in rows)*100,
      annualized_gross_turnover=sum(t["quantity"]*t["price"] for t in account.ledger)/statistics.fmean(r["equity_usd"] for r in rows)/((date.fromisoformat(selected[-1])-date.fromisoformat(selected[0])).days/365.25),
      limitations=["CPPI floor is not guaranteed: gaps, discrete decisions and execution costs may breach it.",
        "All four ETFs are risky; only non-interest-bearing cash is the model safe asset. No borrowing, shorting or external flows.",
        "High-water equity is updated at closes only; opening prices only reduce the prior budget and test the permanent 10% latch.",
        "D/E act after first evaluation close; F selections begin only at first month-end. All trades fill the following open.",
        "Adjusted OHLC synthetic fractional units, not actual integer-share/dividend cash accounting.",
        "Full fills, fixed costs; no halts, partial fills, tax, interest or forced last-day liquidation.",
        "Previously inspected periods are retrospective; no claim of untouched future validation or production equivalence."])
    return rows,account.ledger,report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prices",type=Path);parser.add_argument("--candidate",choices=["D","E","F"],required=True)
    parser.add_argument("--start",required=True);parser.add_argument("--end",required=True)
    parser.add_argument("--fee-bps",type=float,default=5);parser.add_argument("--slippage-bps",type=float,default=5)
    parser.add_argument("--output-prefix",type=Path,required=True);parser.add_argument("--lock-file",type=Path,required=True);parser.add_argument("--lock-sha256",required=True)
    args=parser.parse_args()
    try:
        lock_hash=hashlib.sha256(args.lock_file.read_bytes()).hexdigest()
        if lock_hash!=args.lock_sha256.lower():raise ValueError("Locked protocol hash mismatch")
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
