"""Regenerate Java decision golden vectors from unchanged TEAM24 public-data rules."""
import hashlib
import json
from pathlib import Path
import team24_research as engine

root = Path(__file__).resolve().parents[1]
data, _ = engine.load_data(root / 'tools/team24_data')
signals = engine.indicators(data)
vectors = []
for index in (500, 2500, len(data['SPY'])-1):
    snapshot = {s: signals[s][index] for s in engine.SYMBOLS}
    for candidate in engine.CANDIDATES:
        for policy in engine.RISK_POLICIES:
            for equity, hard in ((10000, False), (9500, False), (9200, False), (9000, True)):
                for monthly in (True, False):
                    before = ['SPY', 'QQQ', 'GLD', 'IEF']
                    basket = engine.select_basket(candidate, before, snapshot, monthly)
                    gross = engine.risk_target(equity, 10000, hard, policy, snapshot['SPY']['vol20'])
                    vectors.append(dict(candidateId=f'{candidate}:{policy}', snapshot=snapshot,
                        previousBasket=before, monthly=monthly, equity=equity, peak=10000, hard=hard,
                        anchor=.8, expected=dict(basket=basket, gross=gross,
                        weights=engine.weights_for(candidate, basket, gross),
                        mode=engine.order_mode(monthly, gross, .8, before, basket, hard))))
out = root / 'src/test/resources/strategybridge/team24-decision-vectors.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(dict(sourceSha256=hashlib.sha256((root/'tools/team24_research.py').read_bytes()).hexdigest(),
                              vectors=vectors), separators=(',', ':')), encoding='utf-8')
print(f'{len(vectors)} deterministic TEAM24 comparison vectors')
