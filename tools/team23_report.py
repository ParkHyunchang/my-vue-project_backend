"""Full-period absolute-return metrics and all60 report; no strategy optimization."""
import argparse
import json
import math
from datetime import date
from pathlib import Path

CANDIDATES = ('spy_trend', 'sector_breakout', 'spy_pullback')
POLICIES = ('cushion80', 'recovery_vol')


def metrics(result):
    """Use every evaluation day, including cash/hard-stop days and boundary months."""
    history = result['history']
    if not history:
        raise ValueError('Empty history')
    days = (date.fromisoformat(history[-1]['date']) - date.fromisoformat(history[0]['date'])).days
    initial, final = result['initial'], history[-1]['equity']
    if days <= 0 or initial <= 0 or final <= 0:
        raise ValueError('CAGR needs positive equity and at least two calendar dates')
    cagr = ((final / initial) ** (365.2425 / days) - 1) * 100
    month_ends = {}
    for h in history:
        if not math.isfinite(h['equity']) or h['equity'] <= 0:
            raise ValueError('Invalid equity')
        month_ends[h['date'][:7]] = (h['date'], h['equity'])
    monthly = []
    prior = initial
    months = list(month_ends)
    for i, month in enumerate(months):
        end_date, equity = month_ends[month]
        monthly.append({'month': month, 'end_date': end_date,
                        'return_pct': (equity / prior - 1) * 100,
                        'boundary_month_may_be_partial': i in (0, len(months) - 1)})
        prior = equity
    underway = None
    longest = 0
    previous_date = date.fromisoformat(history[0]['date'])
    for h in history:
        day = date.fromisoformat(h['date'])
        # Stored peak includes intraday open observations; evaluate recovery at close.
        below = h['equity'] < h['peak'] - 1e-7
        if below and underway is None:
            underway = previous_date
        if underway is not None:
            longest = max(longest, (day - underway).days)
        if not below:
            underway = None
        previous_date = day
    return {'calendar_days': days, 'annualized_pct': cagr,
            'annualization_less_than_one_year': days < 365,
            'monthly': monthly, 'monthly_positive_fraction': sum(m['return_pct'] > 1e-10 for m in monthly) / len(monthly),
            'worst_month_pct': min(m['return_pct'] for m in monthly),
            'underwater_max_calendar_days': longest, 'underwater_ongoing': underway is not None,
            'held_days': sum(any(q > 0 for q in h['qty'].values()) for h in history),
            'evaluation_trading_days': len(history), 'mean_exposure': sum(h['exposure'] for h in history) / len(history),
            'max_exposure': max(h['exposure'] for h in history),
            'max_target_gross': max(h['next_target']['gross'] for h in history),
            'cash_only_days': sum(not any(q > 0 for q in h['qty'].values()) for h in history)}


