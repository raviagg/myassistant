#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT/.logs"
mkdir -p "$LOG_DIR"

# Load .env if present
if [ -f "$ROOT/.env" ]; then
  set -o allexport
  # shellcheck source=/dev/null
  source "$ROOT/.env"
  set +o allexport
fi

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()    { echo -e "${GREEN}[start]${NC} $*"; }
warn()    { echo -e "${YELLOW}[warn]${NC}  $*"; }
error()   { echo -e "${RED}[error]${NC} $*"; }

# ── Cleanup on exit ───────────────────────────────────────────────────────────
PIDS=()
CONTAINERS=()
cleanup() {
  info "Shutting down..."
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  for container in "${CONTAINERS[@]}"; do
    docker stop "$container" 2>/dev/null || true
  done
}
trap cleanup EXIT INT TERM

# ── 1. Database ───────────────────────────────────────────────────────────────
info "Starting database..."
if docker ps --format '{{.Names}}' | grep -q '^myassistant-db$'; then
  info "  DB container already running."
elif docker ps -a --format '{{.Names}}' | grep -q '^myassistant-db$'; then
  docker start myassistant-db
  info "  DB container restarted."
else
  warn "  Container 'myassistant-db' not found. Creating it..."
  docker run -d \
    --name myassistant-db \
    -e POSTGRES_DB=myassistant \
    -e POSTGRES_USER=myassistant \
    -e POSTGRES_PASSWORD=changeme \
    -p 5432:5432 \
    -v myassistant-pgdata:/var/lib/postgresql/data \
    pgvector/pgvector:pg16
  info "  DB container created."
fi

# Wait for postgres to accept connections
info "  Waiting for PostgreSQL to be ready..."
until docker exec myassistant-db pg_isready -U myassistant -q 2>/dev/null; do
  sleep 1
done
info "  PostgreSQL is ready."

# ── 2. Scala HTTP server ──────────────────────────────────────────────────────
HTTP_PORT="${SERVER_PORT:-8080}"
info "Starting Scala HTTP server (port $HTTP_PORT)..."
(
  cd "$ROOT/backend/http_server"
  sbt run
) > "$LOG_DIR/http_server.log" 2>&1 &
PIDS+=($!)
HTTP_PID=$!
info "  Scala server PID=$HTTP_PID — logs: $LOG_DIR/http_server.log"

# Wait for health endpoint
info "  Waiting for Scala server to be ready..."
for i in $(seq 1 120); do
  if curl -sf "http://localhost:${HTTP_PORT}/health" > /dev/null 2>&1; then
    info "  Scala server is ready."
    break
  fi
  if ! kill -0 "$HTTP_PID" 2>/dev/null; then
    error "  Scala server process died. Check $LOG_DIR/http_server.log"
    exit 1
  fi
  sleep 2
done

# ── 3. Chatbot server ─────────────────────────────────────────────────────────
if [ -z "${BEDROCK_API_KEY:-}" ]; then
  warn "BEDROCK_API_KEY is not set — chatbot server may fail to authenticate."
fi

CHATBOT_PORT="${CHATBOT_PORT:-8000}"
info "Starting chatbot server (port $CHATBOT_PORT)..."
(
  cd "$ROOT/backend/chatbot_server"
  uvicorn main:app --reload --port "$CHATBOT_PORT"
) > "$LOG_DIR/chatbot_server.log" 2>&1 &
PIDS+=($!)
info "  Chatbot server PID=${PIDS[-1]} — logs: $LOG_DIR/chatbot_server.log"

# Wait for chatbot server
info "  Waiting for chatbot server to be ready..."
for i in $(seq 1 30); do
  if curl -sf "http://localhost:${CHATBOT_PORT}" > /dev/null 2>&1; then
    info "  Chatbot server is ready."
    break
  fi
  sleep 1
done

# ── 4. Frontend ───────────────────────────────────────────────────────────────
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
info "Starting frontend (port $FRONTEND_PORT)..."
(
  cd "$ROOT/frontend"
  npm run dev
) > "$LOG_DIR/frontend.log" 2>&1 &
PIDS+=($!)
info "  Frontend PID=${PIDS[-1]} — logs: $LOG_DIR/frontend.log"

