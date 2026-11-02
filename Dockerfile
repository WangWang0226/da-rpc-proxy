# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid 10001 --create-home app
WORKDIR /app
COPY --from=build /workspace/build/install/besu-rpc-proxy/ ./
RUN mkdir /data /config && chown app:app /data /config
USER app
EXPOSE 8080
ENV SQLITE_PATH=/data/indexer.sqlite
ENV CONTRACT_CONFIG_PATH=/config/tracked-contracts.json
ENTRYPOINT ["/app/bin/besu-rpc-proxy"]
