# 실제 일봉을 이용한 제한적 추세 재생

이 도구는 현재 제품의 `KiwoomUsTechnicalSignalService.calculate`를 그대로 호출한다. 그러나 **실전 자동매매 전체 전략의 백테스트가 아니다.** 시점별 지수 구성, 거래대금 순위, 재무정보, 장중 거래량과 호가, 체결 대기열이 없어 이를 생략한다. 고정 생존종목 표본과 배당·분할 조정 가격의 가상 소수 단위를 사용한다. 실제 정수 주식 주문/배당 현금 계좌와 다르다.

## 재현 명령

백엔드 저장소 루트, JDK21 및 오프라인 Maven 의존성 준비 상태에서:

```powershell
.\tools\replay-export.ps1
python -B -m unittest discover -s tools -p replay_daily_test.py -v
python -B tools/replay_daily.py tools/market_data/daily.csv tools/market_data/signals.json --start 2018-01-01 --end 2025-12-31 --output-prefix tools/market_data/replay-2018-base
python -B tools/replay_daily.py tools/market_data/daily.csv tools/market_data/signals.json --start 2024-01-01 --end 2025-12-31 --output-prefix tools/market_data/replay-2024-base
python -B tools/replay_daily.py tools/market_data/daily.csv tools/market_data/signals.json --start 2018-01-01 --end 2025-12-31 --slippage-bps 20 --output-prefix tools/market_data/replay-2018-cost25
python -B tools/replay_daily.py tools/market_data/daily.csv tools/market_data/signals.json --start 2024-01-01 --end 2025-12-31 --slippage-bps 20 --output-prefix tools/market_data/replay-2024-cost25
```

내보내기 runner는 기존 격리 테스트와 동일하게 환경변수 허용목록, JDK21, 오프라인 Maven, 테스트 JVM 네트워크 차단을 사용한다. 서버·계좌·실주문 호출은 없다. 입력/출력은 `tools/market_data` 아래 CSV/JSON으로 제한한다. 기본 전체 테스트에서는 합성 어댑터 회귀가 실행되고 실제자료 내보내기는 입력 인자가 없으므로 1개 skipped로 표시된다.

각 실행은 보고서 JSON, 거래 원장 JSON, 일별 equity CSV를 만든다. CSV는 `us_performance.py` 형식이지만 벤치마크 열에는 공식 지수가 아닌 **SPY 배당포함 조정종가 proxy**가 들어간다. ETF 보수·추적 차이가 있다.

## 결과 확인 전에 고정한 가정

- 고정 표본과 출처/해시: `market_data_README.md`, `market_data/manifest.json`.
- $10,000 현금으로 2018~2025와 2024~2025 각각 초기화한다. 첫 거래일 종가는 현금 기준점이며 다음 거래일부터 진입한다. 후자의 구간을 미사용 OOS 실증으로 주장하지 않는다.
- 기본 편도 비용: 수수료 5bps + 슬리피지 5bps. 민감도: 수수료 5bps + 슬리피지 20bps. 결과를 보고 조정하지 않는다.
- 완료된 t-1까지 51개 봉과 t일 종가를 0스프레드 호가 대용값으로 기존 신호에 전달한다. t일 신호는 t+1일 시가에만 체결한다. 제품의 2026~2028 달력은 변경하지 않고, 테스트 범위에서만 SPY 실제 관측 거래일의 직전 거래일 함수로 대체한다. 자료 누락은 해당 신호를 거부한다.
- 생산 기본 설정을 export JSON에 기록한다. 현재 종목당 최대 10%, 거래당 위험 0.5%, 최대 3종목, 일일 2매수, 추세 최대 5거래일, ATR 손절 2배, 추적 ATR 2배/1R 활성화를 사용한다. 1% 현금 여유와 비용을 포함한 현금 상한을 적용한다.
- 후보는 상대강도 순서다. 실전의 이격·스프레드 포함 점수와 다르다. 일일 3% 신규매수 중단은 재생하지 않으며 주문은 하루 시작에만 발생한다.
- 이전 종가까지 확정된 손절선에 당일 저가가 도달하면 손절한다. 시가가 손절선보다 낮으면 더 낮은 시가에 비용을 반영한다. 당일 고가를 먼저 이용해 유리하게 손절선을 높이지 않는다. 추적 고점은 종가로만 갱신한다. TREND 대용 모델에는 별도 익절 목표가 없다.
- 고점 대비 10%는 종가에서 감지하고 다음 시가에 청산한다. 래치는 해당 실행에서 영구 유지한다. 갭 때문에 실제 낙폭은 10%를 넘을 수 있다. 종료일 보유는 종가 평가하며 강제 청산 비용은 반영하지 않는다.
- 모든 주문은 전량 체결 가정이다. 거래정지·부분체결·주문거절·다중 조회 시차를 모델링하지 않는다. 배당·분할 조정 가상단위라 주문가격/수량 자체가 실제 과거 주문을 뜻하지 않는다.

합성 회귀는 계산 구현을 확인할 뿐 수익성을 입증하지 않는다. 실제자료 결과도 위 제한적 모델의 관측 성과로만 해석한다. 출력에는 입력 CSV/신호 JSON/재생 코드/제품 신호 및 기본설정의 SHA256을 남긴다.

자료의 조정계수 부동소수점 연산으로 종가가 고가를 약 1e-14 초과하는 행은, 상대오차 1e-10 이하일 때만 고저가를 시가·종가 포함 범위로 보정한다. 그보다 큰 OHLC 불일치는 거부한다. 보정 수와 허용오차를 Java 신호 및 재생 결과에 기록하며 원본 CSV는 변경하지 않는다.

## 기존 UI 시작값의 별도 비교

결과를 보기 전에 추가 고정한 두 번째 설정은 프론트 `KiwoomUsAutoTrade.vue`의 기존 TREND_PRESET이다. 신호·손절·추적·보유기간·위험비율 값은 위 기본값과 같고, 종목당25%/최대4종목만 다르다. 위 네 명령에 `--max-order-percent 25 --max-positions 4`를 추가하고 출력 prefix에 `-preset`을 붙여 별도로 실행한다. 총8개 비교이며 결과 이후 최적화한 값이 아니다. 운영 DB에 저장된 사용자 설정은 조회하지 않았다. 보고서는 exported_settings/declared_overrides/applied_settings와 원본신호 SHA를 모두 보존한다.
