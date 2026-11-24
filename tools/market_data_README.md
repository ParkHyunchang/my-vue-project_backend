# 고정 실제 시장자료 스냅샷

공개 Yahoo Finance 일봉을 키·계좌·로그인·유료가입 없이 내려받았다. 원본 JSON, 개별 CSV와 SHA256은 `market_data/manifest.json`에 기록한다. 이 자료는 합성값이 아니지만, 제공업체의 사후 수정 여부나 거래소 원장과의 일치까지 감사한 것은 아니다.

## 표본과 기간 — 결과 계산 전 고정

- 비교/시장신호: SPY, QQQ.
- 종목: AAPL, MSFT, AMZN, GOOGL, NVDA, META, JPM, XOM, JNJ, PG. 현재 살아 있는 대형주 예시 10개를 고정했다. 과거 S&P 500 전체 구성종목도, 당시 거래대금 상위50도 아니며 생존·선택편향이 있다.
- 원본기간 2017-10-02~2025-12-31, 종목마다 2,074거래일. 첫 부분은 51거래일 신호 준비용이다.
- 가상 초기자금 USD10,000, 실제 계좌나 기존보유는 반영하지 않는다. 본 평가 2018~2025, 별도 초기화 시나리오 2024~2025. 후자는 과거에 잠가둔 holdout이나 독립적인 OOS 실증이 아니다.
- 현재 코드의 기본 전략 값을 고정하여 평가한다. 결과를 보고 종목·기간·설정을 튜닝하지 않는다.

## 출처와 비교대상

최종 비교에는 추가 확보한 [Yahoo Finance S&P 500 (TR), ^SP500TR](https://finance.yahoo.com/quote/%5ESP500TR/history/)의 총수익 지수 일별 수준을 우선 사용한다. `SP500TR.raw.json`, `SP500TR.csv`, `SP500TR.manifest.json`에 별도로 저장했으며 2,074개 날짜가 최초 표본과 정확히 일치한다. 이 자료는 Yahoo가 배포하는 지수 시계열이며 S&P DJI의 유료 원장과 독립 대조하지는 않았다. 총수익이 이미 포함된 `quote.close`를 사용하고 배당을 다시 더하지 않는다. 기존 `daily.csv`·신호·전략 원장은 바꾸지 않는다.

[Yahoo Finance SPY Historical Data](https://finance.yahoo.com/quote/SPY/history/)는 Adj Close가 분할·배당/자본이득 분배 조정값임을 설명한다. 다운로드 URL은 각 manifest 항목에 남겼다. Yahoo 공개 chart endpoint는 계약된 장기 안정 API가 아니므로 재다운로드가 언제나 가능하다고 가정하지 않는다.

[State Street SPY 공식 상품 설명](https://www.ssga.com/us/en/individual/etfs/state-street-spdr-sp-500-etf-trust-spy)에 따르면 SPY는 비용 차감 전 S&P 500 가격·배당 성과를 추종하는 ETF다. 여기의 비교값은 **SPY 조정종가 기반 배당포함 총수익 대용값**이며, 공식 S&P 500 총수익 지수 자체가 아니다. ETF 추적오차·비용과 공급자의 조정 방식 차이가 포함된다.

기존 replay 보고서의 benchmark는 SPY 보조 비교를 유지한다. 각 `replay-*-sp500tr.json`은 동일 전략원장을 변경 없이 실제 TR 지수에 비교한 최종 비교 파일이다. `market_index_fetch.py`는 별도 지수 다운로드, `market_index_compare.py <replay-prefix...>`는 해시를 검증한 뒤 로컬 지수 비교만 실행한다. `market_replay_audit.py <replay-prefix...>`는 재생 코드나 기존 수익계산기를 import하지 않고 매일의 현금·보유·비용·평가액과 수익률을 독립적으로 다시 계산한다. 현재 8개 시나리오 모두 원장/성과 검산이 통과했다.

## 파일과 재현

```powershell
# 다운로드: 외부접속. 기존 스냅샷이 있으면 덮어쓰지 않고 종료한다.
python -B tools/market_fetch.py
# 이후 준비/재생은 로컬파일만 사용한다.
python -B tools/market_prepare.py
# 별도 검사기로 원본 JSON과 재생 CSV 전체 대조
python -B tools/market_audit.py
```

- `{symbol}.raw.json`: 제공업체 응답 원문, OHLC/조정종가/배당·분할 이벤트.
- `{symbol}.csv`: `open/high/low/close`는 제공업체 quote값, `adj_close`는 제공업체 조정값. `adjustment_factor=adj_close/close`로 같은 행 OHLC를 조정한 `adj_open/high/low`도 보존한다. quote값이 거래 당시 분할 전 가격이라는 뜻은 아니다.
- `daily.csv`: 재생용 `date,symbol,open,high,low,close,volume,raw_open,raw_high,raw_low,raw_close,adjustment_factor`. **앞의 OHLC는 모두 조정값**이다. `raw_*`는 제공업체 quote값을 뜻한다. Volume은 제공업체 값 그대로다.
- `prepared.json`: 원본 manifest와 재생 CSV의 SHA256/행 수.
- `reports/market-data-audit.json`: 별도 원본→재생 대조 결과. 24,888행 조정 OHLC·volume·파일해시·날짜·가격 범위 검사를 통과했다. 독립 거래소 휴일달력 및 타 공급자 가격 대조는 하지 않았다.
- 날짜는 제공된 미국 일봉 타임스탬프의 UTC 날짜로 추출했다(해당 표본의 미국 정규장 일봉은 ET와 동일 날짜). 데이터 누락·비정상가격·OHLC 불일치·중복/역순·종목간 날짜불일치는 중단하며 임의 보간하지 않는다.

사후 배당/분할 조정 OHLC는 연구용 연속가격이며 실제 그날 체결 가능한 달러가격과 다를 수 있다. 소수점 단위 재생은 실전의 정수주·수량 제한·배당현금 처리를 완전히 재현하지 않는다.

## 이 자료로 확인할 수 없는 부분

과거 시점의 지수 구성종목(상장폐지 포함), 매매대금 상위50, 장중 bid/ask와 순서, 동시간대 RVOL, 당시 알려진 재무정보가 없다. 따라서 현재 전략 전체의 정확한 백테스트는 불가능하다. 완료 일봉 기반 추세·상대강도·돌파 신호와 가정한 다음날 체결을 사용하는 **제한적 일봉 신호 재생**만 가능하다. 당일 종가를 호가 대용으로 쓰면 실전 장중 돌파와 다른 전략 실행이다. 검증 보고서에는 비용 가정, 체결시점, 손절 순서, 초기화와 이러한 누락을 함께 표시해야 한다.

SPY보다 높은 결과라도 이 표본의 제한적 재생 결과일 뿐 실제 전략의 우월성 입증이 아니다. 반대로 기준 미달 결과도 숨기지 않는다. 원본자료와 재생코드를 고정한 상태의 결과만 보고하며 실제 주문·계좌 조회는 실행하지 않는다.
