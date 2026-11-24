# TEAM-18 시점별 시장자료 확보 조사

조사일: 2026-11-19. 담당: 기획·자료원 조사. 공식 공급자 문서와 현재 코드 대조다. 아래 상품 소개 확인은 실제 자료 확보·구독 권한 확인·성과 검증을 뜻하지 않는다. 계좌/비밀파일 접근, 가입·구매·운영 변경은 하지 않았다.

## 결론

공개 SEC 재무 공시와 기존 일봉은 일부 입력을 보완할 수 있다. 그러나 이 자료만으로 키움 당시 거래대금 상위 50위, 해당 시각의 호가, Yahoo 재무값과 캐시 상태, 당시 지수 편입 결과를 모두 복원할 수는 없다. 유료 자료가 존재하므로 “과거자료 자체가 없다”는 결론도 부정확하다. 실제 접근 권한과 원본 보관 이력을 확보한 뒤 생산 서비스 입력과의 동등성을 검사해야 한다.

`KiwoomUsFundamentalService`는 Yahoo의 forwardPE/대체 PE와 ROE를 읽고 정상값 1일, 오래된 정상값 최대 7일의 캐시를 사용한다. `KiwoomUsIndexUniverseService`는 Wikipedia의 S&P 500/NASDAQ-100 표를 읽어 캐시한다. 실제 지수 구성 이력과 당시 앱이 보았던 웹페이지/캐시는 다를 수 있다. 현재 구성종목을 과거 전체 기간에 적용하거나 현재 재무값을 과거값으로 넣으면 안 된다.

## 공식 자료원과 사용 가능 범위

