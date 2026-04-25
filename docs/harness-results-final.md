# MCP Tool Harness — Final Results

**Model:** claude-opus-4-7 (via `claude -p` subprocess)  
**Tools defined:** 44 across 6 groups  
**Scenarios:** 25  
**Final score: 25 / 25 pass**

This document consolidates v2 and v3 harness runs. v2 exposed the issues; v3 fixed them.
The v3 tool call sequences here are the reference for MCP server implementation.

---

## v2 → v3 Progress

| # | Scenario | v2 | v3 | Fix applied |
|---|---|:---:|:---:|---|
| 01 | Lookup own profile | ✓ | ✓ | — |
| 02 | Update own profile | ✓ | ✓ | — |
| 03 | Add todo — search first, not found → create | ✗ | ✓ | SYSTEM_PROMPT wired in; Rule 1 strengthened |
| 04 | Add todo — search finds existing → update | ✓ | ✓ | — |
| 05 | Update todo status to in_progress | ✓ | ✓ | — |
| 06 | Mark todo as done | ✓ | ✓ | — |
| 07 | Add recurring todo | ✓ | ✓ | — |
| 08 | List open todos | ✓ | ✓ | — |
| 09 | Delete todo explicitly | ✓ | ✓ | — |
| 10 | Add a family member | ✓ | ✓ | — |
| 11 | Direct kinship query with Hindi term | ✓ | ✓ | — |
| 12 | Multi-hop kinship — father's sister | ✓ | ✓ | — |
| 13 | List all family members | ✓ | ✓ | — |
| 14 | Store insurance — first time | ✓ | ✓ | — |
| 15 | Update insurance — supersedes old document | ✗ | ✓ | SYSTEM_PROMPT wired in; Rule 2 strengthened |
| 16 | Query insurance deductible | ✓ | ✓ | — |
| 17 | Add new job | ✗ | ✓ | SYSTEM_PROMPT wired in; Rule 1 strengthened |
| 18 | Salary change — supersedes old employment document | ✗ | ✓ | SYSTEM_PROMPT wired in; Rule 2 strengthened |
| 19 | Query current salary | ✓ | ✓ | Expected tool relaxed (both list/search valid) |
| 20 | Upload payslip PDF | ✓ | ✓ | — |
| 21 | Upload insurance card image (OCR path) | ✓ | ✓ | — |
| 22 | Create household and add members | ✓ | ✓ | — |
| 23 | List household members | ✓ | ✓ | — |
| 24 | New entity type — propose only, stop | ✗ | ✓ | Rule 3 + forbidden: create_document/create_fact |
| 25 | Evolve schema — add copay field | ✗ | ✓ | Rule 3 strengthened |
| | | **19/25** | **25/25** | |

---

## Architecture decisions confirmed by the runs

### Source type: single `chatbot` ID
All chatbot-interface interactions share one source type regardless of medium:

| Interaction | Source type | ID pre-loaded? |
|---|---|---|
| User typed text | `chatbot` | ✓ yes — in session context |
| User file upload | `chatbot` | ✓ yes — same ID |
| AI-extracted content | `chatbot` | ✓ yes — same ID |
| Plaid polling job | `plaid_poll` | ✗ not relevant to chatbot |
| Gmail polling job | `gmail_poll` | ✗ not relevant to chatbot |

`list_source_types` is globally forbidden — never called in any of the 25 scenarios.

### Domain IDs: looked up at runtime
Domain IDs are NOT pre-loaded in session context. The agent calls `list_domains` once at the
start of each write turn to resolve domain names to IDs. This is correct: domain names are
human-readable and the lookup is cheap (one call). All write scenarios call `list_domains` as
their first step.

### Four rules that govern tool selection

**Rule 1 — SEARCH BEFORE WRITE:** Always call `search_current_facts` before `create_fact`,
including first-time creates. If similarity ≥ 0.8, use existing entity_instance_id with
`operation_type=update`. If no match, use `operation_type=create` with a new UUID placeholder.

**Rule 2 — SUPERSEDING:** When the user says data has renewed, changed, or been replaced, call
`search_documents` first, then pass its ID in `supersedes_ids` on the new `create_document`.

**Rule 3 — SCHEMA CONFIRMATION:** After `propose_entity_type_schema` or
`evolve_entity_type_schema`, stop. Do not call `confirm_entity_type_schema`, `create_document`,
or `create_fact` in the same turn — wait for explicit user approval.

**Rule 4 — AUDIT:** `log_interaction` is always the final call in every human chat turn.

