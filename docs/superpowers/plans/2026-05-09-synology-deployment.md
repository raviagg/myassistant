# Synology Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Containerize all three app services and deploy them on a Synology DS220+ NAS with a Cloudflare tunnel at `assistant.raviagg.com`, with one-click CI/CD via GitHub Actions + self-hosted runner.

**Architecture:** Five Docker containers (postgres, http-server, chatbot-server, frontend/nginx, cloudflared) managed by docker-compose.yml on Synology. GitHub Actions builds images on push to main and pushes to ghcr.io; a self-hosted runner on Synology pulls and restarts changed containers. Cloudflare Access protects the app with an email allowlist.

**Tech Stack:** Docker, docker-compose v2, GitHub Actions, ghcr.io, nginx:alpine, eclipse-temurin:21-jre-jammy, python:3.11-slim, node:20-alpine, cloudflare/cloudflared, Cloudflare Zero Trust Access.

**Spec:** `docs/superpowers/specs/2026-05-09-synology-deployment-design.md`

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `backend/http_server/Dockerfile` | Create | Build Scala fat JAR into minimal JRE image |
| `backend/chatbot_server/Dockerfile` | Create | Python FastAPI server image |
| `frontend/Dockerfile` | Create | Multi-stage: node build → nginx:alpine |
| `frontend/nginx.conf` | Create | Serve React SPA, proxy /api/* to chatbot-server with SSE support |
| `docker-compose.yml` | Create | Orchestrate all 5 services with health checks and volumes |
| `.github/workflows/deploy.yml` | Create | Build images + deploy to Synology on push to main |

---

## Task 1: http-server Dockerfile (Scala)

**Files:**
- Create: `backend/http_server/Dockerfile`

The Scala server uses Tess4j (OCR) which requires the system `tesseract-ocr` binary. Use `eclipse-temurin:21-jre-jammy` (Debian-based) to install it. GitHub Actions runs `sbt assembly` separately before `docker build`, so this Dockerfile just copies the pre-built JAR.

- [ ] **Step 1: Verify the JAR path matches what sbt produces**

```bash
# In backend/http_server, run sbt assembly locally first to confirm JAR path
cd backend/http_server
sbt assembly
ls target/scala-3.4.2/myassistant-backend.jar
# Expected: file exists
```

- [ ] **Step 2: Create the Dockerfile**

```dockerfile
# backend/http_server/Dockerfile
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr wget \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/scala-3.4.2/myassistant-backend.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
    CMD wget -qO- http://localhost:8080/health || exit 1

ENV JAVA_OPTS="-Xms64m -Xmx256m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

- [ ] **Step 3: Build the image locally to confirm it works**

```bash
cd backend/http_server
docker build -t myassistant-http-server:test .
# Expected: Successfully built <hash>
```

- [ ] **Step 4: Confirm the image starts (will fail DB connection — that's fine)**

```bash
docker run --rm -e DB_URL=jdbc:postgresql://localhost:5432/test myassistant-http-server:test
# Expected: starts, logs DB connection error — NOT a startup crash
# Ctrl+C to stop
```

- [ ] **Step 5: Commit**

```bash
git add backend/http_server/Dockerfile
git commit -m "feat(deploy): add Dockerfile for Scala http-server"
```

---

## Task 2: chatbot-server Dockerfile (Python)

**Files:**
- Create: `backend/chatbot_server/Dockerfile`

Uses `pyproject.toml` with hatchling. Install with `pip install .` to pull all dependencies. The entry point is `uvicorn main:app`.

- [ ] **Step 1: Create the Dockerfile**

```dockerfile
# backend/chatbot_server/Dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY pyproject.toml .
RUN pip install --no-cache-dir . && pip install --no-cache-dir "uvicorn[standard]>=0.30"

COPY . .

EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 2: Build the image locally**

```bash
cd backend/chatbot_server
docker build -t myassistant-chatbot-server:test .
# Expected: Successfully built <hash>
```

- [ ] **Step 3: Confirm container starts (will fail — no ANTHROPIC_API_KEY — that's fine)**

```bash
docker run --rm -e CHATBOT_HTTP_URL=http://localhost:8080 myassistant-chatbot-server:test
# Expected: uvicorn starts on port 8000, logs are visible
# Ctrl+C to stop
```

- [ ] **Step 4: Commit**

```bash
git add backend/chatbot_server/Dockerfile
git commit -m "feat(deploy): add Dockerfile for Python chatbot-server"
```

---

## Task 3: frontend Dockerfile + nginx.conf

**Files:**
- Create: `frontend/Dockerfile`
- Create: `frontend/nginx.conf`

Multi-stage build: `node:20-alpine` builds the React app, `nginx:alpine` serves the static files. nginx proxies `/api/*` to `chatbot-server:8000` with SSE streaming support (buffering off).

- [ ] **Step 1: Create nginx.conf**

```nginx
# frontend/nginx.conf
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    # Proxy all /api/* to chatbot-server
    # proxy_buffering off is required for SSE streaming (chat responses)
    location /api/ {
        proxy_pass         http://chatbot-server:8000;
        proxy_http_version 1.1;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_set_header   Connection '';
        proxy_buffering    off;
        proxy_cache        off;
        chunked_transfer_encoding on;
    }

    # React SPA — serve index.html for all non-file routes
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

- [ ] **Step 2: Create the Dockerfile**

```dockerfile
# frontend/Dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

- [ ] **Step 3: Build the image locally**

```bash
cd frontend
docker build -t myassistant-frontend:test .
# Expected: Successfully built <hash>
# The npm build step should produce a dist/ directory
```

- [ ] **Step 4: Run the container and test static file serving**

```bash
docker run --rm -p 8081:80 myassistant-frontend:test
# In another terminal:
curl -s http://localhost:8081 | head -5
# Expected: <!doctype html> ...  (React app HTML)
# Ctrl+C to stop
```

- [ ] **Step 5: Commit**

```bash
git add frontend/Dockerfile frontend/nginx.conf
git commit -m "feat(deploy): add Dockerfile and nginx.conf for frontend"
```

---

## Task 4: docker-compose.yml

**Files:**
- Create: `docker-compose.yml` (repo root)

Orchestrates all 5 services. Uses `env_file: /volume1/docker/myassistant/.env` (absolute path on Synology — this path is only resolved at runtime on Synology, not locally). Health checks ensure services start in order.

- [ ] **Step 1: Create docker-compose.yml**

```yaml
# docker-compose.yml
services:

  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    volumes:
      - /volume1/docker/myassistant/postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - app-net

  http-server:
    image: ghcr.io/raviagg/myassistant/http-server:latest
    restart: unless-stopped
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      DB_USER: ${POSTGRES_USER}
      DB_PASSWORD: ${POSTGRES_PASSWORD}
      AUTH_TOKEN: ${AUTH_TOKEN}
      FILE_STORAGE_BASE_PATH: /data/files
      JAVA_OPTS: -Xms64m -Xmx256m
    volumes:
      - /volume1/docker/myassistant/uploads:/data/files
    depends_on:
      postgres:
        condition: service_healthy
    networks:
      - app-net

  chatbot-server:
    image: ghcr.io/raviagg/myassistant/chatbot-server:latest
    restart: unless-stopped
    environment:
      CHATBOT_HTTP_URL: http://http-server:8080
      CHATBOT_AUTH_TOKEN: ${AUTH_TOKEN}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      CORS_ORIGIN: https://assistant.raviagg.com
    depends_on:
      http-server:
        condition: service_healthy
    networks:
      - app-net

  frontend:
    image: ghcr.io/raviagg/myassistant/frontend:latest
    restart: unless-stopped
    depends_on:
      - chatbot-server
    networks:
      - app-net

  cloudflared:
    image: cloudflare/cloudflared:latest
    restart: unless-stopped
    command: tunnel --no-autoupdate run --token ${CLOUDFLARE_TUNNEL_TOKEN}
    environment:
      TUNNEL_TOKEN: ${CLOUDFLARE_TUNNEL_TOKEN}
    depends_on:
      - frontend
    networks:
      - app-net

networks:
  app-net:
    driver: bridge
```

- [ ] **Step 2: Validate docker-compose.yml syntax**

```bash
# At repo root — use a local .env for syntax check only
cat > /tmp/test.env <<EOF
POSTGRES_USER=test
POSTGRES_PASSWORD=test
POSTGRES_DB=test
AUTH_TOKEN=test
ANTHROPIC_API_KEY=test
CLOUDFLARE_TUNNEL_TOKEN=test
EOF
docker compose --env-file /tmp/test.env config
# Expected: prints resolved YAML with no errors
rm /tmp/test.env
```

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(deploy): add docker-compose.yml for all 5 services"
```

---

## Task 5: GitHub Actions workflow

**Files:**
- Create: `.github/workflows/deploy.yml`

Two jobs: `build` runs on GitHub servers (builds + pushes images to ghcr.io), `deploy` runs on the self-hosted Synology runner (pulls + restarts containers). PRs trigger build only.

- [ ] **Step 1: Create the workflow directory**

```bash
mkdir -p .github/workflows
```

- [ ] **Step 2: Create deploy.yml**

```yaml
# .github/workflows/deploy.yml
name: Build and Deploy

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'sbt'

      - name: Build Scala fat JAR
        working-directory: backend/http_server
        run: sbt assembly

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Build and push http-server
        uses: docker/build-push-action@v6
        with:
          context: backend/http_server
          push: ${{ github.ref == 'refs/heads/main' }}
          tags: ghcr.io/${{ github.repository }}/http-server:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

      - name: Build and push chatbot-server
        uses: docker/build-push-action@v6
        with:
          context: backend/chatbot_server
          push: ${{ github.ref == 'refs/heads/main' }}
          tags: ghcr.io/${{ github.repository }}/chatbot-server:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

      - name: Build and push frontend
        uses: docker/build-push-action@v6
        with:
          context: frontend
          push: ${{ github.ref == 'refs/heads/main' }}
          tags: ghcr.io/${{ github.repository }}/frontend:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

  deploy:
    needs: build
    runs-on: self-hosted
    if: github.ref == 'refs/heads/main'

    steps:
      - uses: actions/checkout@v4

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Deploy
        run: |
          cp docker-compose.yml /volume1/docker/myassistant/docker-compose.yml
          cd /volume1/docker/myassistant
          docker compose pull
          docker compose up -d
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/deploy.yml
git commit -m "feat(deploy): add GitHub Actions build and deploy workflow"
```

---

## Task 6: One-Time Synology Setup

This task is performed manually on Synology via SSH. Run each step once before the first deploy.

**6a — Get the Cloudflare tunnel token**

- [ ] **Step 1: Get the tunnel token (run on Synology SSH)**

```bash
sudo cloudflared tunnel token myassistant
# Outputs a long base64 token — copy it, you'll need it for .env
```

**6b — Create persistent data directories and .env**

- [ ] **Step 2: Create directories**

```bash
sudo mkdir -p /volume1/docker/myassistant/postgres-data
sudo mkdir -p /volume1/docker/myassistant/uploads
sudo chown -R aggarwalnas:users /volume1/docker/myassistant
```

- [ ] **Step 3: Generate strong random secrets**

```bash
# Run twice — once for POSTGRES_PASSWORD, once for AUTH_TOKEN
openssl rand -hex 32
openssl rand -hex 32
```

- [ ] **Step 4: Create .env**

```bash
cat > /volume1/docker/myassistant/.env <<'EOF'
# PostgreSQL
POSTGRES_USER=myassistant
POSTGRES_PASSWORD=<paste POSTGRES_PASSWORD from openssl>
POSTGRES_DB=myassistant

# Scala http-server
AUTH_TOKEN=<paste AUTH_TOKEN from openssl>

# Python chatbot-server
ANTHROPIC_API_KEY=<your sk-ant-... key>

# Cloudflare tunnel
CLOUDFLARE_TUNNEL_TOKEN=<paste token from cloudflared tunnel token>
EOF
chmod 600 /volume1/docker/myassistant/.env
```

**6c — Fix docker socket permissions permanently**

- [ ] **Step 5: Create a boot task in DSM Task Scheduler**

> DSM web UI → Control Panel → Task Scheduler → Create → Triggered Task → User-defined script
> - Task name: `fix-docker-socket`
> - Event: Boot-up
> - Run as: root
> - Script: `chown root:docker /var/run/docker.sock`
> → Save

**6d — Install GitHub Actions self-hosted runner**

- [ ] **Step 6: Get the runner registration token from GitHub**

> GitHub → raviagg/myassistant → Settings → Actions → Runners → New self-hosted runner
> - OS: Linux, Architecture: x64
> - Copy the `./config.sh --url ... --token ...` command shown on the page

- [ ] **Step 7: Install the runner on Synology**

```bash
# On Synology SSH
mkdir -p /volume1/docker/github-runner
cd /volume1/docker/github-runner

# Use the exact download URL shown on GitHub Settings → Runners → New self-hosted runner page
# It looks like:
# curl -o actions-runner-linux-x64.tar.gz -L https://github.com/actions/runner/releases/download/v<VERSION>/actions-runner-linux-x64-<VERSION>.tar.gz
# Copy and run the exact command GitHub shows you, then:
tar xzf actions-runner-linux-x64.tar.gz

# Register runner (paste the full command from GitHub Settings page)
./config.sh --url https://github.com/raviagg/myassistant --token <TOKEN_FROM_GITHUB>
# Accept defaults when prompted
```

- [ ] **Step 8: Make the runner start on boot via DSM Task Scheduler**

> DSM web UI → Control Panel → Task Scheduler → Create → Triggered Task → User-defined script
> - Task name: `github-runner`
> - Event: Boot-up
> - Run as: aggarwalnas
> - Script:
> ```bash
> cd /volume1/docker/github-runner && ./run.sh &
> ```
> → Save

- [ ] **Step 9: Start the runner now (without rebooting)**

```bash
cd /volume1/docker/github-runner
./run.sh &
# Expected: "Listening for Jobs" in output
```

> Back on GitHub Settings → Runners — your runner should show as **Idle** (green dot).

**6e — Configure Cloudflare Access**

- [ ] **Step 10: Set up Cloudflare Access**

> cloudflare.com → Zero Trust → Access → Applications → Add an application
> - Type: Self-hosted
> - Application name: Personal Assistant
> - Application domain: `assistant.raviagg.com`
> → Next
>
> Policy:
> - Policy name: Email allowlist
> - Action: Allow
> - Include rule: Emails → `raaggarw@adobe.com`
> - Add another: Emails → `<wife's email>`
> → Save

---

## Task 7: First Deploy and Smoke Test

- [ ] **Step 1: Push to main to trigger the pipeline**

```bash
# From your dev machine
git push origin main
# Watch GitHub Actions → Actions tab → Build and Deploy workflow
```

- [ ] **Step 2: Monitor the build job (runs on GitHub servers)**

Expected sequence in GitHub Actions UI:
1. Build Scala fat JAR (~3-5 min)
2. Build and push http-server (~2 min)
3. Build and push chatbot-server (~1 min)
4. Build and push frontend (~1 min)

- [ ] **Step 3: Monitor the deploy job (runs on Synology)**

Expected in GitHub Actions UI:
```
✓ Checkout
✓ Log in to GitHub Container Registry
✓ Deploy
  Pulling http-server ... done
  Pulling chatbot-server ... done
  Pulling frontend ... done
  ...
  Creating myassistant_postgres_1 ... done
  Creating myassistant_http-server_1 ... done
  ...
```

- [ ] **Step 4: Verify all containers are running on Synology**

```bash
# On Synology SSH
cd /volume1/docker/myassistant
sudo docker compose ps
# Expected: all 5 services showing "running" or "healthy"
```

- [ ] **Step 5: End-to-end test**

Open `https://assistant.raviagg.com` in your browser.
Expected:
1. Cloudflare Access login page appears
2. Enter `raaggarw@adobe.com` → receive OTP email → enter code
3. App loads — login screen appears
4. Log in and verify chat works

- [ ] **Step 6: Stop the nginx hello-world test container if still running**

```bash
# On Synology SSH
sudo docker stop hello-world && sudo docker rm hello-world 2>/dev/null || true
```

---

## Environment Variable Reference

Full list of what goes in `/volume1/docker/myassistant/.env` on Synology:

```
# PostgreSQL
POSTGRES_USER=myassistant
POSTGRES_PASSWORD=<openssl rand -hex 32>
POSTGRES_DB=myassistant

# Scala http-server (read as AUTH_TOKEN)
AUTH_TOKEN=<openssl rand -hex 32>

# Python chatbot-server
ANTHROPIC_API_KEY=sk-ant-...

# Cloudflare tunnel
CLOUDFLARE_TUNNEL_TOKEN=<cloudflared tunnel token myassistant>
```

Note: `CHATBOT_AUTH_TOKEN` is NOT in .env — docker-compose.yml maps `AUTH_TOKEN` → `CHATBOT_AUTH_TOKEN` in the chatbot-server's environment block, so the same value is used for both services without duplication.
