# 운영 연결 없는 백엔드 테스트

Windows PowerShell에서 프로젝트 폴더로 이동한 뒤 실행한다. JDK 21과 Maven, 기존 로컬 Maven 의존성 캐시가 필요하다.

```powershell
.\test-isolated.ps1
# 테스트 + 패키징 + Spotless 검사까지:
.\test-isolated.ps1 -Verify
```

`JAVA_HOME`은 JDK 21 설치 폴더를 가리켜야 한다. 스크립트는 다른 Java 버전에서 테스트를 시작하지 않는다. `.env`, NAS, MySQL 서버, 증권사 키, AI API 키가 필요 없다. `run-local.ps1`이나 `mvn spring-boot:run`은 이 테스트의 준비 단계가 아니다.

## 어떤 환경인가

- 기존 mock 중심 테스트 전체와 실제 `User`/`UserRepository`의 H2 메모리 DB CRUD 테스트를 실행한다.
- H2 JDBC URL은 테스트 코드가 `jdbc:h2:mem:team01_<UUID>`로 직접 지정한다. 환경변수나 application.yml로 연결 대상을 바꾸지 않으며 종료 시 폐기한다.
- H2 fixture는 `User` 하나만 Hibernate에 명시 등록한다. WebApplication, component scan, Spring Boot 초기화 runner, 자동매매와 스케줄러를 시작하지 않는다.
- HTTP/WS 테스트는 기존 WebClient exchangeFunction과 모의 connector를 사용한다. 실제 외부 응답을 녹화하거나 운영 데이터로 fixture를 만들지 않는다.
- Maven `isolated-tests` 프로필이 테스트 JVM 시작 시 Byte Buddy agent를 연결해 Mockito의 실행 중 attach를 피한다. static/final mock을 유지한다.
- 같은 테스트 JVM의 `TestNetworkGuard`는 표준 Java 네트워크 연결·DNS 조회·listen/accept·multicast와 자식 프로세스, `.env*` 읽기를 거부한다. localhost도 허용하지 않는다. guard 제거도 거부한다.
- 실행 스크립트는 상속 환경을 시스템 실행 변수 allowlist로 제한하고 종료 시 복구한다. 운영 DB/API 키, SPRING_APPLICATION_JSON, JAVA_TOOL_OPTIONS 등의 값은 테스트 자식에 넘기지 않는다.

테스트 guard는 `src/test`에만 있으므로 제품 JAR에 포함되지 않는다. 네트워크 native transport는 테스트 프로필에서 비활성화한다. 운영 설정과 배포 워크플로는 변경하지 않는다.

## 제한

이 환경은 신뢰된 테스트 코드의 실수 방지용이며 악성 코드용 OS 샌드박스가 아니다. JNI 등 직접 native 호출은 별도 검토가 필요하다. Java SecurityManager는 폐기 예정 API이므로 이 진입점은 **JDK 21 전용**이다. JDK 업그레이드 시 guard를 끄지 말고 컨테이너 등 검증된 차단 방식으로 교체해야 한다. 관련 경고는 현재 예상되는 출력이다.

H2 검증은 MySQL 문법·마이그레이션 전체 호환성, 실제 HTTP 인증/인가, 전체 앱 부팅, 브라우저 E2E 또는 운영 검증을 대신하지 않는다. MySQL 전용 DDL을 가진 모든 Entity를 자동 스캔하지 않는다.

`mvn -o`는 의존성 다운로드 차단이다. 테스트의 실제 네트워크 차단은 test JVM guard가 담당한다. Maven 프로세스/플러그인 자체가 OS 수준으로 네트워크 격리됐다는 의미가 아니다. 필요한 의존성이 캐시에 없으면 실행은 실패한다. 의존성 준비는 별도 단계로 관리하며 이 스크립트는 자동 다운로드나 자동 패키지 설치를 하지 않는다.

## 결과와 문제 해결

- `[passed]`는 Maven 종료 코드 0일 때만 표시한다. 결과 수와 skipped 수는 `target/surefire-reports/TEST-*.xml` 및 이번 실행 로그에서 확인한다.
- `[failed]`는 테스트·컴파일·스타일·의존성 실패다. `[incomplete]`는 실행 준비/환경 오류이며 통과가 아니다.
- 테스트 fork에는 180초 제한이 있다. 컴파일과 Maven 준비 단계는 이 제한에 포함되지 않는다. 중단 시 해당 터미널에서 Ctrl+C를 사용하며 다른 Java 프로세스를 일괄 종료하지 않는다.
- `TEST_ISOLATION` 실패는 실제 네트워크 또는 비밀 파일 접근 시도다. 차단을 풀지 말고 해당 요청에 명시적인 대역을 사용한다.
- Mockito attach 오류가 재발하면 이 전용 프로필로 실행했는지와 Byte Buddy agent 파일의 캐시 존재를 확인한다. 기본 `mvn test`는 같은 격리를 보장하지 않는다.
- 일부 테스트만 성공한 로그나 이전 실행의 XML로 전체 통과를 판단하지 않는다. 테스트 제외/skip으로 실패를 숨기지 않는다.

직접 Maven을 사용하는 비-Windows 환경에서도 JDK 21에서 `mvn -o -Pisolated-tests test`로 같은 test JVM guard를 사용할 수 있다. 다만 이 직접 명령에는 PowerShell 진입점의 환경변수 allowlist가 적용되지 않는다.

## 설계 근거

