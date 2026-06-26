# Backend Architecture

Spring Boot 백엔드 구조 규칙집.  
신규 기능 추가 전 반드시 읽고, 아래 규칙을 따른다.

---

## 1. 폴더 구조

```
src/main/java/com/hyunchang/webapp/
├── controller/          # HTTP 요청 진입점. 인증/인가 검사 후 Service 호출
├── service/             # 비즈니스 로직 전담
│   ├── ai/              # AI 프로바이더 인터페이스 및 구현체 (Cloudflare, Gemini, Groq)
│   └── prompt/          # 프롬프트 정의 및 변수 바인딩
├── repository/          # DB 접근 전담. JPA Repository 인터페이스
│   └── todo/            # Todo 도메인 Repository (하위 도메인 분리 예시)
├── entity/              # JPA Entity. DB 테이블과 1:1 매핑
│   └── todo/            # Todo 도메인 Entity
├── dto/                 # Controller 입출력 전용 객체. Entity 노출 금지
├── config/              # Spring 설정, Security, Filter, CORS 등
├── common/              # 프레임워크 중립 유틸 (SecurityUtil, WebUtil)
├── exception/           # 커스텀 예외 클래스 8종
└── util/                # 순수 정적 유틸리티

src/main/resources/
├── application.yml      # 공통 설정
└── application-*.yml    # 환경별 설정 (prod, dev)
```

---

## 2. 레이어별 책임 경계

### Controller
**해야 하는 것**
- HTTP 메서드/경로 매핑
- 요청 파라미터 · 바디 수신 및 DTO로 바인딩
- `@Valid` 입력 검증
- Service 호출 후 응답 DTO를 `ResponseEntity`로 반환

**여기 들어가면 안 되는 코드**
- 비즈니스 로직 (계산, 분기, 집계)
- Repository 직접 주입 및 호출
- Entity 객체를 `@RestController` 반환값으로 사용
- SQL 쿼리 문자열

---

### Service
**해야 하는 것**
- 비즈니스 규칙 구현
- 트랜잭션 경계 (`@Transactional`)
- Repository 호출 및 Entity → DTO 변환
- 외부 API 호출 (AI, 공공 데이터 등)

**여기 들어가면 안 되는 코드**
- `@RequestMapping` 등 HTTP 관련 어노테이션
- `HttpServletRequest` / `HttpServletResponse` 직접 의존
- View 렌더링 관련 코드

---

### Repository
**해야 하는 것**
- JPA `JpaRepository` 상속
- JPQL / QueryDSL / Native Query 정의
- 단순 DB 조회/저장/삭제

**여기 들어가면 안 되는 코드**
- 비즈니스 로직
- 다른 Repository 주입
- DTO 직접 반환 (Projection 제외)

---

### DTO
**해야 하는 것**
- Controller 입력(`*Request`) 또는 출력(`*Response`) 전용 필드만 포함
- `@Valid` 어노테이션으로 입력 검증 선언
- Entity와 독립적으로 버전 관리 가능한 계약 역할

**여기 들어가면 안 되는 코드**
- `@Entity`, `@Table` 등 JPA 어노테이션
- 비즈니스 로직 메서드
- 다른 도메인 Entity 참조

---

### Entity
**해야 하는 것**
- DB 테이블과 1:1 매핑
- 연관관계 매핑 (`@OneToMany`, `@ManyToOne` 등)
- 도메인 불변식 보호 메서드 (상태 변경 메서드)

**여기 들어가면 안 되는 코드**
- HTTP/응답 직렬화용 `@JsonIgnore` 남용 (DTO 전환으로 해결)
- Service 또는 Repository 주입
- Controller에서 직접 반환 (DTO 변환 필수)

---

### Config
**해야 하는 것**
- Spring Bean 설정 (`@Configuration`, `@Bean`)
- Security 설정 (`SecurityFilterChain`)
- CORS, Filter, Interceptor 등록

**여기 들어가면 안 되는 코드**
- 비즈니스 로직
- 직접적인 DB 조회

---

### Common / Util
**해야 하는 것**
- 프레임워크 의존 없는 순수 유틸 (날짜, 문자열, 파일 처리)
- 보안 컨텍스트 접근 헬퍼 (`SecurityUtil`)
- 공통 상수 정의

