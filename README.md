# Todo API - Spring Boot + MySQL

투두리스트를 위한 REST API 서버입니다. Spring Boot와 MySQL을 사용하여 구축되었습니다.

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

## 데이터베이스 설정

1. MySQL 서버를 시작합니다.

2. MySQL에 접속하여 데이터베이스를 생성합니다:
   ```sql
   CREATE DATABASE todo_db;
   ```

3. 사용자를 생성하고 권한을 부여합니다 (선택사항):
   ```sql
   CREATE USER 'todo_user'@'localhost' IDENTIFIED BY 'your_password';
   GRANT ALL PRIVILEGES ON todo_db.* TO 'todo_user'@'localhost';
   FLUSH PRIVILEGES;
   ```

## 애플리케이션 설정

1. `src/main/resources/application.properties` 파일에서 데이터베이스 연결 정보를 수정합니다:
   ```properties
   spring.datasource.username=root
   spring.datasource.password=your_password
   ```

## 프로젝트 실행

1. 프로젝트 디렉토리로 이동:
   ```bash
   cd todo-backend-java/todo-api
   ```

2. Maven으로 의존성을 다운로드하고 애플리케이션을 실행:
   ```bash
   mvn spring-boot:run
   ```

3. 또는 JAR 파일로 빌드 후 실행:
   ```bash
   mvn clean package
   java -jar target/todo-api-0.0.1-SNAPSHOT.jar
   ```

4. 애플리케이션이 성공적으로 시작되면 다음 URL에서 확인할 수 있습니다:
   - API 서버: http://localhost:3200
   - API 문서: http://localhost:3200/todos

## API 엔드포인트

### 기본 CRUD 작업

- **GET** `/todos` - 모든 투두 목록 조회
- **GET** `/todos/{id}` - 특정 투두 조회
- **POST** `/todos` - 새 투두 생성
- **PUT** `/todos/{id}` - 투두 수정
- **DELETE** `/todos/{id}` - 투두 삭제

### 추가 기능

- **GET** `/todos?q={keyword}` - 키워드로 투두 검색
- **GET** `/todos/search?q={keyword}` - 검색 전용 엔드포인트
- **GET** `/todos/completed/{true|false}` - 완료 상태별 조회

### 요청/응답 예시

#### 투두 생성 (POST /todos)
```json
{
  "title": "Spring Boot 학습하기",
  "completed": false
}
```

#### 응답
```json
{
  "id": 1,
  "title": "Spring Boot 학습하기",
  "completed": false
}
```

## 프론트엔드 연동

기존 Vue.js 프론트엔드에서 API 주소만 변경하면 됩니다:

1. `my-vue-project/src/axios.js` 파일에서:
   ```javascript
   // 기존: json-server
   // baseURL: 'http://localhost:3200'
   
   // 새로운 Spring Boot API
   baseURL: 'http://localhost:3200'
   ```

2. API 엔드포인트는 동일하게 `/todos`를 사용하므로 추가 변경이 필요하지 않습니다.

## 문제 해결

### 포트 충돌
- 기본 포트 3200이 사용 중인 경우 `application.properties`에서 `server.port`를 변경하세요.

### 데이터베이스 연결 오류
- MySQL 서버가 실행 중인지 확인
- 데이터베이스 이름, 사용자명, 비밀번호가 올바른지 확인
- MySQL 8.0에서 `allowPublicKeyRetrieval=true` 설정이 필요할 수 있습니다.

### 빌드 오류
- Java 17 이상이 설치되어 있는지 확인
- Maven이 올바르게 설치되어 있는지 확인

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