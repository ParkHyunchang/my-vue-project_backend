"""Render all frozen outcomes and paired factor effects; no parameter selection."""
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parent
OUT=ROOT/'team16_results'
rows=json.loads((OUT/'summary.json').read_text())
lookup={(r['period'],r['cost_bps'],r['candidate']):r for r in rows}
lines=['# TEAM-16 추세 보유기간·추가매수 비교 결과','',
       '2026-11-19. 운영 미적용. 4후보×5기간×2비용=40회 회고 검증. 기존 실계좌 전략의 정확한 재현이 아니다.','',
       '**최근 두 기간·두 비용 모두 S&P500 초과 및 관측MDD<=10%를 만족한 후보는 없다. 이번 연구에 근거한 실계좌 전략 교체를 채택하지 않는다.**','',
       '## 사전 고정과 비교','',
       '- full: 최초80% 진입; staged: 최초40%, 추세유지2완료일 뒤80%까지 한 번 증액.','- five: 최초진입일부터5완료일 뒤 다음시가 청산; trend: SMA200 이탈시 다음시가 청산.','- 종가신호→다음시가, 청산당일시가 재진입 없음. 추가매수로 진입일/고점/차단을 초기화하지 않는다.','- 현금$10,000, 정수주, 비용포함 현금99% 한도, 누적고점10% 영구차단을 공통 적용. 10%는 실제손실상한 보장이 아니다.','- 편도비용10bp/25bp, 배당은 미수 평가만 반영하고 재투자하지 않음. 운영3서비스와 코드/자료/계획을 실행 전에 SHA256 잠금했다.','- S&P500TR는 첫평가일종가→마지막종가 총수익. 전략도 첫날종가 현금baseline 후 다음시가부터 거래. 첫밤 노출 차이는 남는다. 보조 SPY 매수보유는 같은 다음시가·비용·정수주, 위험차단은 없다.','',
       '## 전체 결과','',
       '| 기간 | 진입/보유 | 비용 | 수익률 | S&P500 | 초과수익%p | MDD | 체결/추가 | 수수료USD | hard일 |',
       '|---|---|---:|---:|---:|---:|---:|---:|---:|---|']
for r in rows:
    lines.append(f"| {r['period']} | {r['candidate']} | {r['cost_bps']}bp | {r['return_pct']:+.2f}% | {r['benchmark_pct']:+.2f}% | {r['excess_pp']:+.2f} | {r['mdd_pct']:.3f}% | {r['trade_count']}/{r['add_count']} | {r['fees']:.2f} | {r['hard_date'] or '없음'} |")
lines+=['','기간: crisis=2008~2017, middle=2018~2023, recent=2024~2025, latest=2026-01-02~11-17, continuous=2008~2026-11-17. 개별기간은 독립 초기화이며 연속기간은 고점/hard 초기화가 없다.','',
        '## 동일조건 요인 효과','',
        '수익률 차이는 %p. 양수는 앞에 명시한 변경이 수익률을 높였다는 뜻이다. 서로 겹치는 기간을 독립 표본으로 세지 않는다.','',
        '| 기간 | 비용 | full에서 5일→추세보유 | staged에서 5일→추세보유 | five에서 full→staged | trend에서 full→staged |',
        '|---|---:|---:|---:|---:|---:|']
for period in ('crisis','middle','recent','latest','continuous'):
    for cost in (10,25):
        values={c:lookup[period,cost,c]['return_pct'] for c in ('full_five','staged_five','full_trend','staged_trend')}
        lines.append(f"| {period} | {cost}bp | {values['full_trend']-values['full_five']:+.2f} | {values['staged_trend']-values['staged_five']:+.2f} | {values['staged_five']-values['full_five']:+.2f} | {values['staged_trend']-values['full_trend']:+.2f} |")
lines+=['','## 보조 SPY 매수보유·연속운용','',
        '| 기간 | 비용 | SPY 보유수익 | SPY MDD |','|---|---:|---:|---:|']
for period in ('crisis','middle','recent','latest','continuous'):
    for cost in (10,25):
        r=lookup[period,cost,'full_five']['buyhold']
        lines.append(f"| {period} | {cost}bp | {r['return_pct']:+.2f}% | {r['mdd_pct']:.2f}% |")
lines+=['','연속구간은 모든 후보가2010~2012년에hard 차단된 뒤 현금상태를 유지한다. 이를 회복이나 초과수익 성공으로 해석하지 않는다. 배당 재투자 없는 정수주 SPY와 재투자 총수익지수의 장기수익 차이도 존재한다.','',
        '## 해석','',
        '최근 구간에서는5일마다 자동청산하는 것보다 추세가 유지되는 동안 보유하는 방법이 수익률과 거래비용 면에서 개선되는 경우가 있었다. 그러나 전 기간의 우위가 아니며, 최근2026에서도지수초과에미달했다. 추가매수 자체는 일관된 개선을 보이지 않는다. 분할추가를 허용한다는 이유만으로 더 나은 전략이 된다고 볼 수 없다.','',
        '실전의 상위거래대금 후보·일중거래량·재무/호가·SPY/QQQ20/50추세·개별종목 손절 등을 이 엔진은 재생하지 않는다. 따라서 기존서비스의 보유기간/추가금지 설정을 바로 바꾸는 근거로 쓰지 않는다.','',
        '## 검증 및 재현','',
        '`tools`에서 `python -B -m unittest team16_test.py` (합성10tests PASS), `python -B team16_research.py` (최초생성, 기존results가있으면덮어쓰기거부), `python -B team16_report.py` (보고서생성). `pre-run-lock.json`과 `completion.json`에서입력및운영3서비스불변을검증했다. 독립 검토자가40회·76,000개 일별상태를 별도로 재구성하여 PASS를 보고했다. 해당 검토의 재현 스크립트와 audit.json은 저장되지 않았다.','',
        '## 제한','',
        '기존에 관측한 과거자료이며 신규holdout이 아니다. 시가관측뒤수량산정·같은시가±slippage의전량체결가정은 실제개장경매재현이아니다. 일봉은 장중손실/호가/부분체결을재현하지않으며 지급일·세금·환율·현금이자를생략했다. 목표80%는진입시목표이고가격변동후일별재조정은없다. 모든결과는USD기준이다. 운영설정/주문/배포는변경하지않았다.','']
(OUT/'REPORT.md').write_text('\n'.join(lines),encoding='utf-8')
print('REPORT.md written; 40 outcomes and 10 paired-factor rows.')
for r in rows:
    if r['period'] in ('recent','latest'):
        print(r['period'],r['candidate'],r['cost_bps'],round(r['return_pct'],2),round(r['mdd_pct'],2))
