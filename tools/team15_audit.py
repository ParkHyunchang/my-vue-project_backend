"""Independent ledger arithmetic/constraint check; does not import simulation code."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def close(a, b):
    assert abs(a-b) <= 1e-6, (a, b)


def main():
    folder = ROOT / 'team15_results'
    lock = json.loads((folder / 'pre-run-lock.json').read_text())
    for name, expected in lock['sha256'].items():
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == expected
    raw = json.loads((ROOT / 'strategy_v3_data/SPY.raw.json').read_text())['chart']['result'][0]
    day = lambda stamp: datetime.fromtimestamp(stamp, timezone.utc).date().isoformat()
    quote = raw['indicators']['quote'][0]
    prices = {day(stamp): (quote['open'][i], quote['close'][i]) for i, stamp in enumerate(raw['timestamp'])}
    dividends = {day(x['date']):x['amount'] for x in raw['events']['dividends'].values()}
    runs = json.loads((folder / 'runs.json').read_text())
    for run in runs:
        cash, shares, receivable, peak, mdd, spent = run['initial'], 0, 0, run['initial'], 0, 0
        hard = False
        last_date = None
        trades = run['trades']
        for i, row in enumerate(run['history']):
            date = row['date']
            assert last_date is None or date > last_date
            last_date = date
            opening, closing = prices[date]
            receivable += shares * dividends.get(date, 0)
            value = cash + shares * opening + receivable
            peak = max(peak, value)
            dd = 1-value/peak
            mdd = max(mdd, dd)
            if dd >= .10-1e-12:
                hard = True
            today = [t for t in trades if t['date'] == date]
            buys = [t for t in today if t['side'] == 'BUY']
            assert len(buys) <= 1
            for t in today:
                assert isinstance(t['qty'], int) and t['qty'] > 0
                close(t['fee'], t['qty'] * t['price'] * .0005)
                slip = (run['cost_bps']-5)/10000
                if t['side'] == 'BUY':
                    assert not hard and i < 40
                    close(t['price'], opening*(1+slip))
                    cost = t['qty']*t['price']+t['fee']
                    assert cost <= cash*.99+1e-6
                    spent += cost
                    if run['mode'] == 'daily_budget':
                        assert spent <= run['initial']*(i+1)/40+1e-6
                    if run['mode'] == 'daily_share':
                        assert t['qty'] == 1
                    if run['mode'] == 'lump_sum':
                        assert i == 0
                    cash -= cost
                    shares += t['qty']
                else:
                    assert hard and t['qty'] == shares
                    close(t['price'], opening*(1-slip))
                    cash += t['qty']*t['price']-t['fee']
                    shares -= t['qty']
                    value = cash+receivable
                    peak = max(peak,value)
                    mdd=max(mdd,1-value/peak)
                close(cash,t['cash_after'])
            value=cash+shares*closing+receivable
            peak=max(peak,value)
            dd=1-value/peak
            mdd=max(mdd,dd)
            hard=hard or dd>=.10-1e-12
            close(row['cash'],cash)
            close(row['quantity'],shares)
            close(row['dividend_receivable'],receivable)
            close(row['equity'],value)
            close(row['peak'],peak)
            assert row['hard']==hard
            assert cash>=-1e-6
        close(run['final_equity'],value)
        close(run['mdd_pct'],mdd*100)
        close(run['return_pct'],(value/run['initial']-1)*100)
    report={'status':'PASS','runs':len(runs),'checks':'cash, whole shares, costs, budget, no buying after hard, next-open exits, dividends, equity, high water, MDD, immutable inputs'}
    (folder/'audit.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))


if __name__=='__main__':
    main()
