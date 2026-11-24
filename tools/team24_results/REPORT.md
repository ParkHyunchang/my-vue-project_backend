# TEAM-24 거래 비용 차감 연10% 목표 검증

**판정: 연10% 및 위험 통합 기준 통과0/6, 운영 채택 보류.** 최근2년에 연환산11~15% 사례가 있지만2026과장기 연속 구간까지 유지되지 않았다. 분산추세 회복형의 장기CAGR은3.755%이고 모든기간 최대낙폭은7.7973%였다. 집중형의 고점 대비 낙폭은 최대12.187%로 위험 기준도 실패했다.

검사38개 PASS, 실제자료 원장114,000상태·13,309거래 독립검증 PASS, 운영소스749개 불변.

사용자가 확정한 목표는 거래 비용 차감 후 연10% 정도다. 매년10% 또는 미래 수익을 보장하는 뜻은 아니다. S&P 500 초과는 판정에 사용하지 않는다.

운영 미적용. 별도 일봉 가상 원장 연구이며 기존 운영 자동매매나 사용자 실계좌의 실적이 아니다.

## 전략과 실행

- `rotation1`: SPY/QQQ/GLD/IEF 중 SMA200 상회·126일 수익 양수인 후보의 수익 상위1개를 월별 선정한다.
- `riskrank2`: 같은 자격에서126일 수익을63일 변동성으로 나눈 순위의 상위2개를 월별 선정한다. 미충족 슬롯은 현금이다.
- `balanced_trend`: SPY40%·QQQ20%·GLD20%·IEF20% 기본 비율 중 SMA200을 상회한 자산에만 투자한다.
- 위험 예산은 기존 `cushion80`과 변동성·낙폭에 따라20~80% 목표를 산출하는 `recovery_vol`을 비교한다. 계산상 하한은 실제 보유 하한이 아니다.
- 월별 FULL에서 매수하고, 결제 대기로 미완료된 월별 주문은 다음 시가의 일회성 FUND로만 보완한다. FUND는 원래 미매수 수량·현재 목표·현금 한도 내 매수만 하며 재시도나 기준 예산 갱신이 없다. 월중에는 수량을 유지하고, 자격 상실은 EXIT, 위험 목표가 실행 기준보다5%p 이상 작아지면 REDUCE로 매도만 한다. 가격 변동에 따른 잦은 소액 재조정을 줄인다.
- 매수는 비용 포함 결제 현금99% 한도·정수 주식이다.10% 고점 대비 하락 관측 시 영구 매수 차단·청산하고 재개·고점 초기화는 하지 않는다.

## 사전 기준 판정

최근2024–2025와2026 각각 두 비용 모두 CAGR10% 이상, 연속 구간도 두 비용 모두 CAGR10% 이상, 전체 기간·비용에서 관측 낙폭10% 이하·차단/종료 미청산 없음이 연구 판정 기준이다.

| 전략 | 위험 정책 | 최근 수익 목표 | 연속 수익 목표 | 전체 위험 기준 | 종합 |
|---|---|---|---|---|---|
| rotation1 | cushion80 | False | False | False | False |
| riskrank2 | cushion80 | False | False | True | False |
| balanced_trend | cushion80 | False | False | True | False |
| rotation1 | recovery_vol | False | False | False | False |
| riskrank2 | recovery_vol | False | False | False | False |
| balanced_trend | recovery_vol | False | False | True | False |

## 전체60회 성과·위험

수익률·낙폭은%. 누적 수익과 CAGR을 구분한다.2026은1년 미만 구간의 연환산 추정이다. 비용은 편도 총 bps다.

