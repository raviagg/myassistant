# Personal Assistant — Use Case & Design

## What This Is

A personal and family life assistant that remembers everything you tell it. You talk to it in natural language — "I renewed my Aetna insurance, deductible went up to $2,000" — and it extracts, stores, and recalls structured information from that conversation. It handles health records, employment history, finances, todos, family relationships, and more.

The system is built to serve one person or a family household. It is not a generic productivity app — it is a personal memory system that grows richer over time and can answer questions like "what was my salary in 2023?" or "what is my dad's sister called in Hindi?" from structured data it has learned from you.

---

## The Problem It Solves

Life information is scattered across PDFs, emails, memory, and spreadsheets. When you need your insurance deductible, old payslip, or a family member's contact detail, you either remember it or go hunting. This system is a single place that:

- Accepts natural language as input — you don't fill in forms
- Extracts structured facts automatically and stores them in queryable form
- Retains full history — the original text is preserved, and all changes are auditable
- Understands family relationships deeply, including multi-hop cultural kinship terms
- Handles scheduled data feeds (bank transactions, email) the same way it handles chat

---

## Information Domains

The system covers seven life domains out of the box, and new ones can be added at runtime:

| Domain | Examples |
|---|---|
| Health | Insurance cards, medications, conditions, doctor visits |
| Finance | Bank accounts, transactions, expenses |
| Employment | Jobs, roles, salary history, employers |
| Personal Details | Addresses, contact info, preferences |
| Todo | Tasks, reminders, due dates |
| Household | Shared expenses, utilities, property |
| News Preferences | Topics and content interests |

---

## Sources of Information

Information enters the system through two kinds of sources, treated identically:

**Chatbot (human-initiated)**
The primary interface. A person types a message, uploads a file, or pastes content. The agent processes it, extracts facts, and responds. Every interaction is logged.

**Scheduled polling jobs (system-initiated)**
Automated jobs connect to external services and send formatted messages to the same agent pipeline:

- **Plaid** — bank and credit card transactions
- **Gmail** — bills, receipts, payslips from email
- **Apple Health / calendar** — health metrics, appointments (future)

Both sources go through the exact same ingestion pipeline. The `source_type` field on each document records the origin — chatbot, plaid_poll, gmail_poll, etc. — but the agent handles them identically.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Information Sources                  │
│                                                          │
│   Human Chat          Plaid Poll        Gmail Poll       │
│   (typed text,        (bank txns)       (bills,          │
│    file uploads)                         receipts)       │
└──────────┬───────────────────┬──────────────┬───────────┘
           │                   │              │
           └───────────────────┴──────────────┘
                               │
                               ▼
              ┌────────────────────────────────┐
              │         Claude (LLM)           │
              │                                │
              │  System prompt defines agent   │
              │  behaviour: gather → confirm   │
              │  → write. Orchestrates tools   │
              │  to read before writing,       │
              │  deduplicate, and audit.       │
              └────────────────┬───────────────┘
                               │  MCP (stdio)
                               ▼
              ┌────────────────────────────────┐
              │     Python MCP Server          │
              │                                │
              │  One tool per REST endpoint.   │
              │  No business logic — pure      │
              │  HTTP proxy with Bearer auth.  │
              └────────────────┬───────────────┘
                               │  HTTP + Bearer token
                               ▼
              ┌────────────────────────────────┐
              │     Scala HTTP Backend         │
              │     (ZIO + zio-http)           │
              │                                │
              │  REST API for all entities.    │
              │  Generates embeddings.         │
              │  Runs Flyway migrations.       │
              └────────────────┬───────────────┘
                               │
                               ▼
              ┌────────────────────────────────┐
              │  PostgreSQL + pgvector         │
              │                                │
              │  Structured facts, immutable   │
              │  documents, vector embeddings, │
              │  relationship graph,           │
              │  audit log.                    │
              └────────────────────────────────┘
```

**Claude** is the orchestration layer. It decides which tools to call, in what order, and what to write. All intelligence lives here.

**Python MCP server** is a thin translation layer. It exposes every Scala REST endpoint as an MCP tool — one tool per endpoint, same arguments. No business logic lives here.

**Scala backend** owns data integrity, embedding generation, and the REST contract. It is the single source of truth for what is stored.

**PostgreSQL + pgvector** stores everything: people, relationships, documents, facts, schemas, and audit records. Vector embeddings live alongside structured data in the same database — no separate vector store.

---

## How the Agent Thinks

The system prompt given to Claude defines a strict two-phase protocol for handling new information:

**Phase 1 — Gather (no writes)**
Before touching any data, the agent reads everything relevant: does this person already exist? Is there an existing entity instance for this fact? Does a schema exist for this type of information? What document would this supersede? Only after all reads are complete does it propose what it plans to write, and ask the user to confirm.

**Phase 2 — Write (after confirmation)**
Once the user says "yes", the agent executes writes in a fixed order: spine first (create person/household/relationship if needed), then document, then fact. Every turn — read or write — ends with `log_interaction` to the audit log.

This prevents hallucinated writes, ensures deduplication, and gives the user a review step before anything is persisted.

---

## Key Design Decisions

### Documents are immutable, facts are append-only

Nothing is ever updated or deleted in place. When information changes — a salary raise, an insurance renewal — a new document is created pointing to (`supersedes_ids`) the old one, and a new fact with `operation_type=update` is appended. This gives a complete audit trail for free and makes re-extraction safe (any document can be replayed).

**Why:** Simpler than UPDATE/DELETE logic, full history without extra effort, and every fact has provenance back to the original natural language that produced it.

### Depth-1 relationships only, kinship derived at query time

Only 8 atomic relation types are stored: father, mother, son, daughter, brother, sister, husband, wife. All deeper relations (grandfather, aunt, cousin, bua, mama, nana) are derived by traversing the graph at query time. Cultural names for derived relations live in a `kinship_alias` table mapping chains to names in multiple languages.

**Why:** Storing transitive relations creates redundancy and synchronisation problems. Graph traversal is clean, LLM-friendly, and supports any culture's naming system without schema changes.

### Schema is governed and versioned, not hard-coded

`entity_type_schema` defines what fields exist for each (domain, entity_type) pair. When the agent encounters information it has no schema for (a gym membership, a blood pressure reading), it proposes a new schema in text, gets user confirmation, then creates it — and the new fact type is immediately available. Schema evolution increments a version number; all old versions and their facts are retained.

**Why:** The system needs to handle any kind of personal information, including types that don't exist yet. Dynamic DDL (CREATE TABLE per entity type) was rejected in favour of a single JSONB fact table governed by schema definitions.

### Hybrid storage — documents and facts from the same source

Every fact traces back to a document. Documents carry the original natural language and a vector embedding for semantic search. Facts carry structured field values and their own embedding for entity deduplication. Both are queryable — semantic search on documents for "find everything related to my Aetna policy", structured queries on facts for "what is my current deductible".

**Why:** Natural language questions need semantic search; structured questions (totals, timelines, current state) need SQL. Both are served from the same PostgreSQL database via pgvector — no separate vector store to operate.

### Polling jobs are first-class participants

A Plaid transaction feed and a human saying "I spent $80 at the gym" go through the same pipeline. The `source_type` distinguishes origin for audit and provenance, but the agent's behaviour is identical. This means the polling job infrastructure is just a message sender — it doesn't need its own extraction logic.

**Why:** A single ingestion pipeline is simpler to reason about, test, and extend. New data sources are just new rows in `source_type`.
