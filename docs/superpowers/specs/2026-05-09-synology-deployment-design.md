# Synology Deployment Design

**Date:** 2026-05-09
**Branch:** feature/implement-cicd
**Status:** Approved

---

## Overview

Deploy the personal assistant on a Synology DS220+ NAS and expose it publicly via a Cloudflare tunnel at `assistant.raviagg.com`. Access is protected by Cloudflare Access (Zero Trust), allowing a per-email allowlist. CI/CD runs via GitHub Actions with a self-hosted runner on Synology — push to `main` = deployed.

---

## Target Infrastructure

- **Hardware:** Synology DS220+ (x86_64, DSM 7.2, ~992 MB RAM available after package cleanup)
- **Public URL:** `assistant.raviagg.com`
- **Auth:** Cloudflare Access — email allowlist (e.g. raaggarw@adobe.com + wife's email)
- **Container runtime:** Docker via Synology Container Manager
- **Image registry:** GitHub Container Registry (ghcr.io/raviagg/myassistant/*)

---

## Services

Five containers managed by `docker-compose.yml`:

| Service | Image | Network exposure | Notes |
|---|---|---|---|
| `postgres` | postgres:16-alpine | internal only | Persistent volume on Synology |
| `http-server` | ghcr.io/raviagg/myassistant/http-server | internal only | Scala/ZIO fat JAR; Flyway runs on startup |
| `chatbot-server` | ghcr.io/raviagg/myassistant/chatbot-server | internal only | Python FastAPI; calls http-server |
| `frontend` | ghcr.io/raviagg/myassistant/frontend | port 80 | nginx: serves React + proxies /api/* |
| `cloudflared` | cloudflare/cloudflared | outbound only | Tunnel → frontend:80 |

`mcp_server` is **not deployed** — it is a development tool for Claude Code only.

---

## Data Flow

```
Browser
  → Cloudflare Access (auth gate — email allowlist)
  → cloudflared tunnel (621dccd4-1d81-4bdd-aff6-089cb5bfa9a8)
  → frontend nginx:80
      /api/*  → chatbot-server:8000
      /*      → static React files (built into image)

chatbot-server → http-server:8080  (Bearer: CHATBOT_AUTH_TOKEN)
http-server    → postgres:5432
http-server    → /data/files  (uploaded files volume)
```

All containers share a private `app-net` bridge network. Only `frontend` and `cloudflared` have any external exposure.

---

## Persistent Storage

Two host paths mounted into containers:

| Host path (Synology) | Container path | Used by |
|---|---|---|
| `/volume1/docker/myassistant/postgres-data` | `/var/lib/postgresql/data` | postgres |
| `/volume1/docker/myassistant/uploads` | `/data/files` | http-server |

Both survive container restarts and redeploys.

---

## Environment Variables

`.env` file lives at `/volume1/docker/myassistant/.env` on Synology. **Never committed to git.**

```
# PostgreSQL container
POSTGRES_USER=myassistant
POSTGRES_PASSWORD=<strong-random-password>
POSTGRES_DB=myassistant

# http-server (Scala)
DB_URL=jdbc:postgresql://postgres:5432/myassistant
DB_USER=myassistant
DB_PASSWORD=<same as POSTGRES_PASSWORD>
AUTH_TOKEN=<random-hex-32>
FILE_STORAGE_BASE_PATH=/data/files

# chatbot-server (Python)
CHATBOT_HTTP_URL=http://http-server:8080
CHATBOT_AUTH_TOKEN=<same value as AUTH_TOKEN>
ANTHROPIC_API_KEY=sk-ant-...
```

`AUTH_TOKEN` and `CHATBOT_AUTH_TOKEN` must be the same value — http-server reads `AUTH_TOKEN`, chatbot-server reads `CHATBOT_AUTH_TOKEN` and sends it as a Bearer token.

Generate random values:
```bash
openssl rand -hex 32   # for POSTGRES_PASSWORD, AUTH_TOKEN / CHATBOT_AUTH_TOKEN
```

---

## Authentication

**Cloudflare Access (Zero Trust) — no code changes required.**

Configuration in Cloudflare Zero Trust dashboard:
1. Create an Application → Self-hosted → `assistant.raviagg.com`
2. Create a Policy → Allow → Email list:
   - `raaggarw@adobe.com`
   - `<wife's email>`
3. Auth method: One-time PIN (email OTP) or Google

All other visitors receive a Cloudflare 403 before traffic reaches Synology.

---

## CI/CD Pipeline

### Jobs

| Job | Runs on | Triggers |
|---|---|---|
| `build` | GitHub-hosted (ubuntu-latest) | push to `main`, any PR |
| `deploy` | Self-hosted runner (Synology) | push to `main` only |

### Build job steps
1. Checkout repo
2. Set up JDK 21 + sbt cache
3. `sbt assembly` → fat JAR
4. Docker build & push `http-server` → `ghcr.io/raviagg/myassistant/http-server:latest`
5. Docker build & push `chatbot-server` → `ghcr.io/raviagg/myassistant/chatbot-server:latest`
6. Docker build & push `frontend` → `ghcr.io/raviagg/myassistant/frontend:latest`

### Deploy job steps (self-hosted runner on Synology)
1. Copy `docker-compose.yml` from repo → `/volume1/docker/myassistant/docker-compose.yml`
2. `docker compose pull` — pull new images
3. `docker compose up -d` — restart changed containers only

### Self-hosted runner
- Installed once at `/volume1/docker/github-runner/`
- Polls GitHub outbound — no inbound ports, no SSH keys needed
- Runs as a persistent background process (DSM Task Scheduler on boot)

---

## Files to Create

All new files — no existing files modified:

```
myassistant/
  docker-compose.yml
  .github/
    workflows/
      deploy.yml
  backend/
    http_server/
      Dockerfile
    chatbot_server/
      Dockerfile
  frontend/
    Dockerfile          (multi-stage: node build → nginx:alpine)
    nginx.conf          (serves static files, proxies /api/* to chatbot-server:8000)
```

---

## One-Time Synology Setup (manual, done once)

1. **Fix docker socket permissions permanently** via DSM Task Scheduler:
   - Triggered on boot
   - Command: `chown root:docker /var/run/docker.sock`

2. **Create data directories:**
   ```bash
   mkdir -p /volume1/docker/myassistant/postgres-data
   mkdir -p /volume1/docker/myassistant/uploads
   ```

3. **Create `.env`:**
   ```bash
   cat > /volume1/docker/myassistant/.env <<EOF
   POSTGRES_PASSWORD=...
   ANTHROPIC_API_KEY=...
   CHATBOT_AUTH_TOKEN=...
   FILE_STORAGE_BASE_PATH=/data/files
   EOF
   ```

4. **Register GitHub Actions self-hosted runner:**
   - GitHub repo → Settings → Actions → Runners → New self-hosted runner
   - Follow instructions on Synology SSH
   - Add runner as a boot task in DSM Task Scheduler

5. **Configure Cloudflare Access** in Zero Trust dashboard:
   - Add application for `assistant.raviagg.com`
   - Add email allowlist policy

---

## JVM Memory Tuning

The Scala server runs with constrained heap to fit within available RAM:

```
JAVA_OPTS=-Xms64m -Xmx256m
```

PostgreSQL uses default settings (sufficient for personal use at low concurrency).

---

## Out of Scope

- HTTPS termination (handled by Cloudflare)
- TLS certificates (handled by Cloudflare)
- Backup strategy for postgres-data and uploads volumes
- Multi-user DB isolation (single shared DB, auth handled at Cloudflare layer)
