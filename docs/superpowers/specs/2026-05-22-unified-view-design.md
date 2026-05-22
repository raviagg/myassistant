# Unified View — Design Spec
**Date:** 2026-05-22

---

## Context

The system collects data from three independent information sources: the identity spine (person, household, relationships), source connections (Plaid, bulk-file-ingest, Gmail, etc.), and chatbot-extracted knowledge (entity_type_schema facts). Each source has its own schema and storage path. There is currently no way to see how all of a person's or household's data connects across these sources, nor a way to define a single coherent view that spans them.

The Unified View gives users (and the chatbot) a 360-degree profile of a person or household — showing what schemas exist across all sources, how they relate, and a unified_schema that merges fields from multiple sources into a coherent entity definition. The chatbot can read from these unified schemas to answer cross-source questions.

---

## Screen Structure

**Pivot:** Person or Household picker at the top bar. Everything on screen is scoped to the selected pivot.

**Sidebar (left nav):**
- **Profile** — person details, household membership, relationships (read-only, always visible)
- **Data Sources** — list of source connections for this person/household (Plaid, bulk-file, Gmail, etc.). NEW badge on connections added within the last 7 days.
- **Extracted Facts** — list of entity_type_schemas where this person/household has facts (finance/transaction, health/insurance, etc.). NEW badge on entity types whose first fact was created within the last 7 days.

No "Unified Schemas" section in sidebar — unified schemas are always shown in the right panel as the output of selecting sources, not a separate nav destination.

**Main area — two panels:**

### Left panel: Source Schema Browser
Shows all source schemas for the selected pivot, grouped by source. Four groups always rendered:

1. **Profile (identity spine)** — `person`, `household`, `relationship` tables with their columns; always shown as the immutable anchor, never changes with source selection
2. **Plaid / native connectors** — actual DB tables (`plaid.accounts`, `plaid.transactions`) with FK arrows between them, one group per source connection
3. **Entity_type_schema sources** (bulk-file-ingest, chatbot) — rendered as virtual tables with their `field_definitions` as columns, FK to `document` and `fact`
4. FK relationships drawn within each source group; `source_connection_id` shown as the cross-group link

Clicking a source in the sidebar collapses groups 2–4 to show only the selected source. Group 1 (Profile) is always visible.

### Right panel: Unified Schema View
Shows `unified_schema` definitions for this pivot — LLM-proposed, user-tuned. Each unified schema displays:
- Name (e.g. `unified.transaction`)
- Status: proposed / approved
- Field list: each unified field shows which source fields map to it across all sources
- Fields with no source mapping in some sources show "null elsewhere" — those rows will be null for that field
- Fields pending accept/reject shown with REVIEW badge
- Source tags at bottom showing which connections contribute rows

### Default state: Full-Picture Mode
When a person/household is selected at the top but no specific source is clicked in the sidebar, **both panels show everything simultaneously** — all source schemas on the left, all unified schemas on the right. This is the overview before drilling in.

### Focused Mode
Clicking a source in the sidebar narrows the left panel to that source's tables only. The right panel highlights which unified schemas that source contributes to.

### Cross-Highlight
Clicking any unified field on the right panel highlights all source fields that map to it across every source group on the left panel simultaneously (amber highlight). Clicking again deselects.

---

## Data Model

### New table: `unified_schema`

```sql
CREATE TABLE unified_schema (
  id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  person_id         UUID REFERENCES person(id),
  household_id      UUID REFERENCES household(id),
  name              TEXT NOT NULL,          -- e.g. "transaction", "insurance"
  description       TEXT,
  status            TEXT NOT NULL DEFAULT 'proposed',  -- proposed | approved
  field_definitions JSONB NOT NULL,         -- see structure below
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT usm_exactly_one_owner CHECK (
    (person_id IS NOT NULL)::int + (household_id IS NOT NULL)::int = 1
  ),
  CONSTRAINT usm_status_valid CHECK (status IN ('proposed', 'approved'))
);
```

**`field_definitions` JSONB structure:**
```json
[
  {
    "name": "merchant",
    "type": "text",
    "status": "approved",
    "sources": [
      { "source_connection_id": "<uuid>", "source_table": "plaid.transactions", "source_field": "merchant_name" },
      { "source_connection_id": "<uuid>", "source_table": "bulk.finance/transaction", "source_field": "merchant" },
      { "source_connection_id": "<uuid>", "source_table": "chatbot.finance/transaction", "source_field": "merchant" }
    ]
  }
]
```

Field `status` per field: `"approved"` | `"pending"` | `"rejected"`. Rejected fields are excluded from read-time queries.

**Scoping:** Each `unified_schema` row belongs to exactly one person OR one household. Different people/households may have different unified schemas even for the same entity concept, reflecting their different connected sources.

---

## Backend — Scala HTTP Server

### New endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/v1/unified-schemas` | List all unified schemas for a person/household (`?personId=` or `?householdId=`) |
| `GET` | `/api/v1/unified-schemas/{id}` | Fetch a single unified schema |
| `POST` | `/api/v1/unified-schemas` | Create (store LLM-proposed schema) |
| `PATCH` | `/api/v1/unified-schemas/{id}` | Update field statuses (accept/reject individual fields), approve overall |
| `DELETE` | `/api/v1/unified-schemas/{id}` | Remove a unified schema |
| `GET` | `/api/v1/unified-schemas/{id}/data` | **Read-time UNION query** — returns rows from all contributing sources mapped to unified fields. Supports `?limit=&offset=`. Each row includes `_source_connection_id` and `_source_type` tags. |
| `GET` | `/api/v1/unified-schemas/source-schemas` | Returns all source schemas (table names, columns, FKs) for a person/household — used to populate left panel |

