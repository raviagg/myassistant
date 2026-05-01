"""
Test scenarios for the MCP tool harness — v5, 25 scenarios with multi-turn conversations.

Changes from v4:
- Two-phase model: every write scenario is now multi-turn.
  Turn 1 (gather): agent reads all relevant existing data, then PROPOSES in text — no writes.
  Turn N (write): after user says "yes", agent executes the writes.
- Correction loop: scenario 04 demonstrates a 3-turn flow (gather → correction → approve).
  Correction turns use read tools only; still no writes until explicit approval.
- Schema governance follows the same two-phase model: gather turn reads schemas and describes
  proposed changes in text; write turn calls create_entity_type_schema (new type) or
  update_entity_type_schema (add/change fields) — both immediately active, no confirm step.
- GATHER_FORBIDDEN: constant listing all write tools including create_entity_type_schema
  and update_entity_type_schema; every gather/correction turn lists it as forbidden_tools.
- All scenarios use 'turns' format: list of {user_message, expected_tools, forbidden_tools?}.
- Per-turn expected_tools and forbidden_tools validated independently.
- GLOBAL_FORBIDDEN_TOOLS: list_source_types enforced across all turns of all scenarios.
"""
from common.system_prompt import build_system_prompt, TEST_PROMPT_ADDENDUM
from .mock_server import (
    PERSON_ID,
    PERSON_ID_2,
    PERSON_ID_3,
    SOURCE_USER_INPUT_ID,
    ENTITY_INSTANCE_ID,
    SCHEMA_TODO_ID,
    DOCUMENT_ID,
    NOW,
)

SYSTEM_PROMPT = (
    build_system_prompt(
        person_id      = PERSON_ID,
        person_name    = "Ravi Aggarwal",
        source_type_id = SOURCE_USER_INPUT_ID,
    )
    + TEST_PROMPT_ADDENDUM
)

# list_source_types must never appear in any turn — source_type_id is always the chatbot ID.
GLOBAL_FORBIDDEN_TOOLS = ["list_source_types"]

# All write tools. Listed as forbidden_tools in every gather and correction turn.
GATHER_FORBIDDEN = [
    "create_person", "update_person", "delete_person",
    "create_household", "update_household", "delete_household",
    "add_person_to_household", "remove_person_from_household",
    "create_relationship", "update_relationship", "delete_relationship",
    "create_document", "create_fact",
    "create_entity_type_schema", "update_entity_type_schema",
    "deactivate_entity_type_schema"]

