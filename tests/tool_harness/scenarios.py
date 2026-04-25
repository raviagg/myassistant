"""
Test scenarios for the MCP tool harness — v4, 25 scenarios.

Changes from v3:
- Rule 0 added: list_entity_type_schemas before every fact write so the agent
  discovers candidate schemas rather than guessing entity type names.
- Rule 1 strengthened: explicit similarity threshold (>=0.8 → must update,
  <0.8 → create new). Never create a new entity_instance_id when a match exists.
- Rule 2 clarified: two use cases for search_documents vs search_current_facts.
- expected_tools updated: list_entity_type_schemas added to all write-fact scenarios.
- Scenario 24 expected updated to use list_entity_type_schemas (not get_current).
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

0. SCHEMA DISCOVERY (every write turn that involves facts):
   a. After resolving domain_id via list_domains, call list_entity_type_schemas(domain_id=...)
      to see ALL available entity types in that domain.
   b. From the returned list, select the entity_type whose name and description best matches
      the information being stored.
   c. If NO schema closely matches — this is a new category of information:
      call propose_entity_type_schema and STOP (see Rule 3). Do not create documents or facts.
   d. If a match is found: call get_current_entity_type_schema(entity_type=best_match) to get
      the full active schema with all field definitions, then continue the write pipeline.

1. SEARCH BEFORE WRITE (strict):
   Before calling create_fact, ALWAYS call search_current_facts first — even if you believe
   the entity is brand new. Use the results as follows:
   - similarity >= 0.8 → MUST use that entity_instance_id with operation_type=update.
     Do NOT generate a new entity_instance_id when a match exists at this threshold.
   - No results or similarity < 0.8 → use a new UUID placeholder with operation_type=create.

2. search_documents vs search_current_facts:
   - search_current_facts: for current merged field values — dedup check before every write,
     and for reading current state ("what is my deductible?", "what is my salary?").
   - search_documents: use in TWO cases:
     (a) SUPERSEDING — when the user says data has changed, renewed, or been replaced
         (e.g. "insurance renewed", "got a raise"), call search_documents to find the old
         source document, then pass its document_id in supersedes_ids on the new create_document.
     (b) HISTORICAL/SOURCE QUERIES — when the user asks about original source content
         ("what did I write when I first registered my insurance?", "show me my original payslip").

3. SCHEMA CONFIRMATION: After propose_entity_type_schema or evolve_entity_type_schema, STOP.
   Do NOT call confirm_entity_type_schema, create_document, or create_fact in the same turn.
   Wait for explicit user approval before confirming.

4. AUDIT: log_interaction is the final call in every human chat turn.\
"""

# list_source_types must never appear — source_type_id is always the chatbot ID from context.
GLOBAL_FORBIDDEN_TOOLS = ["list_source_types"]

