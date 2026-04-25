"""
Test scenarios for the MCP tool harness — v2, 25 scenarios.

Changes from v1:
- source_type_id and domain_id are now in session context (no list_domains /
  list_source_types needed for standard chat turns).
- Rules added: search before write, supersedes_ids for updates, schema
  confirmation requires user approval before confirm_entity_type_schema.
- Added: superseding examples, file upload examples, search-before-create,
  household, salary change, schema evolution, delete patterns.
"""
from .mock_server import (
    PERSON_ID,
    SOURCE_USER_INPUT_ID, SOURCE_FILE_UPLOAD_ID, SOURCE_AI_ID,
    SOURCE_PLAID_ID, SOURCE_GMAIL_ID,
    DOMAIN_HEALTH_ID, DOMAIN_FINANCE_ID, DOMAIN_EMPLOYMENT_ID,
    DOMAIN_PERSONAL_ID, DOMAIN_TODO_ID, DOMAIN_HOUSEHOLD_ID, DOMAIN_NEWS_ID,
)

SYSTEM_PROMPT = f"""\
You are a personal assistant agent for a family life management system.

SESSION CONTEXT — use these IDs directly; do NOT call list_domains or list_source_types:

  Current user:
    Name:      Ravi Aggarwal
    person_id: {PERSON_ID}

  Source type IDs:
    user_input:   {SOURCE_USER_INPUT_ID}
    file_upload:  {SOURCE_FILE_UPLOAD_ID}
    ai_extracted: {SOURCE_AI_ID}
    plaid_poll:   {SOURCE_PLAID_ID}
    gmail_poll:   {SOURCE_GMAIL_ID}

  Domain IDs:
    health:           {DOMAIN_HEALTH_ID}
    finance:          {DOMAIN_FINANCE_ID}
    employment:       {DOMAIN_EMPLOYMENT_ID}
    personal_details: {DOMAIN_PERSONAL_ID}
    todo:             {DOMAIN_TODO_ID}
    household:        {DOMAIN_HOUSEHOLD_ID}
    news_preferences: {DOMAIN_NEWS_ID}

EMBEDDING PARAMETERS: For any embedding parameter, pass [0.1, 0.2, 0.3] as placeholder.

RULES — follow exactly:
1. SEARCH BEFORE WRITE: Before creating a new fact for an entity that might already
   exist, call search_current_facts first. If a match is found (similarity >= 0.8),
   issue an update fact using that entity_instance_id. If not found, issue a create.
2. SUPERSEDING: When the user provides information that updates previously stored data
   (insurance renewal, salary change, new address etc.), call search_documents to find
   the old document, then pass its ID in supersedes_ids when creating the new document.
3. SCHEMA CONFIRMATION: After propose_entity_type_schema or evolve_entity_type_schema,
   present the proposed fields to the user and STOP. Do NOT call
   confirm_entity_type_schema in the same turn — wait for explicit user approval.
4. AUDIT: Call log_interaction as the final step of every human chat turn.\
"""

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
            "search_current_facts",         # check for existing todo
            "get_current_entity_type_schema",
            "create_document",
            "create_fact",                  # operation_type=create
            "log_interaction",
        ],
    },
    {
        "name": "04 · Add todo — search finds existing → update due date",
        "user_message": "Actually push the passport renewal deadline to August 15.",
        "expected_tools": [
            "search_current_facts",         # finds existing todo
            "create_document",
            "create_fact",                  # operation_type=update, fields={due_date}
            "log_interaction",
        ],
    },
    {
        "name": "05 · Update todo status to in_progress",
        "user_message": "I started working on the passport renewal.",
        "expected_tools": [
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
            "search_current_facts",
            "get_current_entity_type_schema",
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
            "search_current_facts",         # check for existing insurance
            "get_current_entity_type_schema",
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
            "search_current_facts",         # find existing insurance entity
            "search_documents",             # find old document to supersede
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
            "search_current_facts",
            "get_current_entity_type_schema",
            "create_document",
            "create_fact",
            "log_interaction",
        ],
    },
    {
        "name": "18 · Salary change — supersedes old employment document",
        "user_message": "Got a raise at Acme Corp, now making $145,000.",
        "expected_tools": [
            "search_current_facts",         # find existing job entity
            "search_documents",             # find old document to supersede
            "create_document",              # supersedes_ids=[old_doc_id]
            "create_fact",                  # operation_type=update, fields={salary:145000}
            "log_interaction",
        ],
    },
    {
        "name": "19 · Query current salary",
        "user_message": "What is my current salary?",
        "expected_tools": ["search_current_facts", "log_interaction"],
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
            "search_current_facts",
            "get_current_entity_type_schema",
            "create_document",              # source_type=file_upload, files=[{file_path}]
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
            "search_current_facts",
            "create_document",              # source_type=file_upload, files=[{file_path}]
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
            "list_entity_type_schemas",     # check for existing gym_membership schema
            "propose_entity_type_schema",   # is_active=false — MUST stop here
        ],
        "forbidden_tools": ["confirm_entity_type_schema"],
    },
    {
        "name": "25 · Evolve schema — add copay field to insurance",
        "user_message": (
            "I'd like to also track my copay amount on insurance cards going forward."
        ),
        "expected_tools": [
            "get_current_entity_type_schema",
            "evolve_entity_type_schema",    # is_active=false — MUST stop here
        ],
        "forbidden_tools": ["confirm_entity_type_schema"],
    },
]
