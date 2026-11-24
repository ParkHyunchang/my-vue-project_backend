"""Independent TEAM25 acceptance checks; synthetic inputs, no network/orders."""
import copy
import unittest
import json
import tempfile
from datetime import date, timedelta, datetime, timezone
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor

import team24_research as reference
from research_lab import replay
from research_lab.core import Lab, LabError
from research_lab.data import append_market, digest


def bars(count=235):
    dates = []
    day = date(2024, 1, 2)
    while len(dates) < count:
        if day.weekday() < 5:
            dates.append(day.isoformat())
        day += timedelta(days=1)
    result = {}
    for k, symbol in enumerate(reference.SYMBOLS):
        result[symbol] = []
        for i, d in enumerate(dates):
            price = 90 + i * (.07 + k * .03)
            result[symbol].append(dict(date=d, open=price, close=price, adj_close=price,
                trade_open=price, trade_close=price, trade_dividend=0.,
                high=price, low=price, volume=100000., dividend=0.,
                split_numerator=1., split_denominator=1.))
    return result


class LedgerAcceptanceTests(unittest.TestCase):
    def test_incremental_state_matches_frozen_research_ledger(self):
        data = bars()
        signals = reference.indicators(data)
        for candidate in reference.CANDIDATES:
            for policy in reference.RISK_POLICIES:
                expected = reference.simulate(data, data['SPY'][200]['date'],
                    data['SPY'][-1]['date'], candidate, 25, signals=signals, risk_policy=policy)
                state = replay.new_account(10000, candidate + ':' + policy)
                for i in range(200, len(data['SPY'])):
                    previous = copy.deepcopy(state)
                    state = replay.advance(state, data, signals, i, 25)
                    self.assertEqual(previous['history'], state['history'][:-1])
                self.assertEqual(state['history'], expected['history'])
                self.assertEqual(state['trades'], expected['trades'])
                self.assertAlmostEqual(replay.summary(state)['equityUsd'], expected['final_equity'])

    def test_switch_keeps_account_and_past_decisions(self):
        data = bars()
        signals = reference.indicators(data)
        state = replay.new_account(10000, 'balanced_trend:recovery_vol')
        for i in range(200, 205):
            state = replay.advance(state, data, signals, i)
        prior = copy.deepcopy(state)
        self.assertTrue(any(prior['qty'].values()))
        state['switch_to'] = 'rotation1:cushion80'
        switched = replay.advance(state, data, signals, 205)
        unchanged = replay.advance(prior, data, signals, 205)
        # Approval affects the completed-close decision, not that day's old open order.
        for field in ('cash', 'qty', 'peak', 'hard', 'unsettled', 'dividends', 'fees', 'slippage'):
            self.assertEqual(switched[field], unchanged[field], field)
        self.assertEqual(switched['trades'], unchanged['trades'])
        self.assertEqual(switched['history'][:-1], prior['history'])
        self.assertEqual(switched['strategy_id'], 'rotation1:cushion80')
        self.assertGreaterEqual(switched['peak'], prior['peak'])
        next_state = replay.advance(switched, data, signals, 206)
        self.assertGreaterEqual(next_state['cash'], 0)
        self.assertEqual(next_state['history'][:-1], switched['history'])

    def test_switch_cannot_reset_hard_stop_or_high_water(self):
        data = bars()
        signals = reference.indicators(data)
        state = replay.new_account(10000, 'balanced_trend:recovery_vol')
        state.update(peak=12000, hard=True, latch={'date': '2024-01-01', 'phase': 'test'},
                     switch_to='rotation1:recovery_vol')
        after = replay.advance(state, data, signals, 200)
        self.assertTrue(after['hard'])
        self.assertEqual(after['peak'], 12000)
        self.assertEqual(after['cash'], 10000)
        self.assertFalse(after['trades'])
        self.assertEqual(after['pending']['gross'], 0)

    def test_duplicate_completed_day_cannot_be_retraded(self):
        data = bars()
        signals = reference.indicators(data)
        state = replay.advance(replay.new_account(10000, 'rotation1:recovery_vol'), data, signals, 200)
        preserved = copy.deepcopy(state)
        with self.assertRaises(ValueError):
            replay.advance(state, data, signals, 200)
        self.assertEqual(state, preserved)


