"""Render every declared comparison, without selecting or tuning parameters."""
import json
import sys
from pathlib import Path

folder = Path(sys.argv[1])
rows = json.loads((folder / 'runs/summary.json').read_text(encoding='utf-8'))
audit = json.loads((folder / 'runs/audit.json').read_text(encoding='utf-8'))
labels = {'spy_trend': 'SPY 추세', 'sector_top1': '업종 상위 1개', 'sector_top3': '업종 상위 3개'}
lines = ['# TEAM-21 실제 일봉 전략 비교', '',
         '운영 적용 없음. 아래 수익률은 새 일봉 연구 전략의 가상 원장 결과이며 기존 운영 자동매매의 실적이 아니다.', '',
         '공개 Yahoo 일봉: 2006-01-03~2026-11-18. 평가 구간별 $10,000 현금 시작, 추가 입금 없음. 8개 업종 ETF 중 126거래일 모멘텀과 200일 평균으로 선택한다. SPY 추세 전략은 동일 위험 규칙을 적용한 대조군이다.', '',
         '## 사전 고정한 30개 결과', '',
         '수익률·낙폭은 %, 초과수익은 %p. 비용은 편도 수수료와 슬리피지 합계. 연환산이 아닌 각 구간 누적 수익률이다.', '',
         '| 구간 | 전략 | 비용 bps | 수익률 | S&P 500 총수익 | 초과수익 | 최대 낙폭 | 평균 투자비중 | 거래수 | 차단일 |',
         '|---|---|---:|---:|---:|---:|---:|---:|---:|---|']
for r in rows:
    latch = r['hard_latch']['date'] if r['hard_latch'] else '없음'
    lines.append(f"| {r['period']} | {labels[r['candidate']]} | {r['cost_bps']} | {r['return_pct']:.3f} | {r['benchmark']['return_pct']:.3f} | {r['excess_return_pp']:+.3f} | {r['mdd_pct']:.3f} | {r['mean_exposure']*100:.2f}% | {r['trade_count']} | {latch} |")
lines += ['', '## 검증과 해석', '',
          f"독립 원장 감사: {audit['status']}, {len(audit['runs'])}회, 일별 상태 {audit['daily_states']:,}개, 매수·매도 {audit['trades']:,}건 재계산.", '',
          '- 위험 예산 공식은 고점 대비 남은 손실 여유의 두 배만 투자한다. 고점에서도 목표 투자비중은 최대20%이고 하락하면 더 줄어든다. 이는 10% 차단과 별도의 보수적 설계이며 사용자 조건 자체가 20% 비중을 강제하는 것은 아니다.',
          '- 당일 완료 종가 신호를 다음 시가에 실행한다. 종목 재선정은 첫 평가일·새 월 첫 종가, 자격 상실 퇴출과 위험비중 조정은 매일 수행한다. 상위3개 중 빈 슬롯은 현금으로 둔다.',
          '- 매수는 수수료 포함 결제된 현금99% 한도와 정수 주식이다. 매도 대금은 다음 거래일 사용하며 과거 전체 구간에도 이 T+1 가정을 적용했다. 역사적 T+2/T+3 제도를 재현하지 않는다.',
          '- 배당은 배당락일에 채권 자산으로 계상하되 지급일 자료가 없어 매수 가능 현금으로 전환하지 않는다. 세금·환율·현금 이자는 제외한다. 지수는 비용·세금 차감 전 배당 재투자 총수익이라 배당 처리도 완전히 같지는 않다.',
          '- 명목 체결가는 분할 이전 가격을 복원하고 분할일 보유 수량을 변경한다. XLF는2016분사를 보통 분할로 처리할 수 없어 성과 계산 전에 제외했다. 남은8개는 XLB/XLE/XLI/XLK/XLP/XLU/XLV/XLY다.',
          '- 10% 고점 대비 하락을 관측하면 영구 매수 잠금·청산한다. 시가 차단은 해당 시가, 종가 차단은 다음 시가 청산이다. 갭과 일중 경로 때문에 최대 손실10%를 보장하지 못한다. 표의 낙폭은 일봉 시가·종가와 비용 반영 관측치다.',
          '- 분리 구간은 새 계좌이며 연속 구간은 고점/잠금을 재설정하지 않았다. 첫 평가일 종가 기준 지수와 비교하고 첫 주문은 다음 시가다. 종료 보유분은 종가 평가이며 강제 청산 비용을 빼지 않았다.',
          '- 기존 보유 종목을 포함한 실계좌 검증은 아니며, 사후 수정 공개 일봉·현재 존속 ETF·이미 검토한 역사 구간이다. 독립 미공개 표본 성과나 미래 수익 보장이 아니다.', '',
          '## 재현 자료', '',
          '- `PLAN.md`: 실행 직전 계획 사본. `pre-run-lock.json`: 코드·자료·계획 SHA256 동결.',
          '- `completion.json`: 모든 결과 해시. `runs/*bps.json`: 일별 현금·보유·배당·신호·체결 원장.',
          '- `runs/audit.json`: 별도 구현의 모든 원장 재계산 결과.',
          '- 자료: `../team21_data/20261119T075458Z-e42ca93a/manifest.json` 및 원문·CSV·배당 조정 감사.', '',
          '비교 기준은 [S&P 총수익 지수 방법론](https://www.spglobal.com/spdji/en/documents/methodologies/methodology-index-math.pdf)의 배당 재투자 지수다. 분할은 [발행사 공지](https://investors.statestreet.com/investor-news-events/press-releases/news-details/2025/State-Street-Investment-Management-Announces-Share-Splits-for-Five-Select-Sector-SPDR-ETFs/default.aspx)를 확인했다.', '']
(folder / 'REPORT.md').write_text('\n'.join(lines), encoding='utf-8')
print(folder / 'REPORT.md')