### Read-time UNION (`/data` endpoint)

For each approved field in `unified_schema.field_definitions`, the backend queries each contributing source and maps fields:

- **Plaid sources:** `SELECT <field_mappings> FROM plaid.transactions t JOIN plaid.accounts a ON t.account_id = a.id JOIN source_connections sc ON a.source_connection_id = sc.id WHERE sc.person_id = ? OR sc.household_id = ?`
- **Entity_type_schema sources (bulk-file, chatbot):** `SELECT <field_mappings> FROM current_facts cf JOIN entity_type_schema ets ON cf.schema_id = ets.id WHERE ets.domain = ? AND ets.entity_type = ? AND (cf.person_id = ? OR cf.household_id = ?)`

Results are UNIONed. Each row carries `_source_connection_id` and `_source_type` so the UI can show provenance.

No materialized tables. Always fresh at query time.

### Source schema introspection (`/source-schemas` endpoint)

**Route must be registered before `/{id}` to avoid the literal "source-schemas" being parsed as an id.**

Returns a structured representation of all source tables for the pivot:
- **Profile tables** — hardcoded schema for `person`, `household`, `relationship` (columns and types are fixed by our migrations; no runtime introspection needed)
- **Plaid / native connectors** — schema served from a static registry (a map of `source_type → table definitions` baked into the Scala config); filtered to source_connections owned by this person/household. The Plaid schema is our own migration so no live `information_schema` introspection is needed or appropriate.
- **Entity_type_schema sources** — query `entity_type_schema` table filtered by source_connections for this pivot; return `field_definitions` as virtual columns grouped by source_connection

---

## LLM Integration — Python Scheduler / Chatbot Server

### When LLM proposes a unified schema
Triggered by:
1. A new source connection is added (scheduler detects new `source_connections` row for this pivot)
2. User clicks "Ask LLM to propose a new unified schema" in the UI

**Prompt inputs:**
- All source schemas for this person/household (table names, column names, types, sample values optional)
- Existing unified schemas for this pivot (if any) — LLM should extend rather than duplicate

**LLM output:** A `unified_schema` JSON object with field_definitions including source mappings and suggested field names/types.

**Storage:** POSTed to `/api/v1/unified-schemas` with status=`proposed`. Surfaced in UI with REVIEW badge.

---

## Frontend

**File to upgrade:** `frontend/src/components/UnifiedViewBuilderTab.tsx` (currently all mock data — replace with real API calls)

**New API functions in `api.ts`:**
- `listUnifiedSchemas(personId?, householdId?)` → `GET /api/v1/unified-schemas`
- `getUnifiedSchemaData(id, limit, offset)` → `GET /api/v1/unified-schemas/{id}/data`
- `updateUnifiedSchema(id, patch)` → `PATCH /api/v1/unified-schemas/{id}`
- `listSourceSchemas(personId?, householdId?)` → `GET /api/v1/unified-schemas/source-schemas`
- `createUnifiedSchema(body)` → `POST /api/v1/unified-schemas`

**New types in `types.ts`:**
- `UnifiedSchema` — id, personId?, householdId?, name, status, fieldDefinitions
- `UnifiedFieldDefinition` — name, type, status, sources
- `FieldSource` — sourceConnectionId, sourceTable, sourceField
- `SourceSchemaGroup` — sourceConnection, tables: SourceTable[]
- `SourceTable` — name, columns: SourceColumn[], foreignKeys

**UI state:**
- Selected pivot (person or household)
- Selected source in sidebar (null = full-picture mode)
- Highlighted unified field (for cross-highlight)
- Expanded/collapsed unified schemas on right panel

---

## Chatbot Integration

New MCP tool: `query_unified_schema(unified_schema_id, filters?, limit?)` — calls `/api/v1/unified-schemas/{id}/data` and returns rows. Allows chatbot to answer cross-source questions like "what were my total transactions across all banks last month?" without knowing which sources exist.

Existing tools (`list_current_facts`, `search_current_facts`) continue to work for entity_type_schema-only queries.

---

## Verification

1. **Schema browser:** Select a person with Plaid + chatbot connections → left panel shows `plaid.accounts`, `plaid.transactions`, and chatbot entity_type_schema virtual tables with FK relationships
2. **Full-picture mode:** Select person at top level (no source clicked) → both panels populate with all source schemas and all unified schemas simultaneously
3. **Cross-highlight:** Click `merchant` field in `unified.transaction` → `plaid.merchant_name`, `bulk.merchant`, `chat.merchant` all highlight amber on left panel
4. **Focused mode:** Click "Plaid · Chase" in sidebar → left panel narrows to Plaid tables only; right panel highlights which unified schemas Plaid contributes to
5. **Household pivot:** Switch from person to household → both panels reload scoped to household (includes all people's data + household-level data)
6. **Read-time data:** Click on `unified.transaction` → sample rows load from `/data` endpoint, each tagged with source_connection
7. **LLM proposal:** Add a new source connection → LLM proposes unified schema → appears with `proposed` status and field-level REVIEW badges
8. **Accept/reject:** Accept `logo_url` field → status updates to `approved`, appears in subsequent `/data` queries; reject → excluded from queries
9. **Chatbot:** Ask chatbot "show my transactions across all banks" → uses `query_unified_schema` MCP tool → returns unified rows from Plaid + bulk-file + chatbot
10. **Different household:** Switch to "Aggarwal Household" → unified schemas differ if household has different source connections than individual person
