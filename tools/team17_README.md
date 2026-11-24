# TEAM-17 실제 서비스 재생용 자료 점검

이 도구는 현재 자동매매 서비스에 넣을 자료의 형식과 누락을 점검한다. 새 ETF 전략을 대신 돌리지 않는다. **현재 보유 일봉자료만으로 실제 자동매매 전체의 S&P500 대비 수익률은 검증할 수 없다.** 누락 자료를 가짜 순위·호가·재무값으로 채워 성과를 계산하지 않는다.

## 두 검증의 범위

1. Java `KiwoomUsProductionReplayHarness`는 실제 `KiwoomUsAutoTradeService` 및 관련 서비스 코드를 호출하고, 계좌·시세·브로커 경계에는 명시된 스냅샷을 전달한다. 결과는 결정/주문 시도/대사 추적이다. 실제 주문이나 수익률 보고가 아니다.
2. Python `team17_validate.py`는 같은 핵심 입력 구조를 사전 점검하고, 별도의 `metadata`가 역사적 성과 검증에 필요한 근거를 갖췄는지 목록화한다. Java 실행기가 Python을 자동 호출하는 연결 파이프라인은 아니다. Python 구조 통과는 Java 실행 성공이나 자료 진위를 보장하지 않는다. 일부 의미 검사(호가/OHLC/순위)는 Python이 더 엄격하다.

합성자료가 정상 통과해도 `performance_allowed:false`, `returns_computed:false`다. `real_point_in_time`이라고 선언하거나 해시 문자열을 넣는 것만으로 출처를 인증하지 않는다. 모든 metadata 형식이 채워져도 `STRUCTURE_VALID_PROVENANCE_UNVERIFIED`이며 성과 승인은 하지 않는다.

## 실행

백엔드 저장소에서:

```powershell
python -B -m unittest discover -s tools -p team17_test.py
python -B tools/team17_validate.py --coverage --output tools/team17_coverage.json
python -B tools/team17_validate.py <입력.json> --output <점검결과.json>
```

coverage의 종료코드2는 의도한 **자료 불충분** 결과다. 합성 핵심형식 통과는 종료코드0이지만 성과 승인과 다르다. 네트워크·계좌·키·운영DB에 연결하지 않는다. Java 실행 명령은 저장소 `run-us-production-replay.ps1`과 Java 담당 안내를 따른다. 두 도구 모두 실계좌 비밀키나 계좌번호를 입력받을 필요가 없다.

2026-11-19 검증: Python 회귀14개 PASS. Java 하네스가 생성한 `target/us-production-replay/run-5ab2141013e049aa85ec4190a472da1d/synthetic-input.json`을 동일 검사기에 넣어 `STRUCTURE_VALID_SYNTHETIC`, core_errors=[]를 확인했다. 결과 `team17_synthetic_validation.json`에 입력/검사기/계약 SHA256을 보존한다. 이는 합성 입력 호환성 확인이며 실제 성과 검증은 아니다.

## Java와 공유하는 핵심 입력

`team17_contract.json`에 필수 키와24개 settings 이름이 있다.

- 최상위: `schema_version:1`, `dataset_kind:synthetic|real_point_in_time`, `initialization:NEW_POLICY_NO_PRIOR_AUTOMATION`, 명시적인 `settings`, `initial_risk_equity_usd`, 순서가 있는 `frames`.
- 현재 하네스는 **새 위험정책 시작**만 지원한다. 기존 수동보유는 첫 `balance`에 포함할 수 있지만 과거 자동주문/위험고점/일일상태/쿨다운 복원은 지원하지 않는다. `initial_state/initial_proposals/initial_risk/initial_control` 최상위 필드는 거부한다. 첫 frame의 open_orders/fills/history는 빈 result_list여야 한다. 이를 기존 정책의 정확한 중간 재시작이라고 주장하지 않는다.
- frame: 고유 `id`, 시간대 포함 `at`·`observed_at`, `actions:[DECIDE|MONITOR|RECONCILE]`, 당시 raw `account.deposit`/`account.balance`, `rankings`, symbol별 `quotes/fundamentals/universe/completed_bars`, `broker`.
- 계좌 관측과 호가 `available_at`는 결정 시각 이전90초 이내. 자료가 나중에 알려진 경우 과거시점으로 되돌려 넣지 않는다. 일봉은 미국동부시간의 현재 세션보다 이전이며51개 이상, 정렬·중복없음·OHLC·volume 유효성 필요.
- rankings는 당시 순위·거래대금·등락률·누적거래량·전일거래량을 보존한다. 전체일 거래량을 장중 누적거래량인 것처럼 쓰지 않는다. 순위 종목별 호가·재무상태·편입·완료봉·주문가능 응답, SPY/QQQ 완료봉이 필요하다.
- fundamentals는 `lookup_status` enum과 `available_at`를 명시한다. CACHE_FRESH/YAHOO_REFRESHED/CACHE_STALE_FALLBACK은3개 수치가 필수다. 다른 상태는 실제 실패/누락 관측을 표현한다. **과거 재무자료가 없다는 이유로 DATA_UNAVAILABLE을 임의로 넣으면 서비스의 우회 분기가 작동하므로 실제 성과 근거로 사용할 수 없다.** 당시 실패 응답의 출처도 보존해야 한다.
- broker: raw `open_orders/fills/history` 각각 `result_list`, 종목별 `orderability`, 순서대로 소비되는 `write_responses:[{api_id,response:{return_code,...}}]`. API id는 ust20000/ust20001/ust20003. 외부 요청을 실행하는 설정이 아니다.
- Windows Python에 tzdata가 없는 경우 ET 날짜변환은 생산 달력 지원범위2026~2028에 한해 미국 DST 규칙으로 계산한다. 이 범위 밖에서는 임의 시간대를 가정하지 않고 거부한다. Java의 실제 시장 달력 검사는 별도로 적용된다.