SCENARIOS = [

    # ── A: Profile ────────────────────────────────────────────────────────

    {
        "name": "01 · Lookup own profile",
        "turns": [
            {
                "user_message": "What's my name and date of birth?",
                "expected_tools": ["get_person"],
            }],
    },
    {
        "name": "02 · Update own profile — gather then write",
        "mock_overrides": {"get_person": {
            "id": "aaaaaaaa-0000-0000-0000-000000000001",
            "full_name": "Ravi Aggarwal",
            "preferred_name": None,
            "gender": "male",
            "date_of_birth": "1985-03-22",
            "user_identifier": "raaggarw@adobe.com",
        }},
        "turns": [
            {
                "user_message": "I go by Ravi, please update my preferred name.",
                "expected_tools": ["get_person"],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, update it.",
                "expected_tools": ["update_person"],
            }],
    },

    # ── B: Todo CRUD ──────────────────────────────────────────────────────

    {
        "name": "03 · Add todo — gather + propose, then write",
        "mock_overrides": {"search_current_facts": []},
        "turns": [
            {
                "user_message": "Remind me to renew my passport by June 30, 2026.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts",         # dedup check — expects no existing match
                    ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, add it.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=create
                    ],
            }],
    },
    {
        "name": "04 · Add todo — correction turn before approval (3-turn flow)",
        "mock_overrides": {"search_current_facts": []},
        "turns": [
            {
                "user_message": "Remind me to schedule a dentist appointment by end of March.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",
                    "get_current_entity_type_schema",
                    "search_current_facts"],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Actually make that end of April, not March.",
                "expected_tools": [
                    # update proposal text; no writes
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, that's right, go ahead.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # due_date = end of April
                    ],
            }],
    },
    {
        "name": "05 · Update todo due date — search finds existing → update",
        "turns": [
            {
                "user_message": "Push my passport renewal deadline to August 15.",
                "expected_tools": [
                    "search_current_facts",         # finds existing passport todo (schema_id known → no schema discovery needed)
                    ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, update it.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=update, fields={due_date}
                    ],
            }],
    },
    {
        "name": "06 · Update todo status to in_progress",
        "turns": [
            {
                "user_message": "I started working on the passport renewal.",
                "expected_tools": [
                    "search_current_facts"],        # entity found → schema_id known → no schema discovery needed
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, mark it as in progress.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=update, fields={status:in_progress}
                    ],
            }],
    },
    {
        "name": "07 · Mark todo as done",
        "turns": [
            {
                "user_message": "Passport renewal is done!",
                "expected_tools": [
                    "search_current_facts"],        # entity found → schema_id known → no schema discovery needed
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, mark it done.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=update/delete
                    ],
            }],
    },
    {
        "name": "08 · List open todos",
        "turns": [
            {
                "user_message": "Show me all my open tasks.",
                "expected_tools": ["list_current_facts"],
            }],
    },
    {
        "name": "09 · Delete a todo — gather then delete",
        "mock_overrides": {
            "search_current_facts": [{
                "entity_instance_id": ENTITY_INSTANCE_ID,
                "schema_id": SCHEMA_TODO_ID,
                "document_id": DOCUMENT_ID,
                "current_fields": {
                    "title": "schedule dentist appointment",
                    "status": "open",
                    "due_date": "2026-03-31",
                },
                "similarity_score": 0.91,
                "created_at": NOW,
                "updated_at": NOW,
            }],
        },
        "turns": [
            {
                "user_message": "Drop the dentist appointment reminder, I don't need it.",
                "expected_tools": [
                    "search_current_facts",             # finds dentist todo (schema_id known → no schema discovery)
                    ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, delete it.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=delete
                    ],
            }],
    },

    # ── C: Relationships & kinship ────────────────────────────────────────

    {
        "name": "10 · Add a family member — gather then write",
        "mock_overrides": {
            "search_persons": [],       # Priya not yet in system → create her
            "list_relationships": [],   # no existing wife either; prevents Claude finding Priya via graph
        },
        "turns": [
            {
                "user_message": "My wife is Priya Sharma, born March 15 1988.",
                "expected_tools": [
                    "search_persons",               # check if Priya already exists → returns []
                    ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, add her.",
                "expected_tools": [
                    "create_person",
                    "create_relationship",          # Ravi → wife → Priya
                    ],
            }],
    },
    {
        "name": "11 · Direct kinship query with Hindi term",
        "turns": [
            {
                "user_message": "What is Priya's relation to me? Give me the Hindi term.",
                "expected_tools": ["search_persons", "resolve_kinship"],
            }],
    },
    {
        "name": "12 · Multi-hop kinship — answer question + propose adding person",
        "mock_overrides": {
            "list_relationships": [
                {"from_person_id": PERSON_ID,   "to_person_id": PERSON_ID_3, "relation_type": "father"},
                {"from_person_id": PERSON_ID_3, "to_person_id": PERSON_ID,   "relation_type": "son"},
                {"from_person_id": PERSON_ID,   "to_person_id": PERSON_ID_2, "relation_type": "wife"},
                {"from_person_id": PERSON_ID_2, "to_person_id": PERSON_ID,   "relation_type": "husband"},
            ],
            # Empty — no Savita in system; dad (PERSON_ID_3) known via list_relationships
            "search_persons": [],
        },
        "turns": [
            {
                "user_message": "My dad's sister is Savita. What do I call her in Hindi?",
                "expected_tools": [
                    "list_relationships",           # find who dad is (PERSON_ID_3)
                    # list_kinship_aliases or training knowledge may answer the Hindi term
                ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                # Claude gathers dad's details (get_person) and re-proposes the write plan in text.
                # No writes yet — this is the second gather/propose turn.
                "user_message": "Yes, add Savita as a new person and link her as my dad's sister.",
                "expected_tools": [],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, go ahead.",
                "expected_tools": [
                    "create_person",
                    "create_relationship",          # dad (PERSON_ID_3) → sister → Savita
                ],
            }],
    },
    {
        "name": "13 · List all family members",
        "turns": [
            {
                "user_message": "Who are all the people recorded in my family?",
                "expected_tools": ["list_relationships"],
            }],
    },

    # ── D: Health / Insurance ─────────────────────────────────────────────

    {
        "name": "14 · Store insurance — gather then write",
        "mock_overrides": {"search_current_facts": []},
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
                    "search_current_facts"],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, save it.",
                "expected_tools": [
                    "create_document",
                    "create_fact",                  # operation_type=create
                    ],
            }],
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
                    "search_current_facts",         # find existing insurance entity (schema_id known → no schema discovery)
                    "search_documents",             # find old doc to supersede (Rule 7a)
                    ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, update it.",
                "expected_tools": [
                    "create_document",              # supersedes_ids=[old_doc_id]
                    "create_fact",                  # operation_type=update
                    ],
            }],
    },
    {
        "name": "16 · Query insurance deductible",
        "turns": [
            {
                "user_message": "What is my current health insurance deductible?",
                "expected_tools": ["search_current_facts"],
            }],
    },

    # ── E: Employment ─────────────────────────────────────────────────────

    {
        "name": "17 · Add new job — gather then write",
        "mock_overrides": {"search_current_facts": []},
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
                    "search_current_facts"],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, add it.",
                "expected_tools": [
                    "create_document",
                    "create_fact"],
            }],
    },
    {
        "name": "18 · Salary change — supersedes old employment document",
        "turns": [
            {
                "user_message": "Got a raise at Acme Corp, now making $145,000.",
                "expected_tools": [
                    "search_current_facts",         # find existing job entity (schema_id known → no schema discovery)
                    "search_documents",             # find old employment doc to supersede
                    ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, update my salary.",
                "expected_tools": [
                    "create_document",              # supersedes_ids=[old_doc_id]
                    "create_fact",                  # operation_type=update, fields={salary:145000}
                    ],
            }],
    },
    {
        "name": "19 · Query current salary",
        "turns": [
            {
                "user_message": "What is my current salary?",
                "expected_tools": [],
            }],
    },

    # ── F: File handling ──────────────────────────────────────────────────

    {
        "name": "20 · Upload payslip PDF — gather (save+extract) then write",
        "mock_overrides": {"search_current_facts": []},
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
                    "search_current_facts"],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, save the payslip data.",
                "expected_tools": [
                    "create_document",
                    "create_fact"],
            }],
    },
    {
        "name": "21 · Upload insurance card image — gather (OCR) then write",
        "mock_overrides": {"search_current_facts": []},
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
                    "search_current_facts"],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, save the insurance card details.",
                "expected_tools": [
                    "create_document",
                    "create_fact"],
            }],
    },

    # ── G: Household ──────────────────────────────────────────────────────

    {
        "name": "22 · Create household and add members — gather then write",
        "mock_overrides": {
            "search_households": [],        # no existing household → Claude creates it
            "list_household_members": [],   # no existing members → Claude adds both
        },
        "turns": [
            {
                "user_message": "Create a household called Aggarwal Family and add me and Priya to it.",
                "expected_tools": [
                    "search_persons",               # find Priya's person_id
                    "search_households",            # check if household already exists → []
                    ],
                "forbidden_tools": GATHER_FORBIDDEN,
            },
            {
                "user_message": "Yes, create it.",
                "expected_tools": [
                    "create_household",
                    "add_person_to_household",      # add Ravi
                    "add_person_to_household",      # add Priya
                    ],
            }],
    },
    {
        "name": "23 · List household members",
        "turns": [
            {
                "user_message": "Who is in the Aggarwal Family household?",
                "expected_tools": [
                    "search_households",
                    "list_household_members"],
            }],
    },

    # ── H: Schema governance ──────────────────────────────────────────────

    {
        "name": "24 · New entity type — gather + propose in text, then write schema",
        "turns": [
            {
                "user_message": "I just got a gym membership at Equinox for $80 per month.",
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",        # gym_membership absent → describe in text
                    ],
                "forbidden_tools": GATHER_FORBIDDEN,  # create_entity_type_schema is in here
            },
            {
                "user_message": "Yes, add that schema.",
                "expected_tools": [
                    "create_entity_type_schema",       # creates schema, immediately active
                    "create_document",
                    "create_fact"],
            }],
    },
    {
        "name": "25 · Update schema — gather + propose in text, then write evolution",
        "turns": [
            {
                "user_message": (
                    "I'd like to also track my copay amount on insurance cards going forward."
                ),
                "expected_tools": [
                    "list_domains",
                    "list_entity_type_schemas",        # find insurance_card in health domain
                    "get_current_entity_type_schema",  # get full current schema
                    ],
                "forbidden_tools": GATHER_FORBIDDEN,  # update_entity_type_schema is in here
            },
            {
                "user_message": "Yes, add that field.",
                "expected_tools": [
                    "update_entity_type_schema",       # full field list, new version immediately active
                    ],
            }],
    }]
