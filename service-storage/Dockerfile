FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY service-storage/target/service-storage-1.0-SNAPSHOT.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
