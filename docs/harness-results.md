# MCP Tool Harness Results

**Date:** 2026-04-24  
**Model:** claude-opus-4-7 (via `claude -p` subprocess)  
**Harness:** `tests/tool_harness/harness.py`  
**Tools defined:** 44 across 6 groups  
**Scenarios run:** 10

---

## Summary

| # | Scenario | Tools called | All expected ✓ | Notes |
|---|---|---|---|---|
| 01 | Lookup own profile | 2 | ✓ | |
| 02 | Add a new todo | 6 | ✓ | |
| 03 | Update todo status | 7 | ✓ | |
| 04 | Mark todo as done | 7 | ✓ | |
| 05 | List open todos | 3 | ✓ | |
| 06 | Add a family member | 7 | ✓ | Both directions of relationship created |
| 07 | Kinship query | 4 | ✓ | `resolve_kinship` used, not multiple `list_relationships` |
| 08 | Store insurance details | 7 | ✓ | |
| 09 | Answer question from stored data | 3 | ✓ | Correctly skips `list_source_types` for read-only query |
| 10 | New entity type (gym membership) | 8 | ✗ (minor) | Used `get_current_entity_type_schema` instead of `list_entity_type_schemas` — reaches same conclusion. See notes. |

---

## Scenario 01 · Lookup own profile

**User:** "What's my name and date of birth?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `get_person` | `person_id="aaaaaaaa-..."` |
| 2 | `log_interaction` | `status="success"`, `person_id="aaaaaaaa-..."` |

**Observation:** Correct. Directly uses `get_person` since `person_id` is in context. No unnecessary lookups.

---

## Scenario 02 · Add a new todo

**User:** "Remind me to renew my passport by June 30, 2026."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `get_current_entity_type_schema` | `domain_id="TODO-DOMAIN-ID"`, `entity_type="todo_item"` |
| 4 | `create_document` | `content_text="Remind me to renew my passport..."`, `source_type_id="USER-INPUT-SOURCE-TYPE-ID"`, `embedding=[0.1, 0.2, 0.3]`, `person_id="aaaaaaaa-..."` |
| 5 | `create_fact` | `entity_instance_id="NEW-UUID-passport-renewal"`, `operation_type="create"`, `fields={"title":"Renew passport","status":"pending","due_date":"2026-06-30"}` |
| 6 | `log_interaction` | `status="success"` |

**Observation:** Correct full ingestion pipeline. Lookups (`list_domains`, `list_source_types`) happen before any writes that need their UUIDs. Schema fetched before fact extraction.

---

## Scenario 03 · Update todo status

**User:** "I started working on the passport renewal."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `search_current_facts` | `entity_type="todo_item"`, `embedding=[0.1,0.2,0.3]`, `limit=5`, `similarity_threshold=0.7` |
| 4 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 5 | `create_document` | `content_text="I started working on the passport renewal."` |
| 6 | `create_fact` | `entity_instance_id="ENTITY-INSTANCE-ID-passport-renewal"`, `operation_type="update"`, `fields={"status":"in_progress"}` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. `search_current_facts` used to resolve `entity_instance_id` before the update. Only the changed field (`status`) passed in the update — correct patch semantics.

---

## Scenario 04 · Mark todo as done

**User:** "Passport renewal is done!"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `search_current_facts` | `entity_type="todo_item"`, `embedding=[0.1,0.2,0.3]`, `limit=5` |
| 4 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 5 | `create_document` | `content_text="Passport renewal is done!"` |
| 6 | `create_fact` | `entity_instance_id="FOUND-PASSPORT-RENEWAL-ENTITY-INSTANCE-ID"`, `operation_type="update"`, `fields={"status":"done"}` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. Entity resolved via `search_current_facts`, update with `status=done`. Note: chose `operation_type="update"` rather than `"delete"` — both are valid interpretations; whether "done" should archive via delete is a product decision to codify in the system prompt.

---

## Scenario 05 · List open todos

**User:** "Show me all my open tasks."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_current_facts` | `person_id="aaaaaaaa-..."`, `domain_id="TODO-DOMAIN-ID"`, `entity_type="todo_item"` |
| 3 | `log_interaction` | `status="success"` |

**Observation:** Correct. Uses `list_current_facts` (structured filter) rather than `search_current_facts` (semantic search) — appropriate for a "show all" request with no natural language entity to resolve.

---

## Scenario 06 · Add a family member

**User:** "My wife is Priya Sharma, born March 15 1988."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_source_types` | — |
| 2 | `search_persons` | `name="Priya Sharma"` |
| 3 | `create_person` | `full_name="Priya Sharma"`, `gender="female"`, `date_of_birth="1988-03-15"` |
| 4 | `create_document` | `content_text="My wife is Priya Sharma, born March 15 1988."` |
| 5 | `create_relationship` | `from_person_id="aaaaaaaa-..."`, `to_person_id="PRIYA-PERSON-ID"`, `relation_type="wife"` |
| 6 | `create_relationship` | `from_person_id="PRIYA-PERSON-ID"`, `to_person_id="aaaaaaaa-..."`, `relation_type="husband"` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. Checks for existing person before creating. Creates both directed relationship rows (Ravi→wife→Priya and Priya→husband→Ravi) — good practice for bidirectional traversal without relying on gender-based inference at query time.

---

## Scenario 07 · Kinship query

