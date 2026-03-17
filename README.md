# my-vue-project_backend (Spring Boot + MySQL)

Spring Boot와 MySQL 기반 백엔드 API입니다.

---

## 기술 스택

- **Backend**: Spring Boot 3.2.0
- **Database**: MySQL 8.0
- **ORM**: Spring Data JPA
- **Language**: Java 17
- **Security**: Spring Security + JWT
- **Build**: Maven

---

## 권한별 접근 가능한 기능

### 일반 사용자 (USER)

- **HOME** - 메인 페이지
- **TODOS** - 할일 관리 (CRUD)

### 프리미엄 사용자 (PREMIUM)

- 일반 사용자 모든 기능
- **HISTORY** - 히스토리 페이지
- **DATING** - 데이팅 페이지

### 관리자 (ADMIN)

- 프리미엄 사용자 모든 기능
- **가계부** - 가계부 관리

---

## Swagger (API 문서)

| 환경 | URL |
|------|-----|
| 로컬 | `http://localhost:3200/swagger-ui/index.html` |
| 서버 | `http://hyunchang.synology.me:3200/swagger-ui/index.html` |

**OpenAPI JSON**: `/v3/api-docs`

### Swagger에서 JWT 인증 후 API 호출

1. `POST /api/auth/login` 호출 후 응답의 **token** 값 복사
2. Swagger 우측 상단 **Authorize** → 토큰 문자열만 입력
3. 보호된 API 호출 가능

---

## 로컬 개발

### 백엔드만 실행

```bash
mvn spring-boot:run
```

### Docker Compose로 백엔드 + MySQL 실행

```bash
docker-compose up -d --build
# http://localhost:3200
```

Dockerfile은 멀티스테이지 빌드로, 로컬에 JAR 없이도 `docker-compose build` 가능합니다.

### 로그 / 재빌드

```bash
# 실시간 로그
docker-compose logs -f backend

# 완전 초기화 후 재빌드
docker-compose down --rmi all --volumes --remove-orphans
docker-compose up -d --build
```

---

## CI/CD 배포

`main` 브랜치에 **push**하면 GitHub Actions가 자동으로:

1. **Docker 이미지 빌드** — Maven 빌드 포함 멀티스테이지 Dockerfile로 이미지 생성
2. **GHCR 푸시** — `ghcr.io/parkhyunchang/my-vue-project_backend:latest`
3. **설정 파일 배포** — `docker-compose.yml`, `init.sql` NAS로 자동 전송
4. **NAS 배포** — SSH 접속 후 `docker-compose pull backend` → `docker-compose up -d --no-deps backend`

### GitHub Secrets (레포 Settings → Secrets and variables → Actions)

| Secret | 설명 |
|--------|------|
| `NAS_HOST` | NAS IP |
| `NAS_USER` | NAS SSH 사용자명 |
| `NAS_SSH_PASSWORD` | NAS SSH 비밀번호 |
| `GHCR_PAT` | GitHub PAT (`read:packages`) — NAS에서 이미지 pull용 |

### NAS 사전 준비 (최초 1회)

1. 디렉토리 생성: `/volume1/docker/my-vue-project_backend`
2. `.env` 파일 생성 (동일 경로):

   ```env
   MYSQL_ROOT_PASSWORD=비밀번호
   MYSQL_DATABASE=vue_personal_project_db
   MYSQL_USER=DB유저
   MYSQL_PASSWORD=DB비밀번호
   ```

이후에는 push만 하면 자동 배포됩니다.

### 배포 정보

| 항목 | 값 |
|------|-----|
| 컨테이너명 | `vue_personal_project-backend` |
| NAS 포트 | `3200` |
| 접속 URL | `http://hyunchang.synology.me:3200` |

### 수동 재시작 (NAS에서)

```bash
cd /volume1/docker/my-vue-project_backend
docker-compose pull backend
docker-compose up -d --no-deps backend
```

---

## 로그 (서버)

서버의 `/volume1/docker/my-vue-project_backend/logs/` 폴더에 날짜별 로그 파일이 자동 저장됩니다.

| 항목 | 값 |
|------|-----|
| 파일명 형식 | `application-YYYY-MM-DD.i.log` |
| 파일당 최대 크기 | 100MB (초과 시 자동 롤링) |
| 보관 기간 | 30일 |
| 전체 최대 크기 | 3GB |

```bash
# 현재 로그 실시간 확인
tail -f /volume1/docker/my-vue-project_backend/logs/application.log

# 에러 로그 검색
grep "ERROR" /volume1/docker/my-vue-project_backend/logs/application.log
```

---

## DB 접속 (서버)

```bash
docker exec -it vue_personal_project-backend-db mysql -u hyunchang88 -p vue_personal_project_db
```
