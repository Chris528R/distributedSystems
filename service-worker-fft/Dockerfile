FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY service-worker-fft/target/service-worker-fft-1.0-SNAPSHOT.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
