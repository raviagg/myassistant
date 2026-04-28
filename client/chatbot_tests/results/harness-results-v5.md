# MCP Tool Harness Results — v5

**Date:** 2026-04-25  
**Model:** claude-opus-4-7 (via `claude -p` subprocess)  
**Harness:** `client/chatbot_tests/tool_harness/harness.py`  
**Tools defined:** 43 across 6 groups  
**Scenarios:** 25 · **Turns:** 44 (7 single-turn + 17 two-turn + 1 three-turn)

## Changes from v4

- **Two-phase model**: every write scenario is multi-turn. Turn 1 (gather) reads all
  relevant existing data and proposes the write in text — no write tools called. Turn N
  (write) executes after explicit user approval.
- **Correction loop**: scenario 04 is a 3-turn flow (gather → correction → approve),
  validating that the agent updates its proposal without writing on intermediate turns.
- **Schema tools simplified**: removed `propose_entity_type_schema`, `confirm_entity_type_schema`,
  and `evolve_entity_type_schema`. Replaced with `create_entity_type_schema` (new type,
  immediately active) and `update_entity_type_schema` (add/change fields, full field list,
  new version immediately active). No `is_active` state machine — the two-phase model
  handles the gate at the conversation turn level.
- **`GATHER_FORBIDDEN`**: 16 tools listed as `forbidden_tools` on every gather and
  correction turn. Includes both `create_entity_type_schema` and `update_entity_type_schema`.
- **`log_interaction`** required in every turn — gather, correction, write, and query alike.

---

## Summary

| # | Scenario | Turns | Pass |
|---|---|---|---|
| 01 | Lookup own profile | 1 | ✓ |
| 02 | Update own profile | 2 | ✓ |
| 03 | Add todo — new | 2 | ✓ |
| 04 | Add todo — correction then approve | 3 | ✓ |
| 05 | Update todo due date | 2 | ✓ |
| 06 | Update todo status | 2 | ✓ |
| 07 | Mark todo as done | 2 | ✓ |
| 08 | List open todos | 1 | ✓ |
| 09 | Delete todo | 2 | ✓ |
| 10 | Add family member | 2 | ✓ |
| 11 | Direct kinship query | 1 | ✓ |
| 12 | Multi-hop kinship + add person | 2 | ✓ |
| 13 | List all family members | 1 | ✓ |
| 14 | Store insurance — first time | 2 | ✓ |
| 15 | Update insurance — supersedes | 2 | ✓ |
| 16 | Query insurance deductible | 1 | ✓ |
| 17 | Add new job | 2 | ✓ |
| 18 | Salary change — supersedes | 2 | ✓ |
| 19 | Query current salary | 1 | ✓ |
| 20 | Upload payslip PDF | 2 | ✓ |
| 21 | Upload insurance card (OCR) | 2 | ✓ |
| 22 | Create household + add members | 2 | ✓ |
| 23 | List household members | 1 | ✓ |
| 24 | New entity type — propose + confirm | 2 | ✓ |
| 25 | Update schema — add field | 2 | ✓ |

**25 / 25 pass · 44 / 44 turns pass**

`list_source_types` absent across all turns ✓  
No write tools in any gather or correction turn ✓

---

## Tool Groups (43 tools)

| Group | Tools |
|---|---|
| 1a Person | create_person, get_person, search_persons, update_person, delete_person |
| 1b Household | create_household, get_household, search_households, update_household, delete_household |
| 1c Person-Household | add_person_to_household, remove_person_from_household, list_household_members, list_person_households |
| 1d Relationship | create_relationship, get_relationship, list_relationships, update_relationship, delete_relationship, resolve_kinship |
| 2a Document | create_document, get_document, list_documents, search_documents |
| 2b Fact | create_fact, get_fact_history, get_current_fact, list_current_facts, search_current_facts |
| 3 Schema | list_entity_type_schemas, get_entity_type_schema, get_current_entity_type_schema, create_entity_type_schema, update_entity_type_schema, deactivate_entity_type_schema |
| 4 Reference | list_domains, list_source_types, list_kinship_aliases |
| 5 Audit | log_interaction |
| 6 Files | save_file, extract_text_from_file, get_file, delete_file |

---

## GATHER_FORBIDDEN (16 tools)

All write tools. Listed as `forbidden_tools` on every gather and correction turn:

