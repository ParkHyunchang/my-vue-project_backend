"""Locked market-recovery research. Reuses immutable v3 accounting, never live services."""
import argparse
import csv
import hashlib
import json
import math
import statistics
from dataclasses import asdict,dataclass
from datetime import date
from pathlib import Path
from replay_daily import load_prices
from us_performance import metrics
from strategy_v3 import Account,rebalance,select_f,trend_on,SYMBOLS,BENCHMARK

RECOVERY=("PROBE25","PROBE50","RECOVERY80")
CAPS={"NORMAL":.80,"WAIT":0.,"PROBE25":.25,"PROBE50":.50,"RECOVERY80":.80,"HARD":0.}

@dataclass(frozen=True)
class Parameters:
    sma_sessions:int=200
    momentum_sessions:int=126
    breakout_sessions:int=20
    wait_sessions:int=20
    stage_sessions:int=20
    soft_threshold:float=.06
    hard_threshold:float=.10
    trial_stop:float=.01
    normal_rearm_drawdown:float=.03
    max_weight:float=.99


def recovery_gate(closes,p=Parameters()):
    return len(closes)>=max(p.sma_sessions,p.breakout_sessions+1) and trend_on(closes,p) and closes[-1]>max(closes[-p.breakout_sessions-1:-1])