**여기 들어가면 안 되는 코드**
- 비즈니스 로직
- Repository / Service 주입

---

## 3. 네이밍 컨벤션

### 클래스명

| 레이어 | 접미사 | 예시 |
|--------|--------|------|
| Controller | `*Controller` | `StockController`, `AuthController` |
| Service | `*Service` | `StockAnalysisService`, `ChatHistoryService` |
| Repository | `*Repository` | `StockHoldingRepository`, `UserRepository` |
| Entity | 접미사 없음 | `StockHolding`, `User`, `Todo` |
| 요청 DTO | `*Request` | `LoginRequest`, `StockAnalysisRequest` |
| 응답 DTO | `*Response` | `AuthResponse`, `PortfolioAnalysisResponse` |
| 조회 전용 DTO | `*Dto` | `StockQuoteDto`, `StockPriceDto` |
| 예외 | `*Exception` | `UserNotFoundException`, `RateLimitException` |
| Config | `*Config` | `SecurityConfig`, `CorsConfig` |

### 메서드명

| 용도 | 패턴 | 예시 |
|------|------|------|
| 단건 조회 | `find*By*` | `findById`, `findByUsername` |
| 목록 조회 | `get*List`, `getAll*` | `getAllHoldings`, `getStockList` |
| 저장/생성 | `save*`, `create*` | `saveHolding`, `createUser` |
| 수정 | `update*` | `updateHolding`, `updateProfile` |
| 삭제 | `delete*`, `remove*` | `deleteHolding` |
| 검증 | `validate*`, `check*` | `validatePassword`, `checkDuplicate` |
| AI 분석 | `analyze*` | `analyzePortfolio`, `analyzeStock` |

### REST API URL

| 규칙 | 형식 | 예시 |
|------|------|------|
| 리소스 경로 | `/api/{domain}/{resource}` | `/api/stock/quote`, `/api/portfolio/holdings` |
| 복수형 리소스 | 명사 복수형 | `/api/todos`, `/api/subscriptions`, `/api/histories` |
| 중첩 리소스 | `/api/{parent}/{child}` | `/api/portfolio/irp/holdings`, `/api/admin/chat/sessions` |
| 구분자 | kebab-case | `/api/admin/portfolio-skills`, `/api/realestate/land/official-price` |
| ID 포함 | `/{id}` 경로 변수 | `/api/todos/{id}` |

### 패키지명

소문자, 단수형, 축약 없이.  
예: `controller`, `service`, `repository`, `entity`, `dto`, `config`, `common`, `util`, `exception`

---

## 4. 신규 기능 추가 시 파일 생성 체크리스트

```
신규 도메인 "XXX" 추가 시 생성 순서:

[ ] 1. entity/Xxx.java                    — DB 테이블 설계
[ ] 2. repository/XxxRepository.java      — JPA 인터페이스
[ ] 3. dto/XxxRequest.java                — 입력 DTO
[ ] 4. dto/XxxResponse.java               — 출력 DTO (Entity 필드를 선택적으로 노출)
[ ] 5. service/XxxService.java            — 비즈니스 로직, Entity ↔ DTO 변환
[ ] 6. controller/XxxController.java      — HTTP 엔드포인트 매핑
[ ] 7. exception/XxxNotFoundException.java — 필요 시 커스텀 예외

신규 AI 분석 기능 추가 시 추가 생성:
[ ] dto/XxxAnalysisRequest.java
[ ] dto/XxxAnalysisResponse.java          — blocked, providerName, model, analyzedAt, report, retryAt, providersStatus 필드 포함
[ ] service/XxxAnalysisService.java       — AiProviderChain 주입, AiResponseParserUtil 사용
```

**파일을 만들기 전 확인 사항**

- Entity가 Controller에 직접 반환되지 않는가? → DTO 필수
- Response DTO에 불필요한 컬럼(비밀번호, 내부 FK 등)이 포함되지 않는가?
- AI 응답 JSON 파싱 로직이 `AiResponseParserUtil`을 통하는가? (직접 복사 금지)
- 새 엔드포인트 URL이 기존 패턴(복수형, kebab-case)을 따르는가?
- 에러 핸들링이 `@RestControllerAdvice`의 글로벌 핸들러를 통하는가?

