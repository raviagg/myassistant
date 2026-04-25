"""
Test scenarios for the MCP tool harness — v5, 25 scenarios with multi-turn conversations.

Changes from v4:
- Two-phase model: every write scenario is now multi-turn.
  Turn 1 (gather): agent reads all relevant existing data, then PROPOSES in text — no writes.
  Turn N (write): after user says "yes", agent executes the writes.
- Correction loop: scenario 04 demonstrates a 3-turn flow (gather → correction → approve).
  Correction turns use read tools only; still no writes until explicit approval.
- GATHER_FORBIDDEN: constant listing all write tools; every gather/correction turn
  lists it as forbidden_tools so the harness validates no writes occur.
- All scenarios use 'turns' format: list of {user_message, expected_tools, forbidden_tools?}.
- Per-turn expected_tools and forbidden_tools validated independently.
- log_interaction required in every turn (gather, correction, write, and query turns alike).
- GLOBAL_FORBIDDEN_TOOLS: list_source_types enforced across all turns of all scenarios.
"""
from .mock_server import (
    PERSON_ID,
    SOURCE_CHATBOT_ID,
)

SYSTEM_PROMPT = f"""\
You are a personal assistant agent for a family life management system.

SESSION CONTEXT:

  Current user:
    Name:      Ravi Aggarwal
    person_id: {PERSON_ID}

  Source type: All interactions through this chatbot use source_type_id: {SOURCE_CHATBOT_ID}
  Do NOT call list_source_types — this ID is fixed for all chatbot interactions
  (typed text, file uploads, and AI-extracted content all share the same chatbot source type).

  Domain IDs are NOT pre-loaded. Call list_domains when you need a domain_id.

EMBEDDING PARAMETERS: For any embedding parameter, pass [0.1, 0.2, 0.3] as placeholder.
For entity_instance_id on a new create, use a descriptive placeholder like "NEW-UUID-passport-renewal".
For UUID values from earlier tool calls, use placeholders like "DOMAIN-ID-FROM-LIST-DOMAINS".

RULES — follow exactly:

0. CLASSIFY: Determine whether the user is providing new information or asking a question.
   - QUESTION / QUERY → use only read tools (get_*, search_*, list_*, resolve_*).
     Answer the question. End with log_interaction. No write tools.
   - NEW INFORMATION → proceed with the GATHER phase (Rule 1).

1. GATHER PHASE (new-information turns only):
   a. Read ALL relevant existing data before proposing anything:
      - Spine: search_persons, list_relationships, search_households, list_household_members
        as relevant to the information provided.
      - Schema: list_domains → list_entity_type_schemas → get_current_entity_type_schema
        for the best-matching entity type (see Rule 5).
      - Facts: search_current_facts to find any existing entity (similarity threshold 0.8).
        ≥ 0.8 → existing entity found, plan an update. < 0.8 or no results → plan a create.
      - Documents: search_documents if this information might supersede existing data
        (e.g. insurance renewed, salary changed).
      - Files: save_file + extract_text_from_file if the user uploaded a file; this counts
        as reading/pre-processing, not a write.
   b. Do NOT assume anything is new until all relevant reads are complete.
   c. After gathering: state in text what you found and exactly what you plan to write.
      Ask the user to confirm.
   d. Do NOT call any write tools (create_*, update_*, add_*, delete_*, confirm_*) in
      this turn. Exception: propose_entity_type_schema is allowed if no schema matches
      (see Rule 5) — but treat that as the end of this turn (see Rule 6).
   e. End with log_interaction.

2. CONFIRMATION: Never call write tools until the user explicitly approves.
   "yes", "go ahead", "do it", "correct", "confirmed", or equivalent counts as approval.

3. CORRECTION TURNS: If the user corrects something before approving:
   a. Do any needed re-reads (search_current_facts, get_person, etc.).
   b. Update the proposal in text.
   c. Do NOT call any write tools. End with log_interaction.
   Repeat until explicit approval.

4. WRITE PHASE: After explicit user approval, execute writes:
   a. Spine: create_person / create_relationship / create_household /
      add_person_to_household / update_person as needed.
   b. Document: create_document (with supersedes_ids if this replaces existing data).
   c. Fact: create_fact with the operation_type determined in the gather phase
      (create if no existing entity; update if entity_instance_id found at ≥ 0.8).
   d. End with log_interaction.

5. SCHEMA DISCOVERY (in gather phase, after list_domains):
   a. list_entity_type_schemas(domain_id=...) — see ALL types in the domain.
   b. Select the entity_type whose name + description best matches the information.
   c. No match → call propose_entity_type_schema (is_active=false) and end the turn
      (Rule 6). Do not proceed to create_document or create_fact.
   d. Match found → get_current_entity_type_schema(entity_type=best_match).

6. SCHEMA CONFIRMATION: After propose_entity_type_schema or evolve_entity_type_schema,
   STOP. Do NOT call confirm_entity_type_schema, create_document, or create_fact in the
   same turn. Wait for explicit user approval before confirming.

7. search_documents vs search_current_facts:
   - search_current_facts: current merged field values — use in gather phase for dedup
     check and for answering "what is my current X?" queries.
   - search_documents:
     (a) SUPERSEDING — when data has changed (renewed, raised, replaced): find the old
         source document, then pass its document_id in supersedes_ids on create_document.
     (b) HISTORICAL/SOURCE QUERIES — when the user asks about original source content.

8. AUDIT: log_interaction is the FINAL call in EVERY turn — query turns, gather turns,
   correction turns, and write turns alike.\
"""

