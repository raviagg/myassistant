# Personal Assistant DB

PostgreSQL schema for a personal/family assistant chatbot.
Stores life information — health, finance, employment, todos and more —
as structured facts extracted from natural language documents.

## Architecture

The schema has four logical layers:

```
Spine           → person, household, person_household, relationship, kinship_alias
Reference       → source_type, domain
Schema Governance → entity_type_schema
Core Data       → document, fact
Observability   → audit_log
```

## Key Design Principles

**Documents are immutable source of truth**
Every piece of information entering the system becomes a `document`.
Documents are never updated or deleted. New information supersedes
old documents via `supersedes_ids`.

**Facts are a structured operation stream**
Facts are extracted from documents and stored as an append-only stream
of create/update/delete operations. Current state is derived by merging
all operations for an entity instance in chronological order.

**Schema is governed and versioned**
`entity_type_schema` defines what facts can be extracted for each
(domain, entity_type) pair. New entity types are proposed by the AI
and confirmed by the user. Schema evolution bumps `schema_version`.

**Semantic search via pgvector**
Both documents and facts carry vector embeddings for NL discovery.

## Running Order

Files must be run in numbered order due to foreign key dependencies:

```
01_spine.sql              person, household, person_household
02_relationships.sql      relationship, kinship_alias
03_reference.sql          source_type, domain
04_schema_governance.sql  entity_type_schema
05_document.sql           document
06_fact.sql               fact, current_facts view
07_audit.sql              audit_log
```

## Prerequisites

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;  -- pgvector
```

## Tables

| Table | Description |
|---|---|
| `person` | Core identity — name, DOB, gender, login |
| `household` | A family or shared living group |
| `person_household` | N:N membership between persons and households |
| `relationship` | Depth-1 directed relationships between persons |
| `kinship_alias` | Maps relation chains to cultural names (bua, mama etc.) |
| `source_type` | Governed vocabulary of data sources |
| `domain` | Governed vocabulary of life domains |
| `entity_type_schema` | Versioned schema definitions for all fact types |
| `document` | Immutable NL content with embeddings |
| `fact` | Structured facts extracted from documents |
| `audit_log` | Full interaction history for chat and polling jobs |
