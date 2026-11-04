# Nexora Reports: multi-stage image (Phase 1, Docker Compose installation).
#
# Build (from this folder):  docker build -t nexora/reports:dev .
# Stage 1 compiles with Maven and JDK 21 (tests are not run here: they run with `mvn clean verify`, as before).
# Stage 2 keeps only a Java 21 runtime and the executable jar, and runs as an unprivileged user (UID 10001).
# Configuration is unchanged: everything comes from the environment (SPRING_PROFILES_ACTIVE, the variables read by
# application*.yml), never from the image. JVM options can be added with JAVA_TOOL_OPTIONS.
# The base images can be replaced (e.g. pinned by digest) with --build-arg BUILD_IMAGE=... / RUNTIME_IMAGE=...

ARG BUILD_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre

FROM ${BUILD_IMAGE} AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
# EX-TLS-1: empty for every normal image. Only "dev-insecure-transport" builds the development image that can accept a
# temporary transport exception (nexora-deploy/compose.insecure-transport.yaml); never use it for production.
ARG MAVEN_PROFILES=
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true ${MAVEN_PROFILES:+-P${MAVEN_PROFILES}} package \
    && cp target/nexora-reports-*.jar /build/app.jar

FROM ${RUNTIME_IMAGE}
ARG MAVEN_PROFILES=
LABEL org.opencontainers.image.title="Nexora Reports" \
      org.opencontainers.image.vendor="Nexora AI" \
      ai.nexora.reports.build-profiles="${MAVEN_PROFILES}"
RUN groupadd --system --gid 10001 nexora \
    && useradd --system --uid 10001 --gid nexora --no-create-home --home-dir /nonexistent \
       --shell /usr/sbin/nologin nexora
WORKDIR /app
COPY --from=build --chown=root:root --chmod=0444 /build/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