# ── 5. Scheduler ──────────────────────────────────────────────────────────────
if [ -z "${NEWSAPIAI_KEY:-}" ]; then
  warn "NEWSAPIAI_KEY is not set — skipping scheduler (news polling will not run)."
else
  info "Starting scheduler..."
  (
    cd "$ROOT/backend/scheduler"
    HTTP_SERVER_URL="http://localhost:${HTTP_PORT}" \
    AUTH_TOKEN="${AUTH_TOKEN:-}" \
    NEWSAPIAI_KEY="${NEWSAPIAI_KEY}" \
    WEB_SEARCH_PROVIDER="${WEB_SEARCH_PROVIDER:-duckduckgo}" \
    BRAVE_API_KEY="${BRAVE_API_KEY:-}" \
    SCHEDULER_TIMEZONE="${SCHEDULER_TIMEZONE:-UTC}" \
    python -u main.py
  ) > "$LOG_DIR/scheduler.log" 2>&1 &
  PIDS+=($!)
  info "  Scheduler PID=${PIDS[-1]} — logs: $LOG_DIR/scheduler.log"
fi

# ── 6. Observability (Loki + Promtail + Grafana) ─────────────────────────────
OBS_NETWORK="myassistant-obs"
docker network inspect "$OBS_NETWORK" > /dev/null 2>&1 || docker network create "$OBS_NETWORK"

# Loki
if docker ps --format '{{.Names}}' | grep -q '^myassistant-loki$'; then
  info "Loki already running."
else
  docker rm -f myassistant-loki 2>/dev/null || true
  docker run -d \
    --name myassistant-loki \
    --network "$OBS_NETWORK" \
    -p 3100:3100 \
    -v myassistant-loki-data:/loki \
    -v "$ROOT/observability/loki-config.yml:/etc/loki/config.yml:ro" \
    grafana/loki:latest \
    -config.file=/etc/loki/config.yml
  info "Loki started — port 3100"
fi
CONTAINERS+=(myassistant-loki)

# Promtail (reads from .logs/)
if docker ps --format '{{.Names}}' | grep -q '^myassistant-promtail$'; then
  info "Promtail already running."
else
  docker rm -f myassistant-promtail 2>/dev/null || true
  docker run -d \
    --name myassistant-promtail \
    --network "$OBS_NETWORK" \
    -v "$ROOT/.logs:/logs:ro" \
    -v "$ROOT/observability/promtail-config-local.yml:/etc/promtail/config.yml:ro" \
    grafana/promtail:latest \
    -config.file=/etc/promtail/config.yml
  info "Promtail started — tailing $LOG_DIR/"
fi
CONTAINERS+=(myassistant-promtail)

# Grafana
GRAFANA_PASSWORD="${GRAFANA_PASSWORD:-admin}"
if docker ps --format '{{.Names}}' | grep -q '^myassistant-grafana$'; then
  info "Grafana already running."
else
  docker rm -f myassistant-grafana 2>/dev/null || true
  docker run -d \
    --name myassistant-grafana \
    --network "$OBS_NETWORK" \
    -p 3001:3000 \
    -v myassistant-grafana-data:/var/lib/grafana \
    -v "$ROOT/observability/grafana/provisioning:/etc/grafana/provisioning:ro" \
    -e GF_SECURITY_ADMIN_PASSWORD="$GRAFANA_PASSWORD" \
    -e GF_USERS_ALLOW_SIGN_UP=false \
    -e GF_AUTH_ANONYMOUS_ENABLED=false \
    grafana/grafana:latest
  info "Grafana started — http://localhost:3001 (admin / $GRAFANA_PASSWORD)"
fi
CONTAINERS+=(myassistant-grafana)

# ── Ready ─────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}All services started.${NC}"
echo "  DB (postgres)    → localhost:5432"
echo "  HTTP server      → http://localhost:${HTTP_PORT}"
echo "  Chatbot server   → http://localhost:${CHATBOT_PORT}"
echo "  Frontend         → http://localhost:${FRONTEND_PORT}"
if [ -n "${NEWSAPIAI_KEY:-}" ]; then
  echo "  Scheduler        → background (polls every 60s)"
fi
echo ""
echo "Logs: $LOG_DIR/"
echo "  Grafana          → http://localhost:3001 (admin / ${GRAFANA_PASSWORD:-admin})"
echo "Press Ctrl+C to stop all services."
echo ""

wait
