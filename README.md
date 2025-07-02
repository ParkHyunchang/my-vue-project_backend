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
- **Java Version**: 17

## 빌드용 이미지
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# 실행용 이미지
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/todo-api-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 3200
ENTRYPOINT ["java", "-jar", "app.jar"] 

======================================================================

위에는 ai 자동완성
이제부터 내가 작성한것들

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

```bash
# 1. 빌드 및 tar 파일 생성 쉘 실행 (단 도커데스크탑 실행하고 이거 실행해야 에러안남!)
./build_backend.ps1


# 2. NAS로 파일/이미지 전송

# 로컬의 my-vue-project-backend 폴더 전체를 NAS의 /docker/my-vue-project-backend 에 업어쳐


# 3. NAS 접속해서 실행
cd /volume1/docker
./vue_personal_project_backend_deploy.sh

```

도커 로그확인방법

```bash
docker logs vue_personal_project-backend
docker logs -f vue_personal_project-backend
```