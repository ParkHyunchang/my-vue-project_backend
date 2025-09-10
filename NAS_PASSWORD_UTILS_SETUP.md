# NAS 패스워드 유틸리티 설정 가이드

## 개요
이 가이드는 NAS에서 패스워드 검증 및 관리 도구를 설정하는 방법을 설명합니다.

## 파일 설명

### 1. `password-utils.sh` (고급 버전)
- 데이터베이스 직접 연결
- 패스워드 해시 생성
- 사용자 목록 조회
- 패스워드 리셋 기능

### 2. `simple-password-check.sh` (간단 버전)
- Spring Boot 애플리케이션을 통한 검증
- 사용자 친화적인 인터페이스
- 실시간 패스워드 검증

## NAS 설정 방법

### 1. 파일 업로드
```bash
# NAS에 파일 업로드
scp password-utils.sh admin@your-nas-ip:/volume1/docker/vue-project/
scp simple-password-check.sh admin@your-nas-ip:/volume1/docker/vue-project/
```

### 2. 실행 권한 부여
```bash
# NAS SSH 접속
ssh admin@your-nas-ip

# 실행 권한 부여
chmod +x /volume1/docker/vue-project/password-utils.sh
chmod +x /volume1/docker/vue-project/simple-password-check.sh
```

### 3. 데이터베이스 연결 정보 수정
`password-utils.sh` 파일의 다음 부분을 NAS 환경에 맞게 수정:

```bash
# 데이터베이스 연결 정보 (환경에 맞게 수정)
DB_HOST="localhost"  # 또는 Docker 컨테이너 IP
DB_PORT="5432"       # PostgreSQL 포트
DB_NAME="vue_personal_project_db"
DB_USER="your_db_user"
DB_PASSWORD="your_db_password"
```

## 사용 방법

### 간단한 패스워드 검증
```bash
cd /volume1/docker/vue-project
./simple-password-check.sh
```

### 고급 기능 사용
```bash
cd /volume1/docker/vue-project

# 도움말 보기
./password-utils.sh help

# 사용자 목록 조회
./password-utils.sh list

# 패스워드 검증
./password-utils.sh verify hyunchang88 mypassword

# 패스워드 해시 생성
./password-utils.sh hash newpassword
```

## Docker 환경에서 사용

### 1. Docker 컨테이너 내부에서 실행
```bash
# 백엔드 컨테이너 접속
docker exec -it vue-project-backend bash

# 스크립트 실행
./password-utils.sh list
```

### 2. Docker Compose를 통한 실행
```bash
# docker-compose.yml에 스크립트 마운트 추가
volumes:
  - ./password-utils.sh:/app/password-utils.sh
  - ./simple-password-check.sh:/app/simple-password-check.sh
```

## 보안 주의사항

1. **스크립트 파일 권한**: 실행 권한만 부여하고 다른 사용자는 읽기 불가
2. **데이터베이스 비밀번호**: 환경 변수나 별도 설정 파일 사용
3. **로그 파일**: 패스워드 관련 로그는 즉시 삭제
4. **네트워크**: 내부 네트워크에서만 사용

## 문제 해결

### PostgreSQL 클라이언트 설치
```bash
# Synology NAS
sudo synopkg install PostgreSQL

# Ubuntu/Debian
sudo apt-get install postgresql-client

# CentOS/RHEL
sudo yum install postgresql
```

### Java 환경 설정
```bash
# Java 설치 확인
java -version
javac -version

# Spring Boot 의존성 확인
ls -la lib/
```

### 네트워크 연결 확인
```bash
# 데이터베이스 연결 테스트
telnet localhost 5432

# Spring Boot 애플리케이션 확인
curl http://localhost:3200/api/auth/check
```

## 예시 사용 시나리오

### 시나리오 1: 관리자 패스워드 확인
```bash
./simple-password-check.sh
# 사용자명: hyunchang88
# 패스워드: [입력]
```

### 시나리오 2: 모든 사용자 목록 확인
```bash
./password-utils.sh list
```

### 시나리오 3: 새 사용자 패스워드 해시 생성
```bash
./password-utils.sh hash newuserpassword
```

## 백업 및 복구

### 스크립트 백업
```bash
# 스크립트 파일 백업
cp password-utils.sh /volume1/backup/
cp simple-password-check.sh /volume1/backup/
```

### 설정 백업
```bash
# 데이터베이스 백업
pg_dump -h localhost -U your_user vue_personal_project_db > backup_$(date +%Y%m%d).sql
```

이 도구들을 사용하여 NAS에서 안전하고 효율적으로 사용자 패스워드를 관리할 수 있습니다.
