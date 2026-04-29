# CLAUDE.md — Personal Assistant
# Persistent context for Claude Code sessions.
# Read this before making any changes to the codebase.

---

## Where to Find Things

| Topic | File |
|---|---|
| Use-case, architecture, design decisions | `docs/use-case.md` |
| REST API contract (all endpoints, request/response shapes) | `docs/http-contract.md` |
| MCP tool definitions (all 43 tools, arguments, behaviour) | `docs/mcp-tools.md` |
| Scala HTTP server — build, run, test, curl examples | `backend/http_server/kickstart.md` |
| Python MCP server — install, run, test, Claude Code config | `backend/mcp_server/kickstart.md` |
| Agent harness tests (tool call validation) | `client/chatbot_tests/tool_harness/` |

---

## Repository Structure

```
myassistant/
  backend/
    http_server/         Scala HTTP server (ZIO + zio-http)
      schema/            PostgreSQL DDL — run 01→07 in order
      src/
      build.sbt
      kickstart.md
    mcp_server/          Python MCP server (FastMCP + httpx)
      tools/
      tests/
      server.py
      kickstart.md
  client/
    chatbot_tests/       Agent tool-harness tests
      tool_harness/
  docs/
    use-case.md
    http-contract.md
    mcp-tools.md
  CLAUDE.md
```

---

## Source of Truth and Sync Rules

The three documents below are the **authoritative source of truth** for this project. All code artifacts must remain in sync with them. When there is a conflict, the docs win — fix the code, not the docs (unless the doc is itself being intentionally updated).

| Document | Source of truth for |
|---|---|
| `docs/use-case.md` | Product purpose, data model rationale, design decisions |
| `docs/mcp-tools.md` | All 43 MCP tool names, parameters, types, and behaviour |
| `docs/http-contract.md` | All 43 REST endpoints — paths, methods, request/response shapes, field names, query params |

### What must stay in sync

| Artifact | Must match |
|---|---|
| `backend/http_server/` — routes, models, DB migrations | `docs/http-contract.md` — field names (camelCase), endpoint paths, status codes |
| `backend/mcp_server/` — tool registrations, HTTP calls | `docs/mcp-tools.md` — tool names, parameter names/types; `docs/http-contract.md` — request bodies, query params sent to the Scala server |
| `client/chatbot_tests/tool_harness/` — `tool_definitions.py`, `scenarios.py` | `docs/mcp-tools.md` — tool names, parameter schemas, expected tool-call patterns |

### Sync discipline

- **Adding or changing an endpoint**: update `http-contract.md` first, then the Scala routes/models, then the Python MCP tools, then the harness definitions.
- **Adding or changing an MCP tool**: update `mcp-tools.md` first, then `mcp_server/tools/`, then the harness definitions.
- **JSON field names**: the Scala server uses camelCase throughout (Circe `derives Codec.AsObject`). `http-contract.md` documents camelCase. The Python MCP server sends camelCase request bodies to match.

---

## Core Design Principles

These govern every data decision in the codebase. Refer to `docs/use-case.md` for full rationale.

### 1. Documents Are Immutable Source of Truth
Every piece of information becomes a `document` first. Documents are NEVER updated or deleted. When information changes, a new document is created with `supersedes_ids` pointing to the old one. Every fact traces back to a document via `fact.document_id`.

### 2. Facts Are an Append-Only Operation Stream
Facts are stored as create/update/delete operations — NEVER updated or deleted in place. Current state is reconstructed by merging all operations for an `entity_instance_id` in chronological order (patch semantics — only changed fields are sent on update).

### 3. Entity Instance Identity
`entity_instance_id` groups all operations on the same logical entity. This UUID is NEVER provided by the user — it is resolved by the agent via semantic search on existing facts before any write.

### 4. Schema Is Governed and Versioned
`entity_type_schema` defines what fields exist for each (domain, entity_type) pair. New entity types are proposed by the AI and confirmed by the user. Schema evolution bumps `schema_version`; all old versions are retained. `mandatory_fields` drives clarifying questions when required fields are missing.

### 5. Hybrid Storage — Documents + Facts
Documents serve NL/semantic queries (pgvector embedding similarity). Facts serve structured queries (SQL aggregations, precise lookups). Both are maintained from the same ingestion pipeline.

### 6. Relationships Are Depth-1 Only
Only 8 atomic relation types stored: father, mother, son, daughter, brother, sister, husband, wife. All deeper relations (grandfather, aunt, cousin etc.) are derived at query time by graph traversal. Cultural names live in `kinship_alias`.

### 7. Polling Jobs Are First-Class Chat Participants
Polling jobs (Plaid, Gmail etc.) send formatted NL messages through the same agent pipeline as a human user. `source_type` on the document records the origin; agent behaviour is identical regardless of source.

---

## Seeded Reference Data

### source_type
```
user_input    — typed directly in chat
file_upload   — PDF, image etc. uploaded by user
ai_extracted  — extracted by AI from another document
plaid_poll    — Plaid banking API
gmail_poll    — Gmail
```

### domain
```
health           — insurance, medications, conditions, visits
finance          — income, expenses, accounts, transactions
employment       — jobs, roles, salary, employers
personal_details — contact info, addresses, preferences
todo             — tasks and reminders
household        — shared expenses, utilities, property
news_preferences — news topics and content preferences
```

### entity_type_schema (initial seed)
```
health     / insurance_card  v1 — provider, plan, deductible, premium, dates
todo       / todo_item       v1 — title, status, due_date, priority, recurrence
employment / job             v1 — employer, role, salary, start_date, end_date
finance    / payslip         v1 — employer, pay_period, gross, tax, net
```

### Field types in field_definitions
```
text      — free text string
number    — numeric value (stored as JSONB number)
date      — ISO date string YYYY-MM-DD
boolean   — true/false
file      — reference to a file in the parent document's files array
```

---

## Database Schema

11 tables across 5 logical layers. DDL lives in `backend/http_server/schema/` (run 01→07 in order).

| Layer | Tables |
|---|---|
| Spine | `person`, `household`, `person_household` |
| Relationships | `relationship`, `kinship_alias` |
| Reference | `source_type`, `domain` |
| Schema Governance | `entity_type_schema` |
| Core Data | `document`, `fact` |
| Observability | `audit_log` |

Views: `current_entity_type_schema` (latest active schema per type), `current_facts` (merged current state per entity instance).

PostgreSQL extensions required: `uuid-ossp`, `vector` (pgvector).
