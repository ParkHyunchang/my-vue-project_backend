"""Public fixed sample acquisition only. No credentials, accounts, or trading."""
import argparse
import csv
import hashlib
import importlib.util
import json
import math
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, build_opener

ROOT=Path(__file__).resolve().parent/'team29_data'
SYMBOLS=('AAPL','MSFT','AMZN','GOOGL','NVDA','JPM','XOM','PG','SPY')
WINDOWS={'daily':('2006-01-01','2026-11-21','1d'),
         '5m':('2026-09-25','2026-11-21','5m'),
         '60m':('2024-11-25','2026-11-21','60m')}
FIELDS=('timestamp_utc','date','slot','symbol','open','high','low','close','volume')
spec=importlib.util.spec_from_file_location('team29_fixed_daily',Path(__file__).with_name('team21_fetch.py'))
base=importlib.util.module_from_spec(spec); spec.loader.exec_module(base)
base.START,base.END,base.SYMBOLS='2006-01-01','2026-11-21',SYMBOLS

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def ny_time(stamp):
    """US post-2007 DST law; used only for fixed 2024-2026 intraday sample."""
    utc=datetime.fromtimestamp(stamp,timezone.utc)
    if not 2024<=utc.year<=2026:raise ValueError('Unsupported timezone year')
    march=datetime(utc.year,3,1,tzinfo=timezone.utc)
    november=datetime(utc.year,11,1,tzinfo=timezone.utc)
    start=march+timedelta(days=(6-march.weekday())%7+7,hours=7)
    end=november+timedelta(days=(6-november.weekday())%7,hours=6)
    return utc.astimezone(timezone(timedelta(hours=-4 if start<=utc<end else -5)))

