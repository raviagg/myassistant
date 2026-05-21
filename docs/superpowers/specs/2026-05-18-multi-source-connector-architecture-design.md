# Multi-Source Connector Architecture Design

**Date:** 2026-05-18  
**Status:** Draft — awaiting user review  
**Scope:** Generalized source connector framework, unified data store, intelligence layer, connection management UI

---

## Context

The current system has two hard-coded polling connectors (Plaid, Gmail/News) and one unstructured source (chatbot). All data lands in a single shared PostgreSQL database with a `source_type` tag for provenance. There is no formal connector abstraction, no per-connection isolation, no schema intelligence layer, and no connection management UI.

The goal is to generalize the architecture so that:
- New source connectors can be added with minimal boilerplate
- Each connection instance has isolated storage and clear person/household scoping
- A unified view layer — powered by LLM schema merging — answers cross-source questions
- Users can manage connections and trigger syncs via a sidebar UI

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                   Source Connectors                     │
│                                                         │
│  Structured                    Unstructured             │
│  ─────────                    ────────────              │
│  Plaid (banking)               Chatbot                  │
│  News                                                   │
│  Bulk File Upload (payslips)                            │
│  Bulk Image Upload (memories)                           │
└────────────────────┬────────────────────────────────────┘
                     │  source_connection_id (universal scope key)
                     ▼
