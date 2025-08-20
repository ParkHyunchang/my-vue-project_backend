# 실행용 이미지
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY app.jar app.jar
EXPOSE 3200
ENTRYPOINT ["java", "-jar", "app.jar"]