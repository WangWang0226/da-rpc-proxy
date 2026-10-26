FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /workspace/build/install/besu-rpc-proxy/ ./
RUN mkdir /data && chown app:app /data
USER app
EXPOSE 8080
ENV SQLITE_PATH=/data/indexer.sqlite
ENTRYPOINT ["/app/bin/besu-rpc-proxy"]
