# TEAM-23 절대 수익·회복력·단기 매매 비교

**판정: 잠정 통합 기준 통과0/6, 운영 채택 보류.** 목표 비중이 손실 여유만으로0에 수렴하는 문제는 해소했으나 수익 회복은 보장되지 않았다. 회복형 단기 전략의 성과는 기간에 따라 엇갈리고 비용에 민감했다. 연속 구간에서는 세 회복형 모두10% 차단에 도달했다.

검사32개와 독립 원장114,024개 상태·36,814건 거래 PASS. 기존 SPY 대조10개 동일·운영 소스749개 불변.

잠정 연환산 참고선: 비용 차감 후 5%. 사용자가 확정한 요구나 예상 수익이 아니다. S&P 500 초과는 필수 조건에서 제외했다.

운영 미적용. 기존 장중 운영 전략의 실적이 아닌 공개 일봉을 이용한 별도 가상 매매다.

## 정책과 전략

- `cushion80`: 기존 손실 여유에 비례해 비중을 줄이는 대조 정책.
- `recovery_vol`: SPY 최근20일 변동성으로 목표를20~80%로 조절하고 낙폭5%/8%에서 각각40%/20%로 제한한다. 하한은 차단 전 목표 예산에만 적용된다. 진입 신호·빈 슬롯·정수 수량 때문에 실제 비중은0일 수도 있다.
- 10% 하락을 관측하면 영구 매수 차단·청산한다. 고점 초기화와 차단 후 자동 재개는 없다.
- `spy_trend`: 기존 월별 SPY SMA200 추세 대조군.
- `sector_breakout`: 업종 ETF의20일 종가 돌파, 최대3개 분산,10일 저점 이탈 또는10개 보유 종가 후 퇴출.
- `spy_pullback`: 상승 추세 SPY의 단순 RSI2 과매도 진입,5일 평균 회복 또는5개 보유 종가 등으로 퇴출.

## 잠정 기준 판정

최근 두 구간·두 비용 모두 참고선 충족, 전체 다섯 구간·두 비용에서 낙폭10% 이내·차단/미청산 없음, 연속 구간 두 비용 모두 양의 CAGR이 필요하다.

| 전략 | 위험 정책 | 최근 수익 기준 | 전체 위험 기준 | 연속 구간 양수 | 종합 |
|---|---|---|---|---|---|
| spy_trend | cushion80 | False | True | True | False |
| sector_breakout | cushion80 | False | True | False | False |
| spy_pullback | cushion80 | False | True | False | False |
| spy_trend | recovery_vol | False | False | True | False |
| sector_breakout | recovery_vol | False | False | False | False |
| spy_pullback | recovery_vol | False | False | False | False |

## 전체60회 성과·위험

수익률과 낙폭은%. 연환산은 평가 구간 전체 달력일을 사용한다.2026년은1년 미만의 연환산 추정이며 누적 수익과 구분한다. 거래 비용은 편도 총 bps다.

