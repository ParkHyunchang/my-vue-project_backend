# 1단계: 빌드용 이미지
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# 2단계: 실행용 이미지
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/todo-api-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 3200
ENTRYPOINT ["java", "-jar", "app.jar"]