def report(folder, reference_pct=5.0):
    folder = Path(folder)
    rows = json.loads((folder / 'runs/summary.json').read_text(encoding='utf-8'))
    audit = json.loads((folder / 'runs/audit.json').read_text(encoding='utf-8'))
    if audit['status'] != 'PASS' or len(rows) != 60 or not math.isfinite(reference_pct):
        raise ValueError('Need audited full60 matrix and finite reference')
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
            performance = len(recent) == 4 and all(r['annualized_pct'] >= reference_pct for r in recent)
            risk = len(subset) == 10 and all(r['mdd_pct'] <= 10 + 1e-8 and not r['hard_latch'] and not r['pending_liquidation'] for r in subset)
            positive = len(continuous) == 2 and all(r['annualized_pct'] > 0 for r in continuous)
            decisions.append({'candidate': candidate, 'risk_policy': policy,
                              'recent_both_costs_reference_pass': performance,
                              'all_periods_risk_pass': risk, 'continuous_positive': positive,
                              'qualified_under_provisional_reference': performance and risk and positive})
    (folder / 'metrics.json').write_text(json.dumps(enriched, indent=2), encoding='utf-8')
    (folder / 'decisions.json').write_text(json.dumps({'reference_annual_pct': reference_pct,
        'reference_status': 'provisional_not_user_agreed', 'decisions': decisions}, indent=2), encoding='utf-8')
    lines = ['# TEAM-23 절대 수익·회복력·단기 매매 비교', '',
             f'잠정 연환산 참고선: 비용 차감 후 {reference_pct:g}%. 사용자가 확정한 요구나 예상 수익이 아니다. S&P 500 초과는 필수 조건에서 제외했다.', '',
             '운영 미적용. 기존 장중 운영 전략의 실적이 아닌 공개 일봉을 이용한 별도 가상 매매다.', '',
             '## 정책과 전략', '',
             '- `cushion80`: 기존 손실 여유에 비례해 비중을 줄이는 대조 정책.',
             '- `recovery_vol`: SPY 최근20일 변동성으로 목표를20~80%로 조절하고 낙폭5%/8%에서 각각40%/20%로 제한한다. 하한은 차단 전 목표 예산에만 적용된다. 진입 신호·빈 슬롯·정수 수량 때문에 실제 비중은0일 수도 있다.',
             '- 10% 하락을 관측하면 영구 매수 차단·청산한다. 고점 초기화와 차단 후 자동 재개는 없다.',
             '- `spy_trend`: 기존 월별 SPY SMA200 추세 대조군.',
             '- `sector_breakout`: 업종 ETF의20일 종가 돌파, 최대3개 분산,10일 저점 이탈 또는10개 보유 종가 후 퇴출.',
             '- `spy_pullback`: 상승 추세 SPY의 단순 RSI2 과매도 진입,5일 평균 회복 또는5개 보유 종가 등으로 퇴출.', '',
             '## 잠정 기준 판정', '',
             '최근 두 구간·두 비용 모두 참고선 충족, 전체 다섯 구간·두 비용에서 낙폭10% 이내·차단/미청산 없음, 연속 구간 두 비용 모두 양의 CAGR이 필요하다.', '',
             '| 전략 | 위험 정책 | 최근 수익 기준 | 전체 위험 기준 | 연속 구간 양수 | 종합 |',
             '|---|---|---|---|---|---|']
    for d in decisions:
        lines.append(f"| {d['candidate']} | {d['risk_policy']} | {d['recent_both_costs_reference_pass']} | {d['all_periods_risk_pass']} | {d['continuous_positive']} | {d['qualified_under_provisional_reference']} |")
    lines += ['', '## 전체60회 성과·위험', '',
              '수익률과 낙폭은%. 연환산은 평가 구간 전체 달력일을 사용한다.2026년은1년 미만의 연환산 추정이며 누적 수익과 구분한다. 거래 비용은 편도 총 bps다.', '',
              '| 기간 | 전략 | 위험 정책 | 비용 | 누적 | 연환산 | 최대낙폭 | 지수 누적(참고) | 차단일 |',
              '|---|---|---|---:|---:|---:|---:|---:|---|']
    for r in enriched:
        latch = r['hard_latch']['date'] if r['hard_latch'] else '없음'
        lines.append(f"| {r['period']} | {r['candidate']} | {r['risk_policy']} | {r['cost_bps']} | {r['return_pct']:.3f} | {r['annualized_pct']:.3f} | {r['mdd_pct']:.3f} | {r['benchmark']['return_pct']:.3f} | {latch} |")
    lines += ['', '## 전체60회 운용·회복', '',
              '양의 월 비율에는 수익0인 현금 대기 월도 포함한다. 첫·마지막 월은 경계 월로 표시해 부분 월 가능성을 남겼으며 제외하지 않았다. 월별 상세는 metrics.json에 있다. 고점 미회복 일수는 원장 고점 대비 종가 평가로 계산하고 진행 중 구간도 포함한다.', '',
              '| 기간 | 전략 | 위험 정책 | 비용 | 양의 월 | 최악의 월 | 평균 비중 | 보유일/평가일 | 체결건수 | 최장 미회복(달력일) | 미회복 진행 중 |',
              '|---|---|---|---:|---:|---:|---:|---|---:|---:|---|']
    for r in enriched:
        lines.append(f"| {r['period']} | {r['candidate']} | {r['risk_policy']} | {r['cost_bps']} | {100*r['monthly_positive_fraction']:.1f}% | {r['worst_month_pct']:.3f} | {100*r['mean_exposure']:.2f}% | {r['held_days']}/{r['evaluation_trading_days']} | {r['trade_count']} | {r['underwater_max_calendar_days']} | {r['underwater_ongoing']} |")
    lines += ['', '## 검증과 제한', '',
              f"독립 감사 {audit['status']}: 일별 상태 {audit['daily_states']:,}개, 거래 {audit['trades']:,}건.", '',
              '- 같은 공개 스냅샷2006-01-03~2026-11-18을 사용하며2008년부터 평가한다. 반복 검토한 역사 구간이라 신규 독립 홀드아웃이 아니다. 결과를 보고 전략 모수를 재조정하지 않았다.',
              '- 매수는 비용 포함 결제 현금99% 한도·정수 주식, 매도 대금은 다음 거래일 사용이다. 모든 역사 구간에T+1을 적용했으며 과거T+2/T+3의 재현은 아니다.',
              '- 각 분리 구간은$10,000 현금 시작이다. 연속 구간은 고점과 차단을 초기화하지 않는다. 기존 실계좌 보유·추가 입금·세금·환율·현금 이자는 제외했다.',
              '- 배당은 채권 평가하되 지급일 자료 부족으로 매수 자금에 사용하지 않는다. 분할 전 명목 가격과 수량을 복원한다. 총수익 지수의 배당 재투자·비용 미차감과 차이가 있다.',
              '- 완료 종가 신호→다음 시가 체결이다. 시가 차단은 해당 시가 청산, 종가 차단과 체결 비용 반영 후 새로 발동한 차단의 잔여 보유는 다음 시가 청산이다. 갭·비용·미관측 장중 경로 때문에 손실10%를 보장하지 못한다.',
              '- 확보한 자료로 검증한 것은 수일 단위 거래다. 장중 초단타 호가·스프레드·부분 체결·지연은 검증하지 않았다. 종료 보유분은 종가 평가이며 강제 청산 비용을 추가 차감하지 않았다.',
              '- PLAN.md와 pre-run-lock.json은 사전 설계·코드·입력 해시, completion.json은 결과 해시, runs/*bps.json은 전체 원장, audit.json은 독립 감사다. metrics.json/decisions.json에 모든 지표와 잠정 판정을 보존한다.', '']
    (folder / 'REPORT.md').write_text('\n'.join(lines), encoding='utf-8')
    return decisions


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('folder', type=Path)
    parser.add_argument('--reference-pct', type=float, default=5.0)
    args = parser.parse_args()
    print(json.dumps(report(args.folder, args.reference_pct)))


if __name__ == '__main__':
    main()
