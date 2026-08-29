# Deploying BoxVault

The backend image contains the whole application: the Spring Boot API plus the
frontend bundle committed in `src/main/resources/static/`.

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

## Local build without the pipeline

```bash
docker build -t boxvault-backend .
docker run --rm -p 8088:8088 -v boxvault-data:/data \
  -e SPRING_DATASOURCE_URL="jdbc:h2:file:/data/boxvault;DB_CLOSE_ON_EXIT=FALSE" boxvault-backend
```
