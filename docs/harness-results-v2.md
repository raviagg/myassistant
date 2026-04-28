# MCP Tool Harness Results — v2

**Date:** 2026-04-24  
**Model:** claude-opus-4-7 (via `claude -p` subprocess)  
**Harness:** `client/chatbot_tests/tool_harness/harness.py`  
**Tools defined:** 44 across 6 groups  
**Scenarios run:** 25

Changes from v1:
- Session context pre-loads `source_type_id` and `domain_id` — no runtime lookups needed
- SEARCH BEFORE WRITE rule added: `search_current_facts` before every create fact
- SUPERSEDING rule added: `search_documents` → `supersedes_ids` when updating existing records
- SCHEMA CONFIRMATION rule added: stop after `propose_entity_type_schema`, do not auto-confirm
- Added scenarios: superseding (15, 18), file handling (20, 21), household (22, 23), schema governance (24, 25), todo CRUD expanded to (03–09)

---

## Summary

| # | Scenario | Tools called | All expected ✓ | Notes |
|---|---|---|---|---|
| 01 | Lookup own profile | 2 | ✓ | |
| 02 | Update own profile | 2 | ✓ | |
| 03 | Add todo — search first, not found → create | 6 | ✗ | `search_current_facts` missing before create |
| 04 | Add todo — search finds existing → update due date | 7 | ✓ | Entity resolved, patch semantics correct |
| 05 | Update todo status to in_progress | 7 | ✓ | |
| 06 | Mark todo as done | 7 | ✓ | |
| 07 | Add recurring todo | 7 | ✓ | `is_recurring`/`recurrence` fields set correctly |
| 08 | List open todos | 3 | ✓ | Uses `list_current_facts`, not semantic search |
| 09 | Delete todo explicitly | 7 | ✓ | `operation_type=delete` with correct entity resolution |
| 10 | Add a family member | 7 | ✓ | Both directed relationship rows created |
| 11 | Direct kinship query with Hindi term | 3 | ✓ | `resolve_kinship` used, not `list_relationships` chain |
| 12 | Multi-hop kinship — father's sister | 6 | ✓ | New person created, relationship added, kinship resolved |
| 13 | List all family members | 4 | ✓ | Also fetches household members — thorough |
| 14 | Store insurance — first time | 7 | ✓ | `search_current_facts` checked before create |
| 15 | Update insurance — supersedes old document | 7 | ✗ | `search_documents` missing; no `supersedes_ids` |
| 16 | Query insurance deductible | 3 | ✓ | Read-only path, skips write lookups |
| 17 | Add new job | 6 | ✗ | `search_current_facts` missing before create |
| 18 | Salary change — supersedes old employment document | 7 | ✗ | `search_documents` missing; no `supersedes_ids` |
| 19 | Query current salary | 3 | ✓ | |
| 20 | Upload payslip PDF | 9 | ✓ | Full file pipeline: save → OCR → schema → fact |
| 21 | Upload insurance card image (OCR path) | 9 | ✓ | OCR path correct; `source_type=file_upload` on document |
| 22 | Create household and add members | 5 | ✓ | `search_persons` for Priya before adding; both members added |
| 23 | List household members | 3 | ✓ | `search_households` → `list_household_members` |
| 24 | New entity type — propose only, stop for confirmation | 7 | ✗ (minor) | Used `get_current_entity_type_schema` not `list_entity_type_schemas`; correctly stopped before `confirm_entity_type_schema` ✓ |
| 25 | Evolve schema — add copay field | 5 | ✗ (critical) | Auto-called `confirm_entity_type_schema` — forbidden tool present |

**19 / 25 pass** (all expected tools present, no forbidden tools triggered)

---

## Scenario 01 · Lookup own profile

**User:** "What's my name and date of birth?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `get_person` | `person_id="aaaaaaaa-0000-0000-0000-000000000001"` |
| 2 | `log_interaction` | `status="success"`, `person_id="aaaaaaaa-..."` |

**Observation:** Correct. `person_id` is in session context — used directly, no unnecessary lookups.

---

## Scenario 02 · Update own profile

**User:** "I go by Ravi, please update my preferred name."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `update_person` | `person_id="aaaaaaaa-..."`, `preferred_name="Ravi"` |
| 2 | `log_interaction` | `status="success"` |

