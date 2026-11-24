"""Public daily market archive. Fixed symbols/host; no account or application imports."""
import csv
import hashlib
import json
import math
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, HTTPRedirectHandler, ProxyHandler, build_opener

SYMBOLS = ('SPY', 'QQQ', 'GLD', 'IEF', '^SP500TR')
FIELDS = ('date', 'symbol', 'open', 'high', 'low', 'close', 'adj_close', 'adjustment_factor',
          'volume', 'dividend', 'split_numerator', 'split_denominator')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('Market-data redirect rejected')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def normalize(raw, symbol, cutoff):
    result = json.loads(raw)['chart']['result'][0]
    if result['meta']['symbol'] != symbol or result['meta'].get('currency') != 'USD':
        raise ValueError('Unexpected market identity')
    quotes = result['indicators']['quote'][0]
    adjusted = result['indicators']['adjclose'][0]['adjclose']
    stamps = result['timestamp']
    if any(len(quotes[k]) != len(stamps) for k in ('open', 'high', 'low', 'close', 'volume')) or len(adjusted) != len(stamps):
        raise ValueError('Incomplete market arrays')
    def day(stamp):
        return datetime.fromtimestamp(stamp, timezone.utc).date().isoformat()
    events = result.get('events', {})
    dividends = {day(v['date']): v['amount'] for v in events.get('dividends', {}).values()}
    splits = {day(v['date']): (v['numerator'], v['denominator']) for v in events.get('splits', {}).values()}
    rows = []
    for i, stamp in enumerate(stamps):
        date = day(stamp)
        # Never consume today's still-revisable bar, even after the close.
        if date >= cutoff:
            continue
        if date < '2006-01-01' or rows and date <= rows[-1]['date']:
            raise ValueError('Invalid market chronology')
        row = {'date': date, 'symbol': symbol}
        row.update({k: quotes[k][i] for k in ('open', 'high', 'low', 'close', 'volume')})
        row.update(adj_close=adjusted[i], dividend=dividends.get(date, 0),
                   split_numerator=splits.get(date, (1, 1))[0], split_denominator=splits.get(date, (1, 1))[1])
        for k, v in row.items():
            if k in ('date', 'symbol'):
                continue
            if isinstance(v, bool) or not isinstance(v, (float, int)) or not math.isfinite(v) or v < 0 or (k not in ('volume', 'dividend') and v == 0):
                raise ValueError('Invalid market number')
        if row['low'] > min(row['open'], row['close']) or row['high'] < max(row['open'], row['close']):
            raise ValueError('Incoherent market OHLC')
        row['adjustment_factor'] = row['adj_close'] / row['close']
        rows.append(row)
    if not rows or rows[0]['date'] != '2006-01-03':
        raise ValueError('Incomplete warmup archive')
    return rows


def write_csv(path, rows):
    with Path(path).open('x', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def fetch_snapshot(root, now=None):
    now = now or datetime.now(timezone.utc)
    # US regular-session bars from strictly earlier UTC dates are completed.
    # This intentionally waits until UTC midnight rather than using today's bar.
    cutoff = now.astimezone(timezone.utc).date().isoformat()
    end = int(datetime.fromisoformat(cutoff).replace(tzinfo=timezone.utc).timestamp())
    folder = Path(root) / (now.strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8])
    folder.mkdir(parents=True, exist_ok=False)
    manifest = {'status': 'collecting', 'symbols': list(SYMBOLS), 'files': [], 'completed_before': cutoff,
                'price_basis': 'Yahoo split-adjusted OHLC, separate dividend-adjusted close; never intraday.',
                'retrieved_utc': now.isoformat()}
    rows = {}
    try:
        opener = build_opener(ProxyHandler({}), NoRedirect())
        for symbol in SYMBOLS:
            url = f'https://query1.finance.yahoo.com/v8/finance/chart/{quote(symbol, safe="")}?period1=1136073600&period2={end}&interval=1d&events=div%2Csplits'
            with opener.open(Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=30) as response:
                raw = response.read(20_000_001)
            if len(raw) > 20_000_000:
                raise ValueError('Market response too large')
            stem = symbol.replace('^', '')
            raw_path, csv_path = folder / f'{stem}.raw.json', folder / f'{stem}.csv'
            raw_path.write_bytes(raw)
            rows[symbol] = normalize(raw, symbol, cutoff)
            write_csv(csv_path, rows[symbol])
            manifest['files'].append({'symbol': symbol, 'source_url': url, 'raw_file': raw_path.name,
                'raw_sha256': sha(raw_path), 'csv_file': csv_path.name, 'csv_sha256': sha(csv_path),
                'rows': len(rows[symbol]), 'first_date': rows[symbol][0]['date'], 'last_date': rows[symbol][-1]['date']})
        calendar = [r['date'] for r in rows['SPY']]
        if any([r['date'] for r in rs] != calendar for rs in rows.values()):
            raise ValueError('Market calendars differ; no forward fill allowed')
        write_csv(folder / 'daily.csv', sorted((r for rs in rows.values() for r in rs), key=lambda r: (r['date'], r['symbol'])))
        manifest.update(status='complete', first_date=calendar[0], last_date=calendar[-1],
                        calendar_rows=len(calendar), daily_file='daily.csv', daily_sha256=sha(folder / 'daily.csv'))
    except Exception as error:
        manifest.update(status='failed', error_type=type(error).__name__)
        raise
    finally:
        (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    return folder