| 기간 | 전략 | 위험 정책 | 비용 | 누적 | 연환산 | 최대낙폭 | 지수 누적(참고) | 차단일 |
|---|---|---|---:|---:|---:|---:|---:|---|
| 2008_2017 | spy_trend | cushion80 | 10 | 31.825 | 2.804 | 8.486 | 129.293 | 없음 |
| 2008_2017 | sector_breakout | cushion80 | 10 | -7.963 | -0.827 | 9.386 | 129.293 | 없음 |
| 2008_2017 | spy_pullback | cushion80 | 10 | 1.548 | 0.154 | 8.754 | 129.293 | 없음 |
| 2008_2017 | spy_trend | recovery_vol | 10 | 48.567 | 4.042 | 9.078 | 129.293 | 없음 |
| 2008_2017 | sector_breakout | recovery_vol | 10 | -5.565 | -0.571 | 10.062 | 129.293 | 2016-10-11 |
| 2008_2017 | spy_pullback | recovery_vol | 10 | -0.374 | -0.037 | 9.440 | 129.293 | 없음 |
| 2008_2017 | spy_trend | cushion80 | 25 | 18.331 | 1.699 | 8.824 | 129.293 | 없음 |
| 2008_2017 | sector_breakout | cushion80 | 25 | -8.989 | -0.938 | 9.843 | 129.293 | 없음 |
| 2008_2017 | spy_pullback | cushion80 | 25 | -5.080 | -0.521 | 9.779 | 129.293 | 없음 |
| 2008_2017 | spy_trend | recovery_vol | 25 | 25.560 | 2.304 | 9.575 | 129.293 | 없음 |
| 2008_2017 | sector_breakout | recovery_vol | 25 | -9.553 | -1.000 | 10.174 | 129.293 | 2011-09-02 |
| 2008_2017 | spy_pullback | recovery_vol | 25 | -6.223 | -0.641 | 10.041 | 129.293 | 2013-10-09 |
| 2018_2023 | spy_trend | cushion80 | 10 | 1.195 | 0.199 | 9.069 | 96.486 | 없음 |
| 2018_2023 | sector_breakout | cushion80 | 10 | -1.247 | -0.209 | 8.003 | 96.486 | 없음 |
| 2018_2023 | spy_pullback | cushion80 | 10 | -9.184 | -1.596 | 9.353 | 96.486 | 없음 |
| 2018_2023 | spy_trend | recovery_vol | 10 | 2.071 | 0.343 | 10.185 | 96.486 | 2023-03-13 |
| 2018_2023 | sector_breakout | recovery_vol | 10 | -1.626 | -0.273 | 9.092 | 96.486 | 없음 |
| 2018_2023 | spy_pullback | recovery_vol | 10 | -9.974 | -1.739 | 10.082 | 96.486 | 2019-08-05 |
| 2018_2023 | spy_trend | cushion80 | 25 | -4.209 | -0.716 | 9.299 | 96.486 | 없음 |
| 2018_2023 | sector_breakout | cushion80 | 25 | -5.980 | -1.024 | 9.718 | 96.486 | 없음 |
| 2018_2023 | spy_pullback | cushion80 | 25 | -9.619 | -1.675 | 9.619 | 96.486 | 없음 |
| 2018_2023 | spy_trend | recovery_vol | 25 | -5.813 | -0.995 | 10.225 | 96.486 | 2020-02-28 |
| 2018_2023 | sector_breakout | recovery_vol | 25 | -5.911 | -1.012 | 10.057 | 96.486 | 2020-11-05 |
| 2018_2023 | spy_pullback | recovery_vol | 25 | -10.203 | -1.781 | 10.203 | 96.486 | 2019-05-13 |
| 2024_2025 | spy_trend | cushion80 | 10 | 18.152 | 8.716 | 6.216 | 48.207 | 없음 |
| 2024_2025 | sector_breakout | cushion80 | 10 | -2.800 | -1.413 | 8.307 | 48.207 | 없음 |
| 2024_2025 | spy_pullback | cushion80 | 10 | 12.176 | 5.925 | 4.011 | 48.207 | 없음 |
| 2024_2025 | spy_trend | recovery_vol | 10 | 22.735 | 10.809 | 6.495 | 48.207 | 없음 |
| 2024_2025 | sector_breakout | recovery_vol | 10 | -2.513 | -1.267 | 9.051 | 48.207 | 없음 |
| 2024_2025 | spy_pullback | recovery_vol | 10 | 12.748 | 6.196 | 3.782 | 48.207 | 없음 |
| 2024_2025 | spy_trend | cushion80 | 25 | 16.196 | 7.811 | 6.077 | 48.207 | 없음 |
| 2024_2025 | sector_breakout | cushion80 | 25 | -5.524 | -2.807 | 9.156 | 48.207 | 없음 |
| 2024_2025 | spy_pullback | cushion80 | 25 | 0.960 | 0.480 | 6.055 | 48.207 | 없음 |
| 2024_2025 | spy_trend | recovery_vol | 25 | 21.933 | 10.446 | 6.562 | 48.207 | 없음 |
| 2024_2025 | sector_breakout | recovery_vol | 25 | -5.892 | -2.997 | 10.075 | 48.207 | 2025-09-05 |
| 2024_2025 | spy_pullback | recovery_vol | 25 | 1.272 | 0.635 | 6.091 | 48.207 | 없음 |
| 2026_ytd | spy_trend | cushion80 | 10 | -4.130 | -4.700 | 4.994 | 7.831 | 없음 |
| 2026_ytd | sector_breakout | cushion80 | 10 | 6.816 | 7.817 | 5.713 | 7.831 | 없음 |
| 2026_ytd | spy_pullback | cushion80 | 10 | -3.075 | -3.502 | 4.289 | 7.831 | 없음 |
| 2026_ytd | spy_trend | recovery_vol | 10 | -2.007 | -2.287 | 5.039 | 7.831 | 없음 |
| 2026_ytd | sector_breakout | recovery_vol | 10 | 5.710 | 6.543 | 7.253 | 7.831 | 없음 |
| 2026_ytd | spy_pullback | recovery_vol | 10 | -1.712 | -1.952 | 4.514 | 7.831 | 없음 |
| 2026_ytd | spy_trend | cushion80 | 25 | -4.673 | -5.316 | 5.425 | 7.831 | 없음 |
| 2026_ytd | sector_breakout | cushion80 | 25 | 3.549 | 4.061 | 6.537 | 7.831 | 없음 |
| 2026_ytd | spy_pullback | cushion80 | 25 | -6.714 | -7.627 | 6.890 | 7.831 | 없음 |
| 2026_ytd | spy_trend | recovery_vol | 25 | -2.617 | -2.982 | 5.210 | 7.831 | 없음 |
| 2026_ytd | sector_breakout | recovery_vol | 25 | 4.426 | 5.068 | 7.176 | 7.831 | 없음 |
| 2026_ytd | spy_pullback | recovery_vol | 25 | -7.023 | -7.975 | 7.198 | 7.831 | 없음 |
| continuous | spy_trend | cushion80 | 10 | 33.672 | 1.549 | 9.279 | 623.463 | 없음 |
| continuous | sector_breakout | cushion80 | 10 | -8.310 | -0.459 | 9.710 | 623.463 | 없음 |
| continuous | spy_pullback | cushion80 | 10 | -0.170 | -0.009 | 9.717 | 623.463 | 없음 |
| continuous | spy_trend | recovery_vol | 10 | 41.444 | 1.854 | 10.343 | 623.463 | 2020-03-03 |
| continuous | sector_breakout | recovery_vol | 10 | -5.565 | -0.303 | 10.062 | 623.463 | 2016-10-11 |
| continuous | spy_pullback | recovery_vol | 10 | -1.992 | -0.107 | 10.167 | 623.463 | 2018-10-11 |
| continuous | spy_trend | cushion80 | 25 | 14.252 | 0.708 | 9.397 | 623.463 | 없음 |
| continuous | sector_breakout | cushion80 | 25 | -9.000 | -0.498 | 9.857 | 623.463 | 없음 |
| continuous | spy_pullback | cushion80 | 25 | -5.080 | -0.276 | 9.779 | 623.463 | 없음 |
| continuous | spy_trend | recovery_vol | 25 | 19.625 | 0.954 | 10.042 | 623.463 | 2019-08-05 |
| continuous | sector_breakout | recovery_vol | 25 | -9.553 | -0.530 | 10.174 | 623.463 | 2011-09-02 |
| continuous | spy_pullback | recovery_vol | 25 | -6.223 | -0.340 | 10.041 | 623.463 | 2013-10-09 |