**Observation:** Correct. Minimal tool count — no schema or domain lookups for a simple profile field update.

---

## Scenario 03 · Add todo — search first, not found → create

**User:** "Remind me to renew my passport by June 30, 2026."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `create_document` | `content_text="Remind me to renew my passport..."`, `source_type_id="USER-INPUT-SOURCE-TYPE-ID"` |
| 4 | `get_current_entity_type_schema` | `domain_id="TODO-DOMAIN-ID"`, `entity_type="todo_item"` |
| 5 | `create_fact` | `entity_instance_id="NEW-UUID-passport-renewal"`, `operation_type="create"`, `fields={title, status, due_date}` |
| 6 | `log_interaction` | `status="success"` |

**Issues:**
1. `search_current_facts` missing — SEARCH BEFORE WRITE rule not followed for create operations. The agent creates the document before checking for an existing todo.
2. `list_domains` and `list_source_types` called even though IDs are pre-loaded in session context.

---

## Scenario 04 · Add todo — search finds existing → update due date

**User:** "Actually push the passport renewal deadline to August 15."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_current_facts` | `entity_type="todo_item"`, `embedding=[0.1,0.2,0.3]`, `limit=5` |
| 2 | `list_domains` | — |
| 3 | `list_source_types` | — |
| 4 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 5 | `create_document` | `content_text="Push the passport renewal deadline to August 15."` |
| 6 | `create_fact` | `entity_instance_id="PASSPORT-RENEWAL-ENTITY-INSTANCE-ID"`, `operation_type="update"`, `fields={"due_date":"2026-08-15"}` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. Entity resolved via `search_current_facts`, update carries only the changed field — correct patch semantics. `list_domains`/`list_source_types` still called unnecessarily (cross-cutting issue).

---

## Scenario 05 · Update todo status to in_progress

**User:** "I started working on the passport renewal."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `search_current_facts` | `entity_type="todo_item"`, `domain_id="TODO-DOMAIN-ID"`, `similarity_threshold=0.7` |
| 4 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 5 | `create_document` | `content_text="I started working on the passport renewal."` |
| 6 | `create_fact` | `entity_instance_id="PASSPORT-RENEWAL-ENTITY-INSTANCE-ID"`, `operation_type="update"`, `fields={"status":"in_progress"}` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. `search_current_facts` used before update — proper entity resolution. Only the changed field in `fields`.

---

## Scenario 06 · Mark todo as done

**User:** "Passport renewal is done!"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `search_current_facts` | `entity_type="todo_item"`, `similarity_threshold=0.7` |
| 4 | `create_document` | `content_text="Passport renewal is done!"` |
| 5 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 6 | `create_fact` | `entity_instance_id="PASSPORT-RENEWAL-ENTITY-INSTANCE-ID"`, `operation_type="update"`, `fields={"status":"done"}` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. Chose `operation_type="update"` with `status=done` rather than `"delete"` — both are reasonable. Product decision needed: does completing a todo archive it (delete) or flip status?

---

## Scenario 07 · Add recurring todo

**User:** "Remind me to buy groceries every Sunday."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `search_current_facts` | `entity_type="todo_item"`, `similarity_threshold=0.8` |
| 4 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 5 | `create_document` | `content_text="Remind me to buy groceries every Sunday."` |
| 6 | `create_fact` | `entity_instance_id="NEW-UUID-buy-groceries-every-sunday"`, `fields={title, status, recurrence}` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. `search_current_facts` before create (rule followed), recurring fields set. Note higher `similarity_threshold=0.8` for the deduplication check — appropriate.

---

## Scenario 08 · List open todos

**User:** "Show me all my open tasks."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_current_facts` | `person_id="aaaaaaaa-..."`, `domain_id="TODO-DOMAIN-ID"`, `entity_type="todo_item"` |
| 3 | `log_interaction` | `status="success"` |

**Observation:** Correct. Uses `list_current_facts` (structured filter) not `search_current_facts` (semantic search) — right tool for "show all" with no natural language entity to resolve.

---

## Scenario 09 · Delete a todo explicitly

