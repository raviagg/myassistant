# MCP Tools Design — Personal Assistant

This document defines all MCP tools exposed to the Claude agent (or any other orchestrator). Tools are organised into five semantic groups that map to the conceptual layers of the data model.

**Architecture:** Python/TypeScript MCP shim → Scala backend service. The shim handles the MCP JSON-RPC protocol; all business logic and DB access lives in the Scala service.

**Design principles:**

1. **Low-level tools only** — no pre-baked orchestration flows. Claude decides the sequence; tools are the primitives.
2. **Server-managed transactions** — each tool is its own atomic unit. Transactions are never exposed to the caller.
3. **Embeddings are external** — callers generate embeddings before calling write tools; the MCP layer only persists and queries them.
4. **All context is explicit** — no session-based identity injection. Every tool receives all the IDs it needs as parameters.
5. **Promote to composite tools later** — once recurring multi-tool patterns emerge in practice, wrap them. Not before.

---

## Table of Contents

| Group | Tools |
|---|---|
| [1a — Person](#group-1a--person-5-tools) | 5 |
| [1b — Household](#group-1b--household-5-tools) | 5 |
| [1c — Person-Household](#group-1c--person-household-4-tools) | 4 |
| [1d — Relationship](#group-1d--relationship-6-tools) | 6 |
| [2a — Document](#group-2a--document-4-tools) | 4 |
| [2b — Fact](#group-2b--fact-5-tools) | 5 |
| [3 — Schema Governance](#group-3--schema-governance-6-tools) | 6 |
| [4 — Reference](#group-4--reference-3-tools) | 3 |
| [5 — Audit](#group-5--audit-1-tool) | 1 |
| [6 — File Handling](#group-6--file-handling-4-tools) | 4 |
| **Total** | **43** |

---

## Group 1a — Person (5 tools)

Person is a mutable entity. Updates and deletes are allowed, subject to referential integrity checks. Semantic/fuzzy name search is deferred to a later iteration; current search is filter-based.

### `create_person`

**Purpose:** Register a new person in the system. This is the first step before any documents, facts, or relationships can be attached to them.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `full_name` | string | yes | Legal or full name |
| `gender` | enum: `male` \| `female` | yes | Used for kinship resolution (e.g. deriving husband ↔ wife inverses) |
| `date_of_birth` | date (ISO 8601) | no | |
| `preferred_name` | string | no | Nickname or common name |
| `user_identifier` | string | no | Unique login handle if this person is a system user |

**Returns:** Full person row including generated `id` and timestamps.

### `get_person`

**Purpose:** Fetch a single person by UUID. Used when the caller already knows the ID (e.g. after resolving it via `search_persons`).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `person_id` | UUID | yes | |

**Returns:** Full person row, or not-found error.

### `search_persons`

**Purpose:** Find persons matching one or more filter criteria. All listing goes through this tool with optional filters. Multiple filters are ANDed together.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | no | Case-insensitive partial match on `full_name` or `preferred_name` |
| `gender` | enum | no | Exact match |
| `date_of_birth` | date | no | Exact match |
| `date_of_birth_from` | date | no | Range lower bound (inclusive) |
| `date_of_birth_to` | date | no | Range upper bound (inclusive) |
| `household_id` | UUID | no | Only persons who are members of this household |
| `limit` | int | no | Default 50 |
| `offset` | int | no | Default 0 |

**Returns:** person[]

### `update_person`

**Purpose:** Update mutable fields on an existing person. Only supplied fields are changed; omitted fields are left unchanged (PATCH semantics).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `person_id` | UUID | yes | |
| `full_name` | string | no | |
| `preferred_name` | string | no | |
| `date_of_birth` | date | no | |
| `gender` | enum | no | |
| `user_identifier` | string | no | |

**Returns:** Updated person row.

### `delete_person`

**Purpose:** Remove a person from the system. The server checks all foreign key references before deleting. If references exist the delete is rejected and the response lists what is blocking it, so the caller can decide whether to cascade or abort.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `person_id` | UUID | yes | |

**Returns:** Success confirmation, or structured blocking error:
```json
{
  "error": "referenced",
  "blocking": { "documents": 4, "facts": 12, "relationships": 2 }
}
```

---

## Group 1b — Household (5 tools)

Household is a mutable entity representing a family or shared living group. Same delete-with-integrity-check pattern as Person.

### `create_household`

**Purpose:** Create a new household. Persons are linked to it separately via the Person-Household group.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | e.g. "Aggarwal Family", "London Flat" |

**Returns:** Full household row including generated `id`.

### `get_household`

**Purpose:** Fetch a single household by UUID.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `household_id` | UUID | yes | |

**Returns:** Full household row, or not-found error.

### `search_households`

**Purpose:** Find households by name. Case-insensitive partial match.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | |

**Returns:** household[]

### `update_household`

**Purpose:** Rename a household.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `household_id` | UUID | yes | |
| `name` | string | yes | |

**Returns:** Updated household row.

### `delete_household`

**Purpose:** Remove a household. Returns a structured error listing blocking references (documents, facts, members) rather than silently failing.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `household_id` | UUID | yes | |

**Returns:** Success confirmation or structured blocking error (same shape as `delete_person`).

---

## Group 1c — Person-Household (4 tools)

Manages the N:N membership between persons and households. Kept as its own group because membership is a distinct concern from the identity of either party.

### `add_person_to_household`

**Purpose:** Link an existing person to an existing household. Idempotent — no error if the link already exists.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `person_id` | UUID | yes | |
| `household_id` | UUID | yes | |

**Returns:** void

### `remove_person_from_household`

**Purpose:** Remove a person's membership from a household. Does not delete the person or the household.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `person_id` | UUID | yes | |
| `household_id` | UUID | yes | |

**Returns:** void

### `list_household_members`

**Purpose:** Return all person IDs who are members of a household. Returns IDs only — call `get_person` for full details if needed.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `household_id` | UUID | yes | |

**Returns:** person_id[]

### `list_person_households`

**Purpose:** Return all household IDs that a person belongs to. Returns IDs only — call `get_household` for full details if needed.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `person_id` | UUID | yes | |

**Returns:** household_id[]

---

## Group 1d — Relationship (6 tools)

Stores depth-1 directed relationships between persons using 8 atomic relation types: `father`, `mother`, `son`, `daughter`, `brother`, `sister`, `husband`, `wife`.

Deeper relations (grandfather, aunt, bua, mama etc.) are never stored — they are derived at query time by traversing the relationship graph. `resolve_kinship` does this traversal server-side via a recursive CTE rather than requiring the caller to make multiple round-trip hops.

### `create_relationship`

**Purpose:** Record a directed relationship between two persons. Both persons must already exist. The 8 atomic types are enforced at the DB level.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `from_person_id` | UUID | yes | The subject |
| `to_person_id` | UUID | yes | The object |
| `relation_type` | enum | yes | One of the 8 atomic types |

**Returns:** relationship row

### `get_relationship`

**Purpose:** Fetch a single directed relationship between two persons.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `from_person_id` | UUID | yes | |
| `to_person_id` | UUID | yes | |

**Returns:** relationship row, or not-found error.

### `list_relationships`

**Purpose:** Return all relationships where the given person appears as either subject or object. Returns both directions so the caller has the full local graph for that person.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `person_id` | UUID | yes | |

**Returns:** relationship[] (includes rows where person is `from` or `to`)

### `update_relationship`

**Purpose:** Change the `relation_type` on an existing directed relationship (e.g. correcting a data entry mistake).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `from_person_id` | UUID | yes | |
| `to_person_id` | UUID | yes | |
| `relation_type` | enum | yes | New relation type |

**Returns:** Updated relationship row.

### `delete_relationship`

**Purpose:** Remove a directed relationship between two persons.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `from_person_id` | UUID | yes | |
| `to_person_id` | UUID | yes | |

**Returns:** void

### `resolve_kinship`

**Purpose:** Derive the cultural name (e.g. "bua", "mama", "nana") for the relationship between two persons, potentially across multiple hops.

The server performs a BFS traversal over the `relationship` table using a recursive CTE, builds the chain of atomic relation types from `from_person_id` to `to_person_id`, then looks up the chain in `kinship_alias` to find the cultural name. Gender from the `person` table is used to resolve directional inverses (e.g. wife ↔ husband) during traversal.

This lives server-side rather than being delegated to the caller because multi-hop traversal would require N round trips and is error-prone for the orchestrator to terminate correctly.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `from_person_id` | UUID | yes | The person asking ("who is my bua?") |
| `to_person_id` | UUID | yes | The person being described |

**Returns:**
```json
{ "chain": ["father", "sister"], "alias": "bua", "language": "hindi" }
```
Returns not-found if no path exists or no alias is registered for the derived chain.

---

## Group 2a — Document (4 tools)

Documents are **immutable**. Once created they are never updated or deleted. This is a core design principle — documents are the source of truth and full audit trail for all information in the system.

When information changes, a new document is created with `supersedes_ids` pointing at the document(s) it replaces. The superseding chain provides complete temporal history.

### `create_document`

**Purpose:** Persist a new document and its embedding. This is the entry point for all information into the system — every piece of data, whether typed by a user or sent by a polling job, becomes a document first.

The embedding is generated externally by the caller before this tool is invoked. The tool only persists it. At least one of `person_id` or `household_id` must be provided.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `content_text` | string | yes | The full natural language content |
| `source_type_id` | UUID | yes | FK to `source_type` (e.g. user_input, gmail_poll) |
| `embedding` | float[] | yes | Pre-generated vector for `content_text` |
| `person_id` | UUID | no | Owner person (at least one of person/household required) |
| `household_id` | UUID | no | Owner household |
| `supersedes_ids` | UUID[] | no | Documents this one replaces |
| `files` | object[] | no | Attached file references |

**Returns:** Full document row including generated `id`.

### `get_document`

**Purpose:** Fetch a single document by UUID. Used for provenance lookups — tracing a fact back to its source document.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `document_id` | UUID | yes | |

**Returns:** Full document row.

### `list_documents`

**Purpose:** Filter-based listing of documents. Used for browsing or auditing what has been ingested for a person or household.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `person_id` | UUID | no | |
| `household_id` | UUID | no | |
| `source_type_id` | UUID | no | |
| `created_after` | timestamp | no | |
| `created_before` | timestamp | no | |
| `limit` | int | no | Default 50 |
| `offset` | int | no | Default 0 |

**Returns:** document[]

### `search_documents`

**Purpose:** Vector similarity search over documents. Used to find documents semantically related to a query — for example, identifying which existing documents a new document might supersede, or retrieving relevant source material for a question.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `embedding` | float[] | yes | Query vector (pre-generated by caller) |
| `person_id` | UUID | no | Restrict to this person's documents |
| `household_id` | UUID | no | Restrict to this household's documents |
| `source_type_id` | UUID | no | |
| `limit` | int | no | Default 10 |
| `similarity_threshold` | float | no | Minimum cosine similarity 0–1. Default 0.7 |

**Returns:** document[] each with an added `similarity_score` field.

---

## Group 2b — Fact (5 tools)

Facts are **append-only**. They are never updated or deleted in place. Every change to an entity produces a new fact row with `operation_type` of `create`, `update`, or `delete`. A logical deletion of an entity is itself a fact row with `operation_type = delete`.

Current state is derived by merging all operations for an `entity_instance_id` in chronological order using patch semantics — later values overwrite earlier ones; null values mean a field was explicitly removed.

**Shape distinction:**

- Tools returning **raw fact rows** (`create_fact`, `get_fact_history`) answer "what happened?" — each row is a single operation with partial fields and full provenance.
- Tools returning **current fact state** (`get_current_fact`, `list_current_facts`, `search_current_facts`) answer "what is the current state?" — fields are the fully merged result of all operations. These use the `current_facts` view internally.

### `create_fact`

**Purpose:** Persist a single fact operation extracted from a document. For a new entity (`operation_type = create`), the caller generates a fresh UUID for `entity_instance_id`. For updates and deletes, the caller first resolves the existing UUID via `search_current_facts`.

The embedding should represent the merged current state of the entity (not just the fields in this single operation). This ensures `search_current_facts` always finds the most up-to-date representation.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `document_id` | UUID | yes | Source document (provenance) |
| `schema_id` | UUID | yes | FK to `entity_type_schema` |
| `entity_instance_id` | UUID | yes | Groups all operations on the same logical entity |
| `operation_type` | enum: `create` \| `update` \| `delete` | yes | |
| `fields` | object | yes | JSONB — only the fields being set/changed in this operation |
| `embedding` | float[] | yes | Pre-generated vector representing merged current entity state |

**Returns:** Raw fact row including generated `id`.

### `get_fact_history`

**Purpose:** Retrieve the full operation history for a single entity instance in chronological order. Used for temporal queries ("what was my salary in 2023?") and debugging extraction pipelines.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `entity_instance_id` | UUID | yes | |

**Returns:** Raw fact row[] ordered by `created_at` ascending.

### `get_current_fact`

**Purpose:** Retrieve the merged current state for a single entity instance. Used when the caller already knows the `entity_instance_id` and wants the latest state. Returns not-found if the entity has been logically deleted.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `entity_instance_id` | UUID | yes | |

**Returns:**
```json
{
  "entity_instance_id": "uuid",
  "schema_id": "uuid",
  "person_id": "uuid",
  "household_id": "uuid",
  "fields": { "title": "passport renewal", "status": "done" },
  "last_updated_at": "2026-04-10T12:00:00Z"
}
```

### `list_current_facts`

**Purpose:** Filter-based listing of current entity states. Used for structured queries like "show all my todo items" or "list all jobs for this person". Operates on the `current_facts` view — only returns entities that are currently active (not deleted).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `person_id` | UUID | no | |
| `household_id` | UUID | no | |
| `domain_id` | UUID | no | |
| `entity_type` | string | no | e.g. `todo_item`, `insurance_card` |
| `limit` | int | no | Default 50 |
| `offset` | int | no | Default 0 |

**Returns:** Current fact state[]

### `search_current_facts`

**Purpose:** Vector similarity search over current entity states. This is the **entity instance resolution tool** — the primary mechanism by which the orchestrator finds the `entity_instance_id` for an entity described in natural language (e.g. "my passport renewal", "the HDFC savings account").

Internally: vector search on `fact.embedding` → deduplicate by `entity_instance_id` → join against `current_facts` view → return merged current state with similarity scores. Only active (non-deleted) entities are returned.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `embedding` | float[] | yes | Query vector (pre-generated by caller) |
| `person_id` | UUID | no | |
| `household_id` | UUID | no | |
| `domain_id` | UUID | no | |
| `entity_type` | string | no | |
| `limit` | int | no | Default 10 |
| `similarity_threshold` | float | no | Default 0.7 |

**Returns:** Current fact state[] each with an added `similarity_score` field.

---

## Group 3 — Schema Governance (6 tools)

Manages the `entity_type_schema` table, which defines what facts can be extracted for each (domain, entity_type) pair.

The key UX pattern is the **two-phase turn model** — the same model used throughout the system:
1. **Gather turn** — Claude encounters information that has no schema. It reads existing schemas via `list_entity_type_schemas` / `get_current_entity_type_schema`, then proposes the new schema or change *in text* as part of its response. No write tools are called.
2. **User confirms** — the user approves the proposed schema in conversation.
3. **Write turn** — Claude calls `create_entity_type_schema` (new type) or `update_entity_type_schema` (add/change fields on existing type). The schema becomes active immediately — there is no separate confirm step.

Schema changes take effect as soon as the write tool is called; the confirmation gate is the user's explicit approval in the prior conversational turn.

Text-based search is intentionally omitted — the table is small enough that `list_entity_type_schemas(active_only=true)` gives the orchestrator sufficient context to reason about existing schemas without needing embeddings.

### `list_entity_type_schemas`

**Purpose:** List schema definitions, optionally filtered. The primary tool for Claude to discover what entity types are already known before deciding whether to propose a new one.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `domain_id` | UUID | no | |
| `entity_type` | string | no | Exact match |
| `active_only` | boolean | no | Default true — excludes superseded versions |

**Returns:** schema[]

### `get_entity_type_schema`

**Purpose:** Fetch a specific schema version by UUID. Used for provenance — tracing which schema version was active when a fact was extracted.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `schema_id` | UUID | yes | |

**Returns:** Full schema row.

### `get_current_entity_type_schema`

**Purpose:** Fetch the latest active schema for a (domain, entity_type) pair. Used during fact extraction to know which fields to extract. Uses the `current_entity_type_schema` view internally.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `domain_id` | UUID | yes | |
| `entity_type` | string | yes | |

**Returns:** Full schema row, or not-found if no active schema exists.

### `create_entity_type_schema`

**Purpose:** Create a new schema definition for a (domain, entity_type) pair that does not yet exist. The schema is immediately active (`is_active = true, schema_version = 1`). Called in the write turn after the user has approved the proposed schema in conversation.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `domain_id` | UUID | yes | |
| `entity_type` | string | yes | Snake_case name e.g. `blood_pressure_reading` |
| `field_definitions` | object[] | yes | Array of field objects: `name`, `type`, `mandatory`, `description` |
| `description` | string | no | Human-readable description of this entity type |

**Returns:** Schema row with `is_active = true` and `schema_version = 1`.

### `update_entity_type_schema`

**Purpose:** Create a new version of an existing active schema — for example, adding a new field or changing a field type. Creates a new schema row with `schema_version` incremented and `is_active = true`; the previous version is deactivated. Called in the write turn after the user approves the proposed change.

The caller must provide the **full field list** for the new version, not a diff. This ensures the new schema row is self-contained and unambiguous.

Facts extracted against older schema versions are not automatically re-extracted; a separate backfill job handles that (out of scope for this tool).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `domain_id` | UUID | yes | |
| `entity_type` | string | yes | Identifies the existing schema to evolve |
| `field_definitions` | object[] | yes | Full field list for the new version — not a diff, provide all fields |
| `description` | string | no | Updated description if needed |

**Returns:** New schema row with incremented `schema_version` and `is_active = true`.

### `deactivate_entity_type_schema`

**Purpose:** Mark the current active schema for a (domain, entity_type) pair as inactive. Used when an entity type is no longer relevant. Does not delete history — all past schema versions and extracted facts are retained.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `domain_id` | UUID | yes | |
| `entity_type` | string | yes | |

**Returns:** void

---

## Group 4 — Reference (3 tools)

Read-only lookups over seeded reference tables. These tables are small and stable. New entries are added via migrations, not MCP tools.

### `list_domains`

**Purpose:** Return all life domains (health, finance, employment, etc.). Used by the orchestrator to resolve a domain name to its UUID before calling other tools.

**Parameters:** none

**Returns:** domain[] — each with `id` and `name`.

### `list_source_types`

**Purpose:** Return all registered source types (user_input, gmail_poll, plaid_poll, etc.). Used to resolve a source type name to its UUID when creating documents.

**Parameters:** none

**Returns:** source_type[] — each with `id` and `name`.

### `list_kinship_aliases`

**Purpose:** Return all cultural kinship name mappings. Each entry maps a chain of atomic relation types to a cultural name in a given language (e.g. `["father", "sister"]` → "bua" in Hindi). Useful for the orchestrator to understand the full alias vocabulary when explaining relationships in conversation.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `language` | string | no | Filter by language e.g. `hindi`, `english` |

**Returns:** kinship_alias[] — each with `chain`, `alias`, and `language`.

---

## Group 5 — Audit (1 tool)

### `log_interaction`

**Purpose:** Persist a record of one interaction turn — whether a human chat message or an automated polling job run — to the audit log. Called after every turn is fully processed.

Exactly one of `person_id` or `job_type` must be set: human turns set `person_id`, polling jobs set `job_type`. This constraint is enforced server-side.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `message_text` | string | yes | The incoming message |
| `response_text` | string | yes | The agent's response |
| `status` | enum: `success` \| `error` \| `partial` | yes | |
| `person_id` | UUID | no | Set for human chat turns |
| `job_type` | string | no | Set for polling job runs (e.g. `plaid_poll`) |
| `tool_calls_json` | object[] | no | Record of all MCP tool calls made during this turn |
| `error_message` | string | no | Set when status is `error` or `partial` |

**Returns:** audit_log row.

---

## Group 6 — File Handling (4 tools)

Files are stored on the local filesystem. File paths are persisted in the `files` array of a document and may also appear in fact `fields` (e.g. an insurance card scan). The storage backend can be extended to S3 later without changing the tool interface — callers always work with opaque file path strings.

### `save_file`

**Purpose:** Persist a file to the local filesystem and return its path. The LLM receives file content as base64 from the chat interface and calls this tool to store it. The returned `file_path` is then passed to `create_document` and/or `extract_text_from_file`.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `content_base64` | string | yes | Base64-encoded file content |
| `filename` | string | yes | Original filename including extension |
| `mime_type` | string | no | e.g. `application/pdf`, `image/jpeg`. Defaults to `application/octet-stream` if omitted. |

**Returns:** `{ file_path: string }`

### `extract_text_from_file`

**Purpose:** Extract plain text from a saved file. Dispatches to the appropriate extraction method based on file type — PDF parser for PDFs, OCR for images, plain read for text files. The extracted text is what gets stored in `document.content_text` and used to generate the embedding.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `file_path` | string | yes | Path returned by `save_file` |

**Returns:** `{ text: string, extraction_method: string }` where `extraction_method` is one of `pdf_parser`, `ocr`, `plain_text`.

### `get_file`

**Purpose:** Retrieve a previously saved file by its path. Returns base64-encoded content for the caller to present or process.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `file_path` | string | yes | |

**Returns:** `{ content_base64: string, mime_type: string, filename: string }`

### `delete_file`

**Purpose:** Delete a file from the filesystem. The server checks whether the file path is still referenced in any document's `files` array before deleting. If references exist the delete is rejected with a structured error listing the blocking document IDs.

Note: documents are immutable, so a referenced file cannot be cleaned up by updating the document. The caller should only delete files they are certain are no longer needed.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `file_path` | string | yes | |

**Returns:** Success confirmation, or structured blocking error:
```json
{
  "error": "referenced",
  "blocking": { "documents": ["uuid1", "uuid2"] }
}
```

---

## Summary

| Group | Tools |
|---|---|
| 1a — Person | 5 |
| 1b — Household | 5 |
| 1c — Person-Household | 4 |
| 1d — Relationship | 6 |
| 2a — Document | 4 |
| 2b — Fact | 5 |
| 3 — Schema Governance | 6 |
| 4 — Reference | 3 |
| 5 — Audit | 1 |
| 6 — File Handling | 4 |
| **Total** | **43** |
