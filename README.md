# my-vue-project_backend

Spring Boot + MySQL 기반 백엔드 API 서버입니다.

---

## 기술 스택

| 항목 | 버전/기술 |
|------|----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.0 |
| Database | MySQL 8.0 + Spring Data JPA |
| Security | Spring Security + JWT |
| Build | Maven |

---

## 로컬 실행

### 실행

```powershell
.\run-local.ps1
```

`.env` 파일의 환경변수를 읽어 Spring Boot를 실행합니다.

### .env 설정

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DB_HOST` | `NAS_SERVER_IP` | DB 호스트 (로컬 DB 사용 시 → `localhost`) |
| `UPLOAD_BASE_URL` | `http://NAS_SERVER_IP:3200` | 사진 리다이렉트 URL (로컬 사진 사용 시 → 빈값) |

**서버 리소스 사용 (기본)** — 로컬에서 실행해도 서버 DB와 사진을 그대로 봅니다.
```env
DB_HOST=NAS_SERVER_IP
UPLOAD_BASE_URL=http://NAS_SERVER_IP:3200
```

**로컬 리소스 사용** — 로컬 MySQL과 `./uploads/images/` 폴더를 사용합니다.
```env
DB_HOST=localhost
UPLOAD_BASE_URL=
```

> `UPLOAD_BASE_URL`이 설정되어 있으면 `/uploads/images/**` 요청을 해당 서버로 302 리다이렉트합니다 (NAS 마운트 불필요).

---

## Swagger (API 문서)

| 환경 | URL |
|------|-----|
| 로컬 | `http://localhost:3200/swagger-ui/index.html` |
| 서버 | `http://hyunchang.synology.me:3200/swagger-ui/index.html` |

**JWT 인증 방법**: `POST /api/auth/login` → 응답의 `token` 복사 → Swagger 우측 상단 **Authorize** → 토큰 입력

---

## CI/CD 배포

`main` 브랜치 push 시 GitHub Actions가 자동으로 실행됩니다.

1. Docker 이미지 빌드 및 GHCR 푸시 (`ghcr.io/parkhyunchang/my-vue-project_backend:latest`)
2. `docker-compose.yml`, `init.sql` NAS 전송
3. NAS SSH 접속 후 이미지 pull → 컨테이너 재시작

### GitHub Secrets

| Secret | 설명 |
|--------|------|
| `NAS_HOST` | NAS IP |
| `NAS_USER` | NAS SSH 사용자명 |
| `NAS_SSH_PASSWORD` | NAS SSH 비밀번호 |
| `GHCR_PAT` | GitHub PAT (`read:packages`) |

### NAS 최초 설정

1. 디렉토리 생성: `/volume1/docker/my-vue-project_backend`
2. `.env` 파일 생성 (동일 경로):

```env
MYSQL_ROOT_PASSWORD=
MYSQL_DATABASE=vue_personal_project_db
MYSQL_USER=
MYSQL_PASSWORD=
DB_USERNAME=
DB_PASSWORD=
JWT_SECRET=
ALPHAVANTAGE_API_KEY=
ANTHROPIC_API_KEY=
ADMIN_USERS=hyunchang88,admin
```

### 수동 재시작 (NAS)

```bash
cd /volume1/docker/my-vue-project_backend
docker-compose pull backend && docker-compose up -d --no-deps backend
```

---

## 서버 로그

로그 파일 위치: `/volume1/docker/my-vue-project_backend/logs/`

```bash
# 실시간 확인
tail -f /volume1/docker/my-vue-project_backend/logs/application.log

# 에러 검색
grep "ERROR" /volume1/docker/my-vue-project_backend/logs/application.log
```

---

## 서버 DB 접속

```bash
docker exec -it vue_personal_project-backend-db mysql -u hyunchang88 -p vue_personal_project_db
```