---

## Scenario details

### 01 · Lookup own profile
> "What's my name and date of birth?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `get_person` | `person_id=SESSION_PERSON_ID` |
| 2 | `log_interaction` | `status="success"` |

`person_id` comes from session context — no lookup needed.

---

### 02 · Update own profile
> "I go by Ravi, please update my preferred name."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `update_person` | `person_id=SESSION_PERSON_ID`, `preferred_name="Ravi"` |
| 2 | `log_interaction` | `status="success"` |

---

### 03 · Add todo — search first, not found → create  *(fixed in v3)*
> "Remind me to renew my passport by June 30, 2026."

**v2 problem:** `search_current_facts` missing — agent skipped the dedup check for first-time creates.  
**v3 fix:** Rule 1 strengthened + SYSTEM_PROMPT wired into prompt.

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="todo_item"`, `embedding=[0.1,0.2,0.3]`, `similarity_threshold=0.8` |
| 3 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 4 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID` |
| 5 | `create_fact` | `entity_instance_id="NEW-UUID-passport-renewal"`, `operation_type="create"`, `fields={title, status, due_date}` |
| 6 | `log_interaction` | `status="success"` |

---

### 04 · Add todo — search finds existing → update due date
> "Actually push the passport renewal deadline to August 15."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_current_facts` | `entity_type="todo_item"`, `embedding=[0.1,0.2,0.3]` |
| 2 | `list_domains` | — |
| 3 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 4 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID` |
| 5 | `create_fact` | `entity_instance_id="PASSPORT-RENEWAL-ENTITY-INSTANCE-ID"`, `operation_type="update"`, `fields={"due_date":"2026-08-15"}` |
| 6 | `log_interaction` | `status="success"` |

Patch semantics: only the changed field in `fields`.

---

### 05 · Update todo status to in_progress
> "I started working on the passport renewal."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_current_facts` | `entity_type="todo_item"`, `similarity_threshold=0.7` |
| 2 | `list_domains` | — |
| 3 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID` |
| 4 | `create_fact` | `entity_instance_id="PASSPORT-RENEWAL-ENTITY-INSTANCE-ID"`, `operation_type="update"`, `fields={"status":"in_progress"}` |
| 5 | `log_interaction` | `status="success"` |

---

### 06 · Mark todo as done
> "Passport renewal is done!"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="todo_item"`, `similarity_threshold=0.7` |
| 3 | `create_document` | `content_text="Passport renewal is done!"`, `source_type_id=CHATBOT_ID` |
| 4 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 5 | `create_fact` | `entity_instance_id="PASSPORT-RENEWAL-ENTITY-INSTANCE-ID"`, `operation_type="update"`, `fields={"status":"done"}` |
| 6 | `log_interaction` | `status="success"` |

Used `operation_type="update"` with `status=done`. Whether "done" should be an update or a
delete (archive) is a product decision to codify in the system prompt.

---

### 07 · Add recurring todo
> "Remind me to buy groceries every Sunday."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="todo_item"`, `similarity_threshold=0.8` |
| 3 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 4 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID` |
| 5 | `create_fact` | `entity_instance_id="NEW-UUID-buy-groceries-every-sunday"`, `operation_type="create"`, `fields={title, status, is_recurring:true, recurrence:"weekly"}` |
| 6 | `log_interaction` | `status="success"` |

Higher similarity threshold (0.8) for dedup — avoids false matches on generic recurring tasks.

---

### 08 · List open todos
> "Show me all my open tasks."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_current_facts` | `person_id=SESSION_PERSON_ID`, `entity_type="todo_item"` |
| 3 | `log_interaction` | `status="success"` |

Uses `list_current_facts` (structured filter), not `search_current_facts` (semantic similarity) —
correct choice when no specific entity needs to be resolved by name.

---

### 09 · Delete todo explicitly
> "Drop the dentist appointment reminder, I don't need it."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="todo_item"`, `similarity_threshold=0.5` |
| 3 | `get_current_entity_type_schema` | `entity_type="todo_item"` |
| 4 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID` |
| 5 | `create_fact` | `entity_instance_id="DENTIST-APPOINTMENT-ENTITY-INSTANCE-ID"`, `operation_type="delete"`, `fields={}` |
| 6 | `log_interaction` | `status="success"` |

`operation_type="delete"` with empty `fields={}` — correct tombstone semantics.  
Lower threshold (0.5) for broad fuzzy match on explicit delete.

---