- [Java 21 SecurityManager 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/SecurityManager.html): JVM 시작 시 사용자 정의 guard 설치.
- [Mockito instrumentation 안내](https://github.com/mockito/mockito/blob/main/mockito-core/src/main/java/org/mockito/Mockito.java): inline mock용 JVM agent. 이 프로젝트는 캐시에 있는 Byte Buddy agent와 실제 실행으로 호환성을 검증한다.

## 실제 브라우저용 격리 앱

일반 테스트와 별도로, Windows/JDK 21에서 브라우저와 실제 HTTP 인증·할 일 흐름을 확인할 수 있다. 이것은 `run-local.ps1`이나 운영 `WebApplication.main`을 실행하는 명령이 아니다.

```powershell
# 앱을 띄우기 전에 outbound 차단·localhost bind 허용을 독립 확인:
.\run-browser-isolated.ps1 -GuardOnly
# HTTP 서버 실행(종료는 같은 터미널에서 Ctrl+C):
.\run-browser-isolated.ps1
```

`BROWSER_GUARD_OK` 후 `BROWSER_APP_READY`가 나와야 준비 완료다. 주소는 `http://127.0.0.1:13200`이고, 프론트의 별도 격리 개발 서버 `http://127.0.0.1:13100`에서 같은 출처 프록시로 사용한다. 매 실행 시 새 메모리 DB에 `browser-user` / `BrowserTest123!` 일반 USER 계정을 생성한다. 이 값은 이 테스트에서만 사용하는 공개 fixture다. 회원가입으로 별도 테스트 사용자도 만들 수 있다.

### 실제 실행하는 부분

- Spring Boot, Tomcat, 실제 Controller/Service/Repository와 전체 JPA Entity를 로드한다. 인증 필터, JWT 쿠키, CSRF 헤더 검사, 메뉴 권한과 회원가입·로그인·할 일 CRUD는 제품 코드를 그대로 사용한다.
- 기존 메뉴/역할·AI 프롬프트 초기화 runner는 메모리 DB에 기본값을 시드한다.
- `BrowserApplication`은 제품 component 패키지를 스캔하되 `WebApplication`의 `@EnableScheduling`을 가져오지 않는다. 시작 시 scheduling postprocessor가 없는지, 거래 설정이 비활성인지, 실제 JDBC URL이 지정한 H2 메모리 DB인지 검사한다.

### 테스트에서 바꾸는 부분

- 아래 8개 외부 경계 서비스는 Mockito 대역이다. 기본 빈 결과일 수 있으며 이 화면을 검증했다고 외부 서비스 정상 응답을 입증한 것은 아니다: `StockService`, `ClaudeService`, `KrxOpenApiService`, `LandPriceService`, `RealEstateApiService`, `RealEstateNewsService`, `TravelGeocodeService`, `SajuCalendarClient`.
- `StockService`의 부팅 시 외부 시세 수집을 제거한다. 나머지 대역에는 Windows JDK HttpClient 생성 단계에서 내부 selector 소켓을 여는 서비스들이 포함된다. guard 허용 범위를 넓혀 우회하지 않는다.
- WebClient 자동 연결 설정 대신 외부 요청을 실패시키는 메모리 exchangeFunction을 주입한다. RestTemplate 외부 요청도 실패시킨다. 그 밖의 직접 HTTP/WS 호출은 JVM 네트워크 guard가 차단한다.
- 전체 Spring scheduler를 등록하지 않는다. 거래 키는 빈 값이고 국내/미국 주문·전략 설정은 false다. 시작 이벤트의 토큰 발급/거래 복구 조건은 비활성 설정으로 통과하지 못한다.
- `ExpenseTableDropMigration`, `TimelineDescriptionMigration`은 제외한다. 운영 DB 마이그레이션의 검증이 아니다.
- H2 MySQL 모드를 사용하며 `browser/orm.xml`에서 Todo.description의 MySQL 전용 charset/collation DDL만 `TEXT`로 바꾼다. Entity 필드와 API는 유지된다. 이것은 실제 MySQL 문법·collation·트랜잭션 호환성 검사가 아니다.
- Tomcat은 Windows 내부 selector 소켓을 피하도록 NIO2를 사용한다. 운영 Tomcat 커넥터 설정 검증이 아니다.

### 격리와 종료

- 프로세스 환경은 실행 변수 allowlist만 남긴다. Spring의 systemEnvironment/systemProperties도 설정 소스에서 제거하고 test classpath의 `browser/application-browser.properties`만 읽는다. 추가 앱 인자는 허용하지 않는다.
- JVM 시작부터 `BrowserNetworkGuard`를 설치한다. 모든 outgoing socket과 외부 DNS, 자식 프로세스, `.env*` 읽기, guard 해제를 거부한다. loopback 이름 해석과 `13200` listen, loopback client accept만 허용하며 서버 주소는 `127.0.0.1`에 고정한다.
- 실행마다 `target/browser-runtime-<UUID>/`를 작업 폴더로 만들며 업로드·임시 캐시도 그 아래에 둔다. 메모리 DB는 프로세스 종료 시 사라진다. 파일은 증거 보존을 위해 자동 삭제하지 않는다.
- 서버는 포그라운드로 실행한다. 종료는 해당 터미널 Ctrl+C를 사용한다. 다른 Java 프로세스를 일괄 종료하지 않는다. 자동화 도구에서는 시작 로그의 PID가 이 harness인지 확인한 후 그 PID만 종료한다.
- Codex 샌드박스가 inbound 연결을 차단하는 환경에서는 같은 스크립트와 JVM guard를 유지한 채 허용된 실행 권한으로 시작해야 브라우저가 접근할 수 있다. OS 방화벽 규칙을 변경하거나 guard를 끄지 않는다.
- 이 harness도 신뢰된 테스트 코드용 실수 방지 장치다. 운영 실행, 상시 서비스, 실거래·실제 AI 응답·운영 데이터 검증을 대체하지 않는다.