| 기간 | 전략 | 위험 정책 | 비용 | 누적 | 연환산 | 최대낙폭 | 지수 누적(참고) | 차단일 |
|---|---|---|---:|---:|---:|---:|---:|---|
| 2008_2017 | rotation1 | cushion80 | 10 | 7.969 | 0.770 | 9.904 | 129.293 | 없음 |
| 2008_2017 | riskrank2 | cushion80 | 10 | 34.064 | 2.978 | 7.649 | 129.293 | 없음 |
| 2008_2017 | balanced_trend | cushion80 | 10 | 35.704 | 3.103 | 6.262 | 129.293 | 없음 |
| 2008_2017 | rotation1 | recovery_vol | 10 | 9.495 | 0.912 | 10.068 | 129.293 | 2010-05-20 |
| 2008_2017 | riskrank2 | recovery_vol | 10 | 55.851 | 4.542 | 8.020 | 129.293 | 없음 |
| 2008_2017 | balanced_trend | recovery_vol | 10 | 42.664 | 3.621 | 7.009 | 129.293 | 없음 |
| 2008_2017 | rotation1 | cushion80 | 25 | 2.617 | 0.259 | 9.955 | 129.293 | 없음 |
| 2008_2017 | riskrank2 | cushion80 | 25 | 22.722 | 2.071 | 7.850 | 129.293 | 없음 |
| 2008_2017 | balanced_trend | cushion80 | 25 | 29.089 | 2.589 | 6.551 | 129.293 | 없음 |
| 2008_2017 | rotation1 | recovery_vol | 25 | 8.089 | 0.782 | 10.051 | 129.293 | 2010-06-03 |
| 2008_2017 | riskrank2 | recovery_vol | 25 | 16.346 | 1.527 | 8.812 | 129.293 | 없음 |
| 2008_2017 | balanced_trend | recovery_vol | 25 | 37.287 | 3.223 | 7.240 | 129.293 | 없음 |
| 2018_2023 | rotation1 | cushion80 | 10 | -2.057 | -0.347 | 9.865 | 96.486 | 없음 |
| 2018_2023 | riskrank2 | cushion80 | 10 | -0.207 | -0.035 | 8.883 | 96.486 | 없음 |
| 2018_2023 | balanced_trend | cushion80 | 10 | 14.403 | 2.273 | 7.155 | 96.486 | 없음 |
| 2018_2023 | rotation1 | recovery_vol | 10 | -1.363 | -0.229 | 10.125 | 96.486 | 2019-05-28 |
| 2018_2023 | riskrank2 | recovery_vol | 10 | -5.608 | -0.959 | 10.497 | 96.486 | 2020-02-28 |
| 2018_2023 | balanced_trend | recovery_vol | 10 | 14.314 | 2.259 | 7.087 | 96.486 | 없음 |
| 2018_2023 | rotation1 | cushion80 | 25 | -2.476 | -0.418 | 9.979 | 96.486 | 없음 |
| 2018_2023 | riskrank2 | cushion80 | 25 | -1.380 | -0.232 | 9.013 | 96.486 | 없음 |
| 2018_2023 | balanced_trend | cushion80 | 25 | 11.094 | 1.773 | 7.470 | 96.486 | 없음 |
| 2018_2023 | rotation1 | recovery_vol | 25 | -1.732 | -0.291 | 10.251 | 96.486 | 2019-05-13 |
| 2018_2023 | riskrank2 | recovery_vol | 25 | 4.013 | 0.659 | 9.785 | 96.486 | 없음 |
| 2018_2023 | balanced_trend | recovery_vol | 25 | 12.933 | 2.052 | 7.484 | 96.486 | 없음 |
| 2024_2025 | rotation1 | cushion80 | 10 | 31.452 | 14.685 | 7.293 | 48.207 | 없음 |
| 2024_2025 | riskrank2 | cushion80 | 10 | 28.287 | 13.293 | 6.555 | 48.207 | 없음 |
| 2024_2025 | balanced_trend | cushion80 | 10 | 23.653 | 11.224 | 4.457 | 48.207 | 없음 |
| 2024_2025 | rotation1 | recovery_vol | 10 | 32.206 | 15.014 | 8.473 | 48.207 | 없음 |
| 2024_2025 | riskrank2 | recovery_vol | 10 | 27.183 | 12.803 | 7.627 | 48.207 | 없음 |
| 2024_2025 | balanced_trend | recovery_vol | 10 | 24.547 | 11.626 | 4.495 | 48.207 | 없음 |
| 2024_2025 | rotation1 | cushion80 | 25 | 28.600 | 13.431 | 7.352 | 48.207 | 없음 |
| 2024_2025 | riskrank2 | cushion80 | 25 | 25.569 | 12.084 | 6.581 | 48.207 | 없음 |
| 2024_2025 | balanced_trend | cushion80 | 25 | 22.680 | 10.784 | 4.517 | 48.207 | 없음 |
| 2024_2025 | rotation1 | recovery_vol | 25 | 28.853 | 13.543 | 8.673 | 48.207 | 없음 |
| 2024_2025 | riskrank2 | recovery_vol | 25 | 25.561 | 12.080 | 7.484 | 48.207 | 없음 |
| 2024_2025 | balanced_trend | recovery_vol | 25 | 23.061 | 10.956 | 4.531 | 48.207 | 없음 |
| 2026_ytd | rotation1 | cushion80 | 10 | 5.784 | 6.650 | 10.720 | 8.070 | 2026-02-02 |
| 2026_ytd | riskrank2 | cushion80 | 10 | 1.009 | 1.156 | 8.165 | 8.070 | 없음 |
| 2026_ytd | balanced_trend | cushion80 | 10 | -1.249 | -1.428 | 4.786 | 8.070 | 없음 |
| 2026_ytd | rotation1 | recovery_vol | 10 | 5.088 | 5.847 | 12.072 | 8.070 | 2026-01-30 |
| 2026_ytd | riskrank2 | recovery_vol | 10 | -0.295 | -0.338 | 9.113 | 8.070 | 없음 |
| 2026_ytd | balanced_trend | recovery_vol | 10 | -3.294 | -3.762 | 6.762 | 8.070 | 없음 |
| 2026_ytd | rotation1 | cushion80 | 25 | 5.544 | 6.372 | 10.831 | 8.070 | 2026-02-02 |
| 2026_ytd | riskrank2 | cushion80 | 25 | 0.361 | 0.413 | 8.427 | 8.070 | 없음 |
| 2026_ytd | balanced_trend | cushion80 | 25 | -1.452 | -1.660 | 4.879 | 8.070 | 없음 |
| 2026_ytd | rotation1 | recovery_vol | 25 | 4.849 | 5.571 | 12.187 | 8.070 | 2026-01-30 |
| 2026_ytd | riskrank2 | recovery_vol | 25 | -0.522 | -0.598 | 9.232 | 8.070 | 없음 |
| 2026_ytd | balanced_trend | recovery_vol | 25 | -3.218 | -3.675 | 6.588 | 8.070 | 없음 |
| continuous | rotation1 | cushion80 | 10 | 5.295 | 0.274 | 9.977 | 625.067 | 없음 |
| continuous | riskrank2 | cushion80 | 10 | 78.320 | 3.112 | 8.763 | 625.067 | 없음 |
| continuous | balanced_trend | cushion80 | 10 | 89.603 | 3.448 | 7.183 | 625.067 | 없음 |
| continuous | rotation1 | recovery_vol | 10 | 9.495 | 0.482 | 10.068 | 625.067 | 2010-05-20 |
| continuous | riskrank2 | recovery_vol | 10 | 113.471 | 4.099 | 9.273 | 625.067 | 없음 |
| continuous | balanced_trend | recovery_vol | 10 | 100.510 | 3.755 | 7.498 | 625.067 | 없음 |
| continuous | rotation1 | cushion80 | 25 | 2.617 | 0.137 | 9.955 | 625.067 | 없음 |
| continuous | riskrank2 | cushion80 | 25 | 43.248 | 1.922 | 9.153 | 625.067 | 없음 |
| continuous | balanced_trend | cushion80 | 25 | 72.356 | 2.926 | 7.584 | 625.067 | 없음 |
| continuous | rotation1 | recovery_vol | 25 | 8.089 | 0.413 | 10.051 | 625.067 | 2010-06-03 |
| continuous | riskrank2 | recovery_vol | 25 | 54.661 | 2.337 | 9.921 | 625.067 | 없음 |
| continuous | balanced_trend | recovery_vol | 25 | 87.683 | 3.392 | 7.797 | 625.067 | 없음 |

