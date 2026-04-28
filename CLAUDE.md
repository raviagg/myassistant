# CLAUDE.md — Personal Assistant DB
# Persistent context for Claude Code sessions
#
# This file captures all design decisions made during the initial
# architecture and schema design phase. Read this before making
# any changes to the codebase.

---

## Project Overview

A personal/family assistant chatbot that stores life information —
health, finance, employment, todos, relationships and more — as
structured facts extracted from natural language documents.

The system has two interfaces:
1. **Chat interface** — a person types NL, the agent understands,
   stores information, and answers questions
2. **Polling jobs** — automated jobs (Plaid, Gmail etc.) send
   formatted NL messages to the same agent, which processes them
   identically to chat messages

The backend is **PostgreSQL + pgvector**. The agent layer is
**Claude via MCP** — a set of MCP tools expose the database
to Claude, which orchestrates reads and writes conversationally.

---

## Repository Structure

```
myassistant/
  backend/
    http_server/              Scala HTTP server (ZIO + zio-http)
      schema/
        01_spine.sql              person, household, person_household
        02_relationships.sql      relationship, kinship_alias
        03_reference.sql          source_type, domain
        04_schema_governance.sql  entity_type_schema, current_entity_type_schema view
        05_document.sql           document
        06_fact.sql               fact, current_facts view
        07_audit.sql              audit_log
    mcp_server/               Python MCP server (FastMCP + httpx)
  CLAUDE.md                   this file
  README.md                   project overview
```

Files must be run in numbered order due to foreign key dependencies.

### Prerequisites
```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;  -- pgvector
```

---

## Complete Table List

| Table | File | Purpose |
|---|---|---|
| `person` | 01 | Core identity — name, DOB, gender, login |
| `household` | 01 | A family or shared living group |
| `person_household` | 01 | N:N membership between persons and households |
| `relationship` | 02 | Depth-1 directed relationships between persons |
| `kinship_alias` | 02 | Maps relation chains to cultural names (bua, mama etc.) |
| `source_type` | 03 | Governed vocabulary of data sources |
| `domain` | 03 | Governed vocabulary of life domains |
| `entity_type_schema` | 04 | Versioned schema definitions for all fact types |
| `document` | 05 | Immutable NL content with pgvector embeddings |
| `fact` | 06 | Structured facts extracted from documents |
| `audit_log` | 07 | Full interaction history for chat and polling jobs |

### Views
| View | File | Purpose |
|---|---|---|
| `current_entity_type_schema` | 04 | Latest active schema per entity type |
| `current_facts` | 06 | Merged current state of all active entity instances |

---

## Core Design Principles

### 1. Documents Are Immutable Source of Truth
Every piece of information entering the system becomes a `document` first.
Documents are NEVER updated or deleted.
When information changes, a new document is created with `supersedes_ids`
pointing to the document(s) it replaces.
Facts are always derived from documents — `fact.document_id` provides
full provenance back to the original NL content.

### 2. Facts Are an Append-Only Operation Stream
Facts are extracted from documents and stored as create/update/delete operations.
Facts are NEVER updated or deleted in place.
Every change produces a new fact row.
Current state is derived by merging all operations for an `entity_instance_id`
in chronological order (patch semantics — later values overwrite earlier ones).
Null field values mean a field was explicitly removed.

### 3. Entity Instance Identity
`entity_instance_id` groups all operations on the same logical entity
(e.g. all updates to one TODO item share the same UUID).
This UUID is NEVER provided by the user in chat.
The backend resolves it via semantic search — the user says
"mark my passport renewal as done" and the system finds the right instance.

### 4. Schema Is Governed and Versioned
`entity_type_schema` defines what facts can be extracted for each
(domain, entity_type) pair.
New entity types are AUTO-PROPOSED by the AI when it encounters
unknown information, and CONFIRMED by the user in chat.
Schema evolution bumps `schema_version`. All versions are retained.
`mandatory_fields` is a generated column — drives clarifying questions
when mandatory fields are missing from extracted facts.

### 5. Hybrid Storage — Documents + Facts
Documents serve NL/semantic queries (embedding similarity search).
Facts serve structured queries (SQL aggregations, precise lookups).
Both worlds are maintained from the same ingestion pipeline.
Every fact row links back to its source document via `document_id`.

### 6. Relationships Are Depth-1 Only
Only 8 atomic relation types are stored:
father, mother, son, daughter, brother, sister, husband, wife.
All deeper relations (grandfather, aunt, cousin etc.) are derived
at query time by traversing the relationship graph.
Cultural names for derived relations live in `kinship_alias` as
chain-to-alias mappings supporting multiple languages.

### 7. Polling Jobs Are First-Class Chat Participants
Polling jobs (Plaid, Gmail etc.) interact with the agent the same
way a human user does — by sending a formatted NL message.
The agent processes both identically.
`source_type` on the document distinguishes origin.
`audit_log` uses `job_type` for system interactions and `person_id`
for human interactions — exactly one must be present.

---

## Key Design Decisions & Rationale

