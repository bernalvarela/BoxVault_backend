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
mkdir -p ~/boxvault/deploy/postgres ~/boxvault/deploy/backup && cd ~/boxvault
BASE=https://raw.githubusercontent.com/bernalvarela/BoxVault_backend/master
curl -O $BASE/docker-compose.yml
curl -o deploy/postgres/01-schema.sql    $BASE/deploy/postgres/01-schema.sql
curl -o deploy/postgres/02-seed-data.sql $BASE/deploy/postgres/02-seed-data.sql
curl -o deploy/backup/backup.sh          $BASE/deploy/backup/backup.sh
curl -o deploy/backup/entrypoint.sh      $BASE/deploy/backup/entrypoint.sh

cat > .env <<EOF
DOCKER_IMAGE=<docker-hub-user>/boxvault-backend:latest
POSTGRES_PASSWORD=$(openssl rand -hex 24)
RUSTFS_ACCESS_KEY=boxvault
RUSTFS_SECRET_KEY=$(openssl rand -hex 24)
BOXVAULT_JWT_SECRET=$(openssl rand -hex 32)
BOXVAULT_ADMIN_USER=admin
BOXVAULT_ADMIN_PASSWORD=$(openssl rand -hex 8)
EOF

docker compose pull && docker compose up -d
```

- The app listens on port **8088**; put a reverse proxy (Caddy / nginx) in front
  for HTTPS if it is exposed to the internet.
- **Logging in.** The app has its own users, so Traefik's basicauth middleware is
  gone from `docker-compose.yml`. On the first start, if the `app_users` table is
  empty, it creates the administrator named by `BOXVAULT_ADMIN_USER` with
  `BOXVAULT_ADMIN_PASSWORD` and flags it *must change password* — that value sits
  in `.env` in clear, so it is a way in, not a password. Read it once
  (`grep BOXVAULT_ADMIN_PASSWORD .env`), log in, change it. `BOXVAULT_JWT_SECRET`
  signs the session tokens: at least 32 characters, and changing it logs everyone
  out (that is also how you kill every session at once). Later starts never touch
  existing users, so removing someone's access stays removed.
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
  The S3 port is not published and the service is not on `traefik-net`, so
  nothing outside the compose network reaches it — which is why plain HTTP is
  enough. The bucket is created by the app the first time something is uploaded;
  downloads are served by the backend, never straight from the store.
- RustFS' **web console** is published on the LAN at `http://<server>:9001`, the
  same way the app is on `:8088`: it bypasses Traefik and is not reachable from
  the Internet. Log in with `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` from
  `.env`. It is for inspecting buckets and objects; the app is the normal way in.
  To turn it off again, drop the `ports:` block from the `rustfs` service and set
  `RUSTFS_CONSOLE_ENABLE: "false"`.
- **Schema changes run themselves.** The app carries its migrations
  (`src/main/resources/db/migration/V{n}__*.sql`) and **Flyway** applies them at
  startup: in order, once each, recorded in `flyway_schema_history`. A deploy no
  longer depends on someone remembering to pipe a `.sql` through `psql`.

  Version 1 is not a file, it is the starting line: `01-schema.sql` creates the
  database the first time its volume is born, and Flyway baselines at 1
  (`baseline-on-migrate`) and carries on from V2. So a genuinely empty database,
  with no init scripts, will not start — the initial schema is not Flyway's job.
  Flyway is off in `dev` (H2, rebuilt from the entities on every start).

  A failed migration leaves the app refusing to start, which is the right
  outcome: better down than running against a schema the code does not expect.
  Check what it did with `docker compose logs boxvault | grep -i flyway`, or ask
  the database: `SELECT version, description, success FROM flyway_schema_history
  ORDER BY installed_rank;`. A migration that has been applied is never edited —
  Flyway checksums them and will refuse to start if one changes; corrections go
  in a new migration. `deploy/postgres/migrations/` keeps the older ones, which
  were applied by hand before this; see the LEEME.md in there.

