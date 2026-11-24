"""Report all predeclared runs and paired changes; never optimize parameters."""
import json
import sys
from pathlib import Path

folder = Path(sys.argv[1])
rows = json.loads((folder / 'runs/summary.json').read_text())
audit = json.loads((folder / 'runs/audit.json').read_text())
base = {(r['candidate'], r['period'], r['cost_bps']): r for r in rows if r['risk_policy'] == 'baseline20'}
decisions = []
for policy in ('cushion80', 'band80'):
    for candidate in ('spy_trend', 'sector_top1', 'sector_top3'):
        subset = [r for r in rows if r['risk_policy'] == policy and r['candidate'] == candidate]
        recent = [r for r in subset if r['period'] in ('2024_2025', '2026_ytd')]
        decisions.append({'risk_policy': policy, 'candidate': candidate,
            'recent_both_costs_outperform': len(recent) == 4 and all(r['excess_return_pp'] > 0 for r in recent),
            'all_periods_observed_risk_pass': len(subset) == 10 and all(r['mdd_pct'] <= 10 + 1e-8 and not r['pending_liquidation'] for r in subset)})
for d in decisions:
    d['qualified'] = d['recent_both_costs_outperform'] and d['all_periods_observed_risk_pass']
(folder / 'decisions.json').write_text(json.dumps(decisions, indent=2))
lines = ['# TEAM-22 투자 비중 제한 개선 비교', '',
         '운영 미적용. 기존 장중 서비스의 실적이 아닌 오프라인 일봉 연구다. 종목 선택·자료·비용·차단은 고정하고 위험 예산만 바꿨다.', '',
         '- baseline20: 기존 위험 여유의2배, 고점에서 최대20%.',
         '- cushion80: 위험 여유의8배, 최대80%. 하락에 따라 연속 축소한다.',
         '- band80: 고점 대비 하락5% 미만80%, 5~8%40%, 8~10%20%. 회복하면 해당 구간 비중으로 복귀한다.',
         '- 모두10% 하락 관측 시 영구 매수 잠금·청산한다. 차단 이후 재개·고점 초기화 없음. 매수는 결제 현금99%와 수수료 한도, 정수 주식이며 차입하지 않는다.', '',
         '## 사전 기준 판정', '',
         '2024–2025 및2026 두 구간·두 비용 모두 지수초과 + 전체5기간·두 비용에서 관측 낙폭10% 이내·미청산 없음. 이 기준은 미래 손실 보장이 아니다.', '',
         '| 위험 예산 | 종목 선택 | 최근 두 구간 지수초과 | 전체 위험 기준 | 통과 |',
         '|---|---|---|---|---|']
for d in decisions:
    lines.append(f"| {d['risk_policy']} | {d['candidate']} | {d['recent_both_costs_outperform']} | {d['all_periods_observed_risk_pass']} | {d['qualified']} |")
lines += ['', '## 전체90회 결과', '',
          '수익률·낙폭은%, 기존대비·지수대비는%p. 모든 수익률은 해당 구간 누적이며 연환산이 아니다. 비용은 편도 수수료+슬리피지 bps이다.', '',
          '| 구간 | 선택 | 위험 예산 | 비용 | 수익률 | 기존대비 | 지수 | 지수대비 | 최대낙폭 | 평균투자비중 | 최대실제비중 | 최대목표비중 | 거래수 | 차단일 |',
          '|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|']
for r in rows:
    b = base[(r['candidate'], r['period'], r['cost_bps'])]
    latch = r['hard_latch']['date'] if r['hard_latch'] else '없음'
    detail = json.loads((folder / 'runs' / f"{r['period']}__{r['candidate']}__{r['risk_policy']}__{r['cost_bps']}bps.json").read_text())
    max_exposure = max(h['exposure'] for h in detail['history'])
    lines.append(f"| {r['period']} | {r['candidate']} | {r['risk_policy']} | {r['cost_bps']} | {r['return_pct']:.3f} | {r['return_pct']-b['return_pct']:+.3f} | {r['benchmark']['return_pct']:.3f} | {r['excess_return_pp']:+.3f} | {r['mdd_pct']:.3f} | {100*r['mean_exposure']:.2f}% | {100*max_exposure:.2f}% | {100*r['max_target_gross']:.2f}% | {r['trade_count']} | {latch} |")
lines += ['', '## 검증과 제한', '',
          f"독립 감사 {audit['status']}: 일별상태 {audit['daily_states']:,}, 거래 {audit['trades']:,}.", '',
          '- 같은 실제 자료2006-01-03~2026-11-18, 평가시작2008년. 고정8개 업종ETF·SPY·S&P500총수익지수. XLF는분사처리자료부족으로제외. 신호는조정종가, 체결은분할전명목가격복원, 배당중복계산없음.',
          '- 완료종가 판단→다음시가체결, 월첫완료종가에선정·매일자격퇴출/비중조정. 지수첫평가종가~마지막종가와비교한다.',
          '- 각분리기간$10,000현금시작. 연속기간에서고점/차단재설정없음. 기존실계좌보유종목·환율·세금·추가입금·현금이자 제외.',
          '- 매도대금은다음거래일사용(T+1을과거전기간적용, 역사적T+2/T+3재현아님). 지급일없는배당은자산에포함하되매수자금으로미사용. 지수는배당재투자/비용차감전이라차이가있다.',
          '- 체결 비용 반영 후 새로 차단되면 추가 매수는 중단하고 잔여 보유분은 다음 시가에 청산한다. 기존 원장의 보수적 체결 가정이다.',
          '- 일봉시가·종가와비용반영평가만관측하므로일중낙폭은미확인. 차단시가청산/종가차단다음시가청산으로갭손실은10%초과가능. 종가최종평가에는강제청산비용을빼지않는다.',
          '- 이미본역사자료에대한사전고정비교이며신규독립홀드아웃이아니다. 새로운모수탐색으로결과를좋게맞추지않았다.',
          '- PLAN.md/ pre-run-lock.json은실행전설계·코드·자료해시, completion.json은결과해시, runs/*bps.json은전체원장, audit.json은독립검사다.', '',
          '[SEC 투자자 안내](https://www.investor.gov/introduction-investing/investing-basics/glossary/stop-order)에서도 차단 기준 가격과 실제 체결 가격이 다를 수 있음을 설명한다. 여기의10%는계좌차단기준이지손실보장상품이아니다.', '']
(folder / 'REPORT.md').write_text('\n'.join(lines), encoding='utf-8')
print(json.dumps(decisions))