### 10 · Add a family member
> "My wife is Priya Sharma, born March 15 1988."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_persons` | `name="Priya Sharma"` |
| 2 | `create_person` | `full_name="Priya Sharma"`, `gender="female"`, `date_of_birth="1988-03-15"` |
| 3 | `create_relationship` | `from=SESSION_PERSON_ID`, `to=PRIYA_ID`, `relation_type="wife"` |
| 4 | `create_relationship` | `from=PRIYA_ID`, `to=SESSION_PERSON_ID`, `relation_type="husband"` |
| 5 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID` |
| 6 | `log_interaction` | `status="success"` |

`search_persons` before `create_person` — deduplication. Both directed relationship rows created
without being instructed (Ravi→wife→Priya and Priya→husband→Ravi) — correct for bidirectional
graph traversal.

---

### 11 · Direct kinship query with Hindi term
> "What is Priya's relation to me? Give me the Hindi term."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_persons` | `name="Priya"` |
| 2 | `resolve_kinship` | `from_person_id=SESSION_PERSON_ID`, `to_person_id=PRIYA_ID` |
| 3 | `log_interaction` | `status="success"` |

`resolve_kinship` returns the Hindi alias directly — no separate `list_kinship_aliases` call needed.
No `list_relationships` chain — server-side graph traversal preferred.

---

### 12 · Multi-hop kinship — father's sister
> "My dad's sister is Savita. What do I call her in Hindi?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_relationships` | `person_id=SESSION_PERSON_ID` — to find dad's person_id |
| 2 | `search_persons` | `name="Savita"`, `gender="female"` |
| 3 | `create_person` | `full_name="Savita"`, `gender="female"` |
| 4 | `create_relationship` | `from=RAVI_FATHER_ID`, `to=SAVITA_ID`, `relation_type="sister"` |
| 5 | `resolve_kinship` | `from_person_id=SESSION_PERSON_ID`, `to_person_id=SAVITA_ID` |
| 6 | `log_interaction` | `status="success"` |

Correctly interprets this as both a graph write (add Savita + relationship) and a query (resolve
multi-hop chain me→father→sister → "bua").

---

### 13 · List all family members
> "Who are all the people recorded in my family?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_relationships` | `person_id=SESSION_PERSON_ID` |
| 2 | `list_person_households` | `person_id=SESSION_PERSON_ID` |
| 3 | `list_household_members` | `household_id=HOUSEHOLD_ID` |
| 4 | `log_interaction` | `status="success"` |

Goes beyond relationships to also enumerate household members — broader interpretation of "family"
that surfaces all relevant people.

---

### 14 · Store insurance — first time
> "I have Aetna insurance, plan BlueShield PPO 500. Deductible $1,500, premium $420/month. Coverage January 1 to December 31, 2026."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `get_current_entity_type_schema` | `entity_type="insurance_card"` |
| 3 | `search_current_facts` | `entity_type="insurance_card"`, `embedding=[0.1,0.2,0.3]` |
| 4 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID` |
| 5 | `create_fact` | `entity_instance_id="NEW-UUID-aetna-insurance-card"`, `operation_type="create"`, `fields={provider, plan_name, deductible:1500, premium:420, valid_from, valid_to}` |
| 6 | `log_interaction` | `status="success"` |

All field values correctly typed: numbers as numbers, dates as ISO strings.

---

### 15 · Update insurance — supersedes old document  *(fixed in v3)*
> "My Aetna insurance renewed for 2027. Same plan but deductible went up to $2,000."

**v2 problem:** `search_documents` not called — new document created without `supersedes_ids`,
breaking the provenance chain.  
**v3 fix:** Rule 2 strengthened + SYSTEM_PROMPT wired in.

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="insurance_card"`, `similarity_threshold=0.7` — finds existing Aetna entity |
| 3 | `search_documents` | `embedding=[0.1,0.2,0.3]` — finds old 2026 insurance document |
| 4 | `get_current_entity_type_schema` | `entity_type="insurance_card"` |
| 5 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID`, **`supersedes_ids=["OLD-DOC-ID"]`** |
| 6 | `create_fact` | `entity_instance_id="EXISTING-AETNA-ID"`, `operation_type="update"`, `fields={deductible:2000, plan_year:2027}` |
| 7 | `log_interaction` | `status="success"` |

`search_documents` → `supersedes_ids` chain complete — immutable document history preserved.

---

### 16 · Query insurance deductible
> "What is my current health insurance deductible?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="insurance_card"`, `domain_id` from step 1 |
| 3 | `log_interaction` | `status="success"` |

