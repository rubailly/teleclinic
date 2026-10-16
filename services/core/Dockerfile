# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Daether.connector.connectTimeout=20000 -Daether.connector.requestTimeout=60000 dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Daether.connector.connectTimeout=20000 -Daether.connector.requestTimeout=60000 package -DskipTests

FROM eclipse-temurin:21.0.5_11-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /src/target/teleclinic-core.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
