# 로컬 상시 모의매매

실계좌 주문을 내지 않는 별도 실행 프로그램이다. 운영 서버와 DB에 연결하지 않는다. 실제 사이트 배포와 구분하며 main push는 하지 않는다. JDK21, Maven 캐시, 프론트 node_modules가 있는 현재 Windows 작업 환경을 사용한다.

## 실행

PowerShell 첫 번째 창에서:

```powershell
cd C:\Users\hyunc\personal_project\my-vue-project_backend
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\run-paper-local.ps1
```

`PAPER_APP_READY` 확인 후 두 번째 창에서:

```powershell
cd C:\Users\hyunc\personal_project\my-vue-project
node scripts/paper-local.cjs
```

브라우저 주소: **http://127.0.0.1:13120**. 가상 시작 자금을 입력해 새 세션을 만들고 **모의 운용 시작**을 누른다. 명시적으로 시작하기 전에는 거래하지 않는다. 화면을 닫아도 백엔드 터미널이 실행 중이면 60초 주기로 모의 운용한다. PC가 꺼지거나 절전 상태이면 동작하지 않는다. 자동 부팅 서비스는 설치하지 않는다.

정지 버튼은 모든 가상 체결을 멈추며 보유를 매도하지 않는다. 서버 종료는 실행 터미널 Ctrl+C. 같은 명령으로 다시 띄우면 기존 DB를 사용하며 RUNNING 세션은 스케줄러가 이어받는다. 먼저 중지한 세션은 중지 상태를 유지한다. HARD10% 차단은 재개하거나 초기화할 수 없다. 새 세션을 만들더라도 이전 기록은 보존된다.

소스 변경 없이 서버만 다시 띄울 때는 `run-paper-local.ps1 -SkipCompile`로 기존 격리 테스트 classpath를 사용할 수 있다. 처음 실행이나 코드 변경 후에는 생략하지 않는다.

## 자금·시세·비교 기준

- 기본 가상자금USD10,000. 실제 계좌의 현금·보유·키를 읽지 않는다. 신규 세션만 USD100~100,000,000 범위에서 지정할 수 있다.
- 연구용 SPY SMA200 전략. 직전 거래일의 연속 완료 종가200개로 신호를 계산하고 정규장의 유효한 현재 시세로 하루1회 목표최대80%를 가상 체결한다. 정수주·각매수비용포함가용현금99%·차입/공매도없음.
- 편도 수수료5bp+불리한슬리피지5bp. 전량체결 가정이다. 실제 주문장·부분체결·배당현금·세금·현금이자는 모형에 없다.
- 고점 대비10%에서 신규매수/재개 영구차단, 보유 가상매도. 갭과비용으로 관측손실이10%를 넘을 수 있으며 상한보장아님.
- 공개 Yahoo 시세를 사용한다. 5분보다 오래되거나 미래시각/누락/불완전/비정규장 시세이면 체결하지 않는다. 무료자료의지연/차단/429오류때문에대기할수있다. 과거시세를실시간체결처럼꾸미지않는다.
- 정규장 09:30~16:00 미국동부시간, 휴일/단축장과서머타임을반영한다. 운용달력2026~2028 밖은거래하지않는다. [NYSE 달력](https://www.nyse.com/trade/hours-calendars), [Yahoo 시세 지연 안내](https://help.yahoo.com/kb/finance/article-exchanges-data-delays-sln2310.html).
- 화면비교는 **SPY 가격수익률을 이용한 S&P500 ETF대용**, 배당미포함이다. 과거연구의 ^SP500TR 배당포함총수익지수와 같지 않다. 전략/대용벤치마크는첫유효시세부터같은기준시점으로계산한다. 수익성입증된전략이아니다.

## 데이터와 격리

- 실제시세 모드: backend `.paper-runtime/paper-local/ledger.mv.db`. 시험시세 모드: `.paper-runtime/paper-fixture/ledger.mv.db`. 빌드결과target과분리해mvn clean에도보존한다. **백업은 서버를정상종료한뒤해당폴더를복사한다.** 파일삭제는기록삭제다. DB와로컬토큰은Git에서제외한다.
- API는127.0.0.1:13220에만바인드하며런처가만든로컬토큰을요구한다. 프론트프록시가동일출처paper요청에만서버쪽에서토큰을붙인다. 토큰은브라우저번들/로그에넣지않는다. 외부공개/포트포워딩용서버가아니다.
- 일반 WebApplication을부팅하지않으며paper서비스·테이블·Repository만명시등록한다. 제품계좌·주문서비스를해석하지않는경계테스트가있다. 브로커어댑터나실계좌전환버튼이없다.
- .env와시스템Spring설정을읽지않고프로세스환경allowlist와고정H2설정을쓴다. fileDB프로세스락과세션행잠금으로중복실행/체결을방지한다.
- localhost는같은사용자컴퓨터를신뢰하는경계다. 같은OS계정의악성프로세스를막는OS샌드박스는아니다.

## 검증 모드

백엔드 `run-paper-local.ps1 -Fixture`, 프론트 `node scripts/paper-local.cjs --fixture`로 별도시험DB/합성시세를쓴다. 공개시세스케줄러는비활성이고명시적인시세갱신만처리한다. FIXTURE 표시와고정시험시각을실제운용성과로취급하지않는다. `.paper-runtime/paper-fixture/scenario` 파일의 normal/stale/failure/drop 값으로통합검증한다. 이파일은실제시세모드에영향없다.

백엔드 `test-isolated.ps1`, 프론트 `npm run test:isolated`와lint, 독립HTTP검증·재시작·브라우저검증 결과는 프론트 docs/ai-team/tasks/TEAM-14.md에기록한다. 과거연구전략이실전에서S&P500을능가한다는증거는아니다.
