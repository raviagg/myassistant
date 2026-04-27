# Kickstart — Personal Assistant Backend

Everything you need to build, test, and run the backend from a fresh checkout.

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 21+ | `brew install openjdk@21` |
| sbt | 1.10+ | `brew install sbt` |
| Docker | any recent | [docker.com](https://www.docker.com/get-started) (needed for integration tests) |
| k6 | 0.54+ | `brew install k6` |
| PostgreSQL | 16 (local dev) | `brew install postgresql@16` or use Docker |
| pgvector extension | — | included in `pgvector/pgvector:pg16` Docker image |

---

## Environment Variables

All variables have defaults that work for local development. Override anything that differs in your environment.

```bash
# ── Database ──────────────────────────────────────────────────
export DB_URL="jdbc:postgresql://localhost:5432/myassistant"
export DB_USER="myassistant"
export DB_PASSWORD="changeme"
export DB_POOL_SIZE=10            # optional, default 10

# ── Auth ─────────────────────────────────────────────────────
# Replace before using in any non-local environment.
export AUTH_TOKEN="dev-token-change-me-in-production"

# ── Server ───────────────────────────────────────────────────
export SERVER_HOST="0.0.0.0"     # optional, default 0.0.0.0
export SERVER_PORT=8080          # optional, default 8080

# ── File storage ─────────────────────────────────────────────
export FILE_STORAGE_BASE_PATH="/tmp/myassistant/files"  # optional
```

If you don't export these, the server uses the defaults in `backend/src/main/resources/application.conf`.

---

## Quickstart: spin up a local DB

If you don't have a PostgreSQL 16 + pgvector instance already, run it in Docker.
The `-v` flag mounts a **named volume** so data survives container restarts and `docker rm`.

```bash
docker run -d \
  --name myassistant-db \
  -e POSTGRES_DB=myassistant \
  -e POSTGRES_USER=myassistant \
  -e POSTGRES_PASSWORD=changeme \
  -p 5432:5432 \
  -v myassistant-pgdata:/var/lib/postgresql/data \
  pgvector/pgvector:pg16
```

Without `-v`, all data is stored inside the container's ephemeral layer and is lost on `docker rm`.

Useful container management commands:

```bash
docker stop myassistant-db      # stop (data kept in volume)
docker start myassistant-db     # restart — data is still there
docker rm myassistant-db        # remove container (volume survives)
docker volume rm myassistant-pgdata  # permanently delete all data
docker volume inspect myassistant-pgdata  # show volume metadata
```

Flyway runs migrations automatically on server start, so no manual schema setup is needed.

---

## Connecting via pgAdmin (or any SQL client)

Use these connection details in pgAdmin, TablePlus, DBeaver, DataGrip, or any other client:

| Field | Value |
|-------|-------|
| Host | `localhost` |
| Port | `5432` |
| Database | `myassistant` |
| Username | `myassistant` |
| Password | `changeme` |
| SSL | disabled (local dev) |

**pgAdmin step-by-step:**
1. Open pgAdmin → right-click **Servers** → **Register → Server**
2. **General** tab: Name = `myassistant-local`
3. **Connection** tab: fill in the table above
4. Click **Save**

Tables only appear after migrations have run. Either start the server once (`sbt run`) or run migrations standalone:

```bash
cd backend
sbt "runMain com.myassistant.db.MigrationRunner"
```

Alternatively, run the SQL files directly in pgAdmin's query tool in order:
`V1__create_extensions.sql` → `V8__audit.sql` (found in `backend/src/main/resources/db/migration/`).

---

## Migrating to a remote PostgreSQL

When moving to a hosted DB (AWS RDS, Supabase, Neon, etc.) you have two paths:

### Option A — Let Flyway migrate the schema (recommended for a fresh remote DB)

Just point the server at the remote DB. Flyway detects no migrations have run and applies V1–V8 automatically:

```bash
DB_URL="jdbc:postgresql://<remote-host>:5432/myassistant" \
DB_USER="<remote-user>" \
DB_PASSWORD="<remote-password>" \
AUTH_TOKEN="<your-token>" \
sbt run
```

> **pgvector on remote:** ensure the `vector` extension is available before starting.
> - **RDS:** enable the `pgvector` parameter in your parameter group (PostgreSQL 15+)
> - **Supabase / Neon:** `vector` is enabled by default
> - **Self-hosted:** `CREATE EXTENSION IF NOT EXISTS vector;` (requires the pgvector package installed)

### Option B — Dump local data and restore to remote

Use this when you want to carry existing local data across.

```bash
# 1. Dump from the local Docker container (schema + data, custom format)
docker exec myassistant-db pg_dump \
  -U myassistant \
  -d myassistant \
  -Fc \
  -f /tmp/myassistant.dump

# 2. Copy the dump file out of the container to your host
docker cp myassistant-db:/tmp/myassistant.dump ./myassistant.dump

# 3. Restore to remote
pg_restore \
  --no-owner \
  --no-privileges \
  -d "postgresql://<remote-user>:<remote-password>@<remote-host>:5432/myassistant" \
  ./myassistant.dump
```

Schema-only dump (no data, if you only want the table structure):

```bash
docker exec myassistant-db pg_dump \
  -U myassistant \
  -d myassistant \
  --schema-only \
  -Fc \
  -f /tmp/myassistant-schema.dump

docker cp myassistant-db:/tmp/myassistant-schema.dump ./myassistant-schema.dump
```

---

## Build

```bash
cd backend

# Compile main sources
sbt compile

# Compile everything including tests
sbt Test/compile

# Build a fat JAR for deployment
sbt assembly
# Output: target/scala-3.4.2/myassistant-backend-assembly-0.1.0-SNAPSHOT.jar
```

---

## Start the Server

```bash
cd backend

# Using defaults (local DB on localhost:5432)
sbt run

# With explicit env vars
DB_URL="jdbc:postgresql://localhost:5432/myassistant" \
DB_USER="myassistant" \
DB_PASSWORD="changeme" \
AUTH_TOKEN="dev-token-change-me-in-production" \
sbt run

# From the fat JAR
java -jar target/scala-3.4.2/myassistant-backend-assembly-0.1.0-SNAPSHOT.jar
```

Server starts on `http://localhost:8080`. Flyway migrations run automatically before the HTTP server binds.

Verify it's up:

```bash
curl http://localhost:8080/health
# {"status":"ok","version":"0.1.0-SNAPSHOT"}
```

---

## Unit & Integration Tests

Unit tests run entirely in-memory (no Docker, no DB). Integration tests use [Testcontainers](https://www.testcontainers.org/) — Docker must be running; a `pgvector/pgvector:pg16` container is started automatically before each suite and torn down after. Flyway migrations run inside the container, so no external DB setup is needed.

```bash
cd backend

# Run unit tests only (fast, no Docker)
sbt "testOnly com.myassistant.unit.*"

# Run integration tests only (requires Docker)
sbt "testOnly com.myassistant.integration.*"

# Run both together
sbt test

# Watch mode for unit tests during development
sbt ~"testOnly com.myassistant.unit.*"
```

**Unit test files** (`unit.*` — all in-memory, mock repositories):

| File | What it covers |
|---|---|
| `unit/services/PersonServiceSpec.scala` | Person CRUD business logic |
| `unit/services/RelationshipServiceSpec.scala` | Relationship validation and lookup |
| `unit/services/KinshipResolverSpec.scala` | BFS kinship chain resolution |
| `unit/services/HouseholdServiceSpec.scala` | Household CRUD + membership lifecycle |
| `unit/services/SchemaServiceSpec.scala` | Schema proposal, versioning, deactivation |
| `unit/services/ReferenceServiceSpec.scala` | Domains, source types, kinship aliases |
| `unit/services/AuditServiceSpec.scala` | Audit log validation (exactly one of personId/jobType) |
| `unit/routes/PersonRoutesSpec.scala` | HTTP routes called in-process via `routes.runZIO` — no server |
| `unit/middleware/ErrorMiddlewareSpec.scala` | All `AppError` → HTTP status mappings |
| `unit/middleware/MetricsMiddlewareSpec.scala` | Prometheus counter increment on request |
| `unit/LogFormatSpec.scala` | Structured log format output |
| `unit/FileRepositorySpec.scala` | File register validation + filesystem existence check |

**Integration test files** (`integration.*` — Testcontainers, real PostgreSQL):

| File | What it covers |
|---|---|
| `integration/PersonRepositorySpec.scala` | Person + Relationship CRUD + referential integrity |
| `integration/FactRepositorySpec.scala` | Fact append-only writes + document/entity-scoped reads |
| `integration/HouseholdRepositorySpec.scala` | Household CRUD + person_household membership |
| `integration/SchemaRepositorySpec.scala` | Schema creation, versioning, deactivation, seeded data |
| `integration/ReferenceRepositorySpec.scala` | Seeded domains/source types + kinship alias filtering |
| `integration/AuditRepositorySpec.scala` | Audit log persistence + pagination |

### Coverage gate (unit + integration combined)

`sbt test` runs both unit and integration tests. Their statement coverage is combined into one number and compared against a **90% minimum enforced in `build.sbt`** — the build fails if it isn't met.

```bash
cd backend

# Run all tests with coverage instrumentation and generate the HTML report
sbt coverage test coverageReport

# The 90% check runs automatically. To re-check without re-running tests:
sbt coverageAggregate
```

**Report:** `backend/target/scala-3.4.2/scoverage-report/index.html`

**What is measured:**

| Package | How it's covered |
|---|---|
| `services.*` | Unit tests (mock repositories) |
| `db.repositories.*` | Testcontainers integration tests (all 9 repos) |
| `db.DatabaseModule`, `db.MigrationRunner` | Testcontainers (called in every integration test) |
| `api.routes.*`, `api.middleware.*` | In-process route tests via `routes.runZIO` |
| `logging.*`, `monitoring.*` | Unit tests (LogFormat, MetricsMiddleware) |

**Excluded from measurement** (pure data classes and ZLayer wiring — no testable logic):
`config.*`, `domain.*`, `Main`

Threshold configuration in `backend/build.sbt`:
```scala
coverageMinimumStmtTotal := 90
coverageFailOnMinimum    := true
```

---

## End-to-End Tests (Cucumber)

Cucumber scenarios describe full user journeys and run against a live HTTP server. The test harness can start its own embedded server automatically, or point at any running instance.

### Configuration

| Env var | Default | Purpose |
|---|---|---|
| `TEST_BASE_URL` | `http://localhost:8181` | URL of the server under test |
| `TEST_AUTH_TOKEN` | `test-token` | Bearer token sent with every request |

When `TEST_BASE_URL` is **not set**, `CucumberServer` starts an embedded ZIO HTTP server on port **8181** before any scenarios run — no manual server setup needed.

When `TEST_BASE_URL` **is set**, tests target that URL instead (use this for CI against a deployed instance or a locally started server on a different port).

### Running

```bash
cd backend

# Run all scenarios (embedded server starts automatically)
sbt "testOnly com.myassistant.e2e.*"

# Run against an already-running server
TEST_BASE_URL=http://localhost:8080 \
TEST_AUTH_TOKEN=dev-token-change-me-in-production \
sbt "testOnly com.myassistant.e2e.*"

# Run a specific tag only
sbt "testOnly com.myassistant.e2e.* -- -tags @smoke"
```

**Feature files:**
- `src/test/scala/com/myassistant/e2e/PersonFeature.feature`
- `src/test/scala/com/myassistant/e2e/FactFeature.feature`

### E2E Coverage (separate from unit + integration gate)

E2E coverage is tracked in its own report and is **not** combined with the 90% unit+integration gate. It is enforced by a shell script rather than `build.sbt`, so a flaky E2E run doesn't block a source build.

```bash
cd backend

# 1. Run E2E tests with coverage instrumentation (embedded server starts automatically)
sbt coverageE2e

# 2. Check the threshold from the repo root (default: 70%)
./scripts/check-e2e-coverage.sh

# Override the threshold
E2E_COVERAGE_THRESHOLD=80 ./scripts/check-e2e-coverage.sh
```

**Report:** `backend/target/e2e-scoverage-report/index.html`

The threshold default (70%) and override mechanism live in `scripts/check-e2e-coverage.sh`. In CI, call the script as a separate step after `sbt coverageE2e` and treat its exit code as the gate.

---

## k6 Performance Tests

Requires k6 installed and a running server.

```bash
# Start the server first (see above), then:

cd backend/k6

# Smoke test — 1 VU, 1 iteration, verifies basic connectivity
k6 run \
  --env BASE_URL=http://localhost:8080 \
  --env AUTH_TOKEN=dev-token-change-me-in-production \
  smoke.js

# Load test — ramps to 50 VUs over 10 minutes, p(95) < 500ms threshold
k6 run \
  --env BASE_URL=http://localhost:8080 \
  --env AUTH_TOKEN=dev-token-change-me-in-production \
  load.js

# Load test with HTML report
k6 run \
  --env BASE_URL=http://localhost:8080 \
  --env AUTH_TOKEN=dev-token-change-me-in-production \
  --out json=results.json \
  load.js
```

---

## Run Everything in One Go

```bash
cd backend

# ── Unit + integration with coverage gate (90%) ───────────────────────────────
sbt coverage test coverageReport
# Fails the build if statement coverage < 90%
# Report: target/scala-3.4.2/scoverage-report/index.html

# ── E2E with separate coverage report ────────────────────────────────────────
sbt coverageE2e
# Report: target/e2e-scoverage-report/index.html

# ── Check E2E threshold (70% default) ────────────────────────────────────────
cd ..
./scripts/check-e2e-coverage.sh

# ── Performance smoke test (server must be running in another terminal) ───────
cd backend
k6 run --env AUTH_TOKEN=dev-token-change-me-in-production k6/smoke.js
```

---

## Sample curl Calls

Set these once and reuse across all calls below:

```bash
BASE="http://localhost:8080/api/v1"
TOKEN="dev-token-change-me-in-production"
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"
```

---

### Health

```bash
curl http://localhost:8080/health
```

---

### Persons

```bash
# Create a person
curl -s -X POST "$BASE/persons" -H "$AUTH" -H "$CT" -d '{
  "fullName": "Ravi Aggarwal",
  "preferredName": "Ravi",
  "dateOfBirth": "1990-01-15",
  "gender": "male"
}' | jq .

# List all persons
curl -s "$BASE/persons" -H "$AUTH" | jq .

# Filter by household
curl -s "$BASE/persons?householdId=<household-uuid>" -H "$AUTH" | jq .

# Get one person
curl -s "$BASE/persons/<person-uuid>" -H "$AUTH" | jq .

# Update a person
curl -s -X PATCH "$BASE/persons/<person-uuid>" -H "$AUTH" -H "$CT" -d '{
  "preferredName": "Rocky"
}' | jq .

# Delete a person
curl -s -X DELETE "$BASE/persons/<person-uuid>" -H "$AUTH"
```

---

### Households

```bash
# Create a household
curl -s -X POST "$BASE/households" -H "$AUTH" -H "$CT" -d '{
  "name": "Aggarwal Family",
  "address": "123 Main St, San Francisco, CA 94105"
}' | jq .

# List households
curl -s "$BASE/households" -H "$AUTH" | jq .

# Get one household
curl -s "$BASE/households/<household-uuid>" -H "$AUTH" | jq .

# Add a person to a household
curl -s -X POST "$BASE/households/<household-uuid>/members" -H "$AUTH" -H "$CT" -d '{
  "personId": "<person-uuid>",
  "role": "primary"
}' | jq .

# Remove a person from a household
curl -s -X DELETE "$BASE/households/<household-uuid>/members/<person-uuid>" -H "$AUTH"
```

---

### Relationships

```bash
# Create a relationship (father, mother, son, daughter, brother, sister, husband, wife)
curl -s -X POST "$BASE/relationships" -H "$AUTH" -H "$CT" -d '{
  "personIdA": "<person-uuid>",
  "personIdB": "<person-uuid>",
  "relationType": "father"
}' | jq .

# List relationships for a person
curl -s "$BASE/relationships?personId=<person-uuid>" -H "$AUTH" | jq .

# Resolve a kinship label between two people (BFS graph traversal)
curl -s "$BASE/relationships/<from-uuid>/<to-uuid>/kinship" -H "$AUTH" | jq .
# → {"chain":["father","brother"],"alias":"uncle","description":"father's brother"}

# Delete a relationship
curl -s -X DELETE "$BASE/relationships/<from-uuid>/<to-uuid>" -H "$AUTH"
```

---

### Documents

```bash
# Create a document (plain text)
curl -s -X POST "$BASE/documents" -H "$AUTH" -H "$CT" -d '{
  "personId": "<person-uuid>",
  "contentText": "My Blue Cross health insurance card. Plan: PPO Gold. Member ID: XYZ123. Group: 456789. Deductible: $1500. Premium: $320/month.",
  "sourceType": "user_input"
}' | jq .

# List documents for a person
curl -s "$BASE/documents?personId=<person-uuid>" -H "$AUTH" | jq .

# Get one document
curl -s "$BASE/documents/<document-uuid>" -H "$AUTH" | jq .

# Semantic search across documents
curl -s -X POST "$BASE/documents/search" -H "$AUTH" -H "$CT" -d '{
  "query": "health insurance deductible",
  "personId": "<person-uuid>",
  "limit": 5
}' | jq .
```

---

### Facts

```bash
# Create a fact
curl -s -X POST "$BASE/facts" -H "$AUTH" -H "$CT" -d '{
  "documentId": "<document-uuid>",
  "schemaId": "<schema-uuid>",
  "entityInstanceId": "<new-or-existing-uuid>",
  "operationType": "create",
  "fields": {
    "provider": "Blue Cross",
    "plan": "PPO Gold",
    "memberId": "XYZ123",
    "deductible": 1500,
    "premium": 320
  }
}' | jq .

# Get all facts for an entity instance (full history)
curl -s "$BASE/facts/<entity-instance-uuid>" -H "$AUTH" | jq .

# Get current merged state of an entity instance
curl -s "$BASE/facts/<entity-instance-uuid>/current" -H "$AUTH" | jq .

# List facts filtered by domain + entity type
curl -s "$BASE/facts?domain=health&entityType=insurance_card&personId=<person-uuid>" -H "$AUTH" | jq .

# Semantic search across facts
curl -s -X POST "$BASE/facts/search" -H "$AUTH" -H "$CT" -d '{
  "query": "insurance premium monthly",
  "personId": "<person-uuid>",
  "limit": 5
}' | jq .

# Aggregate numeric fields across facts
curl -s -X POST "$BASE/facts/aggregate" -H "$AUTH" -H "$CT" -d '{
  "domain": "finance",
  "entityType": "payslip",
  "field": "net",
  "operation": "SUM",
  "personId": "<person-uuid>"
}' | jq .
# → {"operation":"SUM","field":"net","result":87500.00,"count":12}
```

---

### Schema Governance

```bash
# List all entity type schemas
curl -s "$BASE/schemas" -H "$AUTH" | jq .

# Get the current (latest) schema for a domain/type
curl -s "$BASE/schemas/health/insurance_card" -H "$AUTH" | jq .

# Get all versions of a schema
curl -s "$BASE/schemas/health/insurance_card/versions" -H "$AUTH" | jq .

# Create a new entity type schema (immediately active, schema_version=1)
curl -s -X POST "$BASE/schemas" -H "$AUTH" -H "$CT" -d '{
  "domain": "health",
  "entityType": "prescription",
  "fieldDefinitions": [
    {"name": "medication",  "type": "text",    "required": true},
    {"name": "dosage",      "type": "text",    "required": true},
    {"name": "prescribedBy","type": "text",    "required": false},
    {"name": "startDate",   "type": "date",    "required": false},
    {"name": "refills",     "type": "number",  "required": false}
  ]
}' | jq .

# Update a schema (bumps version, old version retained)
curl -s -X POST "$BASE/schemas/health/prescription/versions" -H "$AUTH" -H "$CT" -d '{
  "fieldDefinitions": [
    {"name": "medication",  "type": "text",    "required": true},
    {"name": "dosage",      "type": "text",    "required": true},
    {"name": "prescribedBy","type": "text",    "required": false},
    {"name": "startDate",   "type": "date",    "required": false},
    {"name": "endDate",     "type": "date",    "required": false},
    {"name": "refills",     "type": "number",  "required": false},
    {"name": "pharmacy",    "type": "text",    "required": false}
  ]
}' | jq .

# Deactivate a schema
curl -s -X DELETE "$BASE/schemas/health/prescription/active" -H "$AUTH"
```

---

### Reference Data

```bash
# List all domains
curl -s "$BASE/reference/domains" -H "$AUTH" | jq .
# → [{"name":"health","description":"..."},{"name":"finance",...},...]

# List all source types
curl -s "$BASE/reference/source-types" -H "$AUTH" | jq .
```

---

### Audit Log

```bash
# List audit entries for a person
curl -s "$BASE/audit?personId=<person-uuid>&limit=20" -H "$AUTH" | jq .

# List audit entries for a job type
curl -s "$BASE/audit?jobType=plaid_poll&limit=20" -H "$AUTH" | jq .

# Get a specific audit entry
curl -s "$BASE/audit/<audit-uuid>" -H "$AUTH" | jq .
```

---

### File Uploads

```bash
# Upload a file (PDF, image, etc.)
curl -s -X POST "$BASE/files" \
  -H "$AUTH" \
  -F "file=@/path/to/insurance-card.pdf" \
  -F "personId=<person-uuid>" \
  -F "description=Blue Cross insurance card scan" \
  | jq .

# Get file metadata
curl -s "$BASE/files/<file-uuid>" -H "$AUTH" | jq .

# Download file content
curl -s "$BASE/files/<file-uuid>/content" -H "$AUTH" -o downloaded.pdf
```

---

## Prometheus Metrics

Metrics are exposed at `GET /metrics` (no auth required):

```bash
curl http://localhost:8080/metrics
```

Key counters:
- `http_requests_total{method, path, status}` — request count by endpoint
- `http_request_duration_seconds{method, path}` — latency histogram