```
create_person, update_person, delete_person
create_household, update_household, delete_household
add_person_to_household, remove_person_from_household
create_relationship, update_relationship, delete_relationship
create_document, create_fact
create_entity_type_schema, update_entity_type_schema
deactivate_entity_type_schema
```

---

## Two-Phase Model

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
Forbidden: all 16 write tools (GATHER_FORBIDDEN).

**Turn N — Write** (after user approval)
```
[create_person / create_relationship / ...]   ← spine writes if needed
create_document                               ← [supersedes_ids=...] if renewal
create_fact                                   ← operation_type from gather phase
log_interaction
```

**Schema Write** (after user approves new schema or field change)
```
create_entity_type_schema(domain_id, entity_type, field_definitions)  ← new type
  OR
update_entity_type_schema(domain_id, entity_type, field_definitions)  ← add/change fields (full list)
[create_document + create_fact]                                        ← if original message had data
log_interaction
```

---

## Per-Scenario Tool Calls

Tools marked `(+)` are extra calls beyond the required minimum — logged as `?` by the harness (not validated, but not penalised).

### A: Profile

---

#### 01 · Lookup own profile

| Turn | User message | Tool calls |
|---|---|---|
| 1 | "What's my name and date of birth?" | `get_person` `log_interaction` |

---

#### 02 · Update own profile — gather then write

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "I go by Ravi, please update my preferred name." | `get_person` `log_interaction` |
| 2 (write) | "Yes, update it." | `update_person` `log_interaction` |

---

### B: Todo CRUD

---

#### 03 · Add todo — gather + propose, then write

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "Remind me to renew my passport by June 30, 2026." | `list_domains` `list_entity_type_schemas` `get_current_entity_type_schema` `search_current_facts` `search_documents`(+) `log_interaction` |
| 2 (write) | "Yes, add it." | `create_document` `create_fact` `log_interaction` |

---

#### 04 · Add todo — correction turn before approval (3-turn flow)

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "Remind me to schedule a dentist appointment by end of March." | `list_domains` `search_current_facts` `search_documents`(+) `list_entity_type_schemas` `get_current_entity_type_schema` `log_interaction` |
| 2 (correction) | "Actually make that end of April, not March." | `search_current_facts`(+) `log_interaction` |
| 3 (write) | "Yes, that's right, go ahead." | `create_document` `create_fact` `log_interaction` |

Turn 2 (correction): agent updates proposal text, no writes.
Turn 3: writes proceed with the corrected April due date.

---

#### 05 · Update todo due date — search finds existing → update

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "Push my passport renewal deadline to August 15." | `list_domains` `list_entity_type_schemas` `get_current_entity_type_schema` `search_current_facts` `search_documents`(+) `log_interaction` |
| 2 (write) | "Yes, update it." | `create_document` `create_fact` `log_interaction` |

`search_current_facts` returns similarity ≥ 0.8 → `create_fact` uses `operation_type=update`.

---

#### 06 · Update todo status to in_progress

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "I started working on the passport renewal." | `list_domains` `search_current_facts` `search_documents`(+) `list_entity_type_schemas` `get_current_entity_type_schema` `log_interaction` |
| 2 (write) | "Yes, mark it as in progress." | `create_document` `create_fact` `log_interaction` |

`create_fact` fields: `{status: "in_progress"}`.

---

#### 07 · Mark todo as done

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "Passport renewal is done!" | `list_domains` `search_current_facts` `search_documents`(+) `list_entity_type_schemas` `get_current_entity_type_schema` `log_interaction` |
| 2 (write) | "Yes, mark it done." | `create_document` `create_fact` `log_interaction` |

`create_fact` fields: `{status: "done"}` or `operation_type=delete` depending on agent choice.

---

#### 08 · List open todos

| Turn | User message | Tool calls |
|---|---|---|
| 1 (query) | "Show me all my open tasks." | `list_domains`(+) `list_current_facts` `log_interaction` |

Pure read — `list_current_facts` filtered by entity_type=todo_item, status=open.

---

#### 09 · Delete a todo — gather then delete

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "Drop the dentist appointment reminder, I don't need it." | `list_domains` `list_entity_type_schemas`(+) `search_current_facts` `log_interaction` |
| 2 (write) | "Yes, delete it." | `create_document` `create_fact` `log_interaction` |

`create_fact` uses `operation_type=delete`, `fields={}`, `entity_instance_id` from search.

---

### C: Relationships & Kinship

---

