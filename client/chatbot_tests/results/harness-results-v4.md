# MCP Tool Harness Results — v4

**Date:** 2026-04-25  
**Model:** claude-opus-4-7 (via `claude -p` subprocess)  
**Harness:** `client/chatbot_tests/tool_harness/harness.py`  
**Tools defined:** 44 across 6 groups  
**Scenarios run:** 25

Changes from v3:
- **Rule 0 added**: `list_entity_type_schemas` required before every fact write — agent discovers
  all candidate schemas in a domain and selects the best match, rather than guessing entity type names
- **Rule 1 strengthened**: Explicit similarity threshold documented (≥0.8 → must update, <0.8 → create new);
  `search_current_facts` required even when the entity is believed to be brand new
- **Rule 2 clarified**: Two distinct use cases for `search_documents` documented explicitly:
  (a) SUPERSEDING — find old source document for `supersedes_ids`
  (b) HISTORICAL/SOURCE QUERIES — retrieve original source content
- **`expected_tools` updated**: `list_entity_type_schemas` added to all write-fact scenarios (03–09, 14–15, 17–18, 20–21, 24–25)
- **Scenario 24 updated**: Primary discovery tool changed from `get_current_entity_type_schema` to `list_entity_type_schemas`
  (discovering that gym_membership is absent from health schemas); `create_document` and `create_fact` added to `forbidden_tools`
- **Scenario 25 updated**: `list_entity_type_schemas` added to `expected_tools` to discover insurance_card first
- **`mock_server.py` extended**: Added `_job_schema()` and `_payslip_schema()` helpers;
  `_h_list_entity_type_schemas()` made domain-specific; `_h_get_current_entity_type_schema()` extended for job/payslip
- **`GLOBAL_FORBIDDEN_TOOLS`**: `list_source_types` remains enforced globally across all 25 scenarios

---

## Summary

| # | Scenario | Tools called | All expected ✓ | Notes |
|---|---|---|---|---|
| 01 | Lookup own profile | 2 | ✓ | |
| 02 | Update own profile | 2 | ✓ | |
| 03 | Add todo — search first, not found → create | 6 | ✓ | `list_entity_type_schemas` + `get_current_entity_type_schema` present |
| 04 | Add todo — search finds existing → update due date | 6 | ✓ | |
| 05 | Update todo status to in_progress | 5 | ✓ | |
| 06 | Mark todo as done | 6 | ✓ | |
| 07 | Add recurring todo | 6 | ✓ | |
| 08 | List open todos | 2 | ✓ | |
| 09 | Delete a todo explicitly | 6 | ✓ | |
| 10 | Add a family member | 6 | ✓ | |
| 11 | Direct kinship query with Hindi term | 3 | ✓ | |
| 12 | Multi-hop kinship — father's sister | 6 | ✓ | |
| 13 | List all family members | 4 | ✓ | |
| 14 | Store insurance — first time | 6 | ✓ | `list_entity_type_schemas` + `get_current_entity_type_schema` present |
| 15 | Update insurance — supersedes old document | 7 | ✓ | |
| 16 | Query insurance deductible | 3 | ✓ | |
| 17 | Add new job | 6 | ✓ | `list_entity_type_schemas` + `get_current_entity_type_schema` present |
| 18 | Salary change — supersedes old employment document | 7 | ✓ | |
| 19 | Query current salary | 2–3 | ✓ | Both `list_current_facts` and `search_current_facts` accepted |
| 20 | Upload payslip PDF | 8 | ✓ | Full file pipeline with schema discovery |
| 21 | Upload insurance card image (OCR path) | 8 | ✓ | Full file pipeline with schema discovery |
| 22 | Create household and add members | 5 | ✓ | |
| 23 | List household members | 3 | ✓ | |
| 24 | New entity type — propose only, stop for confirmation | 2 | ✓ | `list_entity_type_schemas` shows gym_membership absent → propose only |
| 25 | Evolve schema — add copay field to insurance | 3 | ✓ | `list_entity_type_schemas` → find insurance_card → evolve, then STOP |

**25 / 25 pass**

`list_source_types` absent in all 25 scenarios ✓

---

## What changed between v3 and v4

### Root problem: agent was guessing entity type names
In v3 the agent called `get_current_entity_type_schema(entity_type="todo_item")` directly —
guessing the entity type name without first checking what schemas exist in a domain.
For well-known types (todo_item, insurance_card) this worked, but for novel types it would fail
silently (404 → proceed anyway) or pick the wrong schema for domains with multiple types.

### Fix: Rule 0 — SCHEMA DISCOVERY
Every write turn now requires:
1. `list_entity_type_schemas(domain_id=...)` — see ALL available types in the domain
2. Pick the best match by name + description
3. If no match → `propose_entity_type_schema` + STOP (Rule 3 still applies)
4. If match found → `get_current_entity_type_schema(entity_type=best_match)` for the full schema

This also makes scenario 24 (gym membership) work correctly: the agent lists health domain schemas,
sees no gym_membership type, and correctly stops at `propose_entity_type_schema`.

