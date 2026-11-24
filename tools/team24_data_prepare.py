"""Offline conversion of verified archived Yahoo raw responses; no network."""
import json
from pathlib import Path
from datetime import datetime, timezone
from team21_fetch import normalize, validate_calendar, write_csv, sha

SOURCE = Path(__file__).resolve().parent / 'strategy_v3_data'
OUTPUT = Path(__file__).resolve().parent / 'team24_data'
SYMBOLS = ('SPY', 'QQQ', 'GLD', 'IEF', '^SP500TR')


def main():
    original = json.loads((SOURCE / 'manifest.json').read_text(encoding='utf-8'))
    inventory = {r['symbol']: r for r in original['files']}
    rows = {}
    payloads = {}
    for symbol in SYMBOLS:
        entry = inventory[symbol]
        path = SOURCE / entry['raw_file']
        if sha(path) != entry['raw_sha256']:
            raise ValueError('Archived source hash mismatch: ' + symbol)
        payloads[symbol] = path.read_bytes()
        rows[symbol] = normalize(payloads[symbol], symbol)
        if any(r['split_numerator'] != r['split_denominator'] for r in rows[symbol]):
            raise ValueError('Unexpected split requires explicit review')
        if len(rows[symbol]) != 5252 or rows[symbol][-1]['date'] != '2026-11-17':
            raise ValueError('Unexpected source coverage')
    dates = validate_calendar(rows)
    OUTPUT.mkdir(exist_ok=False)
    files = []
    for symbol in SYMBOLS:
        entry = inventory[symbol]
        stem = symbol.replace('^', '')
        raw, csv = OUTPUT / f'{stem}.raw.json', OUTPUT / f'{stem}.csv'
        raw.write_bytes(payloads[symbol])
        write_csv(csv, rows[symbol])
        files.append({'symbol': symbol, 'source_url': entry['source_url'],
                      'retrieved_utc': original['retrieved_at_utc'],
                      'raw_file': raw.name, 'raw_sha256': sha(raw),
                      'csv_file': csv.name, 'csv_sha256': sha(csv),
                      'rows': len(rows[symbol]), 'first_date': dates[0], 'last_date': dates[-1]})
    write_csv(OUTPUT / 'daily.csv', sorted((r for rs in rows.values() for r in rs), key=lambda r: (r['date'], r['symbol'])))
    manifest = {'status': 'complete', 'symbols': list(SYMBOLS), 'files': files,
                'first_date': dates[0], 'last_date': dates[-1], 'calendar_rows': len(dates),
                'daily_file': 'daily.csv', 'daily_sha256': sha(OUTPUT / 'daily.csv'),
                'source_manifest_sha256': sha(SOURCE / 'manifest.json'),
                'converted_utc': datetime.now(timezone.utc).isoformat(),
                'price_basis': 'Untouched Yahoo quote OHLC and events, separate adj_close for signals. No splits in this archive. Do not use older dividend-adjusted CSV as execution prices.',
                'limitations': ['Archived revised snapshot through 2026-11-17, not latest download.',
                                'Already inspected historical sample, not fresh holdout.',
                                'Dividend payment dates absent; receivable not spendable.']}
    (OUTPUT / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print('Verified and normalized', len(files), 'sources:', len(dates), 'days', dates[0], dates[-1])


if __name__ == '__main__':
    main()
