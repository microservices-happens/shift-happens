# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
# Tests run in CI (ci.yml), not in the image build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -Dmaven.wagon.http.retryHandler.count=3 dependency:go-offline
COPY src ./src
# The contract lives outside this build context: pass it with `--build-context contracts=contracts`
# (repo root). Maven copies it onto the classpath for Swagger UI.
COPY --from=contracts openapi/scheduling.yaml /contracts/openapi/scheduling.yaml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -Dmaven.wagon.http.retryHandler.count=3 -Dcontracts.openapi.dir=/contracts/openapi package

FROM eclipse-temurin:21-jre
# curl is used by the Compose healthcheck (GET /health).
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
# Pin the agent so local builds remain reproducible across all Java services.
ARG OTEL_JAVA_AGENT_VERSION=2.33.1
RUN curl --fail --location --silent --show-error --retry 3 \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar" \
      --output /otel-javaagent.jar
RUN useradd --system --uid 10001 app
USER app
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/otel-javaagent.jar", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
