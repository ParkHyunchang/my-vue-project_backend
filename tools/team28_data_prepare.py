"""Fixed public ETF data acquisition. No credentials, broker or app imports."""
import argparse
import csv
import hashlib
import importlib.util
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, build_opener

START, END = '2006-03-01', '2026-11-21'
ETFS = ('SPY', 'QQQ', 'GLD', 'IEF', 'IWM', 'EFA', 'EEM', 'TLT', 'DBC', 'VNQ',
        'XLB', 'XLE', 'XLI', 'XLK', 'XLP', 'XLU', 'XLV', 'XLY')
SYMBOLS = ETFS + ('^SP500TR',)
ROOT = Path(__file__).resolve().parent / 'team28_data'

# Separate module instance: fixed TEAM21 validation reused without changing its
# file or the globals of another research process.
spec = importlib.util.spec_from_file_location('team28_fixed_collector', Path(__file__).with_name('team21_fetch.py'))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)
collector.START, collector.END, collector.SYMBOLS = START, END, SYMBOLS


def validate(rows):
    if set(rows) != set(SYMBOLS):
        raise ValueError('Exact fixed symbol universe required')
    days = [r['date'] for r in rows['SPY']]
    if not days or days[0] != START or days[-1] != '2026-11-20':
        raise ValueError('Required explicit calendar coverage missing')
    for symbol, rs in rows.items():
        if [r['date'] for r in rs] != days:
            raise ValueError('Calendar mismatch; no filling/intersection: ' + symbol)
        collector.nominal_rows(rs)  # Reject noninteger/reverse split/CIL cases.
    return days


def prepare(output=ROOT):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    manifest = {'status':'collecting', 'symbols':list(SYMBOLS),
                'requested_start':START,'requested_end_exclusive':END,
                'started_utc':datetime.now(timezone.utc).isoformat(), 'files':[],
                'selection':'Fixed nonleveraged US-listed ETF universe before strategy results. DBC inception 2006-02-03; explicit common start 2006-03-01, no silent calendar intersection.',
                'excluded':{'XLF':'2016 real-estate distribution is not an ordinary stock split.',
                            'individual_stocks':'Historical constituent/delisting universe and corporate-action validation unavailable; surviving large-cap picks would add survivorship bias.'},
                'price_basis':'Provider OHLC unchanged, split-adjusted but not dividend-adjusted. adj_close for signals only. Reconstruct nominal trade OHLC by future ordinary splits and apply holdings split on ex-date.',
                'limitations':['Revised public daily data, not point-in-time quotes or full delisted universe.',
                              'No bid/ask or intraday execution validation; daily short-hold research only.',
                              'No dividend payment dates: receivables are not spendable cash.',
                              'EFA/EEM are foreign-market exposures and DBC commodity exposure via US-listed ETFs; account tradability, taxation and PTP treatment not verified.',
                              'Ordinary integer forward splits only; fractional entitlement, reverse splits and spin-offs rejected.'],
                'collector_sha256':collector.sha(Path(__file__)),
                'base_collector_sha256':collector.sha(Path(collector.__file__))}
    rows = {}
    try:
        opener = build_opener(collector.NoRedirect())
        for symbol in SYMBOLS:
            url = collector.url(symbol)
            with opener.open(Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=30) as response:
                raw = response.read(20_000_001)
            if len(raw) > 20_000_000:
                raise ValueError('Oversized response')
            stem = symbol.replace('^','')
            rawpath,csvpath=output/(stem+'.raw.json'),output/(stem+'.csv')
            rawpath.write_bytes(raw)
            rs=collector.normalize(raw,symbol)
            collector.nominal_rows(rs)
            collector.write_csv(csvpath,rs)
            rows[symbol]=rs
            manifest['files'].append({'symbol':symbol,'source_url':url,
                'retrieved_utc':datetime.now(timezone.utc).isoformat(),
                'raw_file':rawpath.name,'raw_sha256':collector.sha(rawpath),
                'csv_file':csvpath.name,'csv_sha256':collector.sha(csvpath),
                'rows':len(rs),'first_date':rs[0]['date'],'last_date':rs[-1]['date'],
                'splits':[{'date':r['date'],'numerator':r['split_numerator'],'denominator':r['split_denominator']} for r in rs if r['split_numerator']!=r['split_denominator']]})
            print(symbol,len(rs),rs[-1]['date'],flush=True)
        dates=validate(rows)
        collector.write_csv(output/'daily.csv',sorted((r for rs in rows.values() for r in rs),key=lambda r:(r['date'],r['symbol'])))
        manifest.update(status='complete',first_date=dates[0],last_date=dates[-1],calendar_rows=len(dates),
                        daily_file='daily.csv',daily_sha256=collector.sha(output/'daily.csv'),
                        calendar_sha256=hashlib.sha256('\n'.join(dates).encode()).hexdigest())
    except Exception as error:
        manifest.update(status='failed',error=type(error).__name__+': '+str(error))
        raise
    finally:
        manifest['finished_utc']=datetime.now(timezone.utc).isoformat()
        (output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    return manifest


def verify(folder):
    folder=Path(folder)
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    if manifest['status']!='complete' or manifest['symbols']!=list(SYMBOLS):
        raise ValueError('Invalid manifest status/universe')
    rows={}
    for entry in manifest['files']:
        for kind in ('raw','csv'):
            if collector.sha(folder/entry[kind+'_file'])!=entry[kind+'_sha256']:
                raise ValueError('Hash mismatch')
        normalized=collector.normalize((folder/entry['raw_file']).read_bytes(),entry['symbol'])
        with (folder/entry['csv_file']).open(newline='',encoding='utf-8') as f:
            saved=list(csv.DictReader(f))
        if len(saved)!=len(normalized):
            raise ValueError('Raw/CSV row count')
        for a,b in zip(saved,normalized):
            for k in collector.FIELDS:
                if (a[k] != b[k]) if k in ('date','symbol') else (float(a[k]) != b[k]):
                    raise ValueError('Raw/CSV normalization mismatch')
        if entry['symbol'] in rows:
            raise ValueError('Duplicate symbol')
        rows[entry['symbol']]=normalized
    dates=validate(rows)
    with (folder/'daily.csv').open(newline='',encoding='utf-8') as f:
        combined=list(csv.DictReader(f))
    expected=sorted((r for rs in rows.values() for r in rs),key=lambda r:(r['date'],r['symbol']))
    if len(combined)!=len(expected):
        raise ValueError('Combined row count')
    for a,b in zip(combined,expected):
        for k in collector.FIELDS:
            if (a[k] != b[k]) if k in ('date','symbol') else (float(a[k]) != b[k]):
                raise ValueError('Combined mismatch')
    if collector.sha(folder/'daily.csv')!=manifest['daily_sha256']:
        raise ValueError('Combined hash mismatch')
    return {'status':'PASS','symbols':len(rows),'calendar_rows':len(dates),'normalized_rows':len(expected),'verified_files':2*len(rows)+1}


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--verify',action='store_true')
    p.add_argument('--output',default=str(ROOT))
    args=p.parse_args()
    print(json.dumps(verify(args.output) if args.verify else prepare(args.output),indent=2))