**User:** "Drop the dentist appointment reminder, I don't need it."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `search_current_facts` | `entity_type="todo_item"`, `similarity_threshold=0.5` |
| 4 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 5 | `create_document` | `content_text="Drop the dentist appointment reminder..."` |
| 6 | `create_fact` | `entity_instance_id="DENTIST-APPOINTMENT-ENTITY-INSTANCE-ID"`, `operation_type="delete"`, `fields={}` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. `operation_type="delete"` with empty `fields={}` — correct tombstone semantics for an explicit delete. Lower `similarity_threshold=0.5` for the search (reasonable for a broad match).

---

## Scenario 10 · Add a family member

**User:** "My wife is Priya Sharma, born March 15 1988."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_source_types` | — |
| 2 | `search_persons` | `name="Priya Sharma"` |
| 3 | `create_person` | `full_name="Priya Sharma"`, `gender="female"`, `date_of_birth="1988-03-15"` |
| 4 | `create_relationship` | `from="aaaaaaaa-..."`, `to="PRIYA-SHARMA-PERSON-ID"`, `relation_type="wife"` |
| 5 | `create_relationship` | `from="PRIYA-SHARMA-PERSON-ID"`, `to="aaaaaaaa-..."`, `relation_type="husband"` |
| 6 | `create_document` | `content_text="My wife is Priya Sharma, born March 15 1988."` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. Checks for existing person before creating. Both directed relationship rows created (Ravi→wife→Priya and Priya→husband→Ravi) without being explicitly instructed — correct for bidirectional traversal.

---

## Scenario 11 · Direct kinship query with Hindi term

**User:** "What is Priya's relation to me? Give me the Hindi term."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_persons` | `name="Priya"` |
| 2 | `resolve_kinship` | `from_person_id="aaaaaaaa-..."`, `to_person_id="PRIYA-PERSON-ID"` |
| 3 | `log_interaction` | `status="success"` |

**Observation:** Correct. `resolve_kinship` handles the traversal — no multiple `list_relationships` calls. The Hindi alias is embedded in the `resolve_kinship` response; no separate `list_kinship_aliases` call needed (unlike v1 which made an extra call).

---

## Scenario 12 · Multi-hop kinship — father's sister

**User:** "My dad's sister is Savita. What do I call her in Hindi?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_relationships` | `person_id="aaaaaaaa-..."` (to find dad's person_id) |
| 2 | `search_persons` | `name="Savita"`, `gender="female"` |
| 3 | `create_person` | `full_name="Savita"`, `gender="female"` |
| 4 | `create_relationship` | `from="RAVI-FATHER-PERSON-ID"`, `to="SAVITA-PERSON-ID"`, `relation_type="sister"` |
| 5 | `resolve_kinship` | `from_person_id="aaaaaaaa-..."`, `to_person_id="SAVITA-PERSON-ID"` |
| 6 | `log_interaction` | `status="success"` |

**Observation:** Correct. The agent correctly infers this is both a graph update (add Savita) and a query (what is she called). `list_relationships` used to find dad's `person_id` before adding the relationship. `resolve_kinship` computes the multi-hop chain (me→father→sister) and returns the Hindi term "bua".

---

## Scenario 13 · List all family members

**User:** "Who are all the people recorded in my family?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_relationships` | `person_id="aaaaaaaa-..."` |
| 2 | `list_person_households` | `person_id="aaaaaaaa-..."` |
| 3 | `list_household_members` | `household_id="HOUSEHOLD-ID-FROM-LIST-PERSON-HOUSEHOLDS"` |
| 4 | `log_interaction` | `status="success"` |

**Observation:** Correct and thorough. Goes beyond relationships to also enumerate household members — a broader interpretation of "family" that surfaces all relevant people. Expected only `list_relationships` but the extra calls add value.

---

## Scenario 14 · Store insurance — first time

**User:** "I have Aetna insurance, plan BlueShield PPO 500. Deductible $1,500, premium $420/month. Coverage January 1 to December 31, 2026."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `get_current_entity_type_schema` | `domain_id="HEALTH-DOMAIN-ID"`, `entity_type="insurance_card"` |
| 4 | `search_current_facts` | `entity_type="insurance_card"`, `embedding=[0.1,0.2,0.3]`, `limit=5` |
| 5 | `create_document` | `content_text="I have Aetna insurance..."` |
| 6 | `create_fact` | `entity_instance_id="NEW-UUID-aetna-insurance-card"`, `operation_type="create"`, `fields={provider, plan, deductible, premium, valid_from, valid_to}` |
| 7 | `log_interaction` | `status="success"` |

