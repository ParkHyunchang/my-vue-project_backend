"""Read verified local market snapshots. No credentials, networking or orders."""
import csv
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import team24_audit
import team24_research

SYMBOLS = ('SPY', 'QQQ', 'GLD', 'IEF', '^SP500TR')


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def load_snapshot(folder, now=None):
    folder = Path(folder).resolve()
    manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('status') != 'complete':
        raise ValueError('완료되지 않은 시장자료입니다.')
    entries = manifest.get('files', [])
    if sorted(e.get('symbol') for e in entries) != sorted(SYMBOLS):
        raise ValueError('고정된 4개 ETF와 비교 지수 자료가 필요합니다.')
    names = {'daily.csv', 'manifest.json'}
    for entry in entries:
        stem = entry['symbol'].replace('^', '')
        if entry['raw_file'] != stem + '.raw.json' or entry['csv_file'] != stem + '.csv':
            raise ValueError('허용되지 않은 시장자료 파일 이름입니다.')
        names.update((entry['raw_file'], entry['csv_file']))
    if manifest.get('daily_file') != 'daily.csv':
        raise ValueError('통합 자료 파일 이름이 다릅니다.')
    for name in names:
        p = folder / name
        if p.is_symlink() or not p.is_file() or p.resolve().parent != folder:
            raise ValueError('시장자료 경로가 올바르지 않습니다.')
    team24_audit.verify_manifest(folder)
    data, benchmark = team24_research.load_data(folder)
    dates = team24_research.validate(data)
    if len(dates) < 201:
        raise ValueError('최소 201거래일 예열 자료가 필요합니다.')
    now = now or datetime.now(timezone.utc)
    if dates[-1] >= now.date().isoformat():
        raise ValueError('UTC 기준 이전 날짜의 완료 일봉만 처리합니다.')
    # Keep extra price/volume fields for correction checks, never for execution.
    with (folder / 'daily.csv').open(encoding='utf-8', newline='') as stream:
        rows = list(csv.DictReader(stream))
    groups = {}
    by_day = {s: {r['date']: r for r in rs} for s, rs in data.items()}
    for r in rows:
        groups.setdefault(r['date'], []).append(r)
        if r['symbol'] in by_day:
            target = by_day[r['symbol']][r['date']]
            for key in ('high', 'low', 'volume'):
                target[key] = float(r[key])
            if target['split_numerator'] != target['split_denominator']:
                raise ValueError('새 분할 자료는 별도 검토가 필요합니다.')
    hashes = {d: digest(groups[d]) for d in dates}
    return {'data': data, 'benchmark': benchmark, 'dates': dates,
            'day_hashes': hashes, 'snapshot_hash': manifest['daily_sha256'],
            'manifest_hash': hashlib.sha256((folder / 'manifest.json').read_bytes()).hexdigest()}


def check_append(previous, current):
    for day, expected in previous.items():
        if current.get(day) != expected:
            raise ValueError('과거 시장자료가 변경되거나 누락되었습니다. 자동 처리를 중단합니다: ' + day)


def append_market(previous, incoming):
    """Allow uniform dividend adjustment rebases, never rewrite registered history.

    Yahoo may multiply all past adjusted closes by one constant when a new
    distribution occurs. Verify that invariance, then rescale only appended
    adjusted prices onto the already registered basis. Raw corrections stop.
    """
    if previous is None:
        return {'data': incoming['data'], 'benchmark': incoming['benchmark']}
    merged = {'data': {}, 'benchmark': dict(previous['benchmark'])}
    for day, value in previous['benchmark'].items():
        if incoming['benchmark'].get(day) != value:
            raise ValueError('과거 비교 지수 자료가 변경되었습니다: ' + day)
    merged['benchmark'].update(incoming['benchmark'])
    for symbol, old in previous['data'].items():
        fresh = incoming['data'][symbol]
        if len(fresh) < len(old) or [r['date'] for r in fresh[:len(old)]] != [r['date'] for r in old]:
            raise ValueError('과거 거래일이 변경되거나 누락되었습니다.')
        factor = old[-1]['adj_close'] / fresh[len(old)-1]['adj_close']
        for left, right in zip(old, fresh):
            for key in ('open', 'close', 'high', 'low', 'volume', 'dividend', 'split_numerator', 'split_denominator'):
                a, b = left[key], right[key]
                if abs(a-b) > 1e-8 * max(1, abs(a)):
                    raise ValueError('과거 원시 가격·배당 자료 변경: ' + symbol + '/' + left['date'])
            if abs(left['adj_close'] - right['adj_close'] * factor) > 1e-5 * max(1, abs(left['adj_close'])):
                raise ValueError('일관되지 않은 조정종가 변경: ' + symbol + '/' + left['date'])
        tail = []
        for row in fresh[len(old):]:
            row = dict(row)
            row['adj_close'] *= factor
            tail.append(row)
        merged['data'][symbol] = old + tail
    return merged
