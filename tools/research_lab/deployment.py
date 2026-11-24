"""Signed, data-only handoff. This module has no account, broker or network access."""
import hashlib
import hmac
import json
import os
from datetime import timedelta
from pathlib import Path

import team24_research as engine
from .core import LabError
from .replay import summary

SCHEMA = 'us-research-deployment-v1'
ENGINE = 'team24-daily-v1'
RESEARCH_HASH = '607b0cec83aaf45c14a57b607cfb1c1b4a54da21503565c9900433ac755e084b'


def signing_key(runtime):
    path = Path(runtime) / 'deployment-signing-key'
    if not path.is_file():
        raise LabError('실계좌 검토용 서명 키가 설정되지 않았습니다.')
    if path.stat().st_size > 4096:
        raise LabError('서명 키 파일 크기가 올바르지 않습니다.')
    key = path.read_text(encoding='utf-8').strip().encode('utf-8')
    if len(key) < 32:
        raise LabError('서명 키는 32바이트 이상이어야 합니다.')
    return key


def envelope(payload, key):
    if not isinstance(key, bytes) or len(key) < 32:
        raise LabError('서명 키가 올바르지 않습니다.')
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
    return {'payloadJson': raw, 'signature': hmac.new(key, raw.encode('utf-8'), hashlib.sha256).hexdigest()}


def base_payload(lab, state, kind):
    if lab.code_hash != RESEARCH_HASH:
        raise LabError('실계좌 실행기와 검증한 연구 코드 버전이 다릅니다.')
    if not state['report']:
        raise LabError('완료된 연구 보고서가 없습니다.')
    now = lab.clock()
    return dict(schema=SCHEMA, kind=kind, engineVersion=ENGINE,
                researchCodeHash=lab.code_hash, reportId=state['report']['id'],
                issuedAt=now.isoformat(), expiresAt=(now+timedelta(hours=24)).isoformat(),
                dataThrough=state['last_market_date'], registeredAt=state['registered_at'],
                risk={'cashOnly': True, 'allHoldings': True, 'drawdownLimitPct': 10})


def export_candidate(lab, candidate_id, report_id, revision, key):
    with lab._db(write=False) as db:
        state = lab._read(db)
        lab._review(db, state, candidate_id, report_id, revision)
        candidate = next(c for c in lab._status(db, state)['candidates'] if c['id'] == candidate_id)
        if not candidate['eligible']:
            raise LabError('실계좌 검토용 내보내기 조건 미충족: ' + ' '.join(candidate['reasons']))
        payload = base_payload(lab, state, 'CANDIDATE')
        payload.update(candidateId=candidate_id, name=candidate['name'], version=candidate['version'],
                       historical=candidate['historical'], forward=candidate['forward'],
                       baseline=summary(state['active']))
        return envelope(payload, key)


def publish_market(lab, runtime):
    key = signing_key(runtime)
    with lab._db(write=False) as db:
        state = lab._read(db)
        lab._review(db, state, state['active_id'], state['report']['id'] if state['report'] else '', state['revision'])
        payload = base_payload(lab, state, 'MARKET')
        signals = engine.indicators(state['market']['data'])
        payload['signals'] = {s: {k: signals[s][-1][k] for k in ('eligible', 'momentum', 'vol20', 'vol63', 'riskrank')} for s in engine.SYMBOLS}
        signed = envelope(payload, key)
    target = Path(runtime) / 'live-market.json'
    temporary = target.with_suffix('.tmp')
    temporary.write_text(json.dumps(signed, ensure_ascii=False), encoding='utf-8')
    os.replace(temporary, target)
    return target


def invalidate_market(runtime):
    """A failed/aborted collector must not leave yesterday's signed file executable."""
    target = Path(runtime) / 'live-market.json'
    if target.exists():
        temporary = target.with_suffix('.tmp')
        temporary.write_text('{"blocked":true}', encoding='utf-8')
        os.replace(temporary, target)
