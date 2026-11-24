import copy
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from team31_readiness import ROOT, cell_pass, observe_database, sha, strict_gate, verify_results


def synthetic_evidence(folder):
    """Small evidence-format fixture, not historical performance evidence."""
    data = folder / 'data'
    data.mkdir()
    (data / 'daily.csv').write_text('synthetic fixture only\n', encoding='utf-8')
    (data / 'manifest.json').write_text('{"kind":"synthetic"}', encoding='utf-8')
    (folder / 'runs').mkdir()
    summary, runs, gates = [], {}, {}
    for variant in range(8):
        name = f'synthetic-{variant}'
        gates[name] = False
        for period in ('recent', 'latest', 'continuous'):
            for cost in (10, 25):
                passes = (period, cost) != ('continuous', 25)
                row = dict(research_variant=name, period=period, cost_bps=cost,
                           initial=100, final_equity=120 if passes else 99,
                           start='2025-01-01', end='2026-01-01', mdd_pct=5,
                           hard_latch=None, pending_liquidation=False,
                           approval_eligible=False, historical_cell_pass=passes,
                           history=[], trades=[])
                filename = f'{name}-{period}-{cost}.json'
                path = folder / 'runs' / filename
                path.write_text(json.dumps(row), encoding='utf-8')
                runs[filename] = dict(status='PASS', sha256=sha(path))
                summary.append({k: v for k, v in row.items() if k not in ('history', 'trades')}
                               | {'file': filename})
    manifest = dict(data=str(data), dailySha256=sha(data / 'daily.csv'),
                    manifestSha256=sha(data / 'manifest.json'),
                    sources={'team30_research.py': sha(ROOT / 'tools/team30_research.py')})
    audit = dict(status='PASS', run_count=48,
                 audit_source_sha256=sha(ROOT / 'tools/team30_audit.py'),
                 runs=runs, historical_gate=gates)
    for filename, value in [('manifest.json', manifest), ('summary.json', summary),
                            ('independent-audit.json', audit)]:
        (folder / filename).write_text(json.dumps(value), encoding='utf-8')


class ReadinessTest(unittest.TestCase):
    def rows(self):
        return [dict(period=p, cost_bps=c, initial=100, final_equity=120,
                     start='2025-01-01', end='2026-01-01', mdd_pct=5,
                     hard_latch=None, pending_liquidation=False)
                for p in ('recent', 'latest', 'continuous') for c in (10, 25)]

    def test_six_cells_required(self):
        rows = self.rows()
        self.assertTrue(strict_gate(rows))
        with self.assertRaises(ValueError):
            strict_gate(rows[:-1])
        rows[-1] = copy.deepcopy(rows[0])
        with self.assertRaises(ValueError):
            strict_gate(rows)

    def test_one_failure_cannot_be_hidden_by_forward(self):
        rows = self.rows()
        rows[-1].update(final_equity=99, forwardDays=100, forwardFills=100)
        self.assertFalse(strict_gate(rows))

    def test_hard_and_pending_rejected(self):
        row = self.rows()[0]
        row['hard_latch'] = '2025-03-01'
        self.assertFalse(cell_pass(row))
        row.update(hard_latch=None, pending_liquidation=True)
        self.assertFalse(cell_pass(row))

    def test_nan_rejected(self):
        row = self.rows()[0]
        row['mdd_pct'] = float('nan')
        with self.assertRaises(ValueError):
            cell_pass(row)

    def test_synthetic_evidence_hashes_and_gates(self):
        # Actual historical archives remain checked by team31_readiness.py.
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            synthetic_evidence(folder)
            groups, hashes = verify_results(folder)
            self.assertEqual(8, len(groups))
            self.assertTrue(all(sum(cell_pass(row) for row in rows) == 5 for rows in groups.values()))
            self.assertFalse(any(strict_gate(rows) for rows in groups.values()))
            self.assertEqual(3, len(hashes))

    def test_evidence_changes_and_false_approval_are_rejected(self):
        for mutation in ('data', 'run', 'source', 'audit', 'coverage', 'summary', 'gate', 'approval'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                synthetic_evidence(folder)
                manifest = json.loads((folder / 'manifest.json').read_text())
                audit = json.loads((folder / 'independent-audit.json').read_text())
                summary = json.loads((folder / 'summary.json').read_text())
                filename = summary[0]['file']
                path = folder / 'runs' / filename
                if mutation == 'data':
                    (folder / 'data/daily.csv').write_text('changed', encoding='utf-8')
                elif mutation == 'run':
                    path.write_text('{}', encoding='utf-8')
                elif mutation == 'source':
                    manifest['sources']['team30_research.py'] = '0' * 64
                elif mutation == 'audit':
                    audit['status'] = 'FAIL'
                elif mutation == 'coverage':
                    summary.pop()
                elif mutation == 'summary':
                    summary[0]['final_equity'] = 999
                elif mutation == 'gate':
                    audit['historical_gate']['synthetic-0'] = True
                elif mutation == 'approval':
                    row = json.loads(path.read_text())
                    row['approval_eligible'] = True
                    path.write_text(json.dumps(row), encoding='utf-8')
                    audit['runs'][filename]['sha256'] = sha(path)
                    summary[0]['approval_eligible'] = True
                for name, value in [('manifest.json', manifest), ('summary.json', summary),
                                    ('independent-audit.json', audit)]:
                    (folder / name).write_text(json.dumps(value), encoding='utf-8')
                with self.assertRaises(ValueError):
                    verify_results(folder)

    def test_readonly_database_and_no_creation(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'paper.sqlite3'
            with self.assertRaises(ValueError):
                observe_database(path)
            self.assertFalse(path.exists())
            account = dict(history=[], trades=[], hard=False)
            state = dict(registered_at='2026-11-22', registered_date='2026-11-22',
                         last_market_date='2026-11-20', code_hash='example', active_id='baseline',
                         active=account, shadows={'baseline': account}, pending_switch=None)
            with closing(sqlite3.connect(path)) as db:
                db.execute('CREATE TABLE state (id INTEGER PRIMARY KEY, payload TEXT)')
                db.execute('INSERT INTO state VALUES (1,?)', (json.dumps(state),))
                db.commit()
            before = sha(path)
            result = observe_database(path)
            self.assertEqual(before, sha(path))
            self.assertFalse(result['newObservationRegistered'])
            self.assertEqual(0, result['active']['days'])


if __name__ == '__main__':
    unittest.main()