- **Invoices and contracts** — the PDFs the app issues (one invoice per collected
  month, the contract from its template) are headed by the **owner of the unit**,
  and its data comes from the *Propietarios* screen: name, NIF, fiscal address
  and IBAN of the comunidad de bienes. That is where it already lives and where
  whoever runs the place maintains it — nothing to redeploy to change a phone
  number. Between several owners the comunidad de bienes wins (it is the one with
  a NIF of its own); a unit with no shares of its own inherits its local's, the
  same rule the tax reports use.

  The `.env` values below are only a **fallback** for whatever that owner record
  leaves empty — useful on an install with no owners loaded yet. A missing value
  is printed as a visible gap (`..........`) rather than silently dropped, so an
  invoice with no NIF looks wrong instead of passing for good:

  | Variable | What it is |
  | --- | --- |
  | `BOXVAULT_ISSUER_NAME` | Fiscal name (defaults to `Comunidad de bienes Pasaxe 29`) |
  | `BOXVAULT_ISSUER_TAX_ID` | NIF of the comunidad — **required on an invoice** |
  | `BOXVAULT_ISSUER_ADDRESS` | Fiscal address, one line |
  | `BOXVAULT_ISSUER_CITY` | Postal code and municipality |
  | `BOXVAULT_ISSUER_EMAIL` / `BOXVAULT_ISSUER_PHONE` | Contact printed on the invoice |
  | `BOXVAULT_ISSUER_IBAN` | Account the rent is collected into |
  | `BOXVAULT_INVOICE_SERIES` | Invoice series letter (default `A`, giving `A2026/0001`) |

  Invoice numbers are correlative per year and are kept on the charge once issued,
  so re-issuing hands back the same document rather than burning a new number.
  A tenancy only invoices when its contract is ticked for it ("Emitir factura de
  cada mensualidad"); then every collection issues one by itself. VAT-exempt
  units (dwellings) can't be ticked and can't be invoiced at all.

  An invoice whose **paper** came out wrong — a missing issuer NIF, say — can be
  redone from the charge (needs ADMINISTRAR on PAGOS): same number, same issue
  date, new PDF replacing the archived one. That is not a new invoice, it is the
  same one printed again. When the **operation** is wrong (amount, tenant,
  period) the delivered invoice stands and what applies is a *factura
  rectificativa* — not implemented yet.
  The contract text is no longer inside the app: contract **templates** are data
  now, managed from the *Plantillas* screen and stored in the object store next
  to the attachments. Each tenancy can pick one; the one marked as default is
  used when it does not. The editor lists the `{{fields}}` a template may use —
  straight from the server, so the list cannot drift from what the generator
  actually substitutes — and previews the PDF with made-up data before saving.
  On a fresh install the app seeds the first template from the one it ships
  with, so generating a contract works before anyone has written one.

- **Automatic backups** — the `backup` service in the compose file. It is a
  `postgres:18.6-alpine` (the same image as the server, so `pg_dump` can never
  disagree with the database version) running nothing but `crond`: every night it
  dumps the database **and** tars the attached files into dated names, and
  deletes the ones that got too old. Both halves are needed — the dump holds
  tenants, contracts, charges, expenses and tax returns; the attachments (scanned
  DNIs, signed contracts, AEAT receipts) live in RustFS and are not in it.

  The two scripts it runs are mounted from `deploy/backup/`, not baked into the
  image, so an existing install has to fetch them once (the `curl` lines above)
  before `docker compose up -d backup` has anything to run.

  It is configured from `.env`, and everything has a default:

  | Variable | Default | What it does |
  | --- | --- | --- |
  | `BOXVAULT_BACKUP_DIR` | `./backups` | Where the copies are written **on the server** |
  | `BACKUP_CRON` | `30 3 * * *` | When it runs (cron format, in the compose `TZ`) |
  | `BACKUP_KEEP_DAYS` | `14` | Days kept before a copy is deleted |
  | `BACKUP_ON_START` | `false` | `true` takes one copy as soon as the service starts |

  To check it works without waiting until the small hours:
  ```bash
  docker compose up -d backup
  docker compose logs backup                            # the "Copias programadas: 30 3 * * *" line
  docker compose exec backup sh /opt/backup/backup.sh   # a backup right now
  ls -lh backups/
  ```
  Each night leaves two files: `boxvault-db-2026-09-17_0330.dump` (PostgreSQL
  `custom` format) and `boxvault-files-2026-09-17_0330.tar.gz`. They are written
  with a `.parcial` extension and only renamed once they finish, so a half
  written copy — the disk filled up, the container was stopped — is never
  mistaken for a good one; and the old ones are pruned only after both of
  today's succeeded, so a failure piles copies up instead of leaving you with
  none.

  Three things worth knowing:
  - The container runs as **root**, so the files in `backups/` are owned by root.
    To read them as yourself: `sudo chown -R $USER backups/`.
  - The dump and the tar are **not atomic with each other**: a file uploaded
    between the two can end up in one copy and not the other. It is a gap of
    seconds, and the alternative — stopping the application every night — costs
    more than it fixes.
  - `backups/` sits on the same disk as the volumes, so it does not protect you
    from that disk dying. Point `BOXVAULT_BACKUP_DIR` at another disk or a
    network share (`/mnt/nas/boxvault`) and the copy starts covering what it is
    really for.

- **Restore** — the database and the files go separately:
  ```bash
  # 1. the database (--clean drops what is there and recreates it)
  docker compose cp backups/boxvault-db-2026-09-17_0330.dump postgres:/tmp/bv.dump
  docker compose exec postgres pg_restore -U boxvault -d boxvault --clean /tmp/bv.dump

  # 2. the attached files, with the application stopped
  docker compose stop boxvault rustfs
  docker run --rm -v boxvault-files:/data -v "$PWD/backups":/backup alpine \
    sh -c 'rm -rf /data/* && tar xzf /backup/boxvault-files-2026-09-17_0330.tar.gz -C /data'
  docker compose start rustfs boxvault
  ```
  A backup that has never been restored is not known to work: it is worth trying
  the `pg_restore` once against a scratch database (`createdb boxvault_test`,
  then `pg_restore -d boxvault_test`).
- Upgrade: `docker compose pull && docker compose up -d`.
- Logs: `docker compose logs -f boxvault` (add `postgres` for the database).
- `psql` on the server: `docker compose exec postgres psql -U boxvault -d boxvault`.

### `password authentication failed for user "boxvault"`

`POSTGRES_PASSWORD` only creates the role the **first** time the `boxvault-db`
volume is built. Changing it in `.env` afterwards changes nothing inside the
database, so the app stops being able to log in. The old password cannot be read
back — PostgreSQL only keeps a hash — but it does not need to be: set the role's
password to whatever `.env` says now, over the container's local socket, which
the official image trusts without a password.

```bash
grep POSTGRES_PASSWORD .env
docker compose exec postgres psql -U boxvault -d boxvault \
  -c "ALTER USER boxvault PASSWORD 'el-valor-de-.env';"
docker compose up -d boxvault
```

Careful with `$` in `.env`: Compose substitutes variables in those values, so
`abc$def` reaches the container cut short. Hence the `openssl rand -hex` above
instead of `-base64` — hex has no characters Compose or a shell will touch.

One confusing detail if it happens on the native image: right after the
connection error you will also see *"Cannot reflectively invoke constructor
`PostgreSQLDialect`"*. That is a consequence, not a second problem — with no
connection, Hibernate falls back to building the dialect by reflection. The hint
is registered in `NativeHints` so the real error is no longer buried, but the
thing to fix is always the connection.

### Schema and initial data

`deploy/postgres/01-schema.sql` is the hand-written schema: the tables with
their foreign keys and the indexes each repository query needs (they are
documented next to the index that serves them). `deploy/postgres/02-seed-data.sql`
is **generated** from `src/main/resources/seed-data.json`, which stays the single
source of truth; regenerate it after editing the JSON:

```bash
node deploy/postgres/generate-seed-sql.mjs
```

`spring.jpa.hibernate.ddl-auto` stays at `update` under `pro` too, so adding a
field to an entity still creates its column by itself. `update` never drops
anything, so the indexes above are safe.

`deploy/postgres/migrations/` holds the changes `update` cannot make by itself —
moving rows, dropping a table, relaxing a constraint. They are **not** run by
anything automatically: `01-schema.sql` only executes when the database volume is
created. Each file says at the top when to run it and how; the current one,
`2026-09-08-documents.sql`, moves the archived documents to the new `documents`
table with its relation tables (`client_documents`, `rental_documents`). A fresh
deployment gets the same result straight from `01-schema.sql` and must skip it. Under `dev` — no profile set, or
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