class RegistryAcceptanceTests(unittest.TestCase):
    """Private synthetic evidence tests are never runtime qualification overrides."""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.data = bars(280)
        self.now = datetime.fromisoformat(self.data['SPY'][205]['date']).replace(tzinfo=timezone.utc)
        self.lab = Lab(self.tmp.name, clock=lambda: self.now)
        self.hist = {c['id']: {'passed': True, 'auditPassed': True, 'annualizedPct': 11.,
            'maxDrawdownPct': 3., 'periods': []} for c in self.lab.catalog}
        self.loader = patch('research_lab.core.load_snapshot').start()
        self.addCleanup(patch.stopall)
        patch.object(Lab, '_historical', return_value=self.hist).start()

    def cycle(self, count):
        data = {s: rows[:count] for s, rows in self.data.items()}
        self.loader.return_value = {'data': data, 'benchmark': {r['date']: r['close'] for r in data['SPY']},
            'dates': [r['date'] for r in data['SPY']], 'snapshot_hash': digest(data)}
        return self.lab.run_cycle('private-test-fixture')

    def qualify_private_fixture(self):
        self.cycle(201)
        ident = 'rotation1:recovery_vol'
        with self.lab._db() as db:
            state = self.lab._read(db)
            candidate = state['shadows'][ident]
            candidate['history'] = [{'equity': 10500.}] * 60
            candidate['trades'] = [{'synthetic_fixture': True}] * 20
            candidate.update(cash=10500., peak=10500.)
            self.lab._save(db, state)
        # Reissue a genuinely bound report via normal cycle, using no future days.
        self.now = datetime.fromisoformat(self.data['SPY'][205]['date']).replace(tzinfo=timezone.utc)
        return ident, self.cycle(205)

    def test_registration_cutoff_restart_and_duplicate_cycle(self):
        first = self.cycle(201)
        self.assertEqual(first['paperAccount']['days'], 0)
        self.now = datetime.fromisoformat(self.data['SPY'][209]['date']).replace(tzinfo=timezone.utc)
        result = self.cycle(209)
        self.assertEqual(result['paperAccount']['days'], 3)  # only dates 206,207,208
        repeat = self.cycle(209)
        self.assertEqual(result, repeat)
        reopened = Lab(self.tmp.name, clock=lambda: self.now)
        self.assertEqual(reopened.status(), result)

    def test_unqualified_and_stale_approvals_do_not_mutate(self):
        status = self.cycle(201)
        args = ('rotation1:recovery_vol', status['report']['id'], status['revision'])
        with self.assertRaises(LabError):
            self.lab.approve(*args)
        self.assertEqual(status, self.lab.status())
        with self.assertRaises(LabError):
            self.lab.reject(args[0], 'incorrect-report', args[2])
        with self.assertRaises(LabError):
            self.lab.reject(args[0], args[1], args[2] - 1)
        self.assertEqual(status, self.lab.status())

    def test_qualified_approval_preserves_account_and_is_single_use(self):
        ident, before = self.qualify_private_fixture()
        candidate = next(c for c in before['candidates'] if c['id'] == ident)
        self.assertTrue(candidate['eligible'], candidate['reasons'])
        after = self.lab.approve(ident, before['report']['id'], before['revision'])
        self.assertEqual(after['paperAccount'], before['paperAccount'])
        self.assertEqual(after['activeStrategy'], before['activeStrategy'])
        self.assertEqual(after['pendingSwitch']['candidateId'], ident)
        with self.assertRaises(LabError):
            self.lab.approve(ident, before['report']['id'], before['revision'])
        self.assertEqual(Lab(self.tmp.name, clock=lambda: self.now).status(), after)

    def test_report_tampering_and_current_evidence_tampering_are_rejected(self):
        ident, status = self.qualify_private_fixture()
        with self.lab._db() as db:
            state = self.lab._read(db)
            state['active']['cash'] -= 1
            self.lab._save(db, state)
        with self.assertRaisesRegex(LabError, '증거'):
            self.lab.approve(ident, status['report']['id'], status['revision'])
        with self.lab._db() as db:
            db.execute('UPDATE reports SET payload=?', (json.dumps({'forged': True}),))
        with self.assertRaisesRegex(LabError, '무결성'):
            self.lab.approve(ident, status['report']['id'], status['revision'])

    def test_code_version_change_requires_explicit_new_registry(self):
        self.cycle(201)
        self.lab.code_hash = 'unrecognized-code'
        with self.assertRaises(LabError):
            self.lab.status()

    def test_qualified_candidate_becomes_unapprovable_when_data_is_stale(self):
        ident, status = self.qualify_private_fixture()
        self.now += timedelta(days=6)
        with self.assertRaisesRegex(LabError, '오래'):
            self.lab.approve(ident, status['report']['id'], status['revision'])
        self.assertIsNone(self.lab.status()['pendingSwitch'])

    def test_hard_latched_account_refuses_otherwise_qualified_candidate(self):
        ident, _ = self.qualify_private_fixture()
        with self.lab._db() as db:
            state = self.lab._read(db)
            state['active']['hard'] = True
            self.lab._save(db, state)
        # A new report binds the hard latch too; refusal must be the risk gate.
        status = self.cycle(206)
        with self.assertRaisesRegex(LabError, '10% 차단'):
            self.lab.approve(ident, status['report']['id'], status['revision'])
        self.assertTrue(self.lab.status()['paperAccount']['hardLatched'])

    def test_concurrent_approvals_commit_only_once(self):
        ident, status = self.qualify_private_fixture()
        second = Lab(self.tmp.name, clock=lambda: self.now)
        def attempt(lab):
            try:
                lab.approve(ident, status['report']['id'], status['revision'])
                return 'approved'
            except LabError:
                return 'refused'
        with ThreadPoolExecutor(max_workers=2) as workers:
            outcomes = list(workers.map(attempt, [self.lab, second]))
        self.assertEqual(sorted(outcomes), ['approved', 'refused'])
        events = [e for e in self.lab.status()['audit'] if e['event'] == 'SWITCH_APPROVED']
        self.assertEqual(len(events), 1)

    def test_adjustment_rebase_preserves_old_rows_and_rejects_corrections(self):
        old = {'data': {s: rows[:201] for s, rows in self.data.items()}, 'benchmark': {}}
        fresh = {'data': copy.deepcopy(self.data), 'benchmark': {}}
        for rows in fresh['data'].values():
            for row in rows:
                row['adj_close'] *= .99
        merged = append_market(old, fresh)
        for symbol in old['data']:
            self.assertEqual(merged['data'][symbol][:201], old['data'][symbol])
            self.assertAlmostEqual(merged['data'][symbol][-1]['adj_close'], self.data[symbol][-1]['adj_close'])
        fresh['data']['SPY'][10]['close'] += 1
        with self.assertRaises(ValueError):
            append_market(old, fresh)


if __name__ == '__main__':
    unittest.main()