SCENARIOS = [

    # ── A: Profile ────────────────────────────────────────────────────────

    {
        "name": "01 · Lookup own profile",
        "user_message": "What's my name and date of birth?",
        "expected_tools": ["get_person", "log_interaction"],
    },
    {
        "name": "02 · Update own profile",
        "user_message": "I go by Ravi, please update my preferred name.",
        "expected_tools": ["update_person", "log_interaction"],
    },

    # ── B: Todo CRUD ──────────────────────────────────────────────────────

    {
        "name": "03 · Add todo — search first, not found → create",
        "user_message": "Remind me to renew my passport by June 30, 2026.",
        "expected_tools": [
            "list_entity_type_schemas",     # discover todo domain schemas
            "get_current_entity_type_schema",
            "search_current_facts",         # check for existing todo (Rule 1)
            "create_document",
            "create_fact",                  # operation_type=create
            "log_interaction",
        ],
    },
    {
        "name": "04 · Add todo — search finds existing → update due date",
        "user_message": "Actually push the passport renewal deadline to August 15.",
        "expected_tools": [
            "list_entity_type_schemas",     # discover todo domain schemas
            "get_current_entity_type_schema",
            "search_current_facts",         # finds existing todo (Rule 1)
            "create_document",
            "create_fact",                  # operation_type=update, fields={due_date}
            "log_interaction",
        ],
    },
    {
        "name": "05 · Update todo status to in_progress",
        "user_message": "I started working on the passport renewal.",
        "expected_tools": [
            "list_entity_type_schemas",
            "get_current_entity_type_schema",
            "search_current_facts",
            "create_document",
            "create_fact",                  # operation_type=update, fields={status:in_progress}
            "log_interaction",
        ],
    },
    {
        "name": "06 · Mark todo as done",
        "user_message": "Passport renewal is done!",
        "expected_tools": [
            "list_entity_type_schemas",
            "get_current_entity_type_schema",
            "search_current_facts",
            "create_document",
            "create_fact",                  # operation_type=update/delete
            "log_interaction",
        ],
    },
    {
        "name": "07 · Add recurring todo",
        "user_message": "Remind me to buy groceries every Sunday.",
        "expected_tools": [
            "list_entity_type_schemas",
            "get_current_entity_type_schema",
            "search_current_facts",
            "create_document",
            "create_fact",                  # fields={is_recurring:true, recurrence:"weekly"}
            "log_interaction",
        ],
    },
    {
        "name": "08 · List open todos",
        "user_message": "Show me all my open tasks.",
        "expected_tools": ["list_current_facts"],
    },
    {
        "name": "09 · Delete a todo explicitly",
        "user_message": "Drop the dentist appointment reminder, I don't need it.",
        "expected_tools": [
            "list_entity_type_schemas",
            "get_current_entity_type_schema",
            "search_current_facts",
            "create_document",
            "create_fact",                  # operation_type=delete
            "log_interaction",
        ],
    },

    # ── C: Relationships & kinship ────────────────────────────────────────

    {
        "name": "10 · Add a family member",
        "user_message": "My wife is Priya Sharma, born March 15 1988.",
        "expected_tools": [
            "search_persons",
            "create_person",
            "create_relationship",          # Ravi → wife → Priya
            "log_interaction",
        ],
    },
    {
        "name": "11 · Direct kinship query with Hindi term",
        "user_message": "What is Priya's relation to me? Give me the Hindi term.",
        "expected_tools": ["search_persons", "resolve_kinship"],
    },
    {
        "name": "12 · Multi-hop kinship — father's sister",
        "user_message": "My dad's sister is Savita. What do I call her in Hindi?",
        "expected_tools": ["search_persons", "resolve_kinship"],
    },
    {
        "name": "13 · List all family members",
        "user_message": "Who are all the people recorded in my family?",
        "expected_tools": ["list_relationships"],
    },

    # ── D: Health / Insurance ─────────────────────────────────────────────

    {
        "name": "14 · Store insurance — first time",
        "user_message": (
            "I have Aetna insurance, plan BlueShield PPO 500. "
            "Deductible $1,500, premium $420/month. "
            "Coverage January 1 to December 31, 2026."
        ),
        "expected_tools": [
            "list_entity_type_schemas",     # discover health domain schemas
            "get_current_entity_type_schema",
            "search_current_facts",         # check for existing insurance (Rule 1)
            "create_document",
            "create_fact",                  # operation_type=create
            "log_interaction",
        ],
    },
    {
        "name": "15 · Update insurance — supersedes old document",
        "user_message": (
            "My Aetna insurance renewed for 2027. "
            "Same plan but deductible went up to $2,000."
        ),
        "expected_tools": [
            "list_entity_type_schemas",
            "get_current_entity_type_schema",
            "search_current_facts",         # find existing insurance entity (Rule 1)
            "search_documents",             # find old document to supersede (Rule 2a)
            "create_document",              # supersedes_ids=[old_doc_id]
            "create_fact",                  # operation_type=update
            "log_interaction",
        ],
    },
    {
        "name": "16 · Query insurance deductible",
        "user_message": "What is my current health insurance deductible?",
        "expected_tools": ["search_current_facts", "log_interaction"],
    },

    # ── E: Employment ─────────────────────────────────────────────────────

    {
        "name": "17 · Add new job",
        "user_message": (
            "I started a new job at Acme Corp as Senior Engineer, "
            "salary $120,000/year, start date March 1 2024."
        ),
        "expected_tools": [
            "list_entity_type_schemas",     # discover employment domain schemas
            "get_current_entity_type_schema",
            "search_current_facts",         # check for existing job (Rule 1)
            "create_document",
            "create_fact",
            "log_interaction",
        ],
    },
    {
        "name": "18 · Salary change — supersedes old employment document",
        "user_message": "Got a raise at Acme Corp, now making $145,000.",
        "expected_tools": [
            "list_entity_type_schemas",
            "get_current_entity_type_schema",
            "search_current_facts",         # find existing job entity (Rule 1)
            "search_documents",             # find old document to supersede (Rule 2a)
            "create_document",              # supersedes_ids=[old_doc_id]
            "create_fact",                  # operation_type=update, fields={salary:145000}
            "log_interaction",
        ],
    },
    {
        "name": "19 · Query current salary",
        "user_message": "What is my current salary?",
        "expected_tools": ["log_interaction"],
    },

    # ── F: File handling ──────────────────────────────────────────────────

    {
        "name": "20 · Upload payslip PDF",
        "user_message": (
            "I'm uploading my March 2026 payslip from Acme Corp. "
            "[Attached: payslip_march_2026.pdf | base64: JVBERi0xLjQ=]"
        ),
        "expected_tools": [
            "save_file",
            "extract_text_from_file",
            "list_entity_type_schemas",     # discover finance domain schemas
            "get_current_entity_type_schema",
            "search_current_facts",         # dedup check (Rule 1)
            "create_document",              # source_type=chatbot, files=[{file_path}]
            "create_fact",
            "log_interaction",
        ],
    },
    {
        "name": "21 · Upload insurance card image (OCR path)",
        "user_message": (
            "Here is a photo of my new Aetna insurance card. "
            "[Attached: insurance_card_front.jpg | base64: /9j/4AAQSkZJRg==]"
        ),
        "expected_tools": [
            "save_file",
            "extract_text_from_file",       # OCR
            "list_entity_type_schemas",     # discover health domain schemas
            "get_current_entity_type_schema",
            "search_current_facts",         # dedup check (Rule 1)
            "create_document",              # source_type=chatbot, files=[{file_path}]
            "create_fact",
            "log_interaction",
        ],
    },

    # ── G: Household ──────────────────────────────────────────────────────

    {
        "name": "22 · Create household and add members",
        "user_message": "Create a household called Aggarwal Family and add me and Priya to it.",
        "expected_tools": [
            "create_household",
            "search_persons",               # find Priya's person_id
            "add_person_to_household",      # add Ravi
            "add_person_to_household",      # add Priya
            "log_interaction",
        ],
    },
    {
        "name": "23 · List household members",
        "user_message": "Who is in the Aggarwal Family household?",
        "expected_tools": [
            "search_households",
            "list_household_members",
        ],
    },

    # ── H: Schema governance ──────────────────────────────────────────────

    {
        "name": "24 · New entity type — propose only, stop for confirmation",
        "user_message": "I just got a gym membership at Equinox for $80 per month.",
        "expected_tools": [
            "list_entity_type_schemas",        # see all health schemas — gym_membership absent
            "propose_entity_type_schema",      # no match → propose new, is_active=false, STOP
        ],
        "forbidden_tools": ["confirm_entity_type_schema", "create_document", "create_fact"],
    },
    {
        "name": "25 · Evolve schema — add copay field to insurance",
        "user_message": (
            "I'd like to also track my copay amount on insurance cards going forward."
        ),
        "expected_tools": [
            "list_entity_type_schemas",        # find insurance_card in health domain
            "get_current_entity_type_schema",  # get full current schema
            "evolve_entity_type_schema",       # add copay_amount field, is_active=false, STOP
        ],
        "forbidden_tools": ["confirm_entity_type_schema"],
    },
]