## 전체60회 거래 비용·회복

수수료·슬리피지 합계는USD다. 양의 월/연도 비율에는 현금 대기0%와 부분 경계 기간도 포함한다. 고점 미회복 기간은 진행 중 구간도 포함한다.

| 기간 | 전략 | 위험 정책 | 비용 | 양의 월 | 양의 연도 | 최악의 연도 | 최악의 월 | 평균 비중 | 최대 실제 비중 | 최대 목표 예산 | 보유일/평가일 | 체결수 | 총 비용USD | 미회복 최장일 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|
| 2008_2017 | rotation1 | cushion80 | 10 | 55.0% | 60.0% | -2.862 | -4.866 | 12.41% | 79.97% | 80.00% | 2331/2518 | 175 | 171.71 | 2949 |
| 2008_2017 | riskrank2 | cushion80 | 10 | 60.0% | 70.0% | -3.598 | -4.816 | 43.56% | 79.87% | 80.00% | 2427/2518 | 427 | 431.81 | 821 |
| 2008_2017 | balanced_trend | cushion80 | 10 | 57.5% | 90.0% | -3.772 | -3.571 | 40.54% | 78.97% | 80.00% | 2483/2518 | 524 | 276.94 | 901 |
| 2008_2017 | rotation1 | recovery_vol | 10 | 11.7% | 20.0% | -4.483 | -4.323 | 8.10% | 72.01% | 80.00% | 545/2518 | 58 | 132.39 | 2949 |
| 2008_2017 | riskrank2 | recovery_vol | 10 | 60.0% | 70.0% | -4.561 | -5.536 | 51.39% | 80.13% | 80.00% | 2427/2518 | 358 | 531.58 | 576 |
| 2008_2017 | balanced_trend | recovery_vol | 10 | 57.5% | 90.0% | -4.639 | -3.879 | 40.67% | 79.05% | 80.00% | 2483/2518 | 465 | 290.33 | 776 |
| 2008_2017 | rotation1 | cushion80 | 25 | 18.3% | 20.0% | -2.980 | -4.855 | 8.00% | 80.07% | 80.00% | 921/2518 | 98 | 320.99 | 2949 |
| 2008_2017 | riskrank2 | cushion80 | 25 | 59.2% | 60.0% | -4.141 | -4.535 | 40.56% | 79.96% | 80.00% | 2427/2518 | 417 | 975.63 | 1480 |
| 2008_2017 | balanced_trend | cushion80 | 25 | 56.7% | 90.0% | -4.131 | -3.570 | 39.25% | 77.80% | 80.00% | 2483/2518 | 502 | 653.13 | 949 |
| 2008_2017 | rotation1 | recovery_vol | 25 | 12.5% | 20.0% | -4.454 | -4.329 | 8.25% | 71.37% | 80.00% | 547/2518 | 59 | 328.63 | 2949 |
| 2008_2017 | riskrank2 | recovery_vol | 25 | 58.3% | 60.0% | -5.388 | -5.570 | 42.67% | 80.26% | 80.00% | 2427/2518 | 349 | 1283.67 | 1039 |
| 2008_2017 | balanced_trend | recovery_vol | 25 | 56.7% | 90.0% | -5.082 | -4.108 | 40.59% | 78.92% | 80.00% | 2483/2518 | 458 | 710.17 | 783 |
| 2018_2023 | rotation1 | cushion80 | 10 | 19.4% | 16.7% | -2.205 | -4.610 | 9.40% | 79.97% | 80.00% | 622/1509 | 57 | 69.00 | 1948 |
| 2018_2023 | riskrank2 | cushion80 | 10 | 51.4% | 33.3% | -5.797 | -4.486 | 21.60% | 77.97% | 80.00% | 1295/1509 | 177 | 125.82 | 2163 |
| 2018_2023 | balanced_trend | cushion80 | 10 | 55.6% | 83.3% | -5.438 | -3.537 | 31.14% | 76.57% | 80.00% | 1297/1509 | 245 | 152.27 | 732 |
| 2018_2023 | rotation1 | recovery_vol | 10 | 9.7% | 16.7% | -2.087 | -5.628 | 9.44% | 80.58% | 80.00% | 298/1509 | 20 | 52.39 | 1948 |
| 2018_2023 | riskrank2 | recovery_vol | 10 | 18.1% | 0.0% | -3.128 | -4.687 | 12.36% | 78.56% | 80.00% | 506/1509 | 60 | 81.97 | 2163 |
| 2018_2023 | balanced_trend | recovery_vol | 10 | 52.8% | 83.3% | -5.131 | -3.317 | 31.95% | 78.11% | 80.00% | 1297/1509 | 250 | 192.16 | 771 |
| 2018_2023 | rotation1 | cushion80 | 25 | 19.4% | 16.7% | -2.171 | -4.746 | 8.95% | 80.06% | 80.00% | 622/1509 | 56 | 163.77 | 1948 |
| 2018_2023 | riskrank2 | cushion80 | 25 | 48.6% | 33.3% | -5.681 | -4.492 | 19.63% | 78.06% | 80.00% | 1295/1509 | 173 | 280.98 | 2163 |
| 2018_2023 | balanced_trend | cushion80 | 25 | 56.9% | 50.0% | -5.670 | -3.738 | 30.02% | 73.75% | 80.00% | 1297/1509 | 245 | 360.35 | 771 |
| 2018_2023 | rotation1 | recovery_vol | 25 | 9.7% | 16.7% | -1.883 | -5.751 | 9.33% | 80.67% | 80.00% | 287/1509 | 20 | 130.21 | 1948 |
| 2018_2023 | riskrank2 | recovery_vol | 25 | 50.0% | 50.0% | -7.293 | -5.333 | 28.52% | 78.65% | 80.00% | 1295/1509 | 142 | 393.98 | 1263 |
| 2018_2023 | balanced_trend | recovery_vol | 25 | 52.8% | 83.3% | -5.497 | -3.432 | 31.68% | 78.43% | 80.00% | 1297/1509 | 244 | 458.86 | 771 |
| 2024_2025 | rotation1 | cushion80 | 10 | 62.5% | 100.0% | 7.274 | -3.048 | 52.99% | 78.50% | 80.00% | 501/502 | 63 | 133.44 | 261 |
| 2024_2025 | riskrank2 | cushion80 | 10 | 70.8% | 100.0% | 12.249 | -1.424 | 56.84% | 77.08% | 80.00% | 501/502 | 89 | 120.52 | 280 |
| 2024_2025 | balanced_trend | cushion80 | 10 | 75.0% | 100.0% | 11.044 | -1.492 | 56.18% | 73.40% | 80.00% | 501/502 | 100 | 55.21 | 152 |
| 2024_2025 | rotation1 | recovery_vol | 10 | 62.5% | 100.0% | 6.195 | -4.119 | 56.34% | 80.58% | 80.00% | 501/502 | 41 | 131.95 | 261 |
| 2024_2025 | riskrank2 | recovery_vol | 10 | 66.7% | 100.0% | 11.540 | -1.847 | 57.17% | 79.08% | 80.00% | 501/502 | 65 | 121.08 | 425 |
| 2024_2025 | balanced_trend | recovery_vol | 10 | 70.8% | 100.0% | 10.679 | -1.654 | 57.09% | 76.36% | 80.00% | 501/502 | 80 | 48.82 | 170 |
| 2024_2025 | rotation1 | cushion80 | 25 | 62.5% | 100.0% | 6.418 | -3.113 | 51.96% | 79.75% | 80.00% | 501/502 | 63 | 330.14 | 264 |
| 2024_2025 | riskrank2 | cushion80 | 25 | 70.8% | 100.0% | 11.064 | -1.624 | 55.54% | 78.37% | 80.00% | 501/502 | 87 | 291.94 | 285 |
| 2024_2025 | balanced_trend | cushion80 | 25 | 75.0% | 100.0% | 10.659 | -1.536 | 56.15% | 73.19% | 80.00% | 501/502 | 101 | 140.84 | 152 |
| 2024_2025 | rotation1 | recovery_vol | 25 | 62.5% | 100.0% | 5.185 | -4.182 | 55.15% | 81.06% | 80.00% | 501/502 | 40 | 324.41 | 285 |
| 2024_2025 | riskrank2 | recovery_vol | 25 | 66.7% | 100.0% | 10.735 | -1.670 | 56.97% | 79.90% | 80.00% | 501/502 | 66 | 307.27 | 425 |
| 2024_2025 | balanced_trend | recovery_vol | 25 | 70.8% | 100.0% | 10.425 | -1.681 | 56.47% | 75.70% | 80.00% | 501/502 | 81 | 121.05 | 174 |
| 2026_ytd | rotation1 | cushion80 | 10 | 9.1% | 100.0% | 5.784 | -1.587 | 6.41% | 77.56% | 80.00% | 19/221 | 4 | 16.04 | 293 |
| 2026_ytd | riskrank2 | cushion80 | 10 | 45.5% | 100.0% | 1.009 | -2.614 | 21.49% | 73.21% | 80.00% | 220/221 | 21 | 24.55 | 293 |
| 2026_ytd | balanced_trend | cushion80 | 10 | 45.5% | 0.0% | -1.249 | -2.465 | 30.35% | 67.79% | 80.00% | 220/221 | 31 | 24.17 | 293 |
| 2026_ytd | rotation1 | recovery_vol | 10 | 9.1% | 100.0% | 5.088 | -2.015 | 6.75% | 80.58% | 80.00% | 19/221 | 2 | 15.97 | 293 |
| 2026_ytd | riskrank2 | recovery_vol | 10 | 54.5% | 0.0% | -0.295 | -3.167 | 24.47% | 74.63% | 80.00% | 220/221 | 29 | 42.93 | 293 |
| 2026_ytd | balanced_trend | recovery_vol | 10 | 45.5% | 0.0% | -3.294 | -3.918 | 33.49% | 68.85% | 80.00% | 220/221 | 29 | 30.10 | 293 |
| 2026_ytd | rotation1 | cushion80 | 25 | 9.1% | 100.0% | 5.544 | -1.680 | 6.42% | 77.65% | 80.00% | 19/221 | 4 | 40.09 | 293 |
| 2026_ytd | riskrank2 | cushion80 | 25 | 45.5% | 100.0% | 0.361 | -2.718 | 21.39% | 73.28% | 80.00% | 220/221 | 23 | 64.16 | 293 |
| 2026_ytd | balanced_trend | cushion80 | 25 | 45.5% | 0.0% | -1.452 | -2.557 | 30.18% | 67.86% | 80.00% | 220/221 | 30 | 56.68 | 293 |
| 2026_ytd | rotation1 | recovery_vol | 25 | 9.1% | 100.0% | 4.849 | -2.133 | 6.76% | 80.66% | 80.00% | 19/221 | 2 | 39.92 | 293 |
| 2026_ytd | riskrank2 | recovery_vol | 25 | 45.5% | 0.0% | -0.522 | -3.278 | 24.12% | 74.83% | 80.00% | 220/221 | 23 | 92.10 | 293 |
| 2026_ytd | balanced_trend | recovery_vol | 25 | 45.5% | 0.0% | -3.218 | -4.013 | 32.60% | 68.92% | 80.00% | 220/221 | 25 | 63.89 | 293 |
| continuous | rotation1 | cushion80 | 10 | 35.7% | 31.6% | -2.862 | -4.866 | 7.82% | 79.97% | 80.00% | 2954/4750 | 223 | 205.80 | 6194 |
| continuous | riskrank2 | cushion80 | 10 | 57.3% | 73.7% | -6.982 | -5.499 | 38.49% | 80.16% | 80.00% | 4446/4750 | 756 | 822.63 | 1295 |
| continuous | balanced_trend | cushion80 | 10 | 58.1% | 84.2% | -5.511 | -3.673 | 38.71% | 78.97% | 80.00% | 4504/4750 | 940 | 587.30 | 919 |
| continuous | rotation1 | recovery_vol | 10 | 6.2% | 10.5% | -4.483 | -4.323 | 4.29% | 72.01% | 80.00% | 545/4750 | 58 | 132.39 | 6194 |
| continuous | riskrank2 | recovery_vol | 10 | 57.3% | 73.7% | -7.234 | -5.536 | 44.48% | 80.13% | 80.00% | 4446/4750 | 610 | 1065.26 | 1253 |
| continuous | balanced_trend | recovery_vol | 10 | 58.1% | 84.2% | -5.185 | -3.879 | 39.86% | 79.32% | 80.00% | 4504/4750 | 864 | 685.66 | 909 |
| continuous | rotation1 | cushion80 | 25 | 9.7% | 10.5% | -2.980 | -4.855 | 4.24% | 80.07% | 80.00% | 921/4750 | 98 | 320.99 | 6194 |
| continuous | riskrank2 | cushion80 | 25 | 56.4% | 63.2% | -4.141 | -4.535 | 33.37% | 79.96% | 80.00% | 4446/4750 | 694 | 1647.89 | 2357 |
| continuous | balanced_trend | cushion80 | 25 | 57.3% | 84.2% | -5.809 | -3.866 | 37.45% | 77.80% | 80.00% | 4504/4750 | 912 | 1351.67 | 949 |
| continuous | rotation1 | recovery_vol | 25 | 6.6% | 10.5% | -4.454 | -4.329 | 4.37% | 71.37% | 80.00% | 547/4750 | 59 | 328.63 | 6194 |
| continuous | riskrank2 | recovery_vol | 25 | 56.4% | 63.2% | -7.066 | -5.570 | 39.22% | 80.26% | 80.00% | 4446/4750 | 578 | 2180.32 | 1260 |
| continuous | balanced_trend | recovery_vol | 25 | 56.8% | 84.2% | -5.651 | -4.108 | 39.42% | 79.55% | 80.00% | 4504/4750 | 839 | 1599.09 | 909 |