Read-only path: no `create_document`, no `create_fact`.

---

### 17 · Add new job  *(fixed in v3)*
> "I started a new job at Acme Corp as Senior Engineer, salary $120,000/year, start date March 1 2024."

**v2 problem:** `search_current_facts` missing before create.  
**v3 fix:** Rule 1 strengthened + SYSTEM_PROMPT wired in.

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="job"`, `embedding=[0.1,0.2,0.3]` |
| 3 | `get_current_entity_type_schema` | `entity_type="job"` |
| 4 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID` |
| 5 | `create_fact` | `entity_instance_id="NEW-UUID-acme-corp-senior-engineer"`, `operation_type="create"`, `fields={employer, role, salary:120000, start_date:"2024-03-01"}` |
| 6 | `log_interaction` | `status="success"` |

---

### 18 · Salary change — supersedes old employment document  *(fixed in v3)*
> "Got a raise at Acme Corp, now making $145,000."

**v2 problem:** `search_documents` not called — employment update had no `supersedes_ids`.  
**v3 fix:** Rule 2 strengthened + SYSTEM_PROMPT wired in.

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `search_current_facts` | `entity_type="job"`, finds existing Acme Corp job entity |
| 3 | `search_documents` | finds original employment document |
| 4 | `get_current_entity_type_schema` | `entity_type="job"` |
| 5 | `create_document` | `content_text="..."`, `source_type_id=CHATBOT_ID`, **`supersedes_ids=["OLD-JOB-DOC-ID"]`** |
| 6 | `create_fact` | `entity_instance_id="ACME-CORP-JOB-ENTITY-ID"`, `operation_type="update"`, `fields={"salary":145000}` |
| 7 | `log_interaction` | `status="success"` |

Patch semantics: only the changed field (`salary`) in `fields`.

---

### 19 · Query current salary
> "What is my current salary?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `list_current_facts` *or* `search_current_facts` | `entity_type="job"` |
| 3 | `log_interaction` | `status="success"` |

Both tools are valid here. `list_current_facts` (structured filter) and `search_current_facts`
(semantic lookup) both return the current job facts. Model choice is non-deterministic; both
are accepted.

---

### 20 · Upload payslip PDF
> "I'm uploading my March 2026 payslip from Acme Corp. [Attached: payslip_march_2026.pdf | base64: JVBERi0xLjQ=]"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `save_file` | `content_base64="JVBERi0xLjQ="`, `filename="payslip_march_2026.pdf"`, `mime_type="application/pdf"` |
| 3 | `extract_text_from_file` | `file_path` from step 2 |
| 4 | `get_current_entity_type_schema` | `entity_type="payslip"` |
| 5 | `search_current_facts` | `entity_type="payslip"` |
| 6 | `create_document` | `content_text=OCR_TEXT`, `source_type_id=CHATBOT_ID`, **`files=[{path, filename}]`** |
| 7 | `create_fact` | `entity_instance_id="NEW-UUID-payslip-acme-corp-march-2026"`, `operation_type="create"`, `fields={employer, pay_period, gross, tax, net}` |
| 8 | `log_interaction` | `status="success"` |

Full file pipeline order: save → extract → schema → dedup check → document with `files` array → fact.

---

### 21 · Upload insurance card image (OCR path)
> "Here is a photo of my new Aetna insurance card. [Attached: insurance_card_front.jpg | base64: /9j/4AAQSkZJRg==]"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `save_file` | `content_base64="..."`, `filename="insurance_card_front.jpg"`, `mime_type="image/jpeg"` |
| 3 | `extract_text_from_file` | `file_path` from step 2 (OCR on JPEG) |
| 4 | `get_current_entity_type_schema` | `entity_type="insurance_card"` |
| 5 | `create_document` | `content_text=OCR_TEXT`, `source_type_id=CHATBOT_ID`, `files=[path]` |
| 6 | `search_current_facts` | `entity_type="insurance_card"` |
| 7 | `create_fact` | `entity_instance_id="NEW-UUID-aetna-insurance-card"`, `operation_type="create"`, `fields={provider, plan_name, ...}` |
| 8 | `log_interaction` | `status="success"` |

Same pipeline as PDF but for images. `extract_text_from_file` performs OCR on the JPEG.
Note: `search_current_facts` appears after `create_document` in this variant (vs. before in
scenario 20) — both reach the same outcome.

---