### Fix: Rule 1 — explicit similarity threshold
Added the numeric threshold (≥0.8 → must update) so the agent has a clear, unambiguous decision rule
instead of a vague "if similar enough". Also clarified that `search_current_facts` is required
**even when the entity is believed to be brand new** — prevents the optimistic-create anti-pattern.

### Fix: Rule 2 — two clear use cases for search_documents
The distinction between `search_current_facts` (merged current state) and `search_documents`
(raw source text) was implicit. Now explicitly documented as two named cases:
- SUPERSEDING: find old document → pass its ID in `supersedes_ids` on new `create_document`
- HISTORICAL/SOURCE QUERIES: retrieve original text as written

---

## Rule Set Reference (v4)

```
Rule 0. SCHEMA DISCOVERY (every write turn with facts):
  a. list_entity_type_schemas(domain_id=...) — see all available types
  b. select best match by name + description
  c. no match → propose_entity_type_schema + STOP
  d. match found → get_current_entity_type_schema(entity_type=best_match)

Rule 1. SEARCH BEFORE WRITE (strict):
  Before create_fact, ALWAYS call search_current_facts.
  similarity >= 0.8 → use existing entity_instance_id, operation_type=update
  no results or < 0.8 → new UUID placeholder, operation_type=create

Rule 2. search_documents vs search_current_facts:
  search_current_facts → current merged state (dedup check + current value lookups)
  search_documents →
    (a) SUPERSEDING: find old doc → supersedes_ids on new create_document
    (b) HISTORICAL/SOURCE QUERIES: retrieve original source text

Rule 3. SCHEMA CONFIRMATION:
  After propose_entity_type_schema or evolve_entity_type_schema: STOP.
  Do NOT call confirm_entity_type_schema, create_document, or create_fact in same turn.

Rule 4. AUDIT: log_interaction is the final call in every human chat turn.
```

---

## Standard write-fact pipeline (v4)

Every scenario that stores a new or updated fact follows this sequence:

| Step | Tool | Purpose |
|---|---|---|
| 1 | `list_domains` | resolve domain_id for the information category |
| 2 | `list_entity_type_schemas` | discover all entity types in domain (Rule 0a) |
| 3 | `get_current_entity_type_schema` | get full field definitions for best-match type (Rule 0d) |
| 4 | `search_current_facts` | dedup check — find existing entity if any (Rule 1) |
| 5 | `create_document` | store NL source (with `supersedes_ids` if renewing) |
| 6 | `create_fact` | store structured facts with `operation_type` create or update |
| 7 | `log_interaction` | audit trail (Rule 4) |

For **renewals/changes** (scenarios 15, 18): insert `search_documents` between steps 4 and 5 to find the old source document for `supersedes_ids`.

For **file uploads** (scenarios 20, 21): insert `save_file` + `extract_text_from_file` before step 1.

---

## Selected scenario details

### Scenario 03 · Add todo — schema discovery path

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_entity_type_schemas` | `domain_id="TODO-DOMAIN-ID"` → returns `[todo_item]` |
| 3 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 4 | `search_current_facts` | `query="passport renewal"`, `similarity_threshold=0.8` → no results |
| 5 | `create_document` | `source_type_id="dddd0001-..."` (chatbot, no lookup needed) |
| 6 | `create_fact` | `operation_type="create"`, `fields={title, status, due_date}` |
| 7 | `log_interaction` | `status="success"` |

Agent discovers todo_item schema via `list_entity_type_schemas`, does not guess the name.

### Scenario 24 · New entity type — schema absence detection

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_entity_type_schemas` | `domain_id="HEALTH-DOMAIN-ID"` → returns `[insurance_card]` only |
| 3 | `propose_entity_type_schema` | `entity_type="gym_membership"`, `is_active=false`, fields=[gym_name, monthly_cost, ...] |

`gym_membership` absent from health domain schemas → agent proposes new type and STOPS.
`confirm_entity_type_schema`, `create_document`, `create_fact` all absent — full gate working.

### Scenario 25 · Schema evolution — discovery first

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_entity_type_schemas` | `domain_id="HEALTH-DOMAIN-ID"` → finds `insurance_card` |
| 3 | `get_current_entity_type_schema` | `entity_type="insurance_card"` |
| 4 | `evolve_entity_type_schema` | adds `copay_amount` field, `is_active=false` |

`list_entity_type_schemas` now appears first to confirm insurance_card exists before evolving it.
Stops after `evolve_entity_type_schema`. `confirm_entity_type_schema` absent — gate working.

---

## Progression: v1 → v4

| Version | Pass rate | Key improvement |
|---|---|---|
| v1 | 12/25 | Baseline — tool catalog only, no rules, no session context |
| v2 | 19/25 | Added rules + session context to scenarios.py (but never sent to Claude) |
| v3 | 25/25 | Fixed SYSTEM_PROMPT injection; single chatbot source_type; domain IDs via list_domains |
| v4 | 25/25 | Schema discovery via list_entity_type_schemas; explicit similarity threshold; clarified search_documents use cases |