## 연속 구간의 실제 연도별 수익

연도별 값은 연환산하지 않은 실제 관측 누적 수익이다.2026은11월17일까지다. 첫·마지막 연도는 부분 기간 가능성을 표시하며 상세 연도·월별 값은 metrics.json에 모두 보존한다.

| 전략 | 위험 정책 | 비용 | 연도 | 수익률% | 경계 연도 |
|---|---|---:|---|---:|---|
| rotation1 | cushion80 | 10 | 2008 | 6.592 | True |
| rotation1 | cushion80 | 10 | 2009 | 2.518 | False |
| rotation1 | cushion80 | 10 | 2010 | -2.862 | False |
| rotation1 | cushion80 | 10 | 2011 | -0.795 | False |
| rotation1 | cushion80 | 10 | 2012 | 0.035 | False |
| rotation1 | cushion80 | 10 | 2013 | 0.645 | False |
| rotation1 | cushion80 | 10 | 2014 | 0.581 | False |
| rotation1 | cushion80 | 10 | 2015 | -0.683 | False |
| rotation1 | cushion80 | 10 | 2016 | -0.245 | False |
| rotation1 | cushion80 | 10 | 2017 | 2.196 | False |
| rotation1 | cushion80 | 10 | 2018 | -1.176 | False |
| rotation1 | cushion80 | 10 | 2019 | -1.093 | False |
| rotation1 | cushion80 | 10 | 2020 | -0.225 | False |
| rotation1 | cushion80 | 10 | 2021 | 0.000 | False |
| rotation1 | cushion80 | 10 | 2022 | 0.000 | False |
| rotation1 | cushion80 | 10 | 2023 | 0.000 | False |
| rotation1 | cushion80 | 10 | 2024 | 0.000 | False |
| rotation1 | cushion80 | 10 | 2025 | 0.000 | False |
| rotation1 | cushion80 | 10 | 2026 | 0.000 | True |
| riskrank2 | cushion80 | 10 | 2008 | 5.184 | True |
| riskrank2 | cushion80 | 10 | 2009 | 4.975 | False |
| riskrank2 | cushion80 | 10 | 2010 | -1.705 | False |
| riskrank2 | cushion80 | 10 | 2011 | 0.886 | False |
| riskrank2 | cushion80 | 10 | 2012 | -1.398 | False |
| riskrank2 | cushion80 | 10 | 2013 | 11.036 | False |
| riskrank2 | cushion80 | 10 | 2014 | 3.717 | False |
| riskrank2 | cushion80 | 10 | 2015 | -3.598 | False |
| riskrank2 | cushion80 | 10 | 2016 | 0.716 | False |
| riskrank2 | cushion80 | 10 | 2017 | 11.053 | False |
| riskrank2 | cushion80 | 10 | 2018 | 0.444 | False |
| riskrank2 | cushion80 | 10 | 2019 | -1.404 | False |
| riskrank2 | cushion80 | 10 | 2020 | 0.620 | False |
| riskrank2 | cushion80 | 10 | 2021 | 10.293 | False |
| riskrank2 | cushion80 | 10 | 2022 | -6.982 | False |
| riskrank2 | cushion80 | 10 | 2023 | 3.379 | False |
| riskrank2 | cushion80 | 10 | 2024 | 9.146 | False |
| riskrank2 | cushion80 | 10 | 2025 | 14.450 | False |
| riskrank2 | cushion80 | 10 | 2026 | 0.751 | True |
| balanced_trend | cushion80 | 10 | 2008 | 0.610 | True |
| balanced_trend | cushion80 | 10 | 2009 | 6.407 | False |
| balanced_trend | cushion80 | 10 | 2010 | 2.684 | False |
| balanced_trend | cushion80 | 10 | 2011 | 1.575 | False |
| balanced_trend | cushion80 | 10 | 2012 | 2.706 | False |
| balanced_trend | cushion80 | 10 | 2013 | 10.392 | False |
| balanced_trend | cushion80 | 10 | 2014 | 2.966 | False |
| balanced_trend | cushion80 | 10 | 2015 | -3.772 | False |
| balanced_trend | cushion80 | 10 | 2016 | 1.129 | False |
| balanced_trend | cushion80 | 10 | 2017 | 6.977 | False |
| balanced_trend | cushion80 | 10 | 2018 | 0.795 | False |
| balanced_trend | cushion80 | 10 | 2019 | 4.033 | False |
| balanced_trend | cushion80 | 10 | 2020 | 7.309 | False |
| balanced_trend | cushion80 | 10 | 2021 | 7.971 | False |
| balanced_trend | cushion80 | 10 | 2022 | -5.511 | False |
| balanced_trend | cushion80 | 10 | 2023 | 0.541 | False |
| balanced_trend | cushion80 | 10 | 2024 | 8.707 | False |
| balanced_trend | cushion80 | 10 | 2025 | 12.034 | False |
| balanced_trend | cushion80 | 10 | 2026 | -0.606 | True |
| rotation1 | recovery_vol | 10 | 2008 | 5.966 | True |
| rotation1 | recovery_vol | 10 | 2009 | 8.180 | False |
| rotation1 | recovery_vol | 10 | 2010 | -4.483 | False |
| rotation1 | recovery_vol | 10 | 2011 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2012 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2013 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2014 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2015 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2016 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2017 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2018 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2019 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2020 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2021 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2022 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2023 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2024 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2025 | 0.000 | False |
| rotation1 | recovery_vol | 10 | 2026 | 0.000 | True |
| riskrank2 | recovery_vol | 10 | 2008 | 4.140 | True |
| riskrank2 | recovery_vol | 10 | 2009 | 8.038 | False |
| riskrank2 | recovery_vol | 10 | 2010 | -1.514 | False |
| riskrank2 | recovery_vol | 10 | 2011 | 4.330 | False |
| riskrank2 | recovery_vol | 10 | 2012 | -0.237 | False |
| riskrank2 | recovery_vol | 10 | 2013 | 15.531 | False |
| riskrank2 | recovery_vol | 10 | 2014 | 3.230 | False |
| riskrank2 | recovery_vol | 10 | 2015 | -4.561 | False |
| riskrank2 | recovery_vol | 10 | 2016 | 1.739 | False |
| riskrank2 | recovery_vol | 10 | 2017 | 16.694 | False |
| riskrank2 | recovery_vol | 10 | 2018 | 0.126 | False |
| riskrank2 | recovery_vol | 10 | 2019 | -1.760 | False |
| riskrank2 | recovery_vol | 10 | 2020 | 2.402 | False |
| riskrank2 | recovery_vol | 10 | 2021 | 12.867 | False |
| riskrank2 | recovery_vol | 10 | 2022 | -7.234 | False |
| riskrank2 | recovery_vol | 10 | 2023 | 3.050 | False |
| riskrank2 | recovery_vol | 10 | 2024 | 9.238 | False |
| riskrank2 | recovery_vol | 10 | 2025 | 13.996 | False |
| riskrank2 | recovery_vol | 10 | 2026 | 1.208 | True |
| balanced_trend | recovery_vol | 10 | 2008 | 0.386 | True |
| balanced_trend | recovery_vol | 10 | 2009 | 7.119 | False |
| balanced_trend | recovery_vol | 10 | 2010 | 4.781 | False |
| balanced_trend | recovery_vol | 10 | 2011 | 1.591 | False |
| balanced_trend | recovery_vol | 10 | 2012 | 3.036 | False |
| balanced_trend | recovery_vol | 10 | 2013 | 11.554 | False |
| balanced_trend | recovery_vol | 10 | 2014 | 3.293 | False |
| balanced_trend | recovery_vol | 10 | 2015 | -4.639 | False |
| balanced_trend | recovery_vol | 10 | 2016 | 1.283 | False |
| balanced_trend | recovery_vol | 10 | 2017 | 8.689 | False |
| balanced_trend | recovery_vol | 10 | 2018 | 1.243 | False |
| balanced_trend | recovery_vol | 10 | 2019 | 2.632 | False |
| balanced_trend | recovery_vol | 10 | 2020 | 7.624 | False |
| balanced_trend | recovery_vol | 10 | 2021 | 7.637 | False |
| balanced_trend | recovery_vol | 10 | 2022 | -5.185 | False |
| balanced_trend | recovery_vol | 10 | 2023 | 0.977 | False |
| balanced_trend | recovery_vol | 10 | 2024 | 11.331 | False |
| balanced_trend | recovery_vol | 10 | 2025 | 11.407 | False |
| balanced_trend | recovery_vol | 10 | 2026 | -1.672 | True |
| rotation1 | cushion80 | 25 | 2008 | 5.545 | True |
| rotation1 | cushion80 | 25 | 2009 | 0.947 | False |
| rotation1 | cushion80 | 25 | 2010 | -2.980 | False |
| rotation1 | cushion80 | 25 | 2011 | -0.728 | False |
| rotation1 | cushion80 | 25 | 2012 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2013 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2014 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2015 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2016 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2017 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2018 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2019 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2020 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2021 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2022 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2023 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2024 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2025 | 0.000 | False |
| rotation1 | cushion80 | 25 | 2026 | 0.000 | True |
| riskrank2 | cushion80 | 25 | 2008 | 4.412 | True |
| riskrank2 | cushion80 | 25 | 2009 | 4.050 | False |
| riskrank2 | cushion80 | 25 | 2010 | -2.298 | False |
| riskrank2 | cushion80 | 25 | 2011 | -0.204 | False |
| riskrank2 | cushion80 | 25 | 2012 | -1.829 | False |
| riskrank2 | cushion80 | 25 | 2013 | 9.282 | False |
| riskrank2 | cushion80 | 25 | 2014 | 2.894 | False |
| riskrank2 | cushion80 | 25 | 2015 | -4.141 | False |
| riskrank2 | cushion80 | 25 | 2016 | 0.159 | False |
| riskrank2 | cushion80 | 25 | 2017 | 9.310 | False |
| riskrank2 | cushion80 | 25 | 2018 | 0.043 | False |
| riskrank2 | cushion80 | 25 | 2019 | -1.769 | False |
| riskrank2 | cushion80 | 25 | 2020 | -0.686 | False |
| riskrank2 | cushion80 | 25 | 2021 | 4.005 | False |
| riskrank2 | cushion80 | 25 | 2022 | -4.140 | False |
| riskrank2 | cushion80 | 25 | 2023 | 1.387 | False |
| riskrank2 | cushion80 | 25 | 2024 | 3.462 | False |
| riskrank2 | cushion80 | 25 | 2025 | 13.231 | False |
| riskrank2 | cushion80 | 25 | 2026 | 0.995 | True |
| balanced_trend | cushion80 | 25 | 2008 | 0.329 | True |
| balanced_trend | cushion80 | 25 | 2009 | 5.956 | False |
| balanced_trend | cushion80 | 25 | 2010 | 2.041 | False |
| balanced_trend | cushion80 | 25 | 2011 | 0.976 | False |
| balanced_trend | cushion80 | 25 | 2012 | 2.403 | False |
| balanced_trend | cushion80 | 25 | 2013 | 10.027 | False |
| balanced_trend | cushion80 | 25 | 2014 | 2.449 | False |
| balanced_trend | cushion80 | 25 | 2015 | -4.131 | False |
| balanced_trend | cushion80 | 25 | 2016 | 0.774 | False |
| balanced_trend | cushion80 | 25 | 2017 | 5.681 | False |
| balanced_trend | cushion80 | 25 | 2018 | 0.198 | False |
| balanced_trend | cushion80 | 25 | 2019 | 3.572 | False |
| balanced_trend | cushion80 | 25 | 2020 | 6.700 | False |
| balanced_trend | cushion80 | 25 | 2021 | 7.220 | False |
| balanced_trend | cushion80 | 25 | 2022 | -5.809 | False |
| balanced_trend | cushion80 | 25 | 2023 | 0.260 | False |
| balanced_trend | cushion80 | 25 | 2024 | 8.090 | False |
| balanced_trend | cushion80 | 25 | 2025 | 10.862 | False |
| balanced_trend | cushion80 | 25 | 2026 | -0.622 | True |
| rotation1 | recovery_vol | 25 | 2008 | 5.112 | True |
| rotation1 | recovery_vol | 25 | 2009 | 7.626 | False |
| rotation1 | recovery_vol | 25 | 2010 | -4.454 | False |
| rotation1 | recovery_vol | 25 | 2011 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2012 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2013 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2014 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2015 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2016 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2017 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2018 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2019 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2020 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2021 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2022 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2023 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2024 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2025 | 0.000 | False |
| rotation1 | recovery_vol | 25 | 2026 | 0.000 | True |
| riskrank2 | recovery_vol | 25 | 2008 | 3.397 | True |
| riskrank2 | recovery_vol | 25 | 2009 | 7.743 | False |
| riskrank2 | recovery_vol | 25 | 2010 | -4.268 | False |
| riskrank2 | recovery_vol | 25 | 2011 | 1.665 | False |
| riskrank2 | recovery_vol | 25 | 2012 | -0.808 | False |
| riskrank2 | recovery_vol | 25 | 2013 | 7.913 | False |
| riskrank2 | recovery_vol | 25 | 2014 | 0.637 | False |
| riskrank2 | recovery_vol | 25 | 2015 | -5.388 | False |
| riskrank2 | recovery_vol | 25 | 2016 | -0.632 | False |
| riskrank2 | recovery_vol | 25 | 2017 | 5.954 | False |
| riskrank2 | recovery_vol | 25 | 2018 | -0.523 | False |
| riskrank2 | recovery_vol | 25 | 2019 | -1.597 | False |
| riskrank2 | recovery_vol | 25 | 2020 | 2.098 | False |
| riskrank2 | recovery_vol | 25 | 2021 | 12.552 | False |
| riskrank2 | recovery_vol | 25 | 2022 | -7.066 | False |
| riskrank2 | recovery_vol | 25 | 2023 | 3.051 | False |
| riskrank2 | recovery_vol | 25 | 2024 | 8.160 | False |
| riskrank2 | recovery_vol | 25 | 2025 | 13.092 | False |
| riskrank2 | recovery_vol | 25 | 2026 | 0.879 | True |
| balanced_trend | recovery_vol | 25 | 2008 | 0.062 | True |
| balanced_trend | recovery_vol | 25 | 2009 | 6.729 | False |
| balanced_trend | recovery_vol | 25 | 2010 | 4.230 | False |
| balanced_trend | recovery_vol | 25 | 2011 | 1.224 | False |
| balanced_trend | recovery_vol | 25 | 2012 | 2.411 | False |
| balanced_trend | recovery_vol | 25 | 2013 | 11.270 | False |
| balanced_trend | recovery_vol | 25 | 2014 | 2.855 | False |
| balanced_trend | recovery_vol | 25 | 2015 | -5.082 | False |
| balanced_trend | recovery_vol | 25 | 2016 | 1.116 | False |
| balanced_trend | recovery_vol | 25 | 2017 | 8.313 | False |
| balanced_trend | recovery_vol | 25 | 2018 | 0.580 | False |
| balanced_trend | recovery_vol | 25 | 2019 | 2.591 | False |
| balanced_trend | recovery_vol | 25 | 2020 | 7.278 | False |
| balanced_trend | recovery_vol | 25 | 2021 | 7.018 | False |
| balanced_trend | recovery_vol | 25 | 2022 | -5.651 | False |
| balanced_trend | recovery_vol | 25 | 2023 | 1.298 | False |
| balanced_trend | recovery_vol | 25 | 2024 | 11.122 | False |
| balanced_trend | recovery_vol | 25 | 2025 | 11.146 | False |
| balanced_trend | recovery_vol | 25 | 2026 | -2.239 | True |