┌─────────────────────────────────────────────────────────┐
│               Raw Source Storage                        │
│                                                         │
│  plaid.*              news.*             document table  │
│  Native SQL tables    Native SQL tables  + fact table   │
│  (shared schema per   (shared schema per (JSONB, schema │
│   connector type,      connector type,    on-the-fly)   │
│   source_connection_id source_connection_id scoped)     │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│            Intelligence Layer (infrequent)              │
│                                                         │
│  1. Reads schemas from all active connectors            │
│  2. LLM proposes unified entity model                   │
│  3. User tunes with sample values from raw data         │
│  4. Approved → materializes unified view + embeddings   │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              Access Layer                               │
│                                                         │
│  unified.persons   unified.transactions  unified.*      │
│  Persistent materialized tables with embeddings         │
│  De-duplication happens here, not at ingestion          │
└─────────────────────────────────────────────────────────┘
```

---

## 1. Source Connection Registry

A single `source_connections` master table is the canonical registry for every connection in the system.

```sql
CREATE TABLE source_connections (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_type         TEXT NOT NULL,          -- 'plaid', 'news', 'chatbot', 'bulk_file', 'bulk_image'
    connection_name     TEXT NOT NULL,          -- human label, e.g. "Ravi's Chase"
    person_id           UUID REFERENCES person(id),
    household_id        UUID REFERENCES household(id),
    config              JSONB NOT NULL DEFAULT '{}',  -- non-secret config fields
    secrets             TEXT,                   -- AES-256-GCM encrypted blob (see Secrets section)
    sync_mode           TEXT NOT NULL,          -- 'scheduled' | 'adhoc' | 're_extract' | 'none'
    sync_schedule       TEXT,                   -- cron expression, nullable
    last_synced_at      TIMESTAMPTZ,
    status              TEXT NOT NULL DEFAULT 'active', -- 'active' | 'paused' | 'error'
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT exactly_one_owner CHECK (
        (person_id IS NOT NULL AND household_id IS NULL) OR
        (person_id IS NULL AND household_id IS NOT NULL)
    )
);
```

`source_connection_id` is the universal scope key — present in **every** table across both structured and unstructured storage.

### Confirmed design decisions (2026-05-20)

**Two-level hierarchy:** `source_connections` is the integration level — one row per `(source_type, person_id)`. Child rows live in connector-specific tables (`plaid.connections`, etc.).

**Plaid credential placement:** `source_connections.secrets` holds `{"client_id": "...", "secret": "..."}` (Plaid API credentials, encrypted). `plaid.connections.access_token` holds the per-item encrypted access_token.

**Uniqueness policy:**
- `(source_type, person_id)` — enforced via droppable partial unique index (one connection per type per person, for now)
- `(connection_name, person_id)` — permanent partial unique index (names are unique per person)

**UI contract:** The source connection edit screen is the management surface for child items. For Plaid: the edit screen lists linked banks with Add/Disconnect. Adding a bank triggers Plaid Link scoped to this connection's credentials.

---

## 2. Structured Source Connectors

### Schema definition in code

Each structured connector defines its schema as a Python class. Schema evolves via code changes + SQL migrations only.

```python
class PlaidConnector(StructuredConnector):
    source_type = "plaid"
    sync_modes = ["scheduled", "adhoc"]

    config_schema = [
        Field("institution_name", type="text", secret=False),
        Field("plaid_client_id", type="text", secret=False),
    ]
    secret_schema = [
        Field("access_token", type="text", secret=True),
    ]

    def schema(self) -> ConnectorSchema:
        # Returns table definitions for the intelligence layer
        return ConnectorSchema(tables=[
            TableDef("connections", ...),
            TableDef("bank_accounts", ...),
            TableDef("transactions", ...),
        ])
```

### PostgreSQL storage — one schema per connector type

All connection instances for a given source type share one Postgres schema (not one schema per instance). The `source_connection_id` column in every table provides instance isolation.

```sql
-- plaid schema (shared across all Plaid connection instances)
CREATE TABLE plaid.connections (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_connection_id  UUID        NOT NULL REFERENCES source_connections(id) ON DELETE CASCADE,
    plaid_item_id         TEXT        NOT NULL UNIQUE,
    institution_name      TEXT        NOT NULL,
    cursor                TEXT,
    access_token          TEXT,       -- AES-256-GCM encrypted; COALESCE-preserved on upsert
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE plaid.bank_accounts (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_connection_id  UUID NOT NULL REFERENCES source_connections(id),
    connection_id         UUID NOT NULL REFERENCES plaid.connections(id),
    account_id            TEXT NOT NULL,
    name                  TEXT NOT NULL,
    account_type          TEXT NOT NULL,     -- 'checking' | 'savings' | 'credit'
    current_balance       DECIMAL(15,2),
    embedding             VECTOR(1536),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE plaid.transactions (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_connection_id  UUID NOT NULL REFERENCES source_connections(id),
    account_id            UUID NOT NULL REFERENCES plaid.bank_accounts(id),
    plaid_transaction_id  TEXT NOT NULL UNIQUE,
    amount                DECIMAL(15,2) NOT NULL,
    date                  DATE NOT NULL,
    merchant_name         TEXT,
    category              TEXT[],
    embedding             VECTOR(1536),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Key properties:**
- Real FK enforcement between accounts → transactions
- Native column types — `amount DECIMAL`, `date DATE`
- `source_connection_id` on every table for instance isolation
- Embeddings on each row for semantic search and unified schema authoring

Adding a new Plaid connection = insert a row in `source_connections`, no DDL changes.  
Adding a new column to Plaid = one `ALTER TABLE`, applied to all instances.

---

## 3. Unstructured Source Connector (Chatbot)

The chatbot connector uses the existing `document` + `fact` model, extended with `source_connection_id`.

### Schema changes to existing tables

```sql
-- Add to existing document table
ALTER TABLE document ADD COLUMN source_connection_id UUID REFERENCES source_connections(id);

-- Add to existing fact table
ALTER TABLE fact ADD COLUMN source_connection_id UUID REFERENCES source_connections(id);
```

A system-created chatbot connection is auto-provisioned for each person/household at setup. It has `sync_mode = 're_extract'` — its only available sync action is re-processing documents against an evolved entity type schema.

### First-class FK support via `entity_ref` field type

To express relationships between fact entities (e.g., a chatbot-tracked "transaction" that references a chatbot-tracked "bank account"), add `entity_ref` as a field type in `entity_type_schema`:

```json
{
  "name": "account_id",
  "type": "entity_ref",
  "ref_entity_type": "bank_account",
  "ref_domain": "finance",
  "mandatory": true
}
```

**Behaviour:**
- The JSONB value stored is the **`entity_instance_id` UUID** of the referenced entity, not a display string
- Before writing a fact with an `entity_ref` field, the agent validates the referenced `entity_instance_id` exists in `current_facts` with matching `entity_type` — if validation fails, the write is rejected with an error and the user is prompted to resolve or create the referenced entity first
- `current_facts` view resolves `entity_ref` fields during query (JOIN on `entity_instance_id`)
- The intelligence layer treats `entity_ref` fields as graph edges when building the unified schema

This gives schema-declared, agent-enforced referential integrity — equivalent to FK semantics without requiring Postgres to enforce across JSONB.

---

## 4. Intelligence Layer

An infrequently-run process (admin-triggered, or auto-triggered when a connector schema changes) that derives a unified entity model from all source schemas.

### Process

```
1. Schema collection
   ├── Structured connectors: read table definitions from ConnectorSchema.schema()
   └── Unstructured (chatbot): read entity_type_schema records from DB

2. LLM prompt
   Input: all collected schemas + sample values (fetched via semantic search on raw embeddings)
   Output: proposed unified entity model
          e.g. unified.person, unified.transaction, unified.account
          with field mappings: plaid.transactions.amount → unified.transaction.amount
                               chatbot.finance/transaction.amount → unified.transaction.amount

3. User review & tuning
   UI shows proposed schema + sample merged rows
   User can rename fields, drop mappings, add custom fields, split/merge entities

4. Materialization
   On approval: write unified schema to unified_schema table
   Run materialization job: populate unified.* tables from raw sources
   Compute embeddings on each unified row
   Schedule incremental refresh on source sync
```

### Unified schema storage

```sql
CREATE TABLE unified_schema (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    entity_name     TEXT NOT NULL,       -- e.g. 'transaction', 'account'
    field_mappings  JSONB NOT NULL,      -- source field → unified field mappings
    schema_version  INT NOT NULL DEFAULT 1,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    approved_at     TIMESTAMPTZ,
    approved_by     UUID REFERENCES person(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Unified view tables (materialized, with embeddings)

```sql
-- Example: unified transactions across Plaid + chatbot
CREATE TABLE unified.transactions (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    unified_schema_id    UUID REFERENCES unified_schema(id),
    source_connection_id UUID REFERENCES source_connections(id),
    source_entity_id     TEXT NOT NULL,    -- original row ID in source table
    person_id            UUID REFERENCES person(id),
    household_id         UUID REFERENCES household(id),
    amount               DECIMAL(15,2),
    date                 DATE,
    merchant             TEXT,
    category             TEXT,
    embedding            VECTOR(1536),     -- embedding of unified representation
    raw_fields           JSONB,            -- all source fields for reference
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    refreshed_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Embeddings strategy:**
- Raw source data: embedded at ingestion (each structured row, each document/fact)
- Unified view: embedded after materialization — powers most end-user queries
- Raw embeddings remain available for intelligence layer (sample values during schema tuning)

---

## 5. Secrets Management

Secrets (API tokens, OAuth refresh tokens) are never stored in plaintext.

**Encryption:** AES-256-GCM, application-layer encryption before DB write.  
**Key:** Single `SECRETS_KEY` environment variable (32 bytes, base64-encoded).  
**Storage:** Encrypted blob in `source_connections.secrets TEXT` column.

```python
# At write time (connection create/update)
encrypted = aes_gcm_encrypt(json.dumps(secret_fields), key=SECRETS_KEY)
db.execute("UPDATE source_connections SET secrets = $1 WHERE id = $2", encrypted, conn_id)

# At sync time (only)
secret_fields = json.loads(aes_gcm_decrypt(row.secrets, key=SECRETS_KEY))
```

**Properties:**
- DB at rest: encrypted blob, useless without `SECRETS_KEY`
- `SECRETS_KEY` never logged, never in config JSONB, only in environment
- Secrets never returned by API — connection read endpoints return `config` only, never `secrets`
- UI sends secrets over HTTPS on create/update; server encrypts before write
- Rotation: re-encrypt all blobs with new key (one-time migration script)

---

## 6. Connection Management UI

A sidebar panel accessible from the main chat interface.

### Connection list view
- Lists all connections for the current person/household
- Shows: connector icon, connection name, status (active/paused/error), last synced time
- Actions: Edit, Delete, Sync Now (if sync_mode supports it), Pause/Resume

### Create / Edit connection form
- Connector type selector (Plaid, News, Bulk File, Bulk Image, ...)
- Dynamically renders config fields from `ConnectorType.config_schema`
- Secret fields rendered as password inputs, never pre-filled on edit
- Person vs. Household scope selector
- Sync mode selector (options restricted to what the connector type supports)
- Cron schedule picker (shown only if sync_mode = 'scheduled')

### Adhoc sync
- "Sync Now" button triggers `POST /api/v1/source-connections/{id}/sync`
- Shows real-time progress (SSE or polling) — same stream as existing chatbot SSE
- Chatbot connections show "Re-extract" instead of "Sync Now"

### Intelligence layer trigger
- Separate "Rebuild Unified View" button (admin-level, not per-connection)
- Shows schema proposal UI inline — proposed entity types, field mappings, sample values
- User tunes and approves; triggers materialization job

---

## 7. Gap Analysis — Current vs. Target

| Component | Current State | Target State | Gap |
|---|---|---|---|
| Source connector abstraction | None — Plaid/Gmail hardcoded in scheduler | `StructuredConnector` / `UnstructuredConnector` base classes | New |
| `source_connection_id` | Not present | FK in every table (structured + document + fact) | Migration |
| Person/household scoping | `person_id`/`household_id` on `document` | Same, inherited from connection registry | Refactor |
| Plaid storage | Facts + JSONB via entity_type_schema | Native `plaid.*` tables | Rewrite |
| News storage | Facts + JSONB | Native `news.*` tables | Rewrite |
| Chatbot storage | `document` + `fact` tables | Same + `source_connection_id` + `entity_ref` field type | Extend |
| Secrets | Plaid token in env var / DB plaintext | AES-encrypted in `source_connections.secrets` | New |
| Connection management API | None | Full CRUD + sync trigger endpoints | New |
| Connection management UI | None | Sidebar panel | New |
| Intelligence layer | None | LLM schema merge + unified view | New |
| Unified view | None | `unified.*` materialized tables + embeddings | New |

---

## 8. What Is NOT in Scope (This Design)

- Gmail connector (exists, will be migrated in a follow-up)
- Bulk image upload connector (future)
- Multi-person household deduplication rules (access-time concern, future)
- OAuth flow for Plaid link (already implemented — reuse as-is)
- Re-extraction scheduler for chatbot (future — referenced but not designed here)

---

## 8b. UI Aesthetic Specification (React implementation must match exactly)

### Color Palette

| Role | Value |
|---|---|
| Page background | `#0f172a` |
| Card / panel background | `#1e293b` |
| Input / code background | `#0f172a` |
| Elevated input background | `#0c1a3a` (blue-tinted, used for chat user bubble background) |
| Border default | `#334155` |
| Border subtle | `#1e293b` |
| Text primary | `#e2e8f0` |
| Text secondary | `#94a3b8` |
| Text muted | `#64748b` |
| Text very muted | `#475569` |
| Accent purple primary | `#4f46e5` |
| Accent purple hover | `#4338ca` |
| Accent purple light | `#a5b4fc` |
| Accent purple bg tint | `rgba(99,102,241,0.15)` |
| Accent purple border tint | `rgba(99,102,241,0.25)` |
| Success green | `#4ade80` |
| Success green bg | `#052e16` |
| Success green border | `#166534` |
| Warning yellow | `#fbbf24` |
| Warning yellow bg | `#422006` |
| Warning yellow border | `#854d0e` |
| Error red | `#f87171` |
| Error red bg | `#2d0a0a` |
| Error red border | `#991b1b` |
| Info blue | `#60a5fa` |
| Info blue bg | `#0c1a3a` |
| Info blue border | `#1e40af` |
| Run strip background | `#0f1e35` (connection card run-status strip) |
| Run strip border-top | `#1e3a5f` |

### Typography
- Font stack: `-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif`
- Monospace: `'SF Mono', 'Fira Code', monospace` (used for log lines, cron expressions, field names)
- Base size: `13px`
- Nav brand: `15px`, `font-weight: 700`, `letter-spacing: -0.02em`
- Section labels: `11px`, `font-weight: 700`, `text-transform: uppercase`, `letter-spacing: 0.07em`

### Spacing & Shape
- Border radius — cards: `12px`, buttons: `8px`, small buttons: `6-8px`, badges: `99px` (pill), tags: `4-5px`
- Card padding: `14-16px 18-20px`
- Page content padding: `24px`
- Nav height: `50px`, sub-nav height: `40px`

### Component Patterns

**Nav tabs (top-level):** transparent bg, `#94a3b8` text → on hover `#334155` bg, `#e2e8f0` text → active `rgba(99,102,241,0.15)` bg, `#a5b4fc` text

**Sub-tabs (second-level):** same pattern but `12px` font, `6px` radius

**Primary button:** `#4f46e5` bg, white text → hover `#4338ca`

**Ghost button:** transparent bg, `#334155` border, `#94a3b8` text → hover `#334155` bg, `#e2e8f0` text

**Small action button (btn-sm):** `11px`, `1px solid #334155`, transparent bg → hover fills `#334155`

**Danger text button:** `#f87171` text, `#7f1d1d` border, transparent bg

**Badges (status pills):** pill shape (`border-radius: 99px`), colored bg + border + text per status (see color table above)

**"View Logs →" link-button:** `rgba(99,102,241,0.1)` bg, `rgba(99,102,241,0.25)` border, `#4f46e5` text, `10px`, `font-weight: 700` → hover lightens bg to `rgba(99,102,241,0.2)`, text `#a5b4fc`. Disabled state: `#334155` border, `#334155` text.

**Cards:** `#1e293b` bg, `1px solid #334155` border, `border-radius: 12px`, no shadow

**Connection run-status strip:** `background: #0f1e35`, `border-top: 1px solid #1e3a5f`, `padding: 9px 18px 9px 70px` (indented to align with card body text past icon)

**Log modal:** overlay `rgba(0,0,0,0.6)`, box `680px` wide, `80vh` max-height, `border-radius: 14px`, log lines monospace `11px`

**Unified View Builder — lineage diagram:**
- Source schema nodes: `border: 1px solid #92400e`, `background: rgba(120,53,15,0.15)`, title `#fbbf24`
- Unified schema nodes: `border: 2px solid #4f46e5`, `background: rgba(79,70,229,0.1)`, title `#a5b4fc`
- UNION badge: `rgba(99,102,241,0.2)` bg, `#a5b4fc` text, `#4f46e5` border
- PASS-THROUGH badge: `rgba(16,185,129,0.15)` bg, `#6ee7b7` text, `#059669` border
- Arrow gradient: `linear-gradient(90deg, #92400e, #4f46e5)`

**Diff table rows:**
- Added: `background: rgba(5,46,22,0.3)`, `+` operator in green
- Modified: `background: rgba(66,32,6,0.3)`, `~` operator in yellow
- Removed: `background: rgba(45,10,10,0.3)`, `-` operator in red

---

## 9. Verification

After implementation, verify end-to-end by:

1. Create a Plaid connection via the UI for a test person → confirm `source_connections` row + secrets encrypted
2. Trigger adhoc Plaid sync → confirm rows appear in `plaid.connections`, `plaid.bank_accounts`, `plaid.transactions` with correct `source_connection_id`
3. Chat: "what did I spend at Starbucks last month?" → confirm unified view query returns results
4. Create a chatbot fact with an `entity_ref` field → confirm agent validates the referenced entity exists
5. Trigger "Rebuild Unified View" → confirm `unified.transactions` populated with embeddings, cross-source rows visible
6. Delete a connection → confirm cascade removes all associated data in `plaid.*`