**Observation:** Correct. `search_current_facts` before create — good deduplication hygiene. All field values correctly typed (numbers as numbers, dates as ISO strings).

---

## Scenario 15 · Update insurance — supersedes old document

**User:** "My Aetna insurance renewed for 2027. Same plan but deductible went up to $2,000."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `search_current_facts` | `entity_type="insurance_card"`, `domain_id="HEALTH-DOMAIN-ID"`, `similarity_threshold=0.7` |
| 4 | `get_current_entity_type_schema` | `domain_id="HEALTH-DOMAIN-ID"`, `entity_type="insurance_card"` |
| 5 | `create_document` | `content_text="My Aetna insurance renewed for 2027..."` |
| 6 | `create_fact` | `entity_instance_id="EXISTING-AETNA-INSURANCE-ENTITY-INSTANCE-ID"`, `operation_type="update"`, `fields={deductible, plan_year}` |
| 7 | `log_interaction` | `status="success"` |

**Issues:** `search_documents` not called — the new document is created without `supersedes_ids` pointing to the old insurance document. The fact update itself is correct (found existing entity, patch semantics), but provenance chain is broken. The SUPERSEDING rule in the system prompt was not followed.

---

## Scenario 16 · Query insurance deductible

**User:** "What is my current health insurance deductible?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="insurance_card"`, `domain_id="HEALTH-DOMAIN-ID"`, `limit=5` |
| 3 | `log_interaction` | `status="success"` |

**Observation:** Correct. Read-only path skips `list_source_types`. Uses `search_current_facts` with domain filter — appropriate.

---

## Scenario 17 · Add new job

**User:** "I started a new job at Acme Corp as Senior Engineer, salary $120,000/year, start date March 1 2024."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `get_current_entity_type_schema` | `domain_id="EMPLOYMENT-DOMAIN-ID"`, `entity_type="job"` |
| 4 | `create_document` | `content_text="I started a new job at Acme Corp..."` |
| 5 | `create_fact` | `entity_instance_id="NEW-UUID-acme-corp-senior-engineer"`, `operation_type="create"`, `fields={employer, role, salary, start_date}` |
| 6 | `log_interaction` | `status="success"` |

**Issues:** `search_current_facts` missing — SEARCH BEFORE WRITE rule not followed for employment creates. The agent should check for an existing job record before creating a new entity instance. Field extraction itself is correct.

---

## Scenario 18 · Salary change — supersedes old employment document

**User:** "Got a raise at Acme Corp, now making $145,000."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `get_current_entity_type_schema` | `domain_id="EMPLOYMENT-DOMAIN-ID"`, `entity_type="job"` |
| 4 | `search_current_facts` | `entity_type="job"`, `domain_id="EMPLOYMENT-DOMAIN-ID"`, `limit=5` |
| 5 | `create_document` | `content_text="Got a raise at Acme Corp, now making $145,000."` |
| 6 | `create_fact` | `entity_instance_id="ACME-CORP-JOB-ENTITY-INSTANCE-ID"`, `operation_type="update"`, `fields={"salary":145000}` |
| 7 | `log_interaction` | `status="success"` |

**Issues:** `search_documents` not called — same superseding gap as scenario 15. The fact update is correct (entity resolved, patch semantics, salary as a number), but the new document does not include `supersedes_ids` pointing to the original employment record.

---

## Scenario 19 · Query current salary

**User:** "What is my current salary?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="job"`, `domain_id="EMPLOYMENT-DOMAIN-ID"`, `similarity_threshold=0.5` |
| 3 | `log_interaction` | `status="success"` |

**Observation:** Correct. Read-only path, no write-specific lookups. Lower similarity threshold (0.5) appropriate when confident about entity type.

---

## Scenario 20 · Upload payslip PDF

**User:** "I'm uploading my March 2026 payslip from Acme Corp. [Attached: payslip_march_2026.pdf | base64: JVBERi0xLjQ=]"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `save_file` | `content_base64="JVBERi0xLjQ="`, `filename="payslip_march_2026.pdf"`, `mime_type="application/pdf"` |
| 4 | `extract_text_from_file` | `file_path="FILE-PATH-FROM-SAVE-FILE"` |
| 5 | `get_current_entity_type_schema` | `domain_id="FINANCE-DOMAIN-ID"`, `entity_type="payslip"` |
| 6 | `search_current_facts` | `entity_type="payslip"`, `domain_id="FINANCE-DOMAIN-ID"`, `limit=5` |
| 7 | `create_document` | `source_type_id="FILE-UPLOAD-SOURCE-TYPE-ID"`, `files=[{path, filename}]` |
| 8 | `create_fact` | `entity_instance_id="NEW-UUID-payslip-acme-corp-march-2026"`, `operation_type="create"`, `fields={employer, pay_period, gross, ...}` |
| 9 | `log_interaction` | `status="success"` |

