# syntax=docker/dockerfile:1.6

FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

ARG GRADLE_MAX_WORKERS=2
# Work around restricted seccomp/LXC environments where the JDK NIO socket impl
# may fail with `UnixDispatcher.init: Permission denied` during network access.
ENV JAVA_TOOL_OPTIONS="-Djdk.net.usePlainSocketImpl=true"

COPY gradle /app/gradle
COPY gradlew build.gradle.kts settings.gradle.kts /app/

RUN chmod +x /app/gradlew

# Pre-fetch dependencies for better layer caching.
RUN /app/gradlew --no-daemon --max-workers=${GRADLE_MAX_WORKERS} dependencies || true

COPY src /app/src

RUN /app/gradlew --no-daemon --max-workers=${GRADLE_MAX_WORKERS} bootJar


FROM eclipse-temurin:21-jre

WORKDIR /app

ENV JAVA_OPTS=""

COPY --from=build /app/build/libs/*.jar /app/secrux-server.jar

EXPOSE 8080 5155

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/secrux-server.jar"]
