"""Confirmed net10% target; report every fixed candidate without optimization."""
import argparse
import json
from pathlib import Path
from team23_report import metrics as base_metrics

CANDIDATES = ('rotation1', 'riskrank2', 'balanced_trend')
POLICIES = ('cushion80', 'recovery_vol')
TARGET_ANNUAL_PCT = 10.0


def metrics(result):
    """Full-calendar metrics plus observed annual returns, never annualized YTD."""
    out = base_metrics(result)
    year_ends = {}
    for h in result['history']:
        year_ends[h['date'][:4]] = (h['date'], h['equity'])
    annual = []
    prior = result['initial']
    for i, (year, (end_date, equity)) in enumerate(year_ends.items()):
        annual.append({'year': year, 'end_date': end_date,
                       'return_pct': (equity / prior - 1) * 100,
                       'boundary_year_may_be_partial': i in (0, len(year_ends) - 1)})
        prior = equity
    out.update(annual=annual,
               annual_positive_fraction=sum(y['return_pct'] > 1e-10 for y in annual) / len(annual),
               worst_year_pct=min(y['return_pct'] for y in annual))
    return out


def report(folder):
    folder = Path(folder)
    rows = json.loads((folder / 'runs/summary.json').read_text(encoding='utf-8'))
    audit = json.loads((folder / 'runs/audit.json').read_text(encoding='utf-8'))
    if audit['status'] != 'PASS' or len(rows) != 60:
        raise ValueError('Need independently audited complete60 matrix')
    enriched = []
    for r in rows:
        filename = f"{r['period']}__{r['candidate']}__{r['risk_policy']}__{r['cost_bps']}bps.json"
        detail = json.loads((folder / 'runs' / filename).read_text(encoding='utf-8'))
        enriched.append(r | metrics(detail))
    decisions = []
    for policy in POLICIES:
        for candidate in CANDIDATES:
            subset = [r for r in enriched if r['risk_policy'] == policy and r['candidate'] == candidate]
            recent = [r for r in subset if r['period'] in ('2024_2025', '2026_ytd')]
            continuous = [r for r in subset if r['period'] == 'continuous']
            performance = len(recent) == 4 and all(r['annualized_pct'] >= TARGET_ANNUAL_PCT for r in recent)
            long_term = len(continuous) == 2 and all(r['annualized_pct'] >= TARGET_ANNUAL_PCT for r in continuous)
            risk = len(subset) == 10 and all(r['mdd_pct'] <= 10 + 1e-8 and not r['hard_latch'] and not r['pending_liquidation'] for r in subset)
            decisions.append({'candidate': candidate, 'risk_policy': policy,
                              'recent_both_costs_target_pass': performance,
                              'continuous_both_costs_target_pass': long_term,
                              'all_periods_risk_pass': risk,
                              'qualified': performance and long_term and risk})
    (folder / 'metrics.json').write_text(json.dumps(enriched, indent=2), encoding='utf-8')
    (folder / 'decisions.json').write_text(json.dumps({'target_annual_pct': TARGET_ANNUAL_PCT,
        'target_status': 'user_confirmed_cost_net_goal_not_guarantee', 'decisions': decisions}, indent=2), encoding='utf-8')
    lines = ['# TEAM-24 거래 비용 차감 연10% 목표 검증', '',
             '사용자가 확정한 목표는 거래 비용 차감 후 연10% 정도다. 매년10% 또는 미래 수익을 보장하는 뜻은 아니다. S&P 500 초과는 판정에 사용하지 않는다.', '',
             '운영 미적용. 별도 일봉 가상 원장 연구이며 기존 운영 자동매매나 사용자 실계좌의 실적이 아니다.', '',
             '## 전략과 실행', '',
             '- `rotation1`: SPY/QQQ/GLD/IEF 중 SMA200 상회·126일 수익 양수인 후보의 수익 상위1개를 월별 선정한다.',
             '- `riskrank2`: 같은 자격에서126일 수익을63일 변동성으로 나눈 순위의 상위2개를 월별 선정한다. 미충족 슬롯은 현금이다.',
             '- `balanced_trend`: SPY40%·QQQ20%·GLD20%·IEF20% 기본 비율 중 SMA200을 상회한 자산에만 투자한다.',
             '- 위험 예산은 기존 `cushion80`과 변동성·낙폭에 따라20~80% 목표를 산출하는 `recovery_vol`을 비교한다. 계산상 하한은 실제 보유 하한이 아니다.',
             '- 월별 FULL에서 매수하고, 결제 대기로 미완료된 월별 주문은 다음 시가의 일회성 FUND로만 보완한다. FUND는 원래 미매수 수량·현재 목표·현금 한도 내 매수만 하며 재시도나 기준 예산 갱신이 없다. 월중에는 수량을 유지하고, 자격 상실은 EXIT, 위험 목표가 실행 기준보다5%p 이상 작아지면 REDUCE로 매도만 한다. 가격 변동에 따른 잦은 소액 재조정을 줄인다.',
             '- 매수는 비용 포함 결제 현금99% 한도·정수 주식이다.10% 고점 대비 하락 관측 시 영구 매수 차단·청산하고 재개·고점 초기화는 하지 않는다.', '',
             '## 사전 기준 판정', '',
             '최근2024–2025와2026 각각 두 비용 모두 CAGR10% 이상, 연속 구간도 두 비용 모두 CAGR10% 이상, 전체 기간·비용에서 관측 낙폭10% 이하·차단/종료 미청산 없음이 연구 판정 기준이다.', '',
             '| 전략 | 위험 정책 | 최근 수익 목표 | 연속 수익 목표 | 전체 위험 기준 | 종합 |',
             '|---|---|---|---|---|---|']
    for d in decisions:
        lines.append(f"| {d['candidate']} | {d['risk_policy']} | {d['recent_both_costs_target_pass']} | {d['continuous_both_costs_target_pass']} | {d['all_periods_risk_pass']} | {d['qualified']} |")
    lines += ['', '## 전체60회 성과·위험', '',
              '수익률·낙폭은%. 누적 수익과 CAGR을 구분한다.2026은1년 미만 구간의 연환산 추정이다. 비용은 편도 총 bps다.', '',
              '| 기간 | 전략 | 위험 정책 | 비용 | 누적 | 연환산 | 최대낙폭 | 지수 누적(참고) | 차단일 |',
              '|---|---|---|---:|---:|---:|---:|---:|---|']
    for r in enriched:
        latch = r['hard_latch']['date'] if r['hard_latch'] else '없음'
        lines.append(f"| {r['period']} | {r['candidate']} | {r['risk_policy']} | {r['cost_bps']} | {r['return_pct']:.3f} | {r['annualized_pct']:.3f} | {r['mdd_pct']:.3f} | {r['benchmark']['return_pct']:.3f} | {latch} |")
    lines += ['', '## 전체60회 거래 비용·회복', '',
              '수수료·슬리피지 합계는USD다. 양의 월/연도 비율에는 현금 대기0%와 부분 경계 기간도 포함한다. 고점 미회복 기간은 진행 중 구간도 포함한다.', '',
              '| 기간 | 전략 | 위험 정책 | 비용 | 양의 월 | 양의 연도 | 최악의 연도 | 최악의 월 | 평균 비중 | 최대 실제 비중 | 최대 목표 예산 | 보유일/평가일 | 체결수 | 총 비용USD | 미회복 최장일 |',
              '|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|']
    for r in enriched:
        lines.append(f"| {r['period']} | {r['candidate']} | {r['risk_policy']} | {r['cost_bps']} | {100*r['monthly_positive_fraction']:.1f}% | {100*r['annual_positive_fraction']:.1f}% | {r['worst_year_pct']:.3f} | {r['worst_month_pct']:.3f} | {100*r['mean_exposure']:.2f}% | {100*r['max_exposure']:.2f}% | {100*r['max_target_gross']:.2f}% | {r['held_days']}/{r['evaluation_trading_days']} | {r['trade_count']} | {r['fees']+r['slippage']:.2f} | {r['underwater_max_calendar_days']} |")
    lines += ['', '## 연속 구간의 실제 연도별 수익', '',
              '연도별 값은 연환산하지 않은 실제 관측 누적 수익이다.2026은11월17일까지다. 첫·마지막 연도는 부분 기간 가능성을 표시하며 상세 연도·월별 값은 metrics.json에 모두 보존한다.', '',
              '| 전략 | 위험 정책 | 비용 | 연도 | 수익률% | 경계 연도 |',
              '|---|---|---:|---|---:|---|']
    for r in enriched:
        if r['period'] == 'continuous':
            for y in r['annual']:
                lines.append(f"| {r['candidate']} | {r['risk_policy']} | {r['cost_bps']} | {y['year']} | {y['return_pct']:.3f} | {y['boundary_year_may_be_partial']} |")
    lines += ['', '## 검증과 제한', '',
              '- 공통 위험 예산은 SPY20일 변동성을 사용한다. 금·국채를 포함한 실제 포트폴리오의 변동성이나 최대 손실을 직접 예측하는 모형은 아니다.',
              f"독립 감사 {audit['status']}: 일별 상태 {audit['daily_states']:,}개, 거래 {audit['trades']:,}건.", '',
              '- 기존 공개 원문을 별도 정규화한 자료2006-01-03~2026-11-17로2008년부터 평가한다. 새로 확보한 미공개 홀드아웃이 아니며 과거 ETF 연구와 자료를 반복 사용했다. 유리한 결과를 얻기 위해 실행 후 모수를 조정하지 않았다.',
              '- 분리 기간별$10,000 현금 시작, 연속 기간 고점/차단 미초기화. 사용자 기존 보유 계좌·세금·환율·현금 이자·추가입금은 제외한다.',
              '- 매도 대금은 다음 거래일 사용하며 과거 전체에T+1 모형을 적용했다. 배당은 채권 평가하지만 지급일 자료가 없어 매수 현금으로 사용하지 않는다. 지수는 비용 차감 전·배당 재투자라 처리 차이가 있다.',
              '- 완료 종가 판단→다음 시가 체결이며 시가 차단은 해당 시가, 종가 차단과 체결 비용 후 신규 차단 잔여분은 다음 시가 청산이다. 갭·비용·미관측 장중 저점 때문에 최대 손실10%를 보장하지 못한다.',
              '- 시가 체결은 호가·유동성·부분 체결·지연을 재현하지 않는다. 종료 보유분은 종가 평가이며 강제 청산 비용을 추가 차감하지 않는다. 월중 HOLD에서는 실제 비중이 가격에 따라 목표보다 높아질 수도 있다.',
              '- 비용 감소는 실제 체결 수와 비용으로 확인한다. 이전 연구와 자산·기간·전략도 달라져 전체 차이를 거래 빈도 하나의 효과라고 단정하지 않는다.',
              '- 원자료와 지표는 PLAN.md/pre-run-lock.json/completion.json/runs/audit.json/metrics.json/decisions.json 및 개별 원장에 보존한다. 운영 채택은 별도 검토다.', '',
              '상품 확인: [QQQ 발행사](https://www.invesco.com/us/en/financial-products/etfs/invesco-qqq-trust-series-1.html), [GLD 발행사](https://www.ssga.com/us/en/individual/etfs/spdr-gold-shares-gld), [IEF 발행사](https://www.ishares.com/us/products/239456/ishares-7-10-year-treasury-bond-etf). 금·국채 ETF를 연구에 포함한 것이 운영 매매 설정 변경은 아니다.', '']
    (folder / 'REPORT.md').write_text('\n'.join(lines), encoding='utf-8')
    return decisions


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    args = parser.parse_args()
    print(json.dumps(report(args.folder)))


if __name__ == '__main__':
    main()