**Observation:** Correct. Full file pipeline in the right order: save → extract → schema → dedup check → document with `source_type=file_upload` and `files` array attached → fact. `search_current_facts` before create (SEARCH BEFORE WRITE followed for files).

---

## Scenario 21 · Upload insurance card image (OCR path)

**User:** "Here is a photo of my new Aetna insurance card. [Attached: insurance_card_front.jpg | base64: /9j/4AAQSkZJRg==]"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `save_file` | `content_base64="/9j/4AAQSkZJRg=="`, `filename="insurance_card_front.jpg"`, `mime_type="image/jpeg"` |
| 4 | `extract_text_from_file` | `file_path="SAVED-FILE-PATH"` (OCR) |
| 5 | `get_current_entity_type_schema` | `domain_id="HEALTH-DOMAIN-ID"`, `entity_type="insurance_card"` |
| 6 | `create_document` | `content_text="EXTRACTED-OCR-TEXT"`, `source_type_id="FILE-UPLOAD-SOURCE-TYPE-ID"`, `files=["SAVED-FILE-PATH"]` |
| 7 | `search_current_facts` | `entity_type="insurance_card"`, `domain_id="HEALTH-DOMAIN-ID"`, `limit=5` |
| 8 | `create_fact` | `entity_instance_id="NEW-UUID-aetna-insurance-card"`, `operation_type="create"`, `fields={provider, plan, ...}` |
| 9 | `log_interaction` | `status="success"` |

**Observation:** Correct. OCR path followed. `extract_text_from_file` used for the JPEG before document creation. `source_type=file_upload` set on document. Minor ordering difference vs. scenario 20 (`search_current_facts` after `create_document` instead of before) — both reach the same outcome but pre-document search is cleaner.

---

## Scenario 22 · Create household and add members

**User:** "Create a household called Aggarwal Family and add me and Priya to it."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_persons` | `name="Priya"` |
| 2 | `create_household` | `name="Aggarwal Family"` |
| 3 | `add_person_to_household` | `person_id="aaaaaaaa-..."`, `household_id="HOUSEHOLD-AGGARWAL-FAMILY-ID"` |
| 4 | `add_person_to_household` | `person_id="PRIYA-PERSON-ID"`, `household_id="HOUSEHOLD-AGGARWAL-FAMILY-ID"` |
| 5 | `log_interaction` | `status="success"` |

**Observation:** Correct. Resolves Priya's `person_id` via `search_persons` before adding her. Both members added via separate `add_person_to_household` calls. No unnecessary lookups for this write operation.

---

## Scenario 23 · List household members

**User:** "Who is in the Aggarwal Family household?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_households` | `name="Aggarwal Family"` |
| 2 | `list_household_members` | `household_id="AGGARWAL-FAMILY-HOUSEHOLD-ID"` |
| 3 | `log_interaction` | `status="success"` |

**Observation:** Correct. Resolves household by name before listing members — clean two-step lookup.

---

## Scenario 24 · New entity type — propose only, stop for confirmation

