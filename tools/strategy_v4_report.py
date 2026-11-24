"""Report every frozen recovery run and paired original result."""
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / 'strategy_v4_results'
OUTPUT = ROOT.parent.parent / 'my-vue-project/docs/ai-team/tasks/TEAM-13-VALIDATION.md'
LABELS = {'crisis': '2008~2017', 'middle': '2018~2023', 'recent': '2024~2025',
    'latest': '2026-01-02~11-17', 'continuous': '2008~2026-11-17 연속'}


def exposure(path):
    with path.open(encoding='utf-8', newline='') as stream:
        rows = list(csv.DictReader(stream))
    weights = [(float(r['equity_usd'])-float(r['cash_usd']))/float(r['equity_usd']) for r in rows]
    longest = current = 0
    for weight in weights:
        current = current+1 if weight < .01 else 0
        longest = max(current, longest)
    return {'low_pct': sum(w < .01 for w in weights)/len(weights)*100, 'longest_low': longest,
        'final_pct': weights[-1]*100,
        'hard_pct': sum(r['hard_latched'] in ('True','true') for r in rows)/len(rows)*100}


def main():
    summary = json.loads((RESULTS/'summary.json').read_text(encoding='utf-8'))
    completion = json.loads((RESULTS/'completion.json').read_text(encoding='utf-8'))
    if len(summary)!=20 or completion['runs']!=20:
        raise ValueError('Incomplete experiment')
    winners = sum(r['goal_met'] for r in summary)
    breaches = sum(r['mdd_pct']>10 for r in summary)
    lines = ['# TEAM-13 극소 투자비중 고착 개선·재검증', '',
        '2026-11-18. 운영 미적용. 현금 한도·고점 대비10%영구차단을 유지한 회복 상태기계 구현 및20회 회고검증.', '',
        f'**S&P500 초과수익과 관측MDD<=10% 동시 충족 {winners}/20, MDD10% 초과 {breaches}/20.** 모든기간/비용통과후보: '+str(completion['all_conditions_pass_candidates'])+'.', '',
        '## 변경과 사전 고정', '',
        '기존 CPPI는 남은손실여유가 작아지면 투자비중도 거의0이 되어 상승장에서회복하기어려웠다. 새후보는6%soft후현금대기20세션, SPY의SMA200회복과직전20종가고점돌파시25%시험진입, 실제노출20세션·시험손익비음수·추세유지를확인해50→80%로확대한다. 시험최초자산대비1%추가손실이나시장추세실패는재대기한다.', '',
        '6%전역soft는NORMAL에서만발동하며 회복중즉시재중단되는순환을막았다.80%회복단계에서원고점낙폭3%이하일때NORMAL로돌아온다. 회복에피소드기준은승급시리셋하지않고 전역고점/10%hard는항상유지한다. 시가급락검사가예정매수보다우선한다. 10%는실제손실상한보장이아니다.', '',
        'G는이전D의SPY바스켓, H는이전F의주식/금/국채모멘텀바스켓을재사용한다. H의빈슬롯은다른ETF로재분배하지않으며 회복게이트는SPY라금/채권만회복하는국면을놓칠수있다.', '',
        '계획 SHA256 `f871afa11f1e10b072f3d70795043d86b57cb7ba21b6175138190b867f6c21e3`. 같은공개자료26,260행과같은5구간·두편도비용10bp/25bp를사용했다. 코드/계획/자료/v3비교원장까지잠근후20회실행, 결과후튜닝없음. 전체구간은이미관측한회고이며독립holdout이아니다. 겹친기간은독립표본아님.', '',
        '각구간$10,000현금초기화, 연속구간은2008~2026중고점/차단초기화없음. 전략은수수료5bp+slip5/20bp반영USD수익, ^SP500TR는비용없는배당포함총수익지수이며동일첫날종가baseline. 보조SPY비교도JSON에기록.', '',
        '## 전체20회 성과와 기존전략 비교', '',
        '| 기간 | 후보/비용 | 기존수익 | 개선후수익 | S&P500 | 초과수익(%p) | 기존MDD | 개선후MDD | hard일 |',
        '|---|---|---:|---:|---:|---:|---:|---:|---|']
    for r in summary:
        lines.append(f"| {LABELS[r['period']]} | {r['candidate']}/{r['cost_bps']}bp | {r['baseline_return_pct']:+.2f}% | {r['return_pct']:+.2f}% | {r['benchmark_pct']:+.2f}% | {r['excess_pp']:+.2f} | {r['baseline_mdd_pct']:.3f}% | {r['mdd_pct']:.3f}% | {r['latch_date'] or '없음'} |")
    lines += ['', '## 회복·현금정체 지표', '',
        '낮은노출은위험회피/적격자산없음/WAIT/HARD 모두를포함한다. HARD도포함하여영구차단에빨리도달한결과를회복성공으로포장하지않는다. 최초진입전현금일도양쪽에동일하게포함한다. 최장길이는거래세션수다.', '',
        '| 기간 | 후보/비용 | 평균노출 기존→신규 | 노출<1% 일비중 기존→신규 | 최장<1% 기존→신규 | 최종노출 | HARD일비중 |',
        '|---|---|---:|---:|---:|---:|---:|']
    for r in summary:
        old = 'D' if r['candidate']=='G' else 'F'
        before = exposure(ROOT/f"strategy_v3_data/results/{old}-{r['period']}-{r['cost_bps']}bp-equity.csv")
        after = exposure(RESULTS/(r['run']+'-equity.csv'))
        lines.append(f"| {LABELS[r['period']]} | {r['candidate']}/{r['cost_bps']}bp | {r['baseline_average_invested_percent']:.2f}→{r['average_invested_percent']:.2f}% | {before['low_pct']:.2f}→{after['low_pct']:.2f}% | {before['longest_low']}→{after['longest_low']} | {after['final_pct']:.2f}% | {after['hard_pct']:.2f}% |")
    lines += ['', '## 재진입 이력', '',
        '| 기간 | 후보/비용 | 실제 재진입 횟수 | 최초soft→재진입 세션 | 종료상태 |',
        '|---|---|---:|---:|---|']
    for r in summary:
        report=json.loads((RESULTS/(r['run']+'.json')).read_text(encoding='utf-8'))
        delay=report['sessions_first_soft_to_reentry']
        lines.append(f"| {LABELS[r['period']]} | {r['candidate']}/{r['cost_bps']}bp | {report['wait_reentry_count']} | {delay if delay is not None else '해당없음/재진입없음'} | {report['ending_state']} |")
    lines += ['', '## 거래·검증 자료', '',
        '| 기간 | 후보/비용 | CAGR | 매수/매도 | 평균현금비중 | 연간총거래대금/평균자산 |',
        '|---|---|---:|---:|---:|---:|']
    for r in summary:
        report=json.loads((RESULTS/(r['run']+'.json')).read_text(encoding='utf-8'))
        lines.append(f"| {LABELS[r['period']]} | {r['candidate']}/{r['cost_bps']}bp | {r['cagr_pct']:+.2f}% | {report['buy_count']}/{report['sell_count']} | {report['average_cash_percent']:.2f}% | {report['annualized_gross_turnover']:.2f}배 |")
    lines += ['', '1년미만2026 CAGR은수학적환산이며미래예측아님. 상태전이·WAIT기간·재진입/승급·원장·수수료는각실험JSON/CSV에보관한다.', '',
        '## 판정과 검증', '',
        '**회복 경로는 구현했지만, 사용자의 손실·수익 조건을 만족하는 개선으로 채택할 수 없다.** G의 기본비용 장기연속 평균노출은 기존1.06%에서19.17%로 늘었고15회 재진입했다. 그러나2016-06-27 hard발동 후 영구현금 상태로 끝났다. 높은비용에서는2010-05-07에 이미hard가 발동했다. H역시 두비용 모두2010년에hard로중단되어 기존보다 장기성과가악화됐다.', '',
        '이것은 회복가능성을 늘리면서 남아있는 손실여유도 더 소비한 결과다. 10%차단 설정을 바꾸거나 고점을 초기화하지 않았지만, 실제관측최대낙폭은15/20에서10%를넘었고최대10.5573%였다. 신호·차단이 코드대로 작동한 것과 사용자 손실조건 충족은 별개다. 기존보다 낮은노출기간이 줄어든 일부결과만으로 현금정체해결이라고 판정하지 않는다.', '',
        '합성20개(엔진13/독립감사7) PASS, 실제20개 원장·상태·정확수량·수수료·체결시점·수익·MDD·벤치마크 독립감사 PASS. 실험 전18개와 G/H×두비용 합성4개 교차감사를 통과했다. 엔진을잠그고실험을시작한뒤 경계값테스트2개만추가했으며 엔진/계획/자료는바뀌지않았다. 추가테스트작성자는시장성과를보지않았다.', '',
        '기획은 상태계약과함정, 개발은신규엔진, 별도검증은독립상태/원장감사, 총괄은사전잠금·실험·v3대조를담당했다. 6%soft재발동순환, WAITday0반복갱신, 승급시anchor리셋, 미래종가참조를검증했다. 운영에는연결하지않고연구실패와한계를보존한다.', '',
        '## 한계와 운영 상태', '',
        '연구는조정가격가상소수주·완전체결·무이자현금·세전·입출금없음이다. 실정수주·배당지급시점·장중고점/낙폭·부분체결/정지·기존실보유를재현하지않는다. 1%시험손절과10%hard도갭이나체결지연으로넘을수있으며관측MDD초과는그대로보고한다.', '',
        '생산UI/Java/API변경없음. 기존현금·위험·자동매매서비스해시를대조한다. 서버/브라우저/Java전체검증을이번오프라인변경에서새로실행했다고주장하지않는다. 실계좌·주문·푸시·운영배포없음.', '',
        '산출물: BE tools/strategy_v4.py 및test, strategy_v4_audit.py 및test, strategy_v4_plan.md, strategy_v4_run.py, strategy_v4_report.py, strategy_v4_results/. 재현법은strategy_v4_README.md. 기존v3자료/결과는보존했다.', '']
    OUTPUT.write_text('\n'.join(lines), encoding='utf-8')
    print(OUTPUT)


if __name__=='__main__':
    main()
