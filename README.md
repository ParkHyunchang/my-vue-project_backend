# Spring Boot + MySQL

Spring Boot와 MySQL을 사용하여 구축되었습니다.

## 기술 스택

- **Backend**: Spring Boot 3.2.0
- **Database**: MySQL 8.0
- **ORM**: Spring Data JPA
- **Language**: Java 17
- **Security**: Spring Security + JWT
- **Authentication**: JWT Token 기반 인증

## 권한별 접근 가능한 기능

### 🔵 일반 사용자 (USER)
- ✅ **HOME** - 메인 페이지
- ✅ **TODOS** - 할일 관리
- ✅ **TODOS 생성/수정** - 할일 CRUD 기능

### 🟡 프리미엄 사용자 (PREMIUM)
- ✅ **일반 사용자 모든 기능**
- ✅ **HISTORY** - 히스토리 페이지
- ✅ **DATING** - 데이팅 페이지

### 🔴 관리자 (ADMIN)
- ✅ **프리미엄 사용자 모든 기능**
- ✅ **가계부** - 가계부 관리


### 개발시 실행 명령어

1. 백엔드 서버 실행 (Spring Boot)

```bash
# my-vue-project-backend 디렉토리에서
mvn spring-boot:run
```

2. 프론트엔드(Vue) 개발 서버 실행

```bash
# axios.js 에서 개발, 배포 확인 후
# my-vue-project 디렉토리에서
npm run serve

# 포트 확인 명령어
netstat -ano | findstr :3100
taskkill /PID 확인한pid숫자작성  /F
```


### 도커 컨테이너 만들기 위해서 실행

1. docker-compose로 백엔드+DB 실행
```bash
cd my-vue-project-backend
docker-compose up -d --build

# -d : 백그라운드 실행
# --build : 이미지 새로 빌드
```

2. 컨테이너 생성확인
```bash
docker ps -a
```

3. 백엔드 컨테이너 로그 확인
```bash
 docker logs vue_personal_project-backend
```

4. 로그 확인 후 컨테이너 완전 초기화 및 재빌드 명령어
```bash
# 컨테이너, 볼륨, 네트워크 모두 정리
docker-compose down --rmi all --volumes --remove-orphans
# build --no-cache: 새로 빌드된 JAR 파일을 반영하여 이미지를 새로 만듭니다.
docker-compose build --no-cache

# up -d: 컨테이너를 백그라운드로 실행합니다.
docker-compose up -d

# 아래  2개는 로컬 실행시 컨테이너 실행하고 있다는 에러 나면 실행
# 컨테이너 중지
# docker stop vue_personal_project-backend vue_personal_project-backend-db
# 컨테이너 삭제
# docker rm vue_personal_project-backend vue_personal_project-backend-db

# 백엔드 서비스만 재빌드
# docker-compose up -d --build backend

# 도커 올라온거 확인 후
# http://localhost:3200/my-vue-project/todos
# 접속해서 되는지 확인

# 안되면 다시 로그확인
docker logs vue_personal_project-backend
```

### NAS 최초 배포

1. 로컬에서 백엔드 JAR 빌드 & Docker 이미지 생성
```bash
cd ../my-vue-project-backend
mvn clean package -DskipTests
# (이미 Dockerfile, docker-compose.yml이 있다면)
docker-compose build --no-cache
```

2. NAS로 파일/이미지 전송
```bash
A. 소스/빌드 결과물 직접 복사
scp, rsync, 또는 Synology File Station 등으로 
로컬의 my-vue-project-backend 폴더 전체를
NAS의 /docker/my-vue-project-backend 등 원하는 경로에 업로드하세요.
```

3. NAS 접속해서 실행
```bash
ssh [user]@[NAS_IP]
cd /docker/my-vue-project-backend
# (최초 1회) Docker 이미지 빌드
docker-compose build --no-cache
# 컨테이너 실행 (-d 옵션은 백그라운드 실행)
docker-compose up -d
# 상태확인
docker ps
```

### NAS 업데이트 판 배포

#### JAR 파일만 압축
```bash
# 1. 로컬에서 빌드 및 tar 파일 생성
./build_and_package.ps1
# 생성된 파일: backend_deployment_YYYYMMDD_HHMMSS.tar.gz

# 2. NAS로 tar 파일만 전송
# FileZilla 등으로 backend_deployment_*.tar.gz 파일만 NAS의 /docker/my-vue-project_backend/ 에 업로드

# 3. NAS 접속해서 실행 (서버경로 : cd /volume1/docker/my-vue-project_backend)
# 권한 변경
chmod 777 vue_personal_project_backend_deploy.sh
# 줄바꿈 문자 에러나오면 이거 실행!
sed -i 's/\r$//' vue_personal_project_backend_deploy.sh
# 쉘 파일 실행
./vue_personal_project_backend_deploy.sh
```

도커 로그확인방법

```bash
docker logs vue_personal_project-backend
docker logs -f vue_personal_project-backend
```