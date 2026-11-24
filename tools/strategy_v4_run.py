"""Frozen TEAM-13 20-run recovery experiment; offline only."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'tools/strategy_v4_results'
PLAN = ROOT / 'tools/strategy_v4_plan.md'
PLAN_HASH = 'f871afa11f1e10b072f3d70795043d86b57cb7ba21b6175138190b867f6c21e3'
PERIODS = [('crisis', '2008-01-01', '2017-12-31'), ('middle', '2018-01-01', '2023-12-31'),
    ('recent', '2024-01-01', '2025-12-31'), ('latest', '2026-01-01', '2026-11-17'),
    ('continuous', '2008-01-01', '2026-11-17')]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')


def command(arguments):
    result = subprocess.run([sys.executable, '-B', *arguments], cwd=ROOT,
        capture_output=True, text=True, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout)


def main():
    if digest(PLAN) != PLAN_HASH:
        raise ValueError('Protocol changed')
    if OUT.exists():
        raise ValueError('Results already exist; no overwriting')
    market = ROOT / 'tools/strategy_v3_data/daily.csv'
    names = ['strategy_v4.py', 'strategy_v4_audit.py', 'strategy_v4_run.py', 'strategy_v4_plan.md',
        'strategy_v3.py', 'strategy_v3_audit.py', 'strategy_v3_data/daily.csv', 'replay_daily.py', 'us_performance.py']
    hashes = {'tools/' + name: digest(ROOT / 'tools' / name) for name in names}
    for label, _, _ in PERIODS:
        for old in 'DF':
            for cost in (10, 25):
                for suffix in ('.json', '-equity.csv', '-trades.json'):
                    name = f'tools/strategy_v3_data/results/{old}-{label}-{cost}bp{suffix}'
                    hashes[name] = digest(ROOT / name)
    OUT.mkdir()
    save(OUT / 'pre-run-lock.json', {'utc': datetime.now(timezone.utc).isoformat(), 'sha256': hashes,
        'periods': PERIODS, 'candidates': list('GH'), 'runs': 20, 'all_periods_retrospective': True})
    summary = []
    for label, start, end in PERIODS:
        for candidate, baseline in [('G', 'D'), ('H', 'F')]:
            for slip in (5, 20):
                if any(digest(ROOT / name) != value for name, value in hashes.items()):
                    raise ValueError('Frozen input changed')
                prefix = OUT / f'{candidate}-{label}-{5 + slip}bp'
                report = command(['tools/strategy_v4.py', str(market), '--candidate', candidate,
                    '--start', start, '--end', end, '--slippage-bps', str(slip), '--output-prefix', str(prefix),
                    '--lock-file', str(PLAN), '--lock-sha256', PLAN_HASH])
                audit = command(['tools/strategy_v4_audit.py', str(prefix), '--market', str(market)])
                if audit['status'] != 'PASS':
                    raise ValueError('Audit failed')
                save(Path(str(prefix) + '-audit.json'), audit)
                old = json.loads((ROOT / f'tools/strategy_v3_data/results/{baseline}-{label}-{5+slip}bp.json').read_text(encoding='utf-8'))
                m = report['metrics']
                if (m['start'], m['end'], m['benchmark_total_return']) != (old['metrics']['start'], old['metrics']['end'], old['metrics']['benchmark_total_return']):
                    raise ValueError('Baseline comparison window/benchmark mismatch')
                row = {'run': prefix.name, 'period': label, 'candidate': candidate, 'cost_bps': 5+slip,
                    'return_pct': m['strategy_twr']*100, 'benchmark_pct': m['benchmark_total_return']*100,
                    'excess_pp': m['excess_return_percentage_points'], 'mdd_pct': m['strategy_max_drawdown']*100,
                    'cagr_pct': m['strategy_cagr']*100, 'latch_date': report['latch_date'],
                    'goal_met': m['strategy_twr'] > m['benchmark_total_return'] and m['strategy_max_drawdown'] <= .10,
                    'baseline_return_pct': old['metrics']['strategy_twr']*100,
                    'baseline_mdd_pct': old['metrics']['strategy_max_drawdown']*100,
                    'baseline_average_invested_percent': old['average_invested_percent'],
                    'average_invested_percent': report['average_invested_percent'], 'audit': 'PASS'}
                summary.append(row)
                print(json.dumps(row), flush=True)
    save(OUT / 'summary.json', summary)
    save(OUT / 'completion.json', {'utc': datetime.now(timezone.utc).isoformat(), 'runs': len(summary),
        'audits': '20 PASS', 'all_periods_retrospective': True,
        'all_conditions_pass_candidates': [c for c in 'GH' if all(r['goal_met'] for r in summary if r['candidate']==c)],
        'pre_run_lock_sha256': digest(OUT / 'pre-run-lock.json')})


if __name__ == '__main__':
    main()