def simulate(prices,candidate,start,end,fee_bps=5,slippage_bps=5,initial=10000,parameters=Parameters()):
    p=parameters
    if candidate not in ("G","H") or min(p.sma_sessions,p.momentum_sessions,p.breakout_sessions)<2 or min(p.wait_sessions,p.stage_sessions)<1:
        raise ValueError("Invalid locked candidate/lookback")
    if not 0<p.trial_stop<p.normal_rearm_drawdown<p.soft_threshold<p.hard_threshold<1 or not 0<p.max_weight<=.99:
        raise ValueError("Invalid risk parameters")
    if not all(math.isfinite(v) for v in (fee_bps,slippage_bps,initial)) or min(fee_bps,slippage_bps)<0 or max(fee_bps,slippage_bps)>=10000 or initial<=0:
        raise ValueError("Invalid execution assumptions")
    date.fromisoformat(start);date.fromisoformat(end)
    calendar=sorted(day for day,symbol in prices if symbol=="SPY")
    for symbol in (*SYMBOLS,BENCHMARK):
        if sorted(day for day,name in prices if name==symbol)!=calendar:raise ValueError("Four ETF and total-return index calendars must match")
    selected=[d for d in calendar if start<=d<=end]
    if len(selected)<2:raise ValueError("At least two evaluation sessions")
    for day in calendar:
        for symbol in (*SYMBOLS,BENCHMARK):
            if any(not math.isfinite(prices[day,symbol][f]) or prices[day,symbol][f]<=0 for f in ("open","high","low","close")):
                raise ValueError("Invalid OHLC")
    history={s:[] for s in SYMBOLS}
    for day in calendar[:calendar.index(selected[0])]:
        for s in SYMBOLS:history[s].append(prices[day,s]["close"])
    needed=max(p.sma_sessions,p.breakout_sessions+1,p.momentum_sessions+1 if candidate=="H" else 1)
    if any(len(history[s])+1<needed for s in SYMBOLS):raise ValueError("Insufficient warmup")
    following={d:calendar[i+1] if i+1<len(calendar) else None for i,d in enumerate(calendar)}
    account=Account(float(initial));peak=float(initial);fee=fee_bps/10000;slip=slippage_bps/10000
    state="NORMAL";anchor=None;stage_count=0;wait_day0=None;pending=None
    slots=[];slot_count=0;rows=[];transitions=[];selections=[];entries=[]
    first_soft_index=None;latch_date=None;latch_source=None

    def transition(new,day,phase,reason,index):
        nonlocal state,anchor,stage_count,wait_day0,first_soft_index,latch_date,latch_source
        previous=state;state=new
        transitions.append(dict(date=day,phase=phase,from_state=previous,to_state=new,reason=reason))
        if new in ("WAIT","HARD","NORMAL"):
            anchor=None;stage_count=0
        if new=="WAIT":
            wait_day0=None
            if first_soft_index is None and reason.startswith("SOFT"):first_soft_index=index
        if new=="HARD":
            wait_day0=None;latch_date=day;latch_source=phase
        if new in RECOVERY and new!=previous:stage_count=0

    for index,day in enumerate(selected):
        opening={s:prices[day,s]["open"] for s in SYMBOLS}
        open_equity=account.equity(opening);fees=0.;executed=None;executed_reason=""
        prior_anchor=anchor
        override=None
        if index and state!="HARD":
            if open_equity<=peak*(1-p.hard_threshold)+1e-10:
                transition("HARD",day,"OPEN_GAP","HARD_OPEN_GAP",index);override="HARD_OPEN_GAP"
            elif state=="NORMAL" and open_equity<=peak*(1-p.soft_threshold)+1e-10:
                transition("WAIT",day,"OPEN","SOFT_OPEN",index);override="SOFT_OPEN"
            elif state in RECOVERY and anchor is not None and open_equity<=anchor*(1-p.trial_stop)+1e-10:
                transition("WAIT",day,"OPEN","TRIAL_OPEN",index);override="TRIAL_OPEN"
        if override:pending=({s:0. for s in SYMBOLS},day,override)
        if index and pending is not None:
            target,signal_date,reason=pending;target=dict(target)
            if state in ("WAIT","HARD"):target={s:0. for s in SYMBOLS}
            before_ledger=len(account.ledger)
            fees=rebalance(account,target,opening,fee,slip,day,signal_date,reason,p.max_weight)
            executed=target;executed_reason=reason;pending=None
            if state=="WAIT" and wait_day0 is None:wait_day0=index
            if state in RECOVERY and anchor is None:
                actual_buy=any(t["side"]=="BUY" for t in account.ledger[before_ledger:])
                if actual_buy and sum(account.quantities.values())>1e-12:
                    anchor=open_equity;stage_count=0;wait_day0=None
                    entries.append(dict(date=day,session_index=index,trial_anchor=anchor))
                else:
                    # A scheduled signal alone is not a funded recovery episode.
                    state="WAIT";stage_count=0
        state_open=state;anchor_open=anchor;count_open=stage_count
        closing={s:prices[day,s]["close"] for s in SYMBOLS}
        for s in SYMBOLS:history[s].append(closing[s])
        equity=account.equity(closing);peak=max(peak,equity);dd=max(0.,1-equity/peak)
        invested=sum(account.quantities[s]*closing[s] for s in SYMBOLS)
        # Basket maintenance continues during WAIT and uses only completed bars.
        if candidate=="H":
            nxt=following[day];month_end=nxt is not None and day[:7]!=nxt[:7]
            if month_end:
                slots=select_f(history,p);slot_count=len(slots)
                selections.append(dict(date=day,selected=list(slots),slot_count=slot_count))
            else:
                removed=[s for s in slots if not trend_on(history[s],p)]
                if removed:selections.append(dict(date=day,removed=removed,slot_count=slot_count))
                slots=[s for s in slots if s not in removed]
        active=["SPY"] if candidate=="G" else slots
        close_reason="STATE_REBALANCE"
        if state!="HARD":
            if dd>=p.hard_threshold-1e-12:
                transition("HARD",day,"CLOSE","HARD_CLOSE",index);close_reason="HARD_LATCH"
            elif state=="NORMAL" and dd>=p.soft_threshold-1e-12:
                transition("WAIT",day,"CLOSE","SOFT_CLOSE",index);close_reason="SOFT_CLOSE"
            elif state in RECOVERY and anchor is not None:
                if equity<=anchor*(1-p.trial_stop)+1e-10:
                    transition("WAIT",day,"CLOSE","TRIAL_CLOSE",index);close_reason="TRIAL_CLOSE"
                elif not trend_on(history["SPY"],p):
                    transition("WAIT",day,"CLOSE","RECOVERY_TREND_OFF",index);close_reason="RECOVERY_TREND_OFF"
                else:
                    if invested>1e-10:stage_count+=1
                    if state in ("PROBE25","PROBE50") and stage_count>=p.stage_sessions and equity>=anchor:
                        next_state="PROBE50" if state=="PROBE25" else "RECOVERY80"
                        transition(next_state,day,"CLOSE","STAGE_UP",index);close_reason="STAGE_UP"
                    elif state=="RECOVERY80" and dd<=p.normal_rearm_drawdown+1e-12:
                        transition("NORMAL",day,"CLOSE","NORMAL_REARM",index);close_reason="NORMAL_REARM"
            elif state=="WAIT" and wait_day0 is not None and index-wait_day0>=p.wait_sessions and active and recovery_gate(history["SPY"],p):
                transition("PROBE25",day,"CLOSE","MARKET_REENTRY",index);close_reason="MARKET_REENTRY"
        decision={s:0. for s in SYMBOLS}
        if state not in ("WAIT","HARD"):
            denominator=1 if candidate=="G" else slot_count
            for s in active:decision[s]=CAPS[state]/denominator
        if state=="HARD":close_reason="HARD_LATCH"
        pending=(decision,day,close_reason)
        elapsed="" if wait_day0 is None else index-wait_day0
        row=dict(date=day,equity_usd=equity,external_flow_usd=0,fees_usd=fees,
          sp500_total_return_index=prices[day,BENCHMARK]["close"],spy_total_return_index=prices[day,"SPY"]["close"],
          cash_usd=account.cash,peak_equity_usd=peak,drawdown_percent=dd*100,hard_latched=state=="HARD",
          state_open=state_open,state_close=state,trial_anchor="" if anchor is None else anchor,
          trial_anchor_open="" if anchor_open is None else anchor_open,trial_anchor_close="" if anchor is None else anchor,
          stage_exposed_sessions=stage_count,stage_exposed_sessions_open=count_open,
          wait_day0="" if wait_day0 is None else selected[wait_day0],wait_elapsed=elapsed,
          selected_symbols=";".join(active),slot_count=1 if candidate=="G" else slot_count,
          decision_reason=close_reason,executed_reason=executed_reason)
        for s in SYMBOLS:
            row["decision_target_"+s.lower()]=decision[s]
            row["executed_target_"+s.lower()]="" if executed is None else executed[s]
        rows.append(row)
    metric_rows=[{**r,"date":date.fromisoformat(r["date"])} for r in rows]
    exposures=[(r["equity_usd"]-r["cash_usd"])/r["equity_usd"] for r in rows]
    longest=run=0
    for value in exposures:
        run=run+1 if value<.01 else 0;longest=max(longest,run)
    first_entry=next((e for e in entries if first_soft_index is not None and e["session_index"]>first_soft_index),None)
    report=dict(status="ISOLATED_MARKET_RECOVERY_RESEARCH_NOT_PRODUCTION",profitability_proven=False,candidate_id=candidate,
      start=selected[0],end=selected[-1],initial_cash_usd=initial,fee_bps_per_side=fee_bps,slippage_bps_per_side=slippage_bps,
      parameters=asdict(p),hard_drawdown_percent=p.hard_threshold*100,soft_enabled=True,soft_threshold_percent=p.soft_threshold*100,
      latch_date=latch_date,latch_source=latch_source,latch_triggered=state=="HARD",risk_transitions=transitions,selection_transitions=selections,
      recovery_entries=entries,wait_reentry_count=len(entries),first_soft_date=None if first_soft_index is None else selected[first_soft_index],
      sessions_first_soft_to_reentry=None if first_entry is None else first_entry["session_index"]-first_soft_index,
      below_one_percent_exposure_session_percent=sum(v<.01 for v in exposures)/len(rows)*100,
      longest_below_one_percent_exposure_sessions=longest,final_invested_percent=exposures[-1]*100,
      hard_state_session_percent=sum(r["hard_latched"] for r in rows)/len(rows)*100,
      buy_count=sum(t["side"]=="BUY" for t in account.ledger),sell_count=sum(t["side"]=="SELL" for t in account.ledger),
      ending_cash=account.cash,ending_positions={s:q for s,q in account.quantities.items() if q>1e-12},ending_state=state,benchmark_symbol=BENCHMARK,
      metrics=metrics(metric_rows),spy_comparison_metrics=metrics([{**r,"sp500_total_return_index":r["spy_total_return_index"]} for r in metric_rows]),
      average_invested_percent=statistics.fmean(exposures)*100,average_cash_percent=100-statistics.fmean(exposures)*100,
      total_fees_usd=sum(r["fees_usd"] for r in rows),
      annualized_gross_turnover=sum(t["quantity"]*t["price"] for t in account.ledger)/statistics.fmean(r["equity_usd"] for r in rows)/((date.fromisoformat(selected[-1])-date.fromisoformat(selected[0])).days/365.25),
      limitations=["10% permanent hard stop and 1% trial stop are triggers, not guaranteed loss ceilings across gaps and fees.",
       "Only market conditions rearm recovery; global peak and hard latch never reset. Early HARD is not a cash-trap solution.",
       "Adjusted OHLC fractional units/full fills, not actual integer shares/dividend timing/halts/partial execution.",
       "All ETFs are risky, uninvested cash pays no interest; no borrowing, shorts, external flows, tax or final forced liquidation.",
       "All periods were previously observed retrospective studies, not untouched holdouts or production strategy equivalence."])
    return rows,account.ledger,report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prices",type=Path);parser.add_argument("--candidate",choices=["G","H"],required=True)
    parser.add_argument("--start",required=True);parser.add_argument("--end",required=True)
    parser.add_argument("--fee-bps",type=float,default=5);parser.add_argument("--slippage-bps",type=float,default=5)
    parser.add_argument("--output-prefix",type=Path,required=True);parser.add_argument("--lock-file",type=Path,required=True);parser.add_argument("--lock-sha256",required=True)
    args=parser.parse_args()
    try:
        lock_hash=hashlib.sha256(args.lock_file.read_bytes()).hexdigest()
        if lock_hash!=args.lock_sha256.lower():raise ValueError("Locked protocol hash mismatch")
        rows,trades,report=simulate(load_prices(args.prices),args.candidate,args.start,args.end,args.fee_bps,args.slippage_bps)
        report["protocol_lock"]={"path":str(args.lock_file),"sha256":lock_hash}
        dependencies=[args.prices,Path(__file__),Path(__file__).with_name("strategy_v3.py"),Path(__file__).with_name("replay_daily.py"),Path(__file__).with_name("us_performance.py")]
        report["input_sha256"]={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in dependencies}
        prefix=args.output_prefix;prefix.parent.mkdir(parents=True,exist_ok=True)
        for suffix,data in [(".json",report),("-trades.json",trades)]:Path(str(prefix)+suffix).write_text(json.dumps(data,ensure_ascii=False,indent=2,allow_nan=False)+"\n",encoding="utf-8")
        with Path(str(prefix)+"-equity.csv").open("w",encoding="utf-8",newline="") as stream:
            writer=csv.DictWriter(stream,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
        print(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False))
    except (ValueError,KeyError,OSError,statistics.StatisticsError) as error:parser.error(str(error))

if __name__=="__main__":main()