# list_source_types must never appear in any turn — source_type_id is always the chatbot ID.
GLOBAL_FORBIDDEN_TOOLS = ["list_source_types"]

# All write tools. Listed as forbidden_tools in every gather and correction turn.
GATHER_FORBIDDEN = [
    "create_person", "update_person", "delete_person",
    "create_household", "update_household", "delete_household",
    "add_person_to_household", "remove_person_from_household",
    "create_relationship", "update_relationship", "delete_relationship",
    "create_document", "create_fact",
    "confirm_entity_type_schema", "deactivate_entity_type_schema",
]

SCENARIOS = [

    # ── A: Profile ────────────────────────────────────────────────────────

    {
        "name": "01 · Lookup own profile",
        "turns": [
            {
                "user_message": "What's my name and date of birth?",
                "expected_tools": ["get_person", "log_interaction"],
            },
        ],
    },
    {
        "name": "02 · Update own profile — gather then write",
        "turns": [
            {
                "user_message": "I go by Ravi, please update my preferred name.",
                "expected_tools": ["get_person", "log_interaction"],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, update it.",
                "expected_tools": ["update_person", "log_interaction"],
            },
        ],
    },

    # ── B: Todo CRUD ──────────────────────────────────────────────────────

    {
        "name": "03 · Add todo — gather + propose, then write",
        "turns": [
            {
                "user_message": "Remind me to renew my passport by June 30, 2026.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",         # dedup check — expects no existing match
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, add it.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=create
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "04 · Add todo — correction turn before approval (3-turn flow)",
        "turns": [
            {
                "user_message": "Remind me to schedule a dentist appointment by end of March.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Actually make that end of April, not March.",
                "expected_tools": [
                    "log_interaction",              # update proposal text; no writes
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, that's right, go ahead.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # due_date = end of April
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "05 · Update todo due date — search finds existing → update",
        "turns": [
            {
                "user_message": "Push my passport renewal deadline to August 15.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",         # finds existing passport todo (≥0.8)
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, update it.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=update, fields={due_date}
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "06 · Update todo status to in_progress",
        "turns": [
            {
                "user_message": "I started working on the passport renewal.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, mark it as in progress.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=update, fields={status:in_progress}
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "07 · Mark todo as done",
        "turns": [
            {
                "user_message": "Passport renewal is done!",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, mark it done.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=update/delete
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "08 · List open todos",
        "turns": [
            {
                "user_message": "Show me all my open tasks.",
                "expected_tools": ["list_current_facts", "log_interaction"],
            },
        ],
    },
    {
        "name": "09 · Delete a todo — gather then delete",
        "turns": [
            {
                "user_message": "Drop the dentist appointment reminder, I don't need it.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, delete it.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=delete
                    "log_interaction",
                ],
            },
        ],
    },

    # ── C: Relationships & kinship ────────────────────────────────────────

    {
        "name": "10 · Add a family member — gather then write",
        "turns": [
            {
                "user_message": "My wife is Priya Sharma, born March 15 1988.",
                "expected_tools": [
                    "search_persons",               # check if Priya already exists
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, add her.",
                "expected_tools": [
                    "create_person",
                    "create_relationship",          # Ravi → wife → Priya
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "11 · Direct kinship query with Hindi term",
        "turns": [
            {
                "user_message": "What is Priya's relation to me? Give me the Hindi term.",
                "expected_tools": ["search_persons", "resolve_kinship", "log_interaction"],
            },
        ],
    },
    {
        "name": "12 · Multi-hop kinship — answer question + propose adding person",
        "turns": [
            {
                "user_message": "My dad's sister is Savita. What do I call her in Hindi?",
                "expected_tools": [
                    "search_persons",               # find dad + check if Savita exists
                    "resolve_kinship",              # answer the bua question immediately
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, add Savita to my family tree.",
                "expected_tools": [
                    "create_person",
                    "create_relationship",          # dad → sister → Savita
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "13 · List all family members",
        "turns": [
            {
                "user_message": "Who are all the people recorded in my family?",
                "expected_tools": ["list_relationships", "log_interaction"],
            },
        ],
    },

    # ── D: Health / Insurance ─────────────────────────────────────────────

    {
        "name": "14 · Store insurance — gather then write",
        "turns": [
            {
                "user_message": (
                    "I have Aetna insurance, plan BlueShield PPO 500. "
                    "Deductible $1,500, premium $420/month. "
                    "Coverage January 1 to December 31, 2026."
                ),
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, save it.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=create
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "15 · Update insurance — supersedes old document",
        "turns": [
            {
                "user_message": (
                    "My Aetna insurance renewed for 2027. "
                    "Same plan but deductible went up to $2,000."
                ),
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",         # find existing insurance entity (Rule 1)
                    "search_documents",             # find old doc to supersede (Rule 7a)
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, update it.",
                "expected_tools": [
                    "create_document",              # supersedes_ids=[old_doc_id]
                    "create_fact",                  # operation_type=update
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "16 · Query insurance deductible",
        "turns": [
            {
                "user_message": "What is my current health insurance deductible?",
                "expected_tools": ["search_current_facts", "log_interaction"],
            },
        ],
    },

    # ── E: Employment ─────────────────────────────────────────────────────

    {
        "name": "17 · Add new job — gather then write",
        "turns": [
            {
                "user_message": (
                    "I started a new job at Acme Corp as Senior Engineer, "
                    "salary $120,000/year, start date March 1 2024."
                ),
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, add it.",
                "expected_tools": [
                    "create_document",
                    "create_fact",
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "18 · Salary change — supersedes old employment document",
        "turns": [
            {
                "user_message": "Got a raise at Acme Corp, now making $145,000.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",         # find existing job entity
                    "search_documents",             # find old employment doc to supersede
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, update my salary.",
                "expected_tools": [
                    "create_document",              # supersedes_ids=[old_doc_id]
                    "create_fact",                  # operation_type=update, fields={salary:145000}
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "19 · Query current salary",
        "turns": [
            {
                "user_message": "What is my current salary?",
                "expected_tools": ["log_interaction"],
            },
        ],
    },

    # ── F: File handling ──────────────────────────────────────────────────

    {
        "name": "20 · Upload payslip PDF — gather (save+extract) then write",
        "turns": [
            {
                "user_message": (
                    "I'm uploading my March 2026 payslip from Acme Corp. "
                    "[Attached: payslip_march_2026.pdf | base64: JVBERi0xLjQ=]"
                ),
                "expected_tools": [
                    "save_file",
                    "extract_text_from_file",
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, save the payslip data.",
                "expected_tools": [
                    "create_document",
                    "create_fact",
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "21 · Upload insurance card image — gather (OCR) then write",
        "turns": [
            {
                "user_message": (
                    "Here is a photo of my new Aetna insurance card. "
                    "[Attached: insurance_card_front.jpg | base64: /9j/4AAQSkZJRg==]"
                ),
                "expected_tools": [
                    "save_file",
                    "extract_text_from_file",       # OCR
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, save the insurance card details.",
                "expected_tools": [
                    "create_document",
                    "create_fact",
                    "log_interaction",
                ],
            },
        ],
    },

    # ── G: Household ──────────────────────────────────────────────────────

    {
        "name": "22 · Create household and add members — gather then write",
        "turns": [
            {
                "user_message": "Create a household called Aggarwal Family and add me and Priya to it.",
                "expected_tools": [
                    "search_persons",               # find Priya's person_id
                    "search_households",            # check if household already exists
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, create it.",
                "expected_tools": [
                    "create_household",
                    "add_person_to_household",      # add Ravi
                    "add_person_to_household",      # add Priya
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "23 · List household members",
        "turns": [
            {
                "user_message": "Who is in the Aggarwal Family household?",
                "expected_tools": [
                    "search_households",
                    "list_household_members",
                    "log_interaction",
                ],
            },
        ],
    },

    # ── H: Schema governance ──────────────────────────────────────────────

    {
        "name": "24 · New entity type — propose schema (stop gate), then confirm",
        "turns": [
            {
                "user_message": "I just got a gym membership at Equinox for $80 per month.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",        # gym_membership absent from health domain
                    "propose_entity_type_schema",      # no match → propose, is_active=false, STOP
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,  # confirm_entity_type_schema is in here
            },
            {
                "user_message": "Yes, add that schema.",
                "expected_tools": [
                    "confirm_entity_type_schema",
                    "log_interaction",
                ],
            },
        ],
    },
    {
        "name": "25 · Evolve schema — add copay field (stop gate), then confirm",
        "turns": [
            {
                "user_message": (
                    "I'd like to also track my copay amount on insurance cards going forward."
                ),
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",        # find insurance_card in health domain
                    "get_current_entity_type_schema",  # get full current schema
                    "evolve_entity_type_schema",       # add copay_amount field, is_active=false, STOP
                    "log_interaction",
                ],
                "forbidden_tools": GATHER_FORBIDDEN,  # confirm_entity_type_schema is in here
            },
            {
                "user_message": "Yes, add that field.",
                "expected_tools": [
                    "confirm_entity_type_schema",
                    "log_interaction",
                ],
            },
        ],
    },
]
