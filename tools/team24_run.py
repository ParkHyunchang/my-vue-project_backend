"""Explicit, offline pre-run freeze and fixed 60-case execution. No network."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import team24_research as research

ROOT = Path(__file__).resolve().parent
PLAN = ROOT.parent.parent / 'my-vue-project/docs/ai-team/tasks/TEAM-24.md'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['freeze', 'run'])
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lockfile = args.output / 'pre-run-lock.json'
    if args.action == 'freeze':
        args.output.mkdir(parents=True, exist_ok=False)
        files = sorted(ROOT.glob('team24*.py')) + sorted(p for p in args.data.rglob('*') if p.is_file())
        files += [PLAN, ROOT / 'team21_research.py', ROOT / 'team21_audit.py', ROOT / 'team21_test.py', ROOT / 'team22_research.py', ROOT / 'team21_fetch.py', ROOT / 'team23_report.py', ROOT / 'strategy_v3_data/manifest.json']
        lock = {'frozen_utc': datetime.now(timezone.utc).isoformat(),
                'data': str(args.data.resolve()), 'candidates': list(research.CANDIDATES), 'risk_policies': list(research.RISK_POLICIES),
                'cost_bps': list(research.COSTS), 'planned_runs': 60,
                'files': {str(p.resolve()): sha(p) for p in files}}
        lockfile.write_text(json.dumps(lock, indent=2), encoding='utf-8')
        (args.output / 'PLAN.md').write_bytes(PLAN.read_bytes())
        print('Frozen', len(files), 'files; no performance calculated.')
        return
    lock = json.loads(lockfile.read_text(encoding='utf-8'))
    if str(args.data.resolve()) != lock['data']:
        raise ValueError('Data path differs from freeze')
    for name, expected in lock['files'].items():
        if sha(Path(name)) != expected:
            raise ValueError('Frozen input changed: ' + name)
    data, benchmark = research.load_data(args.data)
    summary = research.run_suite(data, benchmark, args.output / 'runs')
    if len(summary) != lock['planned_runs']:
        raise AssertionError('Incomplete suite')
    files = sorted((args.output / 'runs').glob('*.json'))
    (args.output / 'completion.json').write_text(json.dumps({
        'completed_utc': datetime.now(timezone.utc).isoformat(), 'runs': len(summary),
        'pre_run_lock_sha256': sha(lockfile),
        'files': {p.name: sha(p) for p in files}}, indent=2), encoding='utf-8')
    print('Completed', len(summary), 'fixed runs:', args.output)


if __name__ == '__main__':
    main()
