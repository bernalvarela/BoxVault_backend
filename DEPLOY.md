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
- the S3 client of the attached files uses `url-connection-client` (the JDK's own
  HTTP) and Netty / Apache are excluded from `software.amazon.awssdk:s3` on
  purpose: fewer moving parts in the native image. The SDK ships its own GraalVM
  metadata, so nothing of it goes in `NativeHints`;
- the Docker build is a **static musl binary** (`-Pnative,native-static`) compiled for
  the baseline x86-64 ISA (`-march=compatibility`), so it starts on any server CPU or
  VM CPU model; a glibc-linked build from the Oracle Linux 9 GraalVM image needs
  x86-64-v2 and fails with "CPU ISA level is lower than required" on older CPUs;
- the app has one profile per database (`application.yml`): **`dev`** is the H2
  one and is what you get when no profile is set, **`pro`** is PostgreSQL and is
  what `docker-compose.yml` activates. The H2 web console is served under `dev`
  only, so it is never reachable on the server.

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
mkdir -p ~/boxvault/deploy/postgres && cd ~/boxvault
BASE=https://raw.githubusercontent.com/bernalvarela/BoxVault_backend/master
curl -O $BASE/docker-compose.yml
curl -o deploy/postgres/01-schema.sql    $BASE/deploy/postgres/01-schema.sql
curl -o deploy/postgres/02-seed-data.sql $BASE/deploy/postgres/02-seed-data.sql

cat > .env <<EOF
DOCKER_IMAGE=<docker-hub-user>/boxvault-backend:latest
POSTGRES_PASSWORD=$(openssl rand -base64 24)
RUSTFS_ACCESS_KEY=boxvault
RUSTFS_SECRET_KEY=$(openssl rand -base64 24)
EOF

docker compose pull && docker compose up -d
```

- The app listens on port **8088**; put a reverse proxy (Caddy / nginx) in front
  for HTTPS if it is exposed to the internet.
- The data lives in **PostgreSQL 18.6** (`postgres` service), in the `boxvault-db`
  volume. The database is not published outside the compose network; the app
  reaches it as `postgres:5432` with the `pro` Spring profile
  (`SPRING_PROFILES_ACTIVE=pro`).
- The **first** start — and only that one — runs `deploy/postgres/01-schema.sql`
  (tables and indexes) and `02-seed-data.sql` (the historical data reconstructed
  from the bank statements). Once the volume exists the scripts are ignored, so
  payments, expenses and registered tax returns survive restarts and upgrades.
  The filed tax returns are the exception: the app registers them itself on the
  first start, because their snapshot is a report computed from the seeded data.
- The **attached files** (copies of a tenant's DNI, work contracts, photos) do
  *not* live in PostgreSQL: they are objects in the **RustFS** service
  (`boxvault-files`), in the `boxvault-files` volume. RustFS speaks S3 and the app
  talks to it as `http://rustfs:9000` with the `RUSTFS_*` credentials from `.env`.
  Like the database it has no published port and is not on `traefik-net`, so
  nothing outside the compose network reaches it — which is why plain HTTP is
  enough. The bucket is created by the app the first time something is uploaded;
  downloads are served by the backend, never straight from the store.
- Backup / restore — **both** the database and the file store:
  ```bash
  docker compose exec -T postgres pg_dump -U boxvault -Fc boxvault > boxvault-$(date +%F).dump
  docker compose exec -T postgres pg_restore -U boxvault -d boxvault --clean < boxvault-2026-09-03.dump

  # the attached files (the dump above does not contain them)
  docker run --rm -v boxvault-files:/data -v "$PWD":/backup alpine \
    tar czf /backup/boxvault-files-$(date +%F).tar.gz -C /data .
  ```
- Upgrade: `docker compose pull && docker compose up -d`.
- Logs: `docker compose logs -f boxvault` (add `postgres` for the database).
- `psql` on the server: `docker compose exec postgres psql -U boxvault -d boxvault`.

### Schema and initial data

`deploy/postgres/01-schema.sql` is the hand-written schema: the eleven tables with
their foreign keys and the indexes each repository query needs (they are
documented next to the index that serves them). `deploy/postgres/02-seed-data.sql`
is **generated** from `src/main/resources/seed-data.json`, which stays the single
source of truth; regenerate it after editing the JSON:

```bash
node deploy/postgres/generate-seed-sql.mjs
```

`spring.jpa.hibernate.ddl-auto` stays at `update` under `pro` too, so adding a
field to an entity still creates its column by itself. `update` never drops
anything, so the indexes above are safe. Under `dev` — no profile set, or
`SPRING_PROFILES_ACTIVE=dev` — nothing changes: the app runs on the in-memory H2
database and `DataSeeder` loads the JSON on every start, as before.

### Coming from the H2 deployment

The old `boxvault-data` volume (`/data/boxvault.mv.db`) is still mounted in the
`boxvault` service, purely so the previous database remains available as a
backup; nothing writes to it any more. If the H2 database holds payments,
expenses or tax returns entered by hand after the last change to
`seed-data.json`, export them from the H2 file before switching, because the
PostgreSQL database starts from the seed scripts. Once you are satisfied with
the new deployment, `docker volume rm boxvault-data` and drop the volume from
`docker-compose.yml`.

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
DOCKER_IMAGE=boxvault-backend POSTGRES_PASSWORD=local docker compose up
```

Or, with the in-memory H2 database and no PostgreSQL at all (the default profile):

```bash
docker run --rm -p 8088:8088 boxvault-backend
```
