# MCP Tool Harness Results — v5

**Date:** 2026-04-25  
**Model:** claude-opus-4-7 (via `claude -p` subprocess)  
**Harness:** `tests/tool_harness/harness.py`  
**Tools defined:** 44 across 6 groups  
**Scenarios:** 25 · **Turns:** 44 (7 single-turn + 17 two-turn + 1 three-turn)

Changes from v4:
- **Two-phase model**: every write scenario is now multi-turn. Turn 1 (gather) reads all
  relevant existing data and proposes the write in text — no write tools called. Turn N
  (write) executes after explicit user approval.
- **Correction loop**: scenario 04 is a 3-turn flow (gather → correction → approve),
  validating that the agent updates its proposal without writing on intermediate turns.
- **Harness restructured**: all scenarios use `turns` format. Per-turn `expected_tools`
  and `forbidden_tools` validated independently. Conversation history from prior turns
  is embedded in the prompt for each subsequent turn.
- **`GATHER_FORBIDDEN`**: constant (15 tools) listed as `forbidden_tools` on every
  gather and correction turn. Harness validates no write tools appear.
- **`log_interaction`** required in every turn — gather, correction, write, and query alike.

---

## Summary

| # | Scenario | Turns | All expected ✓ | Notes |
|---|---|---|---|---|
| 01 | Lookup own profile | 1 | ✓ | Pure read — single turn |
| 02 | Update own profile | 2 | ✓ | Gather (get_person) → approve → update_person |
| 03 | Add todo — new | 2 | ✓ | Gather + propose → approve → create |
| 04 | Add todo — correction then approve | 3 | ✓ | **3-turn correction loop** |
| 05 | Update todo due date | 2 | ✓ | Gather finds existing (≥0.8) → approve → update |
| 06 | Update todo status | 2 | ✓ | |
| 07 | Mark todo as done | 2 | ✓ | |
| 08 | List open todos | 1 | ✓ | Pure read — single turn |
| 09 | Delete todo | 2 | ✓ | Gather finds entity → approve → delete |
| 10 | Add family member | 2 | ✓ | Gather (search_persons) → approve → create_person + create_relationship |
| 11 | Direct kinship query | 1 | ✓ | Pure read — single turn |
| 12 | Multi-hop kinship + add person | 2 | ✓ | Answers kinship question in gather turn, then writes on approval |
| 13 | List all family members | 1 | ✓ | Pure read — single turn |
| 14 | Store insurance — first time | 2 | ✓ | |
| 15 | Update insurance — supersedes | 2 | ✓ | search_documents in gather turn → supersedes_ids in write turn |
| 16 | Query insurance deductible | 1 | ✓ | Pure read — single turn |
| 17 | Add new job | 2 | ✓ | |
| 18 | Salary change — supersedes | 2 | ✓ | search_documents in gather turn → supersedes_ids in write turn |
| 19 | Query current salary | 1 | ✓ | Pure read — single turn |
| 20 | Upload payslip PDF | 2 | ✓ | save_file + extract_text_from_file in gather turn |
| 21 | Upload insurance card (OCR) | 2 | ✓ | save_file + extract_text_from_file in gather turn |
| 22 | Create household + add members | 2 | ✓ | |
| 23 | List household members | 1 | ✓ | Pure read — single turn |
| 24 | New entity type — propose + confirm | 2 | ✓ | Stop gate: no confirm in Turn 1; confirm in Turn 2 |
| 25 | Evolve schema — evolve + confirm | 2 | ✓ | Stop gate: no confirm in Turn 1; confirm in Turn 2 |

**25 / 25 pass · 44 / 44 turns pass**

`list_source_types` absent across all turns ✓  
No write tools in any gather or correction turn ✓

---

## Two-phase model

Every write scenario follows this structure:

**Turn 1 — Gather + Propose** (no write tools)
```
list_domains
list_entity_type_schemas       ← discover all types in domain
get_current_entity_type_schema ← get field definitions for best match
search_current_facts           ← dedup: ≥0.8 → update, <0.8 → create
[search_documents]             ← if superseding existing data
[save_file + extract_text]     ← if file uploaded
log_interaction
```
Agent proposes in text what it intends to write. No write tools called.
Forbidden: all 15 write tools (GATHER_FORBIDDEN).

**Turn N — Write** (after user approval)
```
[create_person / create_relationship / ...]   ← spine writes if needed
create_document                               ← [supersedes_ids=...] if renewal
create_fact                                   ← operation_type from gather phase
log_interaction
```

---

## Correction loop (scenario 04)

| Turn | User message | Tools called | Writes? |
|---|---|---|---|
| 1 | "Remind me to schedule a dentist appointment by end of March." | list_domains, list_entity_type_schemas, get_current_entity_type_schema, search_current_facts, log_interaction | ✗ |
| 2 | "Actually make that end of April, not March." | log_interaction | ✗ |
| 3 | "Yes, that's right, go ahead." | create_document, create_fact, log_interaction | ✓ |

Turn 2 (correction): agent updates proposal in text, logs the exchange, no writes.
Turn 3: writes proceed with the corrected April due date.

---

## Schema governance (scenarios 24–25)

Both schema scenarios now test the full two-turn governance flow:

**Scenario 24 — New entity type:**
- Turn 1: `list_entity_type_schemas` → gym_membership absent → `propose_entity_type_schema` → STOP → `log_interaction`
- Turn 2: user says "yes" → `confirm_entity_type_schema` → `log_interaction`

**Scenario 25 — Schema evolution:**
- Turn 1: `list_entity_type_schemas` → `get_current_entity_type_schema` → `evolve_entity_type_schema` → STOP → `log_interaction`
- Turn 2: user says "yes" → `confirm_entity_type_schema` → `log_interaction`

The stop gate (no auto-confirm in same turn as propose/evolve) is validated by GATHER_FORBIDDEN
which includes `confirm_entity_type_schema`.

---

## Progression: v1 → v5

| Version | Pass rate | Key improvement |
|---|---|---|
| v1 | 12/25 | Baseline — tool catalog only, no rules |
| v2 | 19/25 | Added rules to scenarios.py (but never sent to Claude) |
| v3 | 25/25 | Fixed SYSTEM_PROMPT injection; single chatbot source type; list_domains at runtime |
| v4 | 25/25 | Schema discovery via list_entity_type_schemas; explicit similarity threshold |
| v5 | 25/25 (44 turns) | Two-phase model: gather+propose turn before every write; correction loop; GATHER_FORBIDDEN validated per-turn |
