"""Generate explicitly synthetic cross-language fixtures, never live approval material."""
import json
from pathlib import Path
from research_lab.data import digest
from research_lab.deployment import SCHEMA, ENGINE, RESEARCH_HASH, envelope

KEY = b'test-only-research-evidence-key-32bytes'


def generate(folder):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    common = dict(schema=SCHEMA, engineVersion=ENGINE, researchCodeHash=RESEARCH_HASH,
        issuedAt='2026-11-23T01:59:00+00:00', expiresAt='2026-11-23T02:59:00+00:00',
        dataThrough='2026-11-20', reportId='a'*64, registeredAt='2026-06-01T00:00:00+00:00',
        risk={'cashOnly': True, 'allHoldings': True, 'drawdownLimitPct': 10})
    candidate = common | dict(kind='CANDIDATE', candidateId='rotation1:recovery_vol',
        name='합성 검사 전용 · 실계좌 사용 금지',
        version=digest(dict(candidate='rotation1', code=RESEARCH_HASH, costBps=25, policy='recovery_vol')),
        historical=dict(passed=True, auditPassed=True, annualizedPct=11,
            periods=[dict(period=period,costBps=cost,annualizedPct=11,maxDrawdownPct=5,
                     hardLatched=False,pendingLiquidation=False) for period in ('recent','latest','continuous') for cost in (10,25)]),
        forward=dict(days=60,fillCount=20,returnPct=5,maxDrawdownPct=4,hardLatched=False),
        baseline=dict(returnPct=3,maxDrawdownPct=3,hardLatched=False))
    market = common | dict(kind='MARKET', signals={s:dict(eligible=True,momentum=.1,vol20=.2,vol63=.3,riskrank=.4) for s in ('SPY','QQQ','GLD','IEF')})
    for name, payload in [('candidate',candidate),('market',market)]:
        (folder / f'python-signed-{name}.json').write_text(json.dumps(envelope(payload,KEY),ensure_ascii=False,indent=2),encoding='utf-8')


if __name__ == '__main__':
    generate(Path(__file__).resolve().parents[2] / 'src/test/resources/strategybridge')