## 역사적 성과용 추가 metadata 계약 — 현재 Java의 성과계산 기능 아님

`metadata`는 Python 자료감사용 확장이다. 이 항목을 넣는다고 Java가 원장/수익률을 자동 복원하지 않는다.

| 항목 | 필요한 근거 |
|---|---|
| sources | 과거 당시 이용가능한 원본 archive URI, 캡처시각, SHA256, 출처 종류. 비밀정보 제외 |
| expected_frame_ids / gaps | 검증 전에 정한 관측·결정·대사 일정과 누락 감사. 데이터 없는 시간을 조용히 건너뛰지 않음 |
| point_in_time | 상위50 페이지 완결성·장진행률/거래량·호가 아카이브·재무 공시/실패상태·지수편입/상장폐지·봉 완료/조정기준·설정이력·브로커이벤트/비용모형 증빙 파일해시 및 source_ids |
| initial_state | 기존보유 관리상태·미체결/예약현금·고점/영구차단·일일상태·쿨다운. 현재 하네스의 복원지원과는 별개로 부족함을 드러내는 필수근거 |
| corporate_actions / external_cashflows | 배당 권리/지급·분할 및 입출금 시각. 배당을 외부입금으로 취급하거나 배당조정 가격에 배당을 다시 더하지 않음 |
| replay_evidence | 실제 서비스 클래스·정확한 소스해시·입력해시·결정출력해시·독립원장감사해시·실제로 실행된 decision_ids. 단순 validated:true로 대체불가 |
| benchmark | 동일 평가시각의 S&P500 총수익지수. 가격지수/SPY가격으로 몰래 대체하지 않음 |
| evaluations | 동일시점 전체 순자산·비용·배당·외부입출금·결정id·완전성. baseline과후속평가 필요 |

`flow_timing:PERIOD_END_INCLUDED_IN_EQUITY`는 기간말 외부 순입금이 최종 순자산에 포함되었음을 뜻한다. 장중입출금이면 입출금 전후 평가를 나눠야 한다. 순자산에서 이미 차감한 수수료/반영한 배당을 평가기가 다시 더하거나 빼면 안 된다. 이 도구는 수익률 계산을 하지 않는다.

자료 해시는 내용의 동일성만 확인할 수 있고 그 내용이 진짜 당시 자료인지 증명하지 못한다. 최종 성과 검증에는 원본 출처·시점성 검토, 실제 서비스 출력과 입력의 연결 검증, 독립 원장 대사, 재현 가능한 체결가정 검토가 추가로 필요하다.

## 현재 coverage 판정

`team17_coverage.json`은 기존 market_data/strategy_v2_data/strategy_v3_data manifest와 원시JSON/CSV의 해시를 다시 확인한다. 일봉·일별 총수익지수·배당 ex-date는 일부 존재하지만 아래는 없다.

- 당시 거래대금 상위50 전체·장중 호가·동시간 거래량
- 당시 알려진 재무정보/실패상태·지수편입과 상장폐지 이력
- 과거 유효 설정·기존계좌/미체결/고점·브로커의 실제 이벤트 순서
- 실제 서비스 결정과 연계된 전체 계좌 원장·입출금·동일시점 벤치마크

따라서 결과는 `BLOCKED_INCOMPLETE_POINT_IN_TIME_DATA`다. 이는 합성 로직 테스트가 실패했다는 뜻이 아니라, 실제 전략의 투자 성과를 판단할 자료가 부족하다는 뜻이다.