---

## 5. 프론트엔드 DTO 동기화 규칙

백엔드 DTO를 변경할 때 반드시 아래 항목을 확인한다.

```
백엔드 변경 → 프론트 확인 체크리스트:

[ ] 필드 추가   — 프론트가 해당 필드를 사용하는지 확인 (없으면 undefined 무시)
[ ] 필드 삭제   — 프론트 접근 코드 grep 후 제거 또는 대체
[ ] 필드명 변경 — 프론트 전체 grep 필수 (JavaScript는 타입 오류 없이 조용히 undefined)
[ ] 타입 변경   — Long↔String, LocalDateTime↔String 변환 방식 프론트 확인
[ ] boolean 필드 — isXxx 필드명은 Jackson 직렬화 시 xxx로 변환됨 주의
                   예: isRequired → required, showInNav → showInNav (is 없으면 그대로)
[ ] 중첩 DTO   — 구조 변경 시 프론트 파싱 코드 함께 수정
[ ] 새 엔드포인트 — 프론트 src/api/ 에 함수 추가 후 컴포넌트에서 임포트
```

**확인 방법**

```bash
# 필드명 grep (프론트 프로젝트 루트에서)
grep -r "fieldName" src/ --include="*.vue" --include="*.js"

# 엔드포인트 URL grep
grep -r "/api/xxx" src/ --include="*.vue" --include="*.js"
```

---

## 현재 예외사항 (즉시 수정 대상 아님, 추후 개선)

아래 항목은 현재 코드에서 위 규칙을 따르지 않고 있다.  
새 코드에서는 예외사항 패턴을 따르지 않는다.

### Entity 직접 반환 (DTO 미사용)

다음 컨트롤러는 Entity를 Response DTO 없이 직접 반환 중이다.  
Jackson 직렬화 시 연관관계 포함 여부와 노출 범위를 점검해야 한다.

| Controller | 반환 Entity |
|-----------|------------|
| `TodoController` | `Todo` |
| `CareerController` | `Career` |
| `DatingController` | `Dating` |
| `DiaryController` | `Diary` |
| `ExperienceController` | `Experience` |
| `HistoryController` | `History` |
| `StockHoldingController` | `StockHolding` |
| `IrpHoldingController` | `IrpHolding` |
| `IsaHoldingController` | `IsaHolding` |
| `PropertyHoldingController` | `PropertyHolding` |
| `SubscriptionController` | `Subscription` |
| `TravelController` | `TravelWishlist`, `TravelVisited`, `TravelItinerary` |
| `PortfolioSkillController` | `PortfolioSkill` |

### DTO 명명 불일치

다음 DTO는 `*Dto` 접미사를 사용하나, 위 규칙에서는 조회 전용에만 `*Dto`를 쓴다.  
현재는 혼재 상태 유지 중.

- `StockQuoteDto`, `StockPriceDto`, `StockNewsDto`, `LandQuoteDto`, `OfficialPriceDto` 등

`ChatMessage`, `StockAnalysisResult`, `RealEstateAnalysisResult` 등은 접미사 없음.

### AI 관련 클래스의 Service 레이어 배치

`service/ai/`, `service/prompt/` 하위에 있지만 `*Service` 접미사 미사용:

- `AiProvider` (interface), `AiProviderChain`, `CloudflareProvider`, `GeminiProvider`, `GroqProvider`
- `PromptDefinition`, `PromptVariable`, `RateLimitTracker`

### JSON 파싱 로직 중복

아래 3개 Service에 동일한 `JSON_FENCE` / `FIRST_OBJECT` 정규식이 복사되어 있다.  
`AiResponseParserUtil`로 추출 예정.

- `StockAnalysisService`
- `RealEstateAnalysisService`
- `PortfolioAnalysisService`

### URL 복수/단수 혼재

일부 리소스가 단수형 경로를 사용 중이다.

- `/api/diary` (단수) vs `/api/todos` (복수)
- `/api/dating` (단수) vs `/api/histories` (복수)
- `/api/realestate` (단어 붙임) vs `/api/admin/portfolio-skills` (kebab)