**User:** "What is Priya's relation to me? Give me the Hindi term."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_persons` | `name="Priya"` |
| 2 | `resolve_kinship` | `from_person_id="aaaaaaaa-..."`, `to_person_id="PRIYA-PERSON-ID"` |
| 3 | `list_kinship_aliases` | `language="hindi"` |
| 4 | `log_interaction` | `status="success"` |

**Observation:** Correct. Uses `resolve_kinship` for the traversal rather than making multiple `list_relationships` calls. Also calls `list_kinship_aliases(language="hindi")` to surface the full vocabulary — slightly redundant since `resolve_kinship` returns the alias directly, but harmless.

---

## Scenario 08 · Store insurance details

**User:** "I have Aetna insurance, plan BlueShield PPO 500. Deductible $1,500 and monthly premium $420. Coverage runs from January 1 to December 31, 2026."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `get_current_entity_type_schema` | `domain_id="HEALTH-DOMAIN-ID"`, `entity_type="insurance_card"` |
| 4 | `search_current_facts` | `entity_type="insurance_card"`, `embedding=[0.1,0.2,0.3]`, `limit=5` |
| 5 | `create_document` | `content_text="I have Aetna insurance..."` |
| 6 | `create_fact` | `entity_instance_id="NEW-UUID-aetna-blueshield-ppo-2026"`, `operation_type="create"`, `fields={"provider":"Aetna","plan":"BlueShield PPO 500","deductible":1500,"premium":420,"valid_from":"2026-01-01","valid_to":"2026-12-31"}` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. Checks for existing insurance facts via `search_current_facts` before creating — good superseding hygiene. All field values correctly extracted and typed (numbers as numbers, dates as ISO strings).

---

## Scenario 09 · Answer question from stored data

**User:** "What is my current health insurance deductible?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `domain_id="HEALTH-DOMAIN-ID"`, `entity_type="insurance_card"`, `embedding=[0.1,0.2,0.3]`, `similarity_threshold=0.5` |
| 3 | `log_interaction` | `status="success"` |

**Observation:** Correct. Read-only path correctly skips `list_source_types` (not needed for reads). Uses `search_current_facts` with a lower similarity threshold (0.5) since it's confident about the entity type — appropriate.

---

## Scenario 10 · New entity type (gym membership)

**User:** "I just got a gym membership at Equinox for $80 per month."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `get_current_entity_type_schema` | `domain_id="HEALTH-DOMAIN-ID"`, `entity_type="gym_membership"` |
| 4 | `propose_entity_type_schema` | `entity_type="gym_membership"`, `field_definitions=[{name:"gym_name",mandatory:true},{name:"monthly_fee",type:"number",...},...]` |
| 5 | `confirm_entity_type_schema` | `schema_id="PROPOSED-GYM-MEMBERSHIP-SCHEMA-ID"` |
| 6 | `create_document` | `content_text="I just got a gym membership at Equinox for $80 per month."` |
| 7 | `create_fact` | `entity_instance_id="NEW-UUID-equinox-gym-membership"`, `operation_type="create"`, `fields={"gym_name":"Equinox","monthly_fee":80}` |
| 8 | `log_interaction` | `status="success"` |

**Observation:** Mostly correct. Used `get_current_entity_type_schema` (not-found) instead of `list_entity_type_schemas` — reaches the same conclusion differently; the expected list should be updated.

**Action item:** The agent auto-confirms the schema (`confirm_entity_type_schema`) in the same turn without user approval. The design intent is propose→show user→wait for confirmation. This must be enforced via the system prompt: "After calling `propose_entity_type_schema`, stop and present the proposed fields to the user. Do not call `confirm_entity_type_schema` until the user explicitly approves."

---

## Key Observations

### What works well

1. **Ingestion pipeline order is correct** — `list_domains` and `list_source_types` always precede write calls that need their UUIDs (scenarios 2, 3, 4, 8).

2. **Entity resolution via `search_current_facts`** — Used correctly before every `update`/`delete` fact operation (scenarios 3, 4). Not used for plain "list all" queries where `list_current_facts` is more appropriate (scenario 5).

3. **Patch semantics respected** — Update facts carry only the changed field (e.g. `{"status":"in_progress"}`), not a full copy of the entity.

4. **Bidirectional relationships** — Both directed rows created when adding a family member (Ravi→wife→Priya and Priya→husband→Ravi), without being explicitly instructed to do so.

5. **`resolve_kinship` preferred over multi-hop traversal** — Claude chose the server-side graph tool rather than chaining multiple `list_relationships` calls.

6. **Read vs write path distinction** — Read-only queries (scenarios 5, 9) correctly skip write-specific lookups like `list_source_types`.

### Action items before implementation

| Priority | Issue | Fix |
|---|---|---|
| High | Scenario 10: agent auto-confirms new schema without user approval | Add to system prompt: "After `propose_entity_type_schema`, present proposed fields to user and wait for confirmation before calling `confirm_entity_type_schema`." |
| Low | Scenario 4: "done" maps to `operation_type="update"` with `status=done`, not `"delete"` | Decide product behaviour: does completing a todo archive it (delete) or just flip status? Codify in system prompt. |
| Low | Scenario 10: `list_entity_type_schemas` vs `get_current_entity_type_schema` | Both work. Update expected tool list in `scenarios.py` to accept either path. |
