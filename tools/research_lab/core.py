"""Durable local research, forward virtual accounts, and reviewed paper switching.

The catalog is bounded, versioned code, not generated executable AI output.
SQLite transactions serialize cycles and decisions. No broker interface exists.
"""
import hashlib
import json
import math
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone, date
from pathlib import Path

import team24_research as engine
import team24_audit as auditor
from .data import load_snapshot, append_market, digest
from .replay import new_account, advance, summary

ACTIVE_DEFAULT = 'balanced_trend:recovery_vol'
NAMES = {'rotation1': '상승 추세 상위 1개', 'riskrank2': '위험조정 상위 2개',
         'balanced_trend': '주식·금·국채 분산 추세'}


class LabError(ValueError):
    pass


class Lab:
    def __init__(self, rootdir, initial_cash=10000, clock=None):
        self.root = Path(rootdir).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.path = self.root / 'research.sqlite3'
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        tools = Path(__file__).resolve().parent.parent
        sources = [Path(__file__), Path(__file__).with_name('replay.py'), Path(__file__).with_name('data.py'),
                   tools / 'team24_research.py', tools / 'team24_audit.py']
        self.code_hash = digest({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sources})
        self.catalog = [{'id': f'{c}:{p}', 'name': NAMES[c] + (' · 회복형' if p == 'recovery_vol' else ' · 손실여유형'),
                         'version': digest({'code': self.code_hash, 'candidate': c, 'policy': p, 'costBps': 25})}
                        for c in engine.CANDIDATES for p in engine.RISK_POLICIES]
        self.by_id = {c['id']: c for c in self.catalog}
        new_account(initial_cash, ACTIVE_DEFAULT)  # validate initial amount, no state reset
        with self._db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS state (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS reports (id TEXT PRIMARY KEY, payload TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS audit (seq INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, event TEXT NOT NULL, detail TEXT NOT NULL)')
            if db.execute('SELECT 1 FROM state WHERE id=1').fetchone() is None:
                state = dict(revision=0, code_hash=self.code_hash, registered_at=None, registered_date=None,
                             initial=float(initial_cash), market=None, last_market_date=None, active_id=ACTIVE_DEFAULT,
                             active=new_account(initial_cash, ACTIVE_DEFAULT),
                             shadows={c['id']: new_account(initial_cash, c['id']) for c in self.catalog},
                             report=None, pending_switch=None, rejected=[], historical={})
                self._save(db, state)

    @contextmanager
    def _db(self, write=True):
        db = sqlite3.connect(self.path, timeout=60)
        db.execute('PRAGMA journal_mode=WAL')
        db.execute('PRAGMA busy_timeout=60000')
        try:
            if write:
                db.execute('BEGIN IMMEDIATE')
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def _read(self, db):
        state = json.loads(db.execute('SELECT payload FROM state WHERE id=1').fetchone()[0])
        if state['code_hash'] != self.code_hash:
            raise LabError('전략 코드 버전이 바뀌었습니다. 기존 원장 자동 재실행·교체를 차단합니다.')
        return state

    @staticmethod
    def _save(db, state):
        db.execute('INSERT INTO state(id,payload) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload',
                   (json.dumps(state, ensure_ascii=False, separators=(',', ':'), allow_nan=False),))

    def _event(self, db, event, detail):
        db.execute('INSERT INTO audit(at,event,detail) VALUES(?,?,?)', (self.clock().isoformat(), event, detail))

    def _historical(self, market):
        data, benchmark = market['data'], market['benchmark']
        latest = data['SPY'][-1]['date']
        year = date.fromisoformat(latest).year
        if latest < '2026-01-02':
            raise LabError('현재 연구 범위에는 2026년 이후 자료가 필요합니다.')
        periods = [('recent', f'{year-2}-01-01', f'{year-1}-12-31'),
                   ('latest', f'{year}-01-01', latest), ('continuous', '2008-01-01', latest)]
        cached = engine.indicators(data)
        independent_data = {s: {r['date']: r for r in rs} for s, rs in data.items()}
        independent_data['^SP500TR'] = {d: {'close': v} for d, v in benchmark.items()}
        independent_signals = auditor.indicator_cache(independent_data)
        out = {}
        for entry in self.catalog:
            candidate, policy = entry['id'].split(':')
            runs = []
            for label, start, end in periods:
                for cost in (10, 25):
                    result = engine.simulate(data, start, end, candidate, cost, signals=cached, risk_policy=policy)
                    auditor.audit(result, independent_data, independent_signals)
                    days = (date.fromisoformat(result['end']) - date.fromisoformat(result['start'])).days
                    annual = ((result['final_equity']/result['initial']) ** (365.2425/max(1,days))-1)*100
                    runs.append({'period': label, 'start': result['start'], 'end': result['end'], 'costBps': cost,
                                 'annualizedPct': annual, 'returnPct': result['return_pct'],
                                 'maxDrawdownPct': result['mdd_pct'], 'hardLatched': bool(result['hard_latch']),
                                 'pendingLiquidation': result['pending_liquidation'], 'fillCount': result['trade_count']})
            long_run = next(r for r in runs if r['period'] == 'continuous' and r['costBps'] == 25)
            passed = all(r['annualizedPct'] >= 10 and r['maxDrawdownPct'] <= 10 and not r['hardLatched']
                         and not r['pendingLiquidation'] for r in runs)
            out[entry['id']] = {'annualizedPct': long_run['annualizedPct'], 'maxDrawdownPct': max(r['maxDrawdownPct'] for r in runs),
                                'passed': passed, 'auditPassed': True, 'periods': runs}
        return out

    def _eligibility(self, state, candidate):
        ident = candidate['id']
        forward, active = summary(state['shadows'][ident]), summary(state['active'])
        hist = state['historical'].get(ident)
        reasons = []
        if ident == state['active_id']:
            reasons.append('현재 기준 전략입니다.')
        if not hist or not hist.get('passed') or not hist.get('auditPassed'):
            reasons.append('과거 비용·연 10%·손실 기준을 통과하지 못했습니다.')
        if forward['days'] < 60:
            reasons.append('등록 이후 신규 일봉 60거래일이 필요합니다.')
        if forward['fillCount'] < 20:
            reasons.append('등록 이후 가상 체결 20건이 필요합니다.')
        if forward['returnPct'] <= 0 or forward['returnPct'] < active['returnPct'] + 2:
            reasons.append('순수익이 양수이고 현재 전략보다 2%p 이상 높아야 합니다.')
        if forward['maxDrawdownPct'] > 10 or forward['maxDrawdownPct'] > active['maxDrawdownPct'] + 1 or forward['hardLatched']:
            reasons.append('신규 관찰 손실·차단 기준을 통과하지 못했습니다.')
        if state['active']['hard']:
            reasons.append('계좌 10% 차단 상태는 전략 교체로 해제할 수 없습니다.')
        if state['pending_switch']:
            reasons.append('이미 승인한 교체가 다음 완료 일봉을 기다리고 있습니다.')
        if state['last_market_date'] is None or (self.clock().date() - date.fromisoformat(state['last_market_date'])).days > 4:
            reasons.append('시장자료가 오래되어 새 확인이 필요합니다.')
        return not reasons, reasons

    def _status(self, db, state):
        entries = []
        for item in self.catalog:
            ok, reasons = self._eligibility(state, item)
            if state['report'] and item['id'] in state['rejected']:
                ok = False
                reasons.append('현재 보고서에서 보류한 후보입니다.')
            entries.append(item | {'historical': state['historical'].get(item['id']),
                                   'forward': summary(state['shadows'][item['id']]), 'eligible': ok, 'reasons': reasons})
        events = [{'at': at, 'event': kind, 'detail': detail} for at,kind,detail in
                  db.execute('SELECT at,event,detail FROM audit ORDER BY seq DESC LIMIT 50')]
        return {'paperOnly': True, 'revision': state['revision'], 'registeredAt': state['registered_at'],
                'registeredDate': state['registered_date'], 'lastMarketDate': state['last_market_date'],
                'activeStrategy': self.by_id[state['active_id']] | {'validatedForLive': False},
                'paperAccount': summary(state['active']), 'candidates': entries, 'report': state['report'],
                'pendingSwitch': state['pending_switch'], 'audit': events,
                'limitations': ['운영 계좌 연결 없음', '등록 이후 완료 일봉 가상 매매', '기존 자료 소급 성과는 전진 관찰이 아님',
                                '10%는 차단 기준이며 갭 손실 상한 보장이 아님', '고정 후보 6개 연구; 임의 AI 코드 생성 없음']}

    def status(self):
        with self._db(write=False) as db:
            return self._status(db, self._read(db))

    def run_cycle(self, dataset_dir):
        now = self.clock()
        incoming = load_snapshot(dataset_dir, now)
        with self._db() as db:
            state = self._read(db)
            merged = append_market(state['market'], incoming)
            latest = incoming['dates'][-1]
            if state['last_market_date'] == latest:
                return self._status(db, state)
            if state['registered_at'] is None:
                state['registered_at'], state['registered_date'] = now.isoformat(), now.date().isoformat()
                self._event(db, 'REGISTERED', '가상 계좌와 후보 6개 등록. 기존 일봉을 신규 관찰로 계산하지 않습니다.')
            signals = engine.indicators(merged['data'])
            for i, day in enumerate(incoming['dates']):
                if day <= state['registered_date'] or state['last_market_date'] and day <= state['last_market_date']:
                    continue
                old_id = state['active_id']
                state['active'] = advance(state['active'], merged['data'], signals, i)
                state['active_id'] = state['active']['strategy_id']
                for item in self.catalog:
                    ident = item['id']
                    state['shadows'][ident] = advance(state['shadows'][ident], merged['data'], signals, i)
                if state['pending_switch']:
                    self._event(db, 'SWITCH_APPLIED' if old_id != state['active_id'] else 'SWITCH_CANCELLED',
                                '다음 완료 종가부터 승인 전략 적용' if old_id != state['active_id'] else '계좌 손실 차단으로 교체 취소')
                    state['pending_switch'] = None
            state['market'], state['last_market_date'] = merged, latest
            state['historical'] = self._historical(merged)
            state['revision'] += 1
            state['rejected'] = []
            evidence = {'revision': state['revision'], 'data': digest(merged), 'code': self.code_hash,
                        'historical': state['historical'], 'forward': {k: summary(v) for k,v in state['shadows'].items()},
                        'active': summary(state['active'])}
            state['report'] = {'id': digest(evidence), 'createdAt': now.isoformat(), 'dataThrough': latest,
                               'targetAnnualPct': 10, 'sourceHash': incoming['snapshot_hash'],
                               'versionHash': self.code_hash, 'evidenceHash': digest(evidence)}
            db.execute('INSERT INTO reports(id,payload) VALUES(?,?)',
                       (state['report']['id'], json.dumps(evidence, ensure_ascii=False, allow_nan=False)))
            self._event(db, 'CYCLE_COMPLETED', f'자료 {latest}까지 연구·가상 매매·비교 완료. 자동 교체 없음.')
            self._save(db, state)
            return self._status(db, state)

    def _review(self, db, state, candidate_id, report_id, expected_revision):
        if candidate_id not in self.by_id:
            raise LabError('등록되지 않은 후보입니다.')
        if type(expected_revision) is not int or expected_revision != state['revision']:
            raise LabError('상태가 변경되었습니다. 최신 보고서를 다시 확인하세요.')
        if not state['report'] or state['report']['id'] != report_id:
            raise LabError('현재 보고서와 다른 승인 요청입니다.')
        payload = db.execute('SELECT payload FROM reports WHERE id=?', (report_id,)).fetchone()
        if not payload or digest(json.loads(payload[0])) != report_id:
            raise LabError('보고서 무결성 검증에 실패했습니다.')
        evidence = json.loads(payload[0])
        current = {'data': digest(state['market']), 'code': self.code_hash, 'historical': state['historical'],
                   'forward': {k: summary(v) for k,v in state['shadows'].items()}, 'active': summary(state['active'])}
        if any(evidence.get(k) != value for k,value in current.items()):
            raise LabError('보고서와 현재 계좌·후보 증거가 일치하지 않습니다.')

    def approve(self, candidate_id, report_id, expected_revision):
        with self._db() as db:
            state = self._read(db)
            self._review(db, state, candidate_id, report_id, expected_revision)
            ok, reasons = self._eligibility(state, self.by_id[candidate_id])
            if candidate_id in state['rejected']:
                reasons.append('현재 보고서에서 보류한 후보입니다.')
                ok = False
            if not ok:
                raise LabError('교체 조건 미충족: ' + ' '.join(reasons))
            state['active']['switch_to'] = candidate_id
            state['pending_switch'] = {'candidateId': candidate_id, 'name': self.by_id[candidate_id]['name'],
                                       'approvedAt': self.clock().isoformat(), 'effective': '다음 완료 일봉 판단부터 적용'}
            state['revision'] += 1
            self._event(db, 'SWITCH_APPROVED', f'{candidate_id} 가상 전략 교체 승인. 현금·보유·고점·차단 유지.')
            self._save(db, state)
            return self._status(db, state)

    def reject(self, candidate_id, report_id, expected_revision):
        with self._db() as db:
            state = self._read(db)
            self._review(db, state, candidate_id, report_id, expected_revision)
            if candidate_id not in state['rejected']:
                state['rejected'].append(candidate_id)
            state['revision'] += 1
            self._event(db, 'CANDIDATE_REJECTED', candidate_id + ' 현재 보고서에서 보류')
            self._save(db, state)
            return self._status(db, state)
