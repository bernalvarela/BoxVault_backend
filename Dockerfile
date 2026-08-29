# Builds the BoxVault backend (which also serves the frontend bundle committed in
# src/main/resources/static) into a small JRE image.
#
#   docker build -t boxvault-backend .
#   docker run -p 8088:8088 -v boxvault-data:/data \
#     -e SPRING_DATASOURCE_URL="jdbc:h2:file:/data/boxvault;DB_CLOSE_ON_EXIT=FALSE" boxvault-backend

# ---- build stage -----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Resolve dependencies first so they are cached between source changes
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
# Tests run in the CI job before the image is built; here we only package
RUN ./mvnw -q -B -DskipTests package && mv target/*.jar target/app.jar

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system boxvault && useradd --system --gid boxvault --home /app boxvault \
    && mkdir -p /data && chown boxvault:boxvault /data
USER boxvault

COPY --from=build /src/target/app.jar app.jar

# H2 database file lives here when SPRING_DATASOURCE_URL points at /data (see docker-compose.yml)
VOLUME /data
EXPOSE 8088

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