### Why not separate fact tables per entity type?
Initially considered having one table per (domain, entity_type) e.g.
`fact_health_insurance_card`. Rejected because:
- Requires dynamic DDL at runtime (CREATE TABLE when new entity type proposed)
- Schema evolution requires ALTER TABLE
- One JSONB fact table with `fields` column handles all entity types
- `entity_type_schema` governs the shape without materialising as tables

### Why immutable documents?
- Full audit trail for free
- Superseding chain provides temporal history
- Re-extraction jobs can replay any document
- No UPDATE/DELETE complexity in the pipeline

### Why append-only facts with patch semantics?
- History is free — "what was my salary in 2022?" works naturally
- No need to find-and-update existing rows (complex with semantic matching)
- Patch semantics (only send changed fields) keeps documents small
- Current state always derivable via `current_facts` view

### Why depth-1 relationships only?
- Storing transitive relations creates redundancy and sync problems
- Graph traversal at query time is clean and LLM-friendly
- `kinship_alias` handles cultural naming without storing derived relations

### Why one document table with nullable person_id + household_id?
- A document can belong to both a person and a household simultaneously
- Proper foreign keys to both tables
- `must_have_owner` constraint ensures at least one is set

### Why source_type and domain as tables not enums?
- New sources (calendar_poll, apple_health_poll) are just new rows
- New domains are just new rows
- No DDL change needed — zero downtime for new integrations

### Why PostgreSQL over a document DB?
- pgvector handles embeddings natively — no separate vector DB
- Full ACID transactions across documents and facts
- JSONB gives document flexibility where needed
- SQL aggregations for structured queries (total spend, salary trends)
- One system to operate, backup and monitor

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
health    / insurance_card   v1 — provider, plan, deductible, premium, dates
todo      / todo_item        v1 — title, status, due_date, priority, recurrence
employment/ job              v1 — employer, role, salary, start_date, end_date
finance   / payslip          v1 — employer, pay_period, gross, tax, net
```

---

## Field Types in entity_type_schema.field_definitions

Valid values for the `type` key in each field definition object:
```
text      — free text string
number    — numeric value (stored as JSONB number)
date      — ISO date string YYYY-MM-DD
boolean   — true/false
file      — reference to a file in the parent document's files array
```

---

## Ingestion Pipeline (To Be Implemented)

The ingestion pipeline is the core of the system.
It runs for every incoming message whether from chat or a polling job.

```
Incoming message (NL text or file)
        ↓
[If file] OCR / text extraction → append to content_text
        ↓
create_document(person_id/household_id, content_text, source_type, files)
        ↓
Generate embedding for content_text → store on document
        ↓
Semantic search existing documents → identify supersedes_ids
        ↓
For each matching entity_type_schema:
  extract_facts_from_document(document_id, schema_id)
        ↓
check_mandatory_fields(schema_id, extracted_fields)
  if missing → ask user clarifying questions → re-extract
        ↓
Resolve entity_instance_id via semantic search on existing facts
  if create → generate new UUID
  if update/delete → find existing UUID
        ↓
create_fact(document_id, schema_id, entity_instance_id,
            operation_type, fields)
        ↓
Generate embedding for merged current fields → store on fact
        ↓
Log to audit_log (message, response, tool_calls, status)
```

---

## MCP Tools (To Be Implemented)

The following MCP tool groups need to be designed and implemented.
This is the next phase of work.

### Person & Household
```
create_person
update_person
create_household
update_household
add_person_to_household
create_relationship
list_persons
get_person
```

### Document
```
create_document
get_document
search_documents        — semantic search via embedding
list_documents          — filtered listing
```

### Fact
```
create_fact
get_facts
get_current_fact        — convenience: latest state only
aggregate_facts         — SUM, AVG, MIN, MAX on numeric fields
search_facts            — semantic search to resolve entity_instance_id
```

### Schema Governance
```
list_entity_types
get_entity_type_schema
propose_entity_type     — AI calls when unknown entity encountered
confirm_entity_type     — user confirms, schema row inserted
evolve_entity_type_schema
confirm_schema_evolution
deactivate_entity_type
list_facts_needing_reextraction
run_reextraction_job
```

### Ingestion Pipeline
```
extract_facts_from_document
check_mandatory_fields
generate_embedding
find_related_documents
```

### External Sources
```
register_external_source
list_external_sources
trigger_poll
get_poll_history
```

### Query & Discovery
```
answer_question         — main NL query orchestrator
get_timeline
get_household_summary
```

### File Handling
```
upload_file
extract_text_from_file
get_file
```

---

## What Has Been Completed

- [x] Full architecture design (hybrid structured + unstructured)
- [x] Complete DDL for all 11 tables
- [x] PostgreSQL COMMENT documentation on all tables and columns
- [x] Seed data for reference tables and initial entity type schemas
- [x] Git repository initialised with initial commit

## What Comes Next

- [ ] Push schema files to remote git repo
- [ ] Set up PostgreSQL instance with pgvector extension
- [ ] Run schema files to initialise database
- [ ] Design and implement MCP server with tool definitions above
- [ ] Implement ingestion pipeline
- [ ] Implement polling jobs (Plaid first)
- [ ] Build chat interface connected to MCP server
- [ ] Test end-to-end with real data
