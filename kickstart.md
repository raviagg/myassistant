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

If you don't have a PostgreSQL 16 + pgvector instance already:

```bash
docker run -d \
  --name myassistant-db \
  -e POSTGRES_DB=myassistant \
  -e POSTGRES_USER=myassistant \
  -e POSTGRES_PASSWORD=changeme \
  -p 5432:5432 \
  pgvector/pgvector:pg16
```

Flyway runs migrations automatically on server start, so no manual schema setup is needed.

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

## Unit Tests

In-memory only — no Docker, no DB required.

```bash
cd backend

# Run all unit tests
sbt "testOnly com.myassistant.unit.*"

# Or just everything in the unit package
sbt "testOnly -- -tags unit"

# Watch mode (re-runs on file change)
sbt ~"testOnly com.myassistant.unit.*"
```

Test files:
- `src/test/scala/com/myassistant/unit/services/PersonServiceSpec.scala`
- `src/test/scala/com/myassistant/unit/services/RelationshipServiceSpec.scala`
- `src/test/scala/com/myassistant/unit/services/KinshipResolverSpec.scala`

---

## Integration Tests

Requires Docker — Testcontainers spins up `pgvector/pgvector:pg16` automatically.

```bash
cd backend

# Run all integration tests
sbt "testOnly com.myassistant.integration.*"
```

The container starts before the first test and is torn down after the suite. Flyway migrations run inside the container. No external DB needed.

Test files:
- `src/test/scala/com/myassistant/integration/PersonRepositorySpec.scala`
- `src/test/scala/com/myassistant/integration/FactRepositorySpec.scala`

---

## End-to-End Tests (Cucumber)

Requires Docker (same Testcontainers approach) and a running server, OR uses the embedded server started by the test harness.

```bash
cd backend

# Run all Cucumber E2E scenarios
sbt "testOnly com.myassistant.e2e.*"
```

Feature files:
- `src/test/scala/com/myassistant/e2e/PersonFeature.feature`
- `src/test/scala/com/myassistant/e2e/FactFeature.feature`

To run a specific scenario by tag:

```bash
sbt "testOnly com.myassistant.e2e.* -- -tags @smoke"
```

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

# 1. Unit + integration + E2E
sbt test

# 2. Then smoke test (server must be running in another terminal)
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