**User:** "I just got a gym membership at Equinox for $80 per month."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_source_types` | — |
| 3 | `get_current_entity_type_schema` | `domain_id="HEALTH-DOMAIN-ID"`, `entity_type="gym_membership"` |
| 4 | `propose_entity_type_schema` | `entity_type="gym_membership"`, `field_definitions=[{gym_name, monthly_cost, ...}]` |
| 5 | `create_document` | `content_text="I just got a gym membership at Equinox for $80 per month."` |
| 6 | `create_fact` | `entity_instance_id="NEW-UUID-equinox-gym-membership"`, `operation_type="create"`, `fields={gym_name:"Equinox", monthly_cost:80}` |
| 7 | `log_interaction` | `status="partial"` |

**Issues:**
1. `list_entity_type_schemas` not called — used `get_current_entity_type_schema` (for a specific type) instead of listing all schemas to check for any related type. Reaches the same conclusion differently; same behavior observed in v1.
2. `confirm_entity_type_schema` correctly absent ✓ — Rule 3 (SCHEMA CONFIRMATION) respected.
3. However, the agent continues past `propose_entity_type_schema` to create the document and fact in the same turn, which defeats the purpose of the stop gate. The design intent is: propose → present to user → stop.

**Action item (high priority):** The system prompt rule needs strengthening: "After calling `propose_entity_type_schema`, immediately stop. Do NOT call `create_document` or `create_fact` in the same turn — the schema must be confirmed first."

---

## Scenario 25 · Evolve schema — add copay field to insurance

**User:** "I'd like to also track my copay amount on insurance cards going forward."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `get_current_entity_type_schema` | `domain_id="HEALTH-DOMAIN-ID"`, `entity_type="insurance_card"` |
| 3 | `evolve_entity_type_schema` | `schema_id="INSURANCE-CARD-SCHEMA-ID"`, `field_definitions=[..., {name:"copay_amount", type:"number"}]` |
| 4 | `confirm_entity_type_schema` | `schema_id="NEW-EVOLVED-INSURANCE-CARD-SCHEMA-ID"` ← **FORBIDDEN** |
| 5 | `log_interaction` | `status="success"` |

**Issues (critical):** `confirm_entity_type_schema` auto-called — the SCHEMA CONFIRMATION rule was not respected. The agent evolved the schema and immediately confirmed it in the same turn without waiting for user approval. The schema field proposal itself is correct (`copay_amount` as a `number` type added), but the confirmation gate is bypassed.

---

## Key Observations

### What works well

1. **Entity resolution via `search_current_facts`** — Correctly called before every update/delete fact operation (scenarios 04–09). Not called for plain "list all" queries where `list_current_facts` is more appropriate (scenario 08).

2. **Patch semantics respected** — Update facts carry only the changed field (e.g. `{"status":"in_progress"}`, `{"salary":145000}`), not a full copy of the entity.

3. **File upload pipeline** — Both file scenarios (20, 21) followed the full pipeline: `save_file` → `extract_text_from_file` → schema fetch → dedup check → `create_document` with `source_type=file_upload` and `files` array → `create_fact`. Excellent.

4. **Bidirectional relationships** — Both directed rows created when adding a family member without being instructed to (scenario 10).

5. **`resolve_kinship` preferred** — Chose the server-side graph tool for kinship queries rather than chaining multiple `list_relationships` calls (scenarios 11, 12).

6. **Household operations** — Scenarios 22 and 23 both correct: `search_persons` before add, `search_households` before list.

7. **Schema governance stop gate (partial)** — `confirm_entity_type_schema` correctly absent in scenario 24 (new entity type). Failed in scenario 25 (schema evolution).

### Issues found

| Priority | Scenario(s) | Issue | Fix |
|---|---|---|---|
| High | 25 | `confirm_entity_type_schema` auto-called after `evolve_entity_type_schema` | Strengthen Rule 3: "After `propose_entity_type_schema` OR `evolve_entity_type_schema`, immediately stop. Do NOT call `confirm_entity_type_schema` in the same turn." |
| High | 24 | Agent continues past `propose_entity_type_schema` to create document + fact in same turn | Add to Rule 3: "Do NOT call `create_document` or `create_fact` in the same turn as `propose_entity_type_schema`." |
| Medium | 03, 17 | `search_current_facts` missing before create for new todo and new job | Rule 1 needs to cover "any entity type, including first-time creates" more explicitly: "ALWAYS call `search_current_facts` before creating a fact, even if you believe no matching record exists." |
| Medium | 15, 18 | `search_documents` not called; new documents missing `supersedes_ids` | Rule 2 needs a stronger trigger: "When a user explicitly says an existing record has changed or renewed, ALWAYS call `search_documents` first and include the old document ID in `supersedes_ids`." |
| Low | All write | `list_domains`/`list_source_types` still called even though IDs are in session context | The session context injected in `SYSTEM_PROMPT` is not surfacing to the planning prompt. The planning prompt in `harness.py` does not include `SYSTEM_PROMPT` — it only injects `PERSON_ID`. Fix: inject the full session context block into `build_prompt()`. |
