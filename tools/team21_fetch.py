"""TEAM21 public, credential-free immutable daily data collector. No trading imports."""
import csv
import hashlib
import json
import math
import time
import uuid
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, HTTPRedirectHandler, build_opener

SYMBOLS = ('XLB', 'XLE', 'XLF', 'XLI', 'XLK', 'XLP', 'XLU', 'XLV', 'XLY', 'SPY', '^SP500TR')
START, END = '2006-01-01', '2026-11-19'
FIELDS = ('date', 'symbol', 'open', 'high', 'low', 'close', 'adj_close', 'adjustment_factor',
          'volume', 'dividend', 'split_numerator', 'split_denominator')
ROOT = Path(__file__).resolve().parent / 'team21_data'


def epoch(day):
    return int(datetime.fromisoformat(day).replace(tzinfo=timezone.utc).timestamp())


def day(stamp):
    return datetime.fromtimestamp(stamp, timezone.utc).date().isoformat()


def url(symbol):
    if symbol not in SYMBOLS:
        raise ValueError('Symbol outside fixed allowlist')
    return (f'https://query1.finance.yahoo.com/v8/finance/chart/{quote(symbol, safe="")}'
            f'?period1={epoch(START)}&period2={epoch(END)}&interval=1d&events=div%2Csplits')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Redirect rejected')


def number(value, positive=False):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value):
        raise ValueError('Non-finite or missing number')
    if value <= 0 if positive else value < 0:
        raise ValueError('Invalid number sign')
    return value


def normalize(raw, symbol):
    payload = json.loads(raw)
    if payload.get('chart', {}).get('error'):
        raise ValueError('Provider error')
    result = payload['chart']['result'][0]
    if result['meta']['symbol'] != symbol or result['meta'].get('currency') != 'USD':
        raise ValueError('Wrong symbol/currency')
    stamps = result['timestamp']
    quotes = result['indicators']['quote'][0]
    adjusted = result['indicators']['adjclose'][0]['adjclose']
    if not stamps or any(len(quotes[k]) != len(stamps) for k in ('open', 'high', 'low', 'close', 'volume')) or len(adjusted) != len(stamps):
        raise ValueError('Missing/misaligned arrays')
    events = result.get('events', {})
    dividends = {day(x['date']): number(x['amount']) for x in events.get('dividends', {}).values()}
    splits = {day(x['date']): (number(x['numerator'], True), number(x['denominator'], True))
              for x in events.get('splits', {}).values()}
    rows = []
    for i, stamp in enumerate(stamps):
        date = day(stamp)
        if not START <= date < END or rows and date <= rows[-1]['date']:
            raise ValueError('Out-of-range, duplicated or unsorted date')
        row = {'date': date, 'symbol': symbol}
        row.update({key: number(quotes[key][i], True) for key in ('open', 'high', 'low', 'close')})
        if row['low'] > min(row['open'], row['close']) or row['high'] < max(row['open'], row['close']):
            raise ValueError('Incoherent OHLC')
        row.update(adj_close=number(adjusted[i], True), volume=number(quotes['volume'][i]),
                   dividend=dividends.get(date, 0), split_numerator=splits.get(date, (1, 1))[0],
                   split_denominator=splits.get(date, (1, 1))[1])
        row['adjustment_factor'] = row['adj_close'] / row['close']
        rows.append(row)
    dates = {r['date'] for r in rows}
    if not set(dividends).issubset(dates) or not set(splits).issubset(dates):
        raise ValueError('Corporate action outside quote calendar')
    return rows


def validate_calendar(all_rows):
    benchmark = [r['date'] for r in all_rows['SPY']]
    for symbol, rows in all_rows.items():
        if [r['date'] for r in rows] != benchmark:
            raise ValueError(f'Calendar mismatch: {symbol}; no filling or silent intersection')
    if benchmark[0] != '2006-01-03':
        raise ValueError('Incomplete starting history')
    return benchmark


