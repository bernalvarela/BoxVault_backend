# Deploying BoxVault

The backend image contains the whole application: the Spring Boot API plus the
frontend bundle committed in `src/main/resources/static/`. It is a **GraalVM native
image**: the `Dockerfile` compiles the app ahead of time into a single binary (no JVM
at runtime, ~50 MB image, starts in well under a second, ~100 MB of RAM).
`Dockerfile.jvm` keeps the classic JVM image as a fallback
(`docker build -f Dockerfile.jvm .`).

Native build on your machine (needs a GraalVM JDK 25, e.g. `sdk install java 25-graalce`):

```bash
./mvnw -Pnative -DskipTests native:compile      # -> target/boxvault-backend (5-10 min)
./target/boxvault-backend
```

Things that matter for the native build (already handled, keep them in mind when
changing the code):
- resources read by hand and classes serialised outside controllers need hints:
  see `config/NativeHints.java` (seed-data.json, tax report DTOs);
- the image's default locale is es-ES (`pom.xml`, native plugin `buildArgs`);
- the Docker build is a **static musl binary** (`-Pnative,native-static`) compiled for
  the baseline x86-64 ISA (`-march=compatibility`), so it starts on any server CPU or
  VM CPU model; a glibc-linked build from the Oracle Linux 9 GraalVM image needs
  x86-64-v2 and fails with "CPU ISA level is lower than required" on older CPUs;
- the H2 web console is off by default (`application.yml`); use the `dev` profile
  locally.

## 1. Publish the image (GitHub Actions → Docker Hub)

`.github/workflows/main.yml` runs the tests on every push and, on `master` (the default branch) and on
`v*` tags, builds `Dockerfile` and pushes it to Docker Hub as
`<DOCKER_USERNAME>/boxvault-backend` with these tags:

| Event | Tags |
|---|---|
| push to `master` | `latest`, `sha-<short sha>` |
| tag `v1.2.3` | `1.2.3`, `1.2`, `sha-<short sha>` |

Repository secrets needed (Settings → Secrets and variables → Actions):

- `DOCKER_USERNAME` — your Docker Hub user.
- `DOCKER_PASSWORD` — a Docker Hub **access token** (Account settings → Security →
  New access token, *Read & Write*), not your account password.

Create the `boxvault-backend` repository on Docker Hub first (or let the first
push create it; it will be public by default — make it private there if needed).

## 2. Update the frontend

The frontend lives in its own repo. To ship a new UI:

```bash
cd BoxVault_frontend && npm run build          # Node 22
rm -rf ../BoxVault_backend/src/main/resources/static/assets
cp -r dist/* ../BoxVault_backend/src/main/resources/static/
cd ../BoxVault_backend && git add src/main/resources/static && git commit -m "Update frontend bundle" && git push
```

The push to `master` triggers the image build.

## 3. Run it on the server

Docker + the compose plugin are the only requirements.

```bash
mkdir -p ~/boxvault && cd ~/boxvault
curl -O https://raw.githubusercontent.com/bernalvarela/BoxVault_backend/master/docker-compose.yml
echo "DOCKER_IMAGE=<docker-hub-user>/boxvault-backend:latest" > .env
docker compose pull && docker compose up -d
```

- The app listens on port **8088**; put a reverse proxy (Caddy / nginx) in front
  for HTTPS if it is exposed to the internet.
- The H2 database is stored in the `boxvault-data` volume
  (`SPRING_DATASOURCE_URL=jdbc:h2:file:/data/boxvault`), so data survives
  restarts and upgrades. Back it up with
  `docker run --rm -v boxvault-data:/data -v "$PWD":/backup alpine tar czf /backup/boxvault-data.tgz -C /data .`
- Upgrade: `docker compose pull && docker compose up -d`.
- Logs: `docker compose logs -f boxvault`.

## 4. Actualización automática (CD)

`deploy/update.sh` pulls the published image and recreates `boxvault` **only when the
image id changed** (ports, environment and the data volume are kept); a run with
nothing new is a no-op. Schedule it from cron every 5 minutes on the server:

```bash
curl -o ~/boxvault/update.sh https://raw.githubusercontent.com/bernalvarela/BoxVault_backend/master/deploy/update.sh
chmod +x ~/boxvault/update.sh
( crontab -l 2>/dev/null; echo "*/5 * * * * /home/$USER/boxvault/update.sh >> /home/$USER/boxvault/update.log 2>&1" ) | crontab -
```

`update.log` records every version that landed and when. Run it by hand
(`~/boxvault/update.sh`) to deploy immediately after a push.

Why not Watchtower: `containrrr/watchtower` is archived; its fork
(`nickfedor/watchtower`) works as a drop-in, but the cron job does the same with
nothing extra to trust or maintain.

A broken commit never reaches the server: the workflow runs the tests before
building the image. To freeze the server on a known version, set `DOCKER_IMAGE` in
`.env` to a `sha-…` or `1.2.3` tag instead of `latest` — the script will then find
nothing new until you change it.

## Local build without the pipeline

```bash
docker build -t boxvault-backend .
docker run --rm -p 8088:8088 -v boxvault-data:/data \
  -e SPRING_DATASOURCE_URL="jdbc:h2:file:/data/boxvault;DB_CLOSE_ON_EXIT=FALSE" boxvault-backend
```
