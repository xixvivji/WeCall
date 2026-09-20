FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY backend/ .
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/wecall-backend-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-Xmx1536m","-jar","/app/app.jar"]