### 22 · Create household and add members
> "Create a household called Aggarwal Family and add me and Priya to it."

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_persons` | `name="Priya"` — resolve Priya's person_id |
| 2 | `create_household` | `name="Aggarwal Family"` |
| 3 | `add_person_to_household` | `person_id=SESSION_PERSON_ID`, `household_id` from step 2 |
| 4 | `add_person_to_household` | `person_id=PRIYA_ID`, `household_id` from step 2 |
| 5 | `log_interaction` | `status="success"` |

`search_persons` before adding Priya — correct ID resolution.

---

### 23 · List household members
> "Who is in the Aggarwal Family household?"

| # | Tool | Key parameters |
|---|---|---|
| 1 | `search_households` | `name="Aggarwal Family"` |
| 2 | `list_household_members` | `household_id` from step 1 |
| 3 | `log_interaction` | `status="success"` |

---

### 24 · New entity type — propose only, stop for confirmation  *(fixed in v3)*
> "I just got a gym membership at Equinox for $80 per month."

**v2 problem:** Agent correctly avoided `confirm_entity_type_schema` but continued to
`create_document` and `create_fact` in the same turn, defeating the stop gate.  
**v3 fix:** Rule 3 extended to forbid `create_document`/`create_fact` in same turn as propose.

| # | Tool | Key parameters |
|---|---|---|
| 1 | `get_current_entity_type_schema` | `entity_type="gym_membership"` → not found (404) |
| 2 | `propose_entity_type_schema` | `entity_type="gym_membership"`, `is_active=false`, `field_definitions=[{name:"gym_name", mandatory:true}, {name:"monthly_cost", type:"number"}, ...]` |

**Stops here.** `confirm_entity_type_schema`, `create_document`, and `create_fact` all absent.
The proposed schema is presented to the user; no data is stored until the user approves.

---

### 25 · Evolve schema — add copay field to insurance  *(fixed in v3)*
> "I'd like to also track my copay amount on insurance cards going forward."

**v2 problem:** `confirm_entity_type_schema` auto-called immediately after `evolve_entity_type_schema`.  
**v3 fix:** Rule 3 strengthened — "stop after propose OR evolve".

| # | Tool | Key parameters |
|---|---|---|
| 1 | `list_domains` | — |
| 2 | `get_current_entity_type_schema` | `entity_type="insurance_card"` |
| 3 | `evolve_entity_type_schema` | `schema_id` from step 2, adds `{name:"copay_amount", type:"number"}` |

**Stops here.** `confirm_entity_type_schema` absent. Schema evolution presented for approval;
not active until confirmed in a subsequent turn.

---

## Patterns and conventions confirmed by all 25 scenarios

### Tool selection for data retrieval

| Query type | Correct tool | Why |
|---|---|---|
| "Show me all my X" (no specific entity) | `list_current_facts` | Structured filter, no entity resolution needed |
| "What is my current X?" | `list_current_facts` or `search_current_facts` | Both valid; model choice |
| "Update/delete X" (named entity) | `search_current_facts` first, then write | Resolve entity_instance_id before write |
| "My X renewed/changed" | `search_current_facts` + `search_documents`, then write | Need entity_id AND old doc_id for supersedes |

### Ingestion pipeline order (every write turn)

```
list_domains                            ← resolve domain_id
search_current_facts                    ← Rule 1: always before create_fact
get_current_entity_type_schema          ← validate field structure
create_document(source_type_id=chatbot) ← immutable source record
  [if superseding: supersedes_ids=[...]]
create_fact(operation_type=create|update|delete)
log_interaction                         ← always last
```

### File upload pipeline order

```
list_domains
save_file(content_base64, filename, mime_type)
extract_text_from_file(file_path)       ← PDF parser or OCR
get_current_entity_type_schema
search_current_facts
create_document(content_text=OCR, source_type_id=chatbot, files=[...])
create_fact
log_interaction
```

### Schema governance turn structure

```
get_current_entity_type_schema          ← check if schema exists
  → if not found: propose_entity_type_schema(is_active=false)
  → if exists: evolve_entity_type_schema(is_active=false)
STOP — present proposed schema to user
  [next turn, after user approves: confirm_entity_type_schema]
```

### Patch semantics
Update facts carry only changed fields, not a full copy of the entity:
- `status` update: `fields={"status":"in_progress"}`
- `salary` update: `fields={"salary":145000}`
- `due_date` update: `fields={"due_date":"2026-08-15"}`
- explicit delete: `fields={}` with `operation_type="delete"`