#### 10 · Add a family member — gather then write

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "My wife is Priya Sharma, born March 15 1988." | `search_persons` `list_relationships`(+) `log_interaction` |
| 2 (write) | "Yes, add her." | `create_person` `create_relationship` `create_document`(+) `log_interaction` |

`create_relationship`: from=Ravi, to=Priya, relation_type=wife.

---

#### 11 · Direct kinship query with Hindi term

| Turn | User message | Tool calls |
|---|---|---|
| 1 (query) | "What is Priya's relation to me? Give me the Hindi term." | `search_persons` `resolve_kinship` `list_kinship_aliases`(+) `log_interaction` |

Pure read. Agent may call `list_kinship_aliases` in addition to `resolve_kinship`.

---

#### 12 · Multi-hop kinship — answer question + propose adding person

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "My dad's sister is Savita. What do I call her in Hindi?" | `list_relationships`(+) `search_persons` `list_kinship_aliases`(+) `resolve_kinship`(+) `log_interaction` |
| 2 (write) | "Yes, add Savita as a new person and link her as my dad's sister." | `create_person` `create_relationship` `create_relationship`(+) `log_interaction` |

Turn 1 answers the Hindi question (bua) and gathers existence check for Savita.
Turn 2 creates Savita and records the relationship from dad to Savita (sister).
Agent may call `resolve_kinship` or `list_kinship_aliases` or both — both are valid.

---

#### 13 · List all family members

| Turn | User message | Tool calls |
|---|---|---|
| 1 (query) | "Who are all the people recorded in my family?" | `list_relationships` `list_person_households`(+) `list_household_members`(+) `log_interaction` |

Pure read — `list_relationships` returns all depth-1 edges from Ravi.

---

### D: Health / Insurance

---

#### 14 · Store insurance — gather then write

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "I have Aetna insurance, plan BlueShield PPO 500. Deductible $1,500, premium $420/month. Coverage Jan 1 – Dec 31, 2026." | `list_domains` `search_current_facts` `search_documents`(+) `list_entity_type_schemas` `get_current_entity_type_schema` `log_interaction` |
| 2 (write) | "Yes, save it." | `create_document` `create_fact` `log_interaction` |

`create_fact` uses `operation_type=create` (no existing insurance entity found).

---

#### 15 · Update insurance — supersedes old document

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "My Aetna insurance renewed for 2027. Same plan but deductible went up to $2,000." | `list_domains` `list_entity_type_schemas` `get_current_entity_type_schema` `search_current_facts` `search_documents` `log_interaction` |
| 2 (write) | "Yes, update it." | `create_document` `create_fact` `log_interaction` |

`search_documents` finds the old insurance document → `create_document` passes `supersedes_ids=[old_doc_id]`.

---

#### 16 · Query insurance deductible

| Turn | User message | Tool calls |
|---|---|---|
| 1 (query) | "What is my current health insurance deductible?" | `list_domains`(+) `search_current_facts` `log_interaction` |

Pure read — `search_current_facts` returns merged current fields including deductible.

---

### E: Employment

---

#### 17 · Add new job — gather then write

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "I started a new job at Acme Corp as Senior Engineer, salary $120,000/year, start date March 1 2024." | `list_domains` `list_entity_type_schemas` `get_current_entity_type_schema` `search_current_facts` `search_documents`(+) `log_interaction` |
| 2 (write) | "Yes, add it." | `create_document` `create_fact` `log_interaction` |

`create_fact` fields: `{employer, role, salary, start_date}`.

---

#### 18 · Salary change — supersedes old employment document

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "Got a raise at Acme Corp, now making $145,000." | `list_domains` `list_entity_type_schemas` `get_current_entity_type_schema` `search_current_facts` `search_documents` `log_interaction` |
| 2 (write) | "Yes, update my salary." | `create_document` `create_fact` `log_interaction` |

`search_documents` finds the old employment doc → `create_document` passes `supersedes_ids=[old_doc_id]`.
`create_fact` uses `operation_type=update`, `fields={salary: 145000}`.

---

#### 19 · Query current salary

| Turn | User message | Tool calls |
|---|---|---|
| 1 (query) | "What is my current salary?" | `search_current_facts` `log_interaction` |

Pure read — `search_current_facts` returns merged current employment facts.

---

### F: File Handling

---

#### 20 · Upload payslip PDF — gather (save+extract) then write

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "I'm uploading my March 2026 payslip from Acme Corp. [Attached: payslip_march_2026.pdf \| base64: JVBERi0xLjQ=]" | `save_file` `extract_text_from_file` `list_domains` `list_entity_type_schemas` `search_current_facts`(+) `search_documents`(+) `get_current_entity_type_schema` `log_interaction` |
| 2 (write) | "Yes, save the payslip data." | `create_document` `create_fact` `log_interaction` |