## 전체60회 운용·회복

양의 월 비율에는 수익0인 현금 대기 월도 포함한다. 첫·마지막 월은 경계 월로 표시해 부분 월 가능성을 남겼으며 제외하지 않았다. 월별 상세는 metrics.json에 있다. 고점 미회복 일수는 원장 고점 대비 종가 평가로 계산하고 진행 중 구간도 포함한다.

| 기간 | 전략 | 위험 정책 | 비용 | 양의 월 | 최악의 월 | 평균 비중 | 보유일/평가일 | 체결건수 | 최장 미회복(달력일) | 미회복 진행 중 |
|---|---|---|---:|---:|---:|---:|---|---:|---:|---|
| 2008_2017 | spy_trend | cushion80 | 10 | 46.7% | -5.553 | 33.77% | 1801/2518 | 1164 | 1186 | True |
| 2008_2017 | sector_breakout | cushion80 | 10 | 46.7% | -2.153 | 16.52% | 1865/2518 | 2958 | 3594 | True |
| 2008_2017 | spy_pullback | cushion80 | 10 | 43.3% | -5.881 | 5.41% | 533/2518 | 572 | 2801 | True |
| 2008_2017 | spy_trend | recovery_vol | 10 | 50.0% | -4.843 | 44.65% | 1801/2518 | 511 | 1128 | True |
| 2008_2017 | sector_breakout | recovery_vol | 10 | 40.8% | -3.836 | 27.58% | 1584/2518 | 2080 | 1278 | True |
| 2008_2017 | spy_pullback | recovery_vol | 10 | 46.7% | -3.526 | 7.04% | 533/2518 | 492 | 2801 | True |
| 2008_2017 | spy_trend | cushion80 | 25 | 46.7% | -5.662 | 29.21% | 1801/2518 | 1112 | 1337 | True |
| 2008_2017 | sector_breakout | cushion80 | 25 | 30.8% | -1.669 | 4.88% | 1397/2518 | 1549 | 3594 | True |
| 2008_2017 | spy_pullback | cushion80 | 25 | 24.2% | -5.324 | 2.36% | 343/2518 | 321 | 2990 | True |
| 2008_2017 | spy_trend | recovery_vol | 25 | 48.3% | -4.978 | 34.34% | 1801/2518 | 375 | 1337 | True |
| 2008_2017 | sector_breakout | recovery_vol | 25 | 15.0% | -1.647 | 5.54% | 543/2518 | 647 | 3594 | True |
| 2008_2017 | spy_pullback | recovery_vol | 25 | 18.3% | -3.762 | 3.25% | 264/2518 | 223 | 2990 | True |
| 2018_2023 | spy_trend | cushion80 | 10 | 44.4% | -5.165 | 23.79% | 1066/1509 | 376 | 1295 | True |
| 2018_2023 | sector_breakout | cushion80 | 10 | 47.2% | -3.640 | 23.93% | 1233/1509 | 1735 | 2163 | True |
| 2018_2023 | spy_pullback | cushion80 | 10 | 37.5% | -3.231 | 3.77% | 330/1509 | 299 | 2159 | True |
| 2018_2023 | spy_trend | recovery_vol | 10 | 37.5% | -6.082 | 27.63% | 884/1509 | 124 | 1245 | True |
| 2018_2023 | sector_breakout | recovery_vol | 10 | 48.6% | -3.741 | 26.80% | 1234/1509 | 1086 | 2163 | True |
| 2018_2023 | spy_pullback | recovery_vol | 10 | 8.3% | -4.214 | 2.38% | 108/1509 | 77 | 2159 | True |
| 2018_2023 | spy_trend | cushion80 | 25 | 44.4% | -4.425 | 18.10% | 1066/1509 | 306 | 2163 | True |
| 2018_2023 | sector_breakout | cushion80 | 25 | 27.8% | -1.714 | 9.67% | 1129/1509 | 1024 | 2163 | True |
| 2018_2023 | spy_pullback | cushion80 | 25 | 8.3% | -3.439 | 1.96% | 142/1509 | 129 | 2159 | True |
| 2018_2023 | spy_trend | recovery_vol | 25 | 18.1% | -4.661 | 11.30% | 426/1509 | 39 | 2163 | True |
| 2018_2023 | sector_breakout | recovery_vol | 25 | 13.9% | -1.940 | 9.24% | 581/1509 | 454 | 2163 | True |
| 2018_2023 | spy_pullback | recovery_vol | 25 | 4.2% | -4.549 | 1.97% | 84/1509 | 62 | 2159 | True |
| 2024_2025 | spy_trend | cushion80 | 10 | 62.5% | -3.086 | 57.56% | 443/502 | 186 | 299 | True |
| 2024_2025 | sector_breakout | cushion80 | 10 | 37.5% | -2.571 | 33.88% | 451/502 | 588 | 589 | True |
| 2024_2025 | spy_pullback | cushion80 | 10 | 50.0% | -2.339 | 18.01% | 133/502 | 142 | 330 | True |
| 2024_2025 | spy_trend | recovery_vol | 10 | 58.3% | -4.114 | 62.52% | 443/502 | 62 | 208 | True |
| 2024_2025 | sector_breakout | recovery_vol | 10 | 33.3% | -3.279 | 38.07% | 451/502 | 433 | 380 | True |
| 2024_2025 | spy_pullback | recovery_vol | 10 | 58.3% | -2.490 | 19.22% | 133/502 | 117 | 261 | True |
| 2024_2025 | spy_trend | cushion80 | 25 | 62.5% | -3.111 | 56.93% | 443/502 | 193 | 322 | True |
| 2024_2025 | sector_breakout | cushion80 | 25 | 25.0% | -1.947 | 27.44% | 451/502 | 536 | 589 | True |
| 2024_2025 | spy_pullback | cushion80 | 25 | 41.7% | -2.204 | 16.14% | 133/502 | 140 | 330 | True |
| 2024_2025 | spy_trend | recovery_vol | 25 | 58.3% | -4.215 | 62.57% | 443/502 | 54 | 208 | True |
| 2024_2025 | sector_breakout | recovery_vol | 25 | 29.2% | -2.747 | 30.45% | 371/502 | 351 | 589 | True |
| 2024_2025 | spy_pullback | recovery_vol | 25 | 45.8% | -2.756 | 18.42% | 133/502 | 116 | 330 | True |
| 2026_ytd | spy_trend | cushion80 | 10 | 27.3% | -2.519 | 52.23% | 192/222 | 71 | 310 | True |
| 2026_ytd | sector_breakout | cushion80 | 10 | 54.5% | -2.494 | 42.01% | 206/222 | 347 | 188 | True |
| 2026_ytd | spy_pullback | cushion80 | 10 | 45.5% | -1.728 | 20.51% | 80/222 | 84 | 313 | True |
| 2026_ytd | spy_trend | recovery_vol | 10 | 27.3% | -2.949 | 61.28% | 192/222 | 17 | 189 | True |
| 2026_ytd | sector_breakout | recovery_vol | 10 | 54.5% | -2.676 | 43.75% | 206/222 | 267 | 188 | True |
| 2026_ytd | spy_pullback | recovery_vol | 10 | 45.5% | -1.951 | 25.76% | 80/222 | 64 | 285 | True |
| 2026_ytd | spy_trend | cushion80 | 25 | 27.3% | -2.514 | 49.62% | 192/222 | 59 | 310 | True |
| 2026_ytd | sector_breakout | cushion80 | 25 | 54.5% | -2.688 | 39.50% | 206/222 | 335 | 188 | True |
| 2026_ytd | spy_pullback | cushion80 | 25 | 9.1% | -1.915 | 14.48% | 80/222 | 77 | 313 | True |
| 2026_ytd | spy_trend | recovery_vol | 25 | 27.3% | -3.209 | 61.41% | 192/222 | 19 | 189 | True |
| 2026_ytd | sector_breakout | recovery_vol | 25 | 63.6% | -3.015 | 41.31% | 206/222 | 221 | 188 | True |
| 2026_ytd | spy_pullback | recovery_vol | 25 | 18.2% | -2.838 | 16.61% | 80/222 | 67 | 313 | True |
| continuous | spy_trend | cushion80 | 10 | 45.4% | -5.553 | 30.60% | 3505/4751 | 1860 | 1787 | True |
| continuous | sector_breakout | cushion80 | 10 | 46.3% | -2.153 | 10.32% | 3667/4751 | 4090 | 6840 | True |
| continuous | spy_pullback | cushion80 | 10 | 27.8% | -5.881 | 3.04% | 675/4751 | 680 | 6047 | True |
| continuous | spy_trend | recovery_vol | 10 | 32.2% | -4.843 | 27.19% | 2229/4751 | 585 | 3218 | True |
| continuous | sector_breakout | recovery_vol | 10 | 21.6% | -3.836 | 14.61% | 1584/4751 | 2080 | 4524 | True |
| continuous | spy_pullback | recovery_vol | 10 | 26.0% | -3.526 | 3.98% | 597/4751 | 533 | 6047 | True |
| continuous | spy_trend | cushion80 | 25 | 45.8% | -5.662 | 23.47% | 3505/4751 | 1565 | 3218 | True |
| continuous | sector_breakout | cushion80 | 25 | 16.3% | -1.669 | 2.59% | 1417/4751 | 1553 | 6840 | True |
| continuous | spy_pullback | cushion80 | 25 | 12.8% | -5.324 | 1.25% | 343/4751 | 321 | 6236 | True |
| continuous | spy_trend | recovery_vol | 25 | 29.5% | -4.978 | 20.72% | 2086/4751 | 416 | 3218 | True |
| continuous | sector_breakout | recovery_vol | 25 | 7.9% | -1.647 | 2.94% | 543/4751 | 647 | 6840 | True |
| continuous | spy_pullback | recovery_vol | 25 | 9.7% | -3.762 | 1.72% | 264/4751 | 223 | 6236 | True |

