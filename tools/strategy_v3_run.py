"""TEAM-12 fixed 30-run retrospective experiment; no live services or orders."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'tools/strategy_v3_data/results'
PLAN = ROOT / 'tools/strategy_v3_plan.md'
PLAN_HASH = 'f589cf061a1a51d29e0aadb6d7d55520d0c742660d6fd82be86a8e00895eb22d'
PERIODS = [('crisis', '2008-01-01', '2017-12-31'),
    ('middle', '2018-01-01', '2023-12-31'),
    ('recent', '2024-01-01', '2025-12-31'),
    ('latest', '2026-01-01', '2026-11-17'),
    ('continuous', '2008-01-01', '2026-11-17')]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')


def main():
    if digest(PLAN) != PLAN_HASH:
        raise ValueError('Protocol changed')
    if OUT.exists():
        raise ValueError('Results already exist; cannot overwrite immutable run')
    OUT.mkdir(parents=True)
    market = ROOT / 'tools/strategy_v3_data/daily.csv'
    names = ['strategy_v3.py', 'strategy_v3_audit.py', 'strategy_v3_run.py',
        'strategy_v3_plan.md', 'strategy_v3_data/daily.csv', 'replay_daily.py', 'us_performance.py']
    hashes = {'tools/' + name: digest(ROOT / 'tools' / name) for name in names}
    save(OUT / 'pre-run-lock.json', {'utc': datetime.now(timezone.utc).isoformat(),
        'sha256': hashes, 'periods': PERIODS, 'candidates': list('DEF'), 'total_runs': 30,
        'all_periods_retrospective': True})
    summary = []
    for label, start, end in PERIODS:
        for candidate in 'DEF':
            for slip in (5, 20):
                if any(digest(ROOT / name) != value for name, value in hashes.items()):
                    raise ValueError('Frozen inputs changed')
                prefix = OUT / f'{candidate}-{label}-{5 + slip}bp'
                result = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/strategy_v3.py'),
                    str(market), '--candidate', candidate, '--start', start, '--end', end,
                    '--slippage-bps', str(slip), '--output-prefix', str(prefix),
                    '--lock-file', str(PLAN), '--lock-sha256', PLAN_HASH],
                    cwd=ROOT, capture_output=True, text=True, encoding='utf-8', check=True)
                report = json.loads(result.stdout)
                checked = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/strategy_v3_audit.py'),
                    str(prefix), '--market', str(market)], cwd=ROOT, capture_output=True,
                    text=True, encoding='utf-8', check=True)
                audit = json.loads(checked.stdout)
                if audit['status'] != 'PASS':
                    raise ValueError('Independent audit failed')
                save(Path(str(prefix) + '-audit.json'), audit)
                m = report['metrics']
                item = {'run': prefix.name, 'period': label, 'candidate': candidate, 'cost_bps': 5 + slip,
                    'return_pct': m['strategy_twr'] * 100,
                    'benchmark_pct': m['benchmark_total_return'] * 100,
                    'excess_pp': m['excess_return_percentage_points'],
                    'mdd_pct': m['strategy_max_drawdown'] * 100,
                    'cagr_pct': m['strategy_cagr'] * 100,
                    'latch_date': report['latch_date'],
                    'goal_met': m['strategy_twr'] > m['benchmark_total_return'] and m['strategy_max_drawdown'] <= .10,
                    'audit': audit['status']}
                summary.append(item)
                print(json.dumps(item), flush=True)
    save(OUT / 'summary.json', summary)
    save(OUT / 'completion.json', {'utc': datetime.now(timezone.utc).isoformat(),
        'runs': len(summary), 'audits': '30 PASS', 'all_periods_retrospective': True,
        'all_conditions_pass_candidates': [c for c in 'DEF' if all(r['goal_met'] for r in summary if r['candidate'] == c)],
        'pre_run_lock_sha256': digest(OUT / 'pre-run-lock.json')})


if __name__ == '__main__':
    main()