| 필요 자료 | 공식 근거 | 접근 및 범위 | 아직 해결하지 못하는 부분 |
|---|---|---|---|
| 제출 시점이 있는 재무 공시 | [SEC EDGAR API](https://www.sec.gov/search-filings/edgar-application-programming-interfaces), [Developer Resources](https://www.sec.gov/about/developer-resources) | data.sec.gov의 submissions/companyfacts는 인증·API 키 없이 제공. JSON과 bulk 자료 지원. accession과 공시 시각을 연결하여 결정 당시 공개된 공시만 선별할 수 있음 | SEC 회계 facts는 Yahoo 애널리스트 예상이익 기반 forward PE와 동일하지 않음. 수정공시를 원래 시점으로 소급하면 안 됨. Yahoo 조회 실패/캐시 나이는 제공하지 않음 |
| 미국 주식 과거 호가·체결 | [Alpaca Market Data](https://docs.alpaca.markets/us/docs/about-market-data-api) | 공식 표상 Basic은 무료, 역사자료 2016년 이후, 최근 15분 제한·200요청/분. 주식 데이터 엔드포인트는 key/secret 인증 필요. 실시간 Basic IEX와 과거 SIP 권한을 혼동하지 말고 실제 계정 권한을 확인해야 함 | 키움 순위 응답과 일치 여부, 전체 거래소/상장폐지 모집단, 데이터 정정·거래조건 처리는 별도 검증. 현재 작업에서 계정을 연결하거나 권한을 시험하지 않음 |
| 전 미국 시장 통합 체결·호가·NBBO | [NYSE Daily TAQ](https://www.nyse.com/data-products/catalog/daily-taq) | 공식 상품은 미국 통합시장 체결/호가, NBBO, master와 관리 메시지를 제공하며 1993년부터의 history를 안내. 구매 경로와 sample 제공 | 전량 무료 공개자료가 아님. 자료만으로 키움 랭킹의 시간·필터 정의가 증명되지 않음. Sample은 장기 수익률 자료가 아님 |
| 이벤트 시각이 있는 거래소별 틱 자료 | [Databento Historical API](https://databento.com/docs/api-historical), [Pricing](https://databento.com/pricing/) | 인증된 역사 API, 데이터셋/스키마/기간별 조회와 비용 추정 지원. 유료 사용량과 거래소 라이선스 확인 필요 | 단일 거래소 feed를 통합시장으로 취급할 수 없음. 거래소별 데이터와 통합시장의 범위 및 순위 재현 검증 필요. 비용 승인 없이 다운로드 요청하지 않음 |
| S&P 500 구성종목·편입/제외 역사와 지수 | [S&P DJI SPICE](https://www.spglobal.com/spdji/en/landing/topic/spice/) | 구독 기반. 공식 설명상 S&P 500 구성종목 1964년부터, adds/drops 1989년부터. 데이터 종류·수익률 종류와 라이선스 확인 필요 | 실제 앱의 Wikipedia 업데이트 지연/캐시를 복원하지 않음. 지수 총수익과 가격수익을 명시적으로 구분해야 함 |
| Nasdaq 역사 지수 구성 | [Nasdaq GIW](https://www.nasdaq.com/products/global-indexes/data/giw) | 구독자에게 과거/현재 지수 구성·비중·기업행사 제공. 필요한 NDX 기간/필드 권한은 별도 확인 | 당시 앱이 실제 반환한 편입 판정과 공급자 구성 이력의 동일성 미확인 |
| S&P 500·NASDAQ-100 역사 구성과 상장폐지 종목 | [Norgate content tables](https://norgatedata.com/data-content-tables.php), [Data FAQ](https://norgatedata.com/data-package-faq.php) | 공식 표에 미국 역사 구성은 Platinum/Diamond, Nasdaq-100은 1990년부터 표시. 지원 Python/플랫폼 연동 존재 | 제공 표의 fundamentals는 현재 단일값으로 분류된다. 구독만으로 과거 Yahoo PE/ROE나 intraday 호가까지 충족한다고 말할 수 없음 |
| Yahoo forward P/E 역사 | [Yahoo plans](https://finance.yahoo.com/about/plans/compare/), [Yahoo historical data help](https://help.yahoo.com/kb/SLN2311.html) | 공식 검색 색인에서 구독 상품의 forward P/E history CSV export 안내를 확인. 원문 직접 열기는 HTTP 429라 상품 세부 전체 확인은 미완료. 일반 historical help는 가격·배당·분할 자료 설명 | forward PE 역사 자체가 없다고 단정하지 않음. 값의 관측 빈도·수정 이력/PIT vintage·ROE·앱 캐시/오류 이력은 확인 필요. 현재값 API 응답을 역사자료로 전용하지 않음 |

## 실제 서비스 성과 평가에 필요한 연결

1. 자료 기간·초기 현금·기존 보유·설정 버전과 결정/감시 주기를 사전 고정한다. 실제 계좌를 읽지 않는 연구라면 그 초기 조건이 가정임을 명시한다.
2. 전체 시장 tick archive에서 순위를 재구성할 때 거래소·거래조건·정정·거래대금 계산·세션과 키움 응답의 동등성을 검증한다. 상위 50 종목만 미리 골라 내려받으면 순위 모집단 증명이 되지 않는다.
3. 공개시각 이전 재무정보와 실제 또는 명시적으로 모델링한 캐시 정책을 제공한다. 기록이 없다는 이유로 `REQUEST_FAILED`를 넣어 필터를 우회하지 않는다.
4. 서비스가 낸 주문으로 현금·미체결·체결·수수료·보유를 갱신하는 별도의 결정론적 원장이 필요하다. 미리 적은 성공 체결 응답은 수익성 검증이 아니다. 체결은 관측자료와 지연/수량/스프레드 가정으로 처리하고 그 가정을 보고한다.
5. 동일 시각과 통화의 현금 포함 계좌 총수익을 S&P 500 총수익과 비교한다. SPY는 지수 그 자체가 아니므로 ETF 대용 시 명시한다. 현금 한도와 10% 고점 대비 차단을 완화하지 않고 실제 관측 최대낙폭도 별도 보고한다.

공급자 상품을 확보해도 당시에 실행하지 않은 주문의 실제 체결을 알 수는 없다. 그 부분은 명시적 체결 모형의 역사적 시뮬레이션이며 실거래 실적과 구별한다. 이미 기록된 앱 입력이 없다면 “과거 운영 응답 완전 복원” 대신 동등성을 검토한 입력 가정의 재생으로 범위를 제한해야 한다.

## 인수인계

- 공개 자료 확보부터 진행하되 위 자료표의 충족/부분/미확보를 각각 기록한다. HTTP 실패는 빈 데이터나 0% 수익으로 치환하지 않는다.
- 유료 가입·비용 발생 요청 및 사용자의 다른 서비스 자격증명은 현재 권한으로 임의 확보하지 않는다. 필요한 자료 범위와 실제 견적/접근 방법을 구체화한 후 결정한다.
- 키움 계좌 연동은 다른 공급자의 역사자료 권한과 별개다. 키움 인증 토큰이나 계좌 키를 시장자료 업체에 전송하지 않는다.
- 본 조사에서는 다운로드 원본을 생성하지 않았다. 실제 확보 목록과 SHA는 총괄의 수집 manifest를 기준으로 한다.
