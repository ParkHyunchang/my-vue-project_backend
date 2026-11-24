"""Supplement: same-open whole-share SPY buy/hold without a drawdown exit.

Requested during review to distinguish index-close timing and risk-rule effects.
Does not alter locked original experiment or tune a strategy.
"""
import json
import math
from pathlib import Path
from statistics import mean
from team15_dca import load_data


def main():
    root=Path(__file__).resolve().parent
    rows,_=load_data()
    prices={r['date']:r for r in rows}
    runs=json.loads((root/'team15_results/runs.json').read_text())
    supplements=[]
    for run in runs:
        if run['mode']!='lump_sum':
            continue
        dates=[r['date'] for r in run['history']]
        fee=.0005
        slip=(run['cost_bps']-5)/10000
        fill=prices[dates[0]]['open']*(1+slip)
        qty=math.floor(10000*.99/(fill*(1+fee)))
        cash=10000-qty*fill*(1+fee)
        dividend=0
        peak=10000
        mdd=0
        for i,date in enumerate(dates):
            row=prices[date]
            if i:
                dividend+=qty*row['dividend']
            for price in (row['open'],row['close']):
                equity=cash+qty*price+dividend
                peak=max(peak,equity)
                mdd=max(mdd,1-equity/peak)
        supplements.append({'window':run['window'],'example_only':run['example_only'],
            'cost_bps':run['cost_bps'],'start':run['start'],'end':run['end'],
            'return_pct':(equity/10000-1)*100,'final_equity':equity,'mdd_pct':mdd*100,
            'quantity':qty,'buy_hold_without_risk_latch':True})
    summaries=[]
    for cost in (10,25):
        sample={x['window']:x for x in supplements if x['cost_bps']==cost and not x['example_only']}
        summaries.append({'cost_bps':cost,'windows':len(sample),
            'buy_hold_mean_return_pct':mean(x['return_pct'] for x in sample.values()),
            'buy_hold_worst_mdd_pct':max(x['mdd_pct'] for x in sample.values()),
            'strategy_beats_buy_hold':{mode:sum(x['return_pct']>sample[x['window']]['return_pct']+1e-10
                for x in runs if x['cost_bps']==cost and x['mode']==mode and not x['example_only'])
                for mode in ('daily_share','daily_budget','lump_sum')}})
    report={'explanation':'Same first-day open/cost/cash/whole-share SPY buy-hold, no 10% latch; comparison only, not proposed risk-policy change.',
            'summary':summaries,'runs':supplements}
    (root/'team15_results/supplement.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(summaries,indent=2))


if __name__=='__main__':
    main()