def intraday(raw,symbol,mode):
    payload=json.loads(raw)
    if payload.get('chart',{}).get('error'):raise ValueError('Provider error')
    r=payload['chart']['result'][0]
    if r['meta']['symbol']!=symbol or r['meta'].get('currency')!='USD' or r['meta'].get('exchangeTimezoneName')!='America/New_York':raise ValueError('Wrong instrument/timezone')
    times=r.get('timestamp',[]); q=r['indicators']['quote'][0]
    if not times or times!=sorted(set(times)) or any(len(q[k])!=len(times) for k in ('open','high','low','close','volume')):raise ValueError('Missing/unsorted/misaligned bars')
    width=5 if mode=='5m' else 60
    start,end,_=WINDOWS[mode]
    rows=[]; rejected=[]; counts={}
    for i,t in enumerate(times):
        dt=ny_time(t); day=dt.date().isoformat(); minutes=dt.hour*60+dt.minute
        if not start<=day<end or not 570<=minutes<960:
            rejected.append({'timestamp_utc':t,'reason':'outside requested completed regular session'});continue
        counts.setdefault(day,0)
        if dt.second or (minutes-570)%width:
            if mode!='60m':raise ValueError('Off-grid bar')
            rejected.append({'timestamp_utc':t,'reason':'off-grid hourly bar; session incomplete'});continue
        values={k:q[k][i] for k in ('open','high','low','close','volume')}
        if any(v is None for v in values.values()):
            rejected.append({'timestamp_utc':t,'reason':'null quote'});continue
        for key,value in values.items():base.number(value,key!='volume')
        if values['low']>min(values['open'],values['close']) or values['high']<max(values['open'],values['close']):raise ValueError('Invalid OHLC')
        rows.append({'timestamp_utc':t,'date':day,'slot':(minutes-570)//width,'symbol':symbol,**values})
        counts[day]+=1
    expected=78 if mode=='5m' else 7
    sessions=[{'symbol':symbol,'date':d,'bars':n,'expected_bars':expected,'complete':n==expected} for d,n in sorted(counts.items())]
    if not rows:raise ValueError('Empty valid regular bars')
    return rows,sessions,rejected

def write_csv(path,rows,fields):
    with path.open('x',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)

def acquire(root=ROOT):
    root=Path(root);root.mkdir(exist_ok=False,parents=True)
    overall={'status':'collecting','symbols':list(SYMBOLS),'started_utc':datetime.now(timezone.utc).isoformat(),
       'source_sha256':sha(__file__),'base_source_sha256':sha(base.__file__),
       'limits':['Surviving fixed sample, no delisting or historical membership universe.',
                 'Recent public aggregate bars are not bid/ask quotes or actual fills.',
                 'Intraday history does not meet existing long-term approval periods.',
                 'Corporate-action metadata is provider data; special distributions may be unverified.',
                 'Incomplete/short/missing sessions are reported, never filled.']}
    (root/'plan.json').write_text(json.dumps(overall|{'windows':WINDOWS},indent=2),encoding='utf-8')
    opener=build_opener(base.NoRedirect())
    for mode,(start,end,interval) in WINDOWS.items():
        folder=root/mode;folder.mkdir()
        manifest={'status':'collecting','symbols':list(SYMBOLS),'requested_start':start,'requested_end_exclusive':end,'interval':interval,'files':[],'errors':[]}
        allrows=[];allsessions=[];allrejected=[]
        for symbol in SYMBOLS:
            url=f'https://query1.finance.yahoo.com/v8/finance/chart/{quote(symbol)}?period1={base.epoch(start)}&period2={base.epoch(end)}&interval={interval}&events=div%2Csplits&includePrePost=false'
            try:
                with opener.open(Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=30) as resp: raw=resp.read(20_000_001)
                if len(raw)>20_000_000:raise ValueError('Response too large')
                rawpath=folder/(symbol+'.raw.json');rawpath.write_bytes(raw)
                if mode=='daily':
                    rows=base.normalize(raw,symbol);special=None
                    try:base.nominal_rows(rows)
                    except ValueError as e:special=str(e)
                    metadata={'unsupported_action':special,'actions':[{'date':r['date'],'numerator':r['split_numerator'],'denominator':r['split_denominator']} for r in rows if r['split_numerator']!=r['split_denominator']]}
                    fields=base.FIELDS
                else:
                    rows,sessions,rejected=intraday(raw,symbol,mode)
                    allsessions+=sessions;allrejected += [dict(symbol=symbol,**x) for x in rejected]
                    metadata={'complete_sessions':sum(s['complete'] for s in sessions),'observed_sessions':len(sessions)};fields=FIELDS
                csvpath=folder/(symbol+'.csv');write_csv(csvpath,rows,fields);allrows+=rows
                manifest['files'].append({'symbol':symbol,'source_url':url,'raw_file':rawpath.name,'raw_sha256':sha(rawpath),'csv_file':csvpath.name,'csv_sha256':sha(csvpath),'rows':len(rows),'first_date':rows[0]['date'],'last_date':rows[-1]['date'],**metadata})
                print(mode,symbol,len(rows),flush=True)
            except Exception as e:
                manifest['errors'].append({'symbol':symbol,'error':type(e).__name__+': '+str(e)})
                print(mode,symbol,'FAILED',type(e).__name__,str(e),flush=True)
        name='daily.csv' if mode=='daily' else 'intraday.csv'
        fields=base.FIELDS if mode=='daily' else FIELDS
        write_csv(folder/name,sorted(allrows,key=lambda r:(r.get('timestamp_utc',r['date']),r['symbol'])),fields)
        if mode!='daily':
            (folder/'session-status.json').write_text(json.dumps(allsessions,indent=2),encoding='utf-8')
            (folder/'rejected-bars.json').write_text(json.dumps(allrejected,indent=2),encoding='utf-8')
        manifest.update(status='complete' if len(manifest['files'])==len(SYMBOLS) else 'partial',daily_file=name,daily_sha256=sha(folder/name))
        (folder/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    overall.update(status='acquired',finished_utc=datetime.now(timezone.utc).isoformat())
    (root/'manifest.json').write_text(json.dumps(overall,indent=2),encoding='utf-8')

def verify(root=ROOT):
    root=Path(root);out={}
    daily_manifest=json.loads((root/'daily/manifest.json').read_text())
    with (root/'daily/daily.csv').open(newline='',encoding='utf-8') as f:daily_rows=list(csv.DictReader(f))
    calendar={s:{r['date'] for r in daily_rows if r['symbol']==s} for s in SYMBOLS}
    coverage={}
    for mode in WINDOWS:
        folder=root/mode;manifest=json.loads((folder/'manifest.json').read_text());rows=[];sessions=[];rejected=[]
        if manifest.get('status')!='complete' or sorted(e['symbol'] for e in manifest.get('files',[]))!=sorted(SYMBOLS):
            out[mode]={'status':'PARTIAL','symbols':len(manifest.get('files',[])),'performance_allowed':False,'reason':'Not all fixed symbols acquired/normalized'};continue
        for e in manifest['files']:
            for k in ('raw','csv'):
                if sha(folder/e[k+'_file'])!=e[k+'_sha256']:raise ValueError('Source hash changed')
            raw=(folder/e['raw_file']).read_bytes()
            if mode=='daily':
                normalized=base.normalize(raw,e['symbol']);special=None
                try:base.nominal_rows(normalized)
                except ValueError as error:special=str(error)
                actions=[{'date':r['date'],'numerator':r['split_numerator'],'denominator':r['split_denominator']} for r in normalized if r['split_numerator']!=r['split_denominator']]
                if e['unsupported_action']!=special or e['actions']!=actions:raise ValueError('Corporate action metadata mismatch')
            else:
                normalized,s,r=intraday(raw,e['symbol'],mode);sessions+=s;rejected += [dict(symbol=e['symbol'],**x) for x in r]
                if e['complete_sessions']!=sum(x['complete'] for x in s) or e['observed_sessions']!=len(s):raise ValueError('Session count metadata mismatch')
            if not normalized or len(normalized)!=e['rows']:raise ValueError('Empty/wrong record count')
            with (folder/e['csv_file']).open(newline='',encoding='utf-8') as f:saved=list(csv.DictReader(f))
            compare(saved,normalized)
            rows+=normalized
        with (folder/manifest['daily_file']).open(newline='',encoding='utf-8') as f:combined=list(csv.DictReader(f))
        compare(combined,sorted(rows,key=lambda r:(r.get('timestamp_utc',r['date']),r['symbol'])))
        if sha(folder/manifest['daily_file'])!=manifest['daily_sha256']:raise ValueError('Combined hash changed')
        if mode!='daily':
            if sessions!=json.loads((folder/'session-status.json').read_text()) or rejected!=json.loads((folder/'rejected-bars.json').read_text()):raise ValueError('Session metadata changed')
            start,end,_=WINDOWS[mode]
            allsessions=[]
            for symbol in SYMBOLS:
                observed={x['date']:x for x in sessions if x['symbol']==symbol}
                for day in sorted(d for d in calendar[symbol] if start<=d<end):
                    x=observed.get(day)
                    allsessions.append(x or {'symbol':symbol,'date':day,'bars':0,'expected_bars':78 if mode=='5m' else 7,'complete':False,'reason':'entire session missing'})
            coverage[mode]={'sessions':allsessions,'missing_entire_sessions':[x for x in allsessions if x['bars']==0],
                'incomplete_sessions':[x for x in allsessions if not x['complete']],
                'corporate_actions':{e['symbol']:json.loads((folder/e['raw_file']).read_text())['chart']['result'][0].get('events',{}) for e in manifest['files']}}
        out[mode]={'status':'PASS','symbols':len(manifest['files']),'rows':len(rows),'verified_price_files':len(manifest['files'])*2+1,'complete_sessions_per_symbol':{s:sum(x['complete'] for x in sessions if x['symbol']==s) for s in SYMBOLS} if sessions else None}
    out['verifier_sha256']=sha(__file__)
    (root/'coverage.json').write_text(json.dumps(coverage,indent=2),encoding='utf-8')
    (root/'verification.json').write_text(json.dumps(out,indent=2),encoding='utf-8');return out

def derive_hourly(root=ROOT):
    root=Path(root);source=root/'60m';folder=root/'60m-normalized';folder.mkdir(exist_ok=False)
    manifest={'status':'complete','symbols':list(SYMBOLS),'interval':'60m','normalization_note':'Original 60m attempt rejected early-close 13:00 off-grid bars. Raw files unchanged; this derived copy rejects those bars and marks short sessions incomplete. Not used for approval.', 'source_sha256':sha(__file__),'files':[]}
    rows=[];sessions=[];rejected=[]
    for symbol in SYMBOLS:
        rawpath=folder/(symbol+'.raw.json');shutil.copyfile(source/(symbol+'.raw.json'),rawpath)
        rs,ss,rr=intraday(rawpath.read_bytes(),symbol,'60m')
        csvpath=folder/(symbol+'.csv');write_csv(csvpath,rs,FIELDS)
        rows+=rs;sessions+=ss;rejected += [dict(symbol=symbol,**x) for x in rr]
        manifest['files'].append({'symbol':symbol,'raw_file':rawpath.name,'raw_sha256':sha(rawpath),'csv_file':csvpath.name,'csv_sha256':sha(csvpath),'rows':len(rs),'complete_sessions':sum(x['complete'] for x in ss),'observed_sessions':len(ss)})
    write_csv(folder/'intraday.csv',sorted(rows,key=lambda r:(r['timestamp_utc'],r['symbol'])),FIELDS)
    manifest.update(daily_file='intraday.csv',daily_sha256=sha(folder/'intraday.csv'))
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    (folder/'session-status.json').write_text(json.dumps(sessions,indent=2),encoding='utf-8')
    (folder/'rejected-bars.json').write_text(json.dumps(rejected,indent=2),encoding='utf-8')
    return {'rows':len(rows),'sessions':len(sessions),'complete':sum(s['complete'] for s in sessions)}

def compare(saved,expected):
    if len(saved)!=len(expected):raise ValueError('Row count mismatch')
    for a,b in zip(saved,expected):
        if set(a)!=set(b):raise ValueError('Schema mismatch')
        for k,v in b.items():
            if (a[k]!=v) if isinstance(v,str) else (float(a[k])!=v):raise ValueError('Normalization mismatch')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--verify',action='store_true');parser.add_argument('--derive-hourly',action='store_true');parser.add_argument('--output',default=str(ROOT));args=parser.parse_args()
    print(json.dumps(derive_hourly(args.output) if args.derive_hourly else verify(args.output),indent=2) if args.verify or args.derive_hourly else acquire(args.output))
