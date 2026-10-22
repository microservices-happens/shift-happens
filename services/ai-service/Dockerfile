# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
# Maven Central occasionally answers a transient 403 to shared CI IPs (docs/ci-demo.md section 6).
# The second and third attempt force an update instead of failing the whole image build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -Dmaven.wagon.http.retryHandler.count=3 dependency:go-offline \
 || mvn -B -q -U -Dmaven.wagon.http.retryHandler.count=3 dependency:go-offline \
 || mvn -B -q -U -Dmaven.wagon.http.retryHandler.count=3 dependency:go-offline
COPY src ./src
# -Dskip.unit.tests=true (not -DskipTests) skips the unit tests: the service pom configures
# Surefire's skipTests from that property, so it takes precedence over -DskipTests.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -Dskip.unit.tests=true -Dmaven.wagon.http.retryHandler.count=3 package \
 || mvn -B -q -U -Dskip.unit.tests=true -Dmaven.wagon.http.retryHandler.count=3 package \
 || mvn -B -q -U -Dskip.unit.tests=true -Dmaven.wagon.http.retryHandler.count=3 package

FROM eclipse-temurin:21-jre
# curl is used by the Compose healthcheck (GET /health).
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 app
USER app
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
