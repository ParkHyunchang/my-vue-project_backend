# Spring Boot + MySQL

Spring Boot와 MySQL을 사용하여 구축되었습니다.

## 기술 스택

- **Backend**: Spring Boot 3.2.0
- **Database**: MySQL 8.0
- **ORM**: Spring Data JPA
- **Language**: Java 17

## 사전 요구사항

1. **Java 17** 이상 설치
2. **Maven** 설치
3. **MySQL** 설치 및 실행
4. **MySQL Workbench** 또는 다른 MySQL 클라이언트 (선택사항)

## 개발 환경

- **IDE**: IntelliJ IDEA, Eclipse, VS Code
- **Database**: MySQL 8.0
- **Build Tool**: Maven
- **Java Version**: 21


### Spring Boot 프로파일 설정

프로젝트는 환경에 따라 자동으로 다른 설정을 사용합니다:

- **local**: 로컬 개발용 (localhost:3306)
- **docker**: Docker 컨테이너용 (db:3306)  
- **prod**: NAS/배포용 (db:3306)

### 개발시 실행 명령어

1. **로컬 개발용 백엔드 서버 실행**
   ```bash
   # my-vue-project-backend 디렉토리에서
   mvn spring-boot:run -Dspring.profiles.active=local
   ```

2. **Docker 환경용 백엔드 서버 실행**
   ```bash
   # my-vue-project-backend 디렉토리에서
   mvn spring-boot:run -Dspring.profiles.active=docker
   ```

3. **배포용 백엔드 서버 실행**
   ```bash
   # my-vue-project-backend 디렉토리에서
   mvn spring-boot:run -Dspring.profiles.active=prod
   ```

4. **기본 실행 (local 프로파일 자동 적용)**
   ```bash
   # my-vue-project-backend 디렉토리에서
   mvn spring-boot:run
   ```

2. 프론트엔드(Vue) 개발 서버 실행

```bash
# my-vue-project 디렉토리에서
npm run serve

# 포트 확인 명령어
netstat -ano | findstr :3100
taskkill /PID 확인한pid숫자작성  /F
```


### 로컬 실행

#### 방법 1: 개발 모드
1. **사전 요구사항 확인**
   - Java 17 이상 설치 확인: `java -version`
   - Maven 설치 확인: `mvn -version`
   - MySQL 8.0 설치 및 실행 확인

2. **백엔드 서버 실행**
   ```bash
   # my-vue-project-backend 디렉토리에서
   # Windows PowerShell/CMD에서
   mvnw.cmd spring-boot:run
   
   # Maven이 설치되어 있다면
    mvn spring-boot:run
   # 또는
   mvn spring-boot:run -Dspring.profiles.active=local
   ```
   - 백엔드 서버가 http://localhost:3200 에서 실행됩니다
   - API 엔드포인트: http://localhost:3200/my-vue-project/todos

3. **프론트엔드 서버 실행 (별도 터미널)**
   ```bash
   # my-vue-project 디렉토리에서
   cd ../my-vue-project
   npm run serve
   ```
   - 프론트엔드가 http://localhost:3100 에서 실행됩니다

4. **브라우저에서 확인**
   - http://localhost:3100 접속하여 전체 애플리케이션 확인
   - http://localhost:3200/my-vue-project/todos 접속하여 API 직접 확인

#### 방법 2: Docker 컨테이너 실행
1. **Docker Desktop 실행**
   - Docker Desktop이 실행 중인지 확인

2. **백엔드 + DB 컨테이너 실행**
   ```bash
   cd my-vue-project-backend
   docker-compose up -d --build
   ```

3. **컨테이너 상태 확인**
   ```bash
   docker ps -a
   ```

4. **로그 확인 (문제 발생시)**
   ```bash
   docker logs vue_personal_project-backend
   ```

5. **브라우저에서 확인**
   - http://localhost:3200/my-vue-project/todos 접속하여 API 확인

#### 컨테이너 완전 초기화 (문제 발생시)
```bash
# 컨테이너, 볼륨, 네트워크 모두 정리
docker-compose down --rmi all --volumes --remove-orphans

# 이미지 새로 빌드
docker-compose build --no-cache

# 컨테이너 재실행
docker-compose up -d
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
# FileZilla 등으로 backend_deployment_*.tar.gz 파일만 NAS의 /docker/my-vue-project-backend/ 에 업로드

# 3. NAS 접속해서 실행
cd /volume1/docker/my-vue-project-backend
./vue_personal_project_backend_deploy.sh
```

도커 로그확인방법

```bash
docker logs vue_personal_project-backend
docker logs -f vue_personal_project-backend
```