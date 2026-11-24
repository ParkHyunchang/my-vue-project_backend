"""Read-only operational shortlist assessment. No Lab constructor or broker imports."""
import hashlib
import json
import math
import re
import sqlite3
from contextlib import closing
from datetime import date, datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SELECTED = ('baseline', 'pause6', 'vol_entry')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def cell_pass(row):
    values = [row[k] for k in ('initial', 'final_equity', 'mdd_pct')]
    require(all(isinstance(v, (int, float)) and math.isfinite(v) for v in values), 'nonfinite metrics')
    require(row['initial'] > 0 and row['final_equity'] >= 0 and row['mdd_pct'] >= 0, 'invalid metrics')
    days = (date.fromisoformat(row['end']) - date.fromisoformat(row['start'])).days
    require(days > 0, 'invalid period')
    annual = ((row['final_equity'] / row['initial']) ** (365.2425 / days) - 1) * 100
    return annual >= 10 and row['mdd_pct'] <= 10 and not row['hard_latch'] and row['pending_liquidation'] is False


def strict_gate(rows):
    require(len(rows) == 6 and {(r['period'], r['cost_bps']) for r in rows} ==
            {(p, c) for p in ('recent', 'latest', 'continuous') for c in (10, 25)}, 'exact unique six cells required')
    return all(cell_pass(r) for r in rows)


def verify_results(folder):
    manifest = read(folder / 'manifest.json')
    audit = read(folder / 'independent-audit.json')
    require(audit['status'] == 'PASS' and audit['run_count'] == 48, 'audit not passed')
    require(audit['audit_source_sha256'] == sha(ROOT / 'tools/team30_audit.py'), 'audit code changed')
    data = Path(manifest['data'])
    require(manifest['dailySha256'] == sha(data / 'daily.csv') and manifest['manifestSha256'] == sha(data / 'manifest.json'), 'data changed')
    for name, expected in manifest['sources'].items():
        require(Path(name).name == name and sha(ROOT / 'tools' / name) == expected, 'source changed')
    summary = read(folder / 'summary.json')
    require(len(summary) == 48 and len({s['file'] for s in summary}) == 48, 'summary coverage')
    require(set(audit['runs']) == {s['file'] for s in summary}, 'audit coverage')
    groups = {}
    for s in summary:
        name = s['file']
        require(Path(name).name == name, 'invalid result path')
        path = folder / 'runs' / name
        require(audit['runs'][name]['status'] == 'PASS' and sha(path) == audit['runs'][name]['sha256'], 'result changed')
        row = read(path)
        require(s == {k: v for k, v in row.items() if k not in ('history', 'trades')} | {'file': name}, 'summary changed')
        require(row['approval_eligible'] is False and row['historical_cell_pass'] == cell_pass(row), 'false eligibility')
        groups.setdefault(row['research_variant'], []).append(s)
    require(len(groups) == 8, 'variant coverage')
    for variant, rows in groups.items():
        require(strict_gate(rows) == audit['historical_gate'][variant], 'gate mismatch')
    return groups, {p.name: sha(p) for p in (folder / 'manifest.json', folder / 'summary.json', folder / 'independent-audit.json')}


def account_summary(account):
    return {'days': len(account['history']), 'fills': len(account['trades']), 'hardLatched': account['hard']}


def observe_database(path):
    """Read only the explicit local paper Lab state; never create an absent DB."""
    require(path.is_file(), 'existing paper database required')
    before = sha(path)
    with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)) as db:
        db.execute('PRAGMA query_only=ON')
        state = json.loads(db.execute('SELECT payload FROM state WHERE id=1').fetchone()[0])
    require(sha(path) == before, 'database changed during assessment; retry snapshot')
    return {'databaseSha256': before, 'registeredAt': state['registered_at'],
            'registeredDate': state['registered_date'], 'lastMarketDate': state['last_market_date'],
            'codeHash': state['code_hash'], 'activePaperStrategy': state['active_id'],
            'active': account_summary(state['active']),
            'shadows': {k: account_summary(v) for k, v in state['shadows'].items()},
            'pendingPaperSwitch': state['pending_switch'] is not None,
            'databaseUnchanged': True, 'newObservationRegistered': False}


def main():
    folder = ROOT / 'tools/team30_results/experiment'
    groups, hashes = verify_results(folder)
    sources = [ROOT / 'tools/research_lab' / n for n in ('core.py', 'replay.py', 'data.py')]
    sources += [ROOT / 'tools' / n for n in ('team24_research.py', 'team24_audit.py')]
    code_hash = digest({p.name: sha(p) for p in sources})
    java = (ROOT / 'src/main/java/com/hyunchang/webapp/service/strategybridge/ResearchEvidenceVerifier.java').read_text(encoding='utf-8')
    py = (ROOT / 'tools/research_lab/deployment.py').read_text(encoding='utf-8')
    java_pin = re.findall(r'"([0-9a-f]{64})"', java)[0]
    py_pin = re.search(r"RESEARCH_HASH = '([0-9a-f]{64})'", py).group(1)
    runtime = ROOT / '.research-runtime/public-smoke'
    observation = observe_database(runtime / 'lab/research.sqlite3')
    pin_matches = code_hash == java_pin == py_pin == observation['codeHash']
    scheduler = runtime / 'scheduler.json'
    schedule = read(scheduler) if scheduler.is_file() else None
    candidates = []
    for variant in SELECTED:
        supported = variant == 'baseline' and pin_matches
        rows = groups[variant]
        candidates.append({'variant': variant, 'role': 'existing paper baseline' if variant == 'baseline' else 'research comparator only',
            'engineVersion': 'team24-daily-v1' if supported else None,
            'strategyVersion': digest({'code': code_hash, 'candidate': 'balanced_trend', 'policy': 'recovery_vol', 'costBps': 25}) if supported else None,
            'executionSupported': supported, 'historicalPassed': strict_gate(rows),
            'passedCells': sum(cell_pass(r) for r in rows), 'totalCells': 6,
            'forward': observation['shadows'].get('balanced_trend:recovery_vol') if variant == 'baseline' else {'days': 0, 'fills': 0, 'registered': False},
            'approved': False, 'liveReady': False,
            'cost25Results': [{k: r[k] for k in ('period', 'annualized_pct', 'return_pct', 'mdd_pct', 'trade_count', 'hard_latch')} for r in rows if r['cost_bps'] == 25]})
    result = {'assessedAt': datetime.now(timezone.utc).isoformat(), 'readOnly': True, 'evidenceIntegrity': 'PASS',
        'verifiedRuns': 48, 'evidenceHashes': hashes, 'researchCodeHash': code_hash, 'javaPin': java_pin,
        'pythonPin': py_pin, 'sourcePinsMatch': pin_matches, 'observation': observation,
        'scheduler': {'settingsPresent': schedule is not None, 'persistedEnabled': schedule.get('enabled') if schedule else None,
            'defaultWhenSettingsAbsent': False, 'runningProcessVerified': False, 'startedByAssessment': False},
        'candidates': candidates, 'liveReadyCount': 0, 'approved': False,
        'limitations': ['Historical six-cell gate fails for every selected candidate; forward waiting alone cannot repair that evidence.',
            'pause6 and vol_entry are not supported by the live execution engine.',
            'User reports USD35.48 orderable cash and JEPQ3 shares; current prices, valuation, persisted risk peak and real fills remain unverified.',
            '10% is an order-block threshold, not a guaranteed maximum realized loss.',
            'Scheduler file describes persisted settings, not proof of a running process.']}
    out = ROOT / 'tools/team31_results'
    out.mkdir(exist_ok=True)
    (out / 'readiness.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