## 검증과 제한

- 공통 위험 예산은 SPY20일 변동성을 사용한다. 금·국채를 포함한 실제 포트폴리오의 변동성이나 최대 손실을 직접 예측하는 모형은 아니다.
독립 감사 PASS: 일별 상태 114,000개, 거래 13,309건.

- 기존 공개 원문을 별도 정규화한 자료2006-01-03~2026-11-17로2008년부터 평가한다. 새로 확보한 미공개 홀드아웃이 아니며 과거 ETF 연구와 자료를 반복 사용했다. 유리한 결과를 얻기 위해 실행 후 모수를 조정하지 않았다.
- 분리 기간별$10,000 현금 시작, 연속 기간 고점/차단 미초기화. 사용자 기존 보유 계좌·세금·환율·현금 이자·추가입금은 제외한다.
- 매도 대금은 다음 거래일 사용하며 과거 전체에T+1 모형을 적용했다. 배당은 채권 평가하지만 지급일 자료가 없어 매수 현금으로 사용하지 않는다. 지수는 비용 차감 전·배당 재투자라 처리 차이가 있다.
- 완료 종가 판단→다음 시가 체결이며 시가 차단은 해당 시가, 종가 차단과 체결 비용 후 신규 차단 잔여분은 다음 시가 청산이다. 갭·비용·미관측 장중 저점 때문에 최대 손실10%를 보장하지 못한다.
- 시가 체결은 호가·유동성·부분 체결·지연을 재현하지 않는다. 종료 보유분은 종가 평가이며 강제 청산 비용을 추가 차감하지 않는다. 월중 HOLD에서는 실제 비중이 가격에 따라 목표보다 높아질 수도 있다.
- 비용 감소는 실제 체결 수와 비용으로 확인한다. 이전 연구와 자산·기간·전략도 달라져 전체 차이를 거래 빈도 하나의 효과라고 단정하지 않는다.
- 원자료와 지표는 PLAN.md/pre-run-lock.json/completion.json/runs/audit.json/metrics.json/decisions.json 및 개별 원장에 보존한다. 운영 채택은 별도 검토다.

상품 확인: [QQQ 발행사](https://www.invesco.com/us/en/financial-products/etfs/invesco-qqq-trust-series-1.html), [GLD 발행사](https://www.ssga.com/us/en/individual/etfs/spdr-gold-shares-gld), [IEF 발행사](https://www.ishares.com/us/products/239456/ishares-7-10-year-treasury-bond-etf). 금·국채 ETF를 연구에 포함한 것이 운영 매매 설정 변경은 아니다.
