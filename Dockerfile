# GraalVM native image of the BoxVault backend (API + the frontend bundle committed in
# src/main/resources/static). Produces a single, fully static binary (musl): no JVM and
# no glibc at runtime, so it runs on any x86-64 machine, ~50 MB image, sub-second start-up.
#
#   docker build -t boxvault-backend .
#   docker run -p 8088:8088 -v boxvault-data:/data \
#     -e SPRING_DATASOURCE_URL="jdbc:h2:file:/data/boxvault;DB_CLOSE_ON_EXIT=FALSE" boxvault-backend
#
# Dockerfile.jvm keeps the classic JVM image as a fallback.

# ---- build stage: GraalVM JDK 25 with native-image and the musl toolchain ----------
FROM ghcr.io/graalvm/native-image-community:25-muslib AS build
WORKDIR /src

# Resolve dependencies first so they are cached between source changes
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B -Pnative,native-static dependency:go-offline

COPY src src
# Tests run in the CI job before the image is built; here Spring AOT-processes the app
# (the parent's "native" profile) and native-image compiles it statically against musl
# ("native-static" profile: --static --libc=musl; -march=compatibility comes from the pom).
RUN ./mvnw -B -Pnative,native-static -DskipTests native:compile \
    && ls -la target/boxvault-backend \
    && (ldd target/boxvault-backend || true)

# ---- runtime stage ---------------------------------------------------------
# The binary is static, so the base only provides a user, a shell for the compose
# healthcheck and CA certificates / timezone data.
FROM debian:bookworm-slim
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system boxvault && useradd --system --gid boxvault --home /app boxvault \
    && mkdir -p /data && chown boxvault:boxvault /data
USER boxvault

COPY --from=build /src/target/boxvault-backend /app/boxvault-backend

# H2 database file lives here when SPRING_DATASOURCE_URL points at /data (see docker-compose.yml)
VOLUME /data
EXPOSE 8088

# Native images accept the usual heap flags (e.g. -Xmx256m); much less is needed than on the JVM
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec /app/boxvault-backend $JAVA_OPTS"]
