"""Frozen TEAM-11 experiment; selection is persisted before evaluation runs."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'tools/strategy_v2_data/results'
PLAN = ROOT / 'tools/strategy_v2_plan.md'
PLAN_HASH = 'eaa0827e0fc30a39ccd8d3abdde34e4832ce56dd5864206d5b1d4147a2ad9a8f'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')


def run(candidate, label, start, end, slip, market):
    prefix = OUT / f'{candidate}-{label}-{5 + slip}bp'
    result = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/strategy_v2.py'),
        str(market), '--candidate', candidate, '--start', start, '--end', end,
        '--slippage-bps', str(slip), '--output-prefix', str(prefix),
        '--lock-file', str(PLAN), '--lock-sha256', PLAN_HASH],
        cwd=ROOT, capture_output=True, text=True, encoding='utf-8', check=True)
    report = json.loads(result.stdout)
    checked = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/strategy_v2_audit.py'),
        str(prefix), '--market', str(market)], cwd=ROOT, capture_output=True,
        text=True, encoding='utf-8', check=True)
    audit = json.loads(checked.stdout)
    if audit['status'] != 'PASS':
        raise ValueError('Independent audit failed')
    save(Path(str(prefix) + '-audit.json'), audit)
    print(json.dumps({'run': prefix.name, 'return': report['metrics']['strategy_twr'],
        'mdd': report['metrics']['strategy_max_drawdown'], 'audit': audit['status']}), flush=True)
    return report


def main():
    if digest(PLAN) != PLAN_HASH:
        raise ValueError('Protocol changed')
    if OUT.exists():
        raise ValueError('Results directory already exists; immutable run, do not overwrite')
    OUT.mkdir(parents=True)
    development = ROOT / 'tools/strategy_v2_data/development/daily.csv'
    evaluation = ROOT / 'tools/strategy_v2_data/daily.csv'
    paths = [PLAN, Path(__file__), development, evaluation] + [ROOT / ('tools/' + name)
        for name in ['strategy_v2.py', 'strategy_v2_audit.py', 'replay_daily.py', 'us_performance.py']]
    hashes = {str(path.relative_to(ROOT)): digest(path) for path in paths}
    save(OUT / 'pre-run-lock.json', {'utc': datetime.now(timezone.utc).isoformat(), 'sha256': hashes})
    reports = {}
    for candidate in 'ABC':
        reports[candidate] = [run(candidate, 'development', '2018-01-01', '2023-12-31', slip, development)
            for slip in (5, 20)]
    eligible = [candidate for candidate in 'ABC' if all(
        r['metrics']['strategy_max_drawdown'] <= .10 + 1e-12 for r in reports[candidate])]
    selected = max(eligible or list('ABC'), key=lambda candidate: reports[candidate][1]['metrics']['strategy_twr'])
    selection = {'utc': datetime.now(timezone.utc).isoformat(), 'candidate': selected,
        'eligible': bool(eligible), 'eligible_candidates': eligible,
        'development_metrics': {c: [r['metrics'] for r in reports[c]] for c in 'ABC'},
        'sha256': hashes, 'evaluation_results_opened': False}
    save(OUT / 'selection-lock.json', selection)
    print('SELECTION LOCKED: ' + selected + '; eligible=' + str(bool(eligible)), flush=True)
    for label, start, end, market in [
        ('retrospective', '2024-01-01', '2025-12-31', development),
        ('continuous', '2018-01-01', '2025-12-31', development),
        ('evaluation', '2026-01-01', '2026-11-17', evaluation)]:
        for slip in (5, 20):
            if any(digest(ROOT / name) != value for name, value in hashes.items()):
                raise ValueError('Frozen inputs changed after selection')
            run(selected, label, start, end, slip, market)
    save(OUT / 'completion.json', {'utc': datetime.now(timezone.utc).isoformat(),
        'runs': 12, 'audits': '12 PASS', 'selection_sha256': digest(OUT / 'selection-lock.json'),
        'evaluation_results_opened': True})


if __name__ == '__main__':
    main()