def write_csv(path, rows):
    with path.open('x', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def nominal_rows(rows):
    """Undo future ordinary splits; cash/share ledger applies split on ex-date."""
    factor = 1.0
    output = []
    for original in reversed(rows):
        row = dict(original)
        ratio = float(row['split_numerator']) / float(row['split_denominator'])
        if ratio != int(ratio) or ratio < 1:
            raise ValueError('Unsupported corporate action; never model spin-off as ordinary split')
        row['future_split_factor'] = factor
        for key in ('open', 'high', 'low', 'close', 'dividend'):
            row['trade_' + key] = float(row[key]) * factor
        output.append(row)
        factor *= ratio
    return list(reversed(output))


def derive(folder):
    folder = Path(folder).resolve()
    if ROOT.resolve() not in folder.parents:
        raise ValueError('Snapshot outside TEAM21 directory')
    source = json.loads((folder / 'manifest.json').read_text())
    if source['status'] != 'complete':
        raise ValueError('Incomplete source snapshot')
    destination = folder / 'nominal-v1'
    destination.mkdir(exist_ok=False)
    metadata = {'source_manifest_sha256': sha(folder / 'manifest.json'), 'files': [],
        'excluded': {'XLF': '2016-09-19 XLRE in-kind spin-off is not an ordinary split; excluded before performance evaluation.'},
        'method': 'trade_OHLC and trade_dividend = provider value times product of ordinary split ratios strictly AFTER that date. Split-date holdings multiply by split ratio BEFORE trading. Provider close remains split-adjusted signal series. No dividend-adjusted prices used for fills.',
        'dividend_basis': 'Yahoo event dividends are historical split-adjusted (e.g. XLK 2025-09-22 event 0.179, nominal approximately 0.358). Provider rounds dividends; reconstructed values inherit rounding. No claim of exact brokerage statement replication.',
        'sources': ['https://help.yahoo.com/kb/SLN28256.html',
                    'https://investors.statestreet.com/investor-news-events/press-releases/news-details/2025/State-Street-Investment-Management-Announces-Share-Splits-for-Five-Select-Sector-SPDR-ETFs/default.aspx',
                    'https://www.sec.gov/Archives/edgar/data/1064641/000119312516698468/filename1.htm',
                    'https://www.miaxglobal.com/sites/default/files/alert-files/XLF_Distribution_39575.pdf']}
    for item in source['files']:
        for kind in ('raw', 'csv'):
            if sha(folder / item[kind + '_file']) != item[kind + '_sha256']:
                raise ValueError('Source hash mismatch')
        if item['symbol'] == 'XLF':
            continue
        with (folder / item['csv_file']).open(newline='') as stream:
            rows = nominal_rows(list(csv.DictReader(stream)))
        path = destination / item['csv_file']
        with path.open('x', newline='', encoding='utf-8') as stream:
            writer = csv.DictWriter(stream, fieldnames=list(FIELDS) + ['future_split_factor'] + ['trade_' + k for k in ('open', 'high', 'low', 'close', 'dividend')])
            writer.writeheader()
            writer.writerows(rows)
        metadata['files'].append({'symbol': item['symbol'], 'csv_file': path.name, 'csv_sha256': sha(path), 'rows': len(rows)})
    with (destination / 'manifest.json').open('x', encoding='utf-8') as stream:
        json.dump(metadata, stream, indent=2)
    print(destination)


def main():
    now = datetime.now(timezone.utc)
    folder = ROOT / (now.strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8])
    folder.mkdir(parents=True, exist_ok=False)
    manifest = {'schema_version': 1, 'status': 'collecting', 'started_utc': now.isoformat(),
                'requested_start': START, 'requested_end_exclusive': END, 'symbols': list(SYMBOLS),
                'price_basis': 'Untouched Yahoo quote OHLC: historical split-adjusted, not dividend-adjusted. adj_close also dividend-adjusted. Events untouched; handle splits explicitly before whole-share ledger use.',
                'limitations': ['Daily data cannot prove intraday fills or enforce a guaranteed 10% loss cap.',
                                'Latest revised historical snapshot, not point-in-time constituents or quotes.',
                                'No dividend payment dates; ex-date amount is not same-day spendable cash.'],
                'files': []}
    all_rows = {}
    try:
        opener = build_opener(NoRedirect())
        for symbol in SYMBOLS:
            source = url(symbol)
            with opener.open(Request(source, headers={'User-Agent': 'Mozilla/5.0'}), timeout=45) as response:
                raw = response.read(20_000_001)
            if len(raw) > 20_000_000:
                raise ValueError('Oversized response')
            stem = symbol.replace('^', '')
            raw_file, csv_file = folder / f'{stem}.raw.json', folder / f'{stem}.csv'
            with raw_file.open('xb') as stream:
                stream.write(raw)
            rows = normalize(raw, symbol)
            write_csv(csv_file, rows)
            all_rows[symbol] = rows
            manifest['files'].append({'symbol': symbol, 'source_url': source,
                'retrieved_utc': datetime.now(timezone.utc).isoformat(),
                'raw_file': raw_file.name, 'raw_sha256': sha(raw_file),
                'csv_file': csv_file.name, 'csv_sha256': sha(csv_file),
                'rows': len(rows), 'first_date': rows[0]['date'], 'last_date': rows[-1]['date'],
                'dividend_count': sum(r['dividend'] > 0 for r in rows),
                'split_count': sum(r['split_numerator'] != r['split_denominator'] for r in rows)})
            print(symbol, len(rows), rows[-1]['date'], flush=True)
            time.sleep(0.5)
        calendar = validate_calendar(all_rows)
        combined = sorted((r for rows in all_rows.values() for r in rows), key=lambda r: (r['date'], r['symbol']))
        write_csv(folder / 'daily.csv', combined)
        manifest.update(status='complete', first_date=calendar[0], last_date=calendar[-1],
                        calendar_rows=len(calendar), calendar_sha256=hashlib.sha256('\n'.join(calendar).encode()).hexdigest(),
                        daily_file='daily.csv', daily_sha256=sha(folder / 'daily.csv'))
    except Exception as error:
        manifest.update(status='failed', error=f'{type(error).__name__}: {error}')
        raise
    finally:
        manifest['finished_utc'] = datetime.now(timezone.utc).isoformat()
        with (folder / 'manifest.json').open('x', encoding='utf-8') as stream:
            json.dump(manifest, stream, indent=2)
        print('Snapshot:', folder, flush=True)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--derive':
        derive(sys.argv[2])
    elif len(sys.argv) == 1:
        main()
    else:
        raise SystemExit('Usage: team21_fetch.py [--derive snapshot-directory]')
