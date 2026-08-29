# GraalVM native image of the BoxVault backend (API + the frontend bundle committed in
# src/main/resources/static). Produces a single statically-compiled binary: no JVM at
# runtime, ~50 MB image, sub-second start-up.
#
#   docker build -t boxvault-backend .
#   docker run -p 8088:8088 -v boxvault-data:/data \
#     -e SPRING_DATASOURCE_URL="jdbc:h2:file:/data/boxvault;DB_CLOSE_ON_EXIT=FALSE" boxvault-backend
#
# Dockerfile.jvm keeps the classic JVM image as a fallback.

# ---- build stage: GraalVM JDK 25 with native-image --------------------------
FROM ghcr.io/graalvm/native-image-community:25 AS build
WORKDIR /src

# Resolve dependencies first so they are cached between source changes
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B -Pnative dependency:go-offline

COPY src src
# Tests run in the CI job before the image is built; here Spring AOT-processes the app
# (the parent's "native" profile) and native-image compiles it. native-image sizes its
# own heap from the memory available to the build (needs a few GB).
RUN ./mvnw -B -Pnative -DskipTests native:compile \
    && ls -la target/boxvault-backend

# ---- runtime stage ---------------------------------------------------------
# The binary links dynamically against glibc / zlib, so a slim Debian is enough
# (bash is there for the compose healthcheck).
FROM debian:bookworm-slim
WORKDIR /app

RUN groupadd --system boxvault && useradd --system --gid boxvault --home /app boxvault \
    && mkdir -p /data && chown boxvault:boxvault /data
USER boxvault

COPY --from=build /src/target/boxvault-backend /app/boxvault-backend

# H2 database file lives here when SPRING_DATASOURCE_URL points at /data (see docker-compose.yml)
VOLUME /data
EXPOSE 8088

# Native images accept the usual heap flags (e.g. -Xmx256m); much less is needed than on the JVM
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec /app/boxvault-backend $JAVA_OPTS"]