`save_file` + `extract_text_from_file` in gather turn count as read/pre-processing (not writes).
Extracted text is used to populate `create_document.content_text`.

---

#### 21 · Upload insurance card image — gather (OCR) then write

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "Here is a photo of my new Aetna insurance card. [Attached: insurance_card_front.jpg \| base64: /9j/4AAQSkZJRg==]" | `save_file` `extract_text_from_file` `list_domains` `list_entity_type_schemas` `get_current_entity_type_schema` `search_current_facts` `search_documents`(+) `log_interaction` |
| 2 (write) | "Yes, save the insurance card details." | `create_document` `create_fact` `log_interaction` |

`extract_text_from_file` performs OCR on the image, returning structured text that informs fact extraction.

---

### G: Household

---

#### 22 · Create household and add members — gather then write

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "Create a household called Aggarwal Family and add me and Priya to it." | `search_households` `search_persons` `log_interaction` |
| 2 (write) | "Yes, create it." | `create_household` `add_person_to_household` `add_person_to_household` `log_interaction` |

`search_households` confirms no existing "Aggarwal Family" household.
`search_persons` resolves Priya's person_id.
Two `add_person_to_household` calls: one for Ravi, one for Priya.

---

#### 23 · List household members

| Turn | User message | Tool calls |
|---|---|---|
| 1 (query) | "Who is in the Aggarwal Family household?" | `search_households` `list_household_members` `get_person`(+) `get_person`(+) `get_person`(+) `log_interaction` |

Pure read. `search_households` resolves household_id, then `list_household_members` returns members.
Agent may call `get_person` for each member to enrich the response.

---

### H: Schema Governance

---

#### 24 · New entity type — gather + propose in text, then write schema

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "I just got a gym membership at Equinox for $80 per month." | `list_domains` `search_current_facts`(+) `search_documents`(+) `list_entity_type_schemas` `list_entity_type_schemas`(+) `log_interaction` |
| 2 (write) | "Yes, add that schema." | `create_entity_type_schema` `create_document`(+) `create_fact`(+) `log_interaction` |

Gather turn: `list_entity_type_schemas` finds no match for gym_membership → agent describes proposed schema in text.
Write turn: `create_entity_type_schema` creates the schema immediately active.
Agent may also store the gym membership data (create_document + create_fact) in the same write turn.

---

#### 25 · Update schema — gather + propose in text, then write evolution

| Turn | User message | Tool calls |
|---|---|---|
| 1 (gather) | "I'd like to also track my copay amount on insurance cards going forward." | `list_domains` `list_entity_type_schemas` `get_current_entity_type_schema` `log_interaction` |
| 2 (write) | "Yes, add that field." | `update_entity_type_schema` `log_interaction` |

Gather turn: `get_current_entity_type_schema` returns existing fields → agent describes the copay_amount addition in text.
Write turn: `update_entity_type_schema` takes the full field list (all existing fields + copay_amount), increments schema_version, activates immediately.

---

## Correction Loop (scenario 04)

| Turn | User message | Tools called | Writes? |
|---|---|---|---|
| 1 | "Remind me to schedule a dentist appointment by end of March." | list_domains, search_current_facts, list_entity_type_schemas, get_current_entity_type_schema, log_interaction | ✗ |
| 2 | "Actually make that end of April, not March." | search_current_facts (+extra), log_interaction | ✗ |
| 3 | "Yes, that's right, go ahead." | create_document, create_fact, log_interaction | ✓ |

Turn 2 (correction): agent updates proposal in text, logs the exchange, no writes.
Turn 3: writes proceed with the corrected April due date.

---

## Progression: v1 → v5

| Version | Pass rate | Key improvement |
|---|---|---|
| v1 | 12/25 | Baseline — tool catalog only, no rules |
| v2 | 19/25 | Added rules to scenarios.py (but never sent to Claude) |
| v3 | 25/25 | Fixed SYSTEM_PROMPT injection; single chatbot source type; list_domains at runtime |
| v4 | 25/25 | Schema discovery via list_entity_type_schemas; explicit similarity threshold |
| v5 | 25/25 (44 turns) | Two-phase model; correction loop; GATHER_FORBIDDEN; simplified schema write tools |