## 검증과 제한

독립 감사 PASS: 일별 상태 114,024개, 거래 36,814건.

- 같은 공개 스냅샷2006-01-03~2026-11-18을 사용하며2008년부터 평가한다. 반복 검토한 역사 구간이라 신규 독립 홀드아웃이 아니다. 결과를 보고 전략 모수를 재조정하지 않았다.
- 매수는 비용 포함 결제 현금99% 한도·정수 주식, 매도 대금은 다음 거래일 사용이다. 모든 역사 구간에T+1을 적용했으며 과거T+2/T+3의 재현은 아니다.
- 각 분리 구간은$10,000 현금 시작이다. 연속 구간은 고점과 차단을 초기화하지 않는다. 기존 실계좌 보유·추가 입금·세금·환율·현금 이자는 제외했다.
- 배당은 채권 평가하되 지급일 자료 부족으로 매수 자금에 사용하지 않는다. 분할 전 명목 가격과 수량을 복원한다. 총수익 지수의 배당 재투자·비용 미차감과 차이가 있다.
- 완료 종가 신호→다음 시가 체결이다. 시가 차단은 해당 시가 청산, 종가 차단과 체결 비용 반영 후 새로 발동한 차단의 잔여 보유는 다음 시가 청산이다. 갭·비용·미관측 장중 경로 때문에 손실10%를 보장하지 못한다.
- 확보한 자료로 검증한 것은 수일 단위 거래다. 장중 초단타 호가·스프레드·부분 체결·지연은 검증하지 않았다. 종료 보유분은 종가 평가이며 강제 청산 비용을 추가 차감하지 않았다.
- PLAN.md와 pre-run-lock.json은 사전 설계·코드·입력 해시, completion.json은 결과 해시, runs/*bps.json은 전체 원장, audit.json은 독립 감사다. metrics.json/decisions.json에 모든 지표와 잠정 판정을 보존한다.
