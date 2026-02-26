# ────────────────────────────────────────────────────────────────
# Build stage: Maven으로 JAR 빌드
# ────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 의존성 캐시 최적화: pom.xml 먼저 복사 후 dependency 다운로드
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# ────────────────────────────────────────────────────────────────
# Runtime stage: 빌드된 JAR만 포함한 가벼운 이미지
# ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/todo-api-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/logs
EXPOSE 3200
ENTRYPOINT ["java", "-jar", "app.jar"]