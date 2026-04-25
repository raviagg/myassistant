"""
Test scenarios that cover the key tool-call patterns in the personal assistant.
Each scenario has a human-readable name, the user message, and the tools we
expect to see called (used for validation output).
"""
from .mock_server import PERSON_ID

SYSTEM_PROMPT = f"""\
You are a personal assistant agent for a family life management system.
You help users store and retrieve their life information — health, finance,
employment, todos, relationships and more.

Current user:
  Name:      Ravi Aggarwal
  person_id: {PERSON_ID}

EMBEDDING PARAMETERS
Whenever a tool requires an `embedding` parameter, pass the placeholder
list [0.1, 0.2, 0.3]. In production a real 1536-dimension vector is
generated externally before you are called.

Process the user's message using the available tools. Think carefully
about which tools to call and in what sequence.\
"""

# Each scenario: name, user_message, expected_tools (ordered list of
# tool names we expect to see, used for the ✓/✗ validation summary).
SCENARIOS = [
    {
        "name": "01 · Lookup own profile",
        "user_message": "What's my name and date of birth?",
        "expected_tools": ["get_person"],
    },
    {
        "name": "02 · Add a new todo",
        "user_message": "Remind me to renew my passport by June 30, 2026.",
        "expected_tools": [
            "list_domains",
            "list_source_types",
            "create_document",
            "get_current_entity_type_schema",
            "create_fact",
            "log_interaction",
        ],
    },
    {
        "name": "03 · Update todo status",
        "user_message": "I started working on the passport renewal.",
        "expected_tools": [
            "search_current_facts",
            "list_source_types",
            "create_document",
            "create_fact",
            "log_interaction",
        ],
    },
    {
        "name": "04 · Mark todo as done",
        "user_message": "Passport renewal is done!",
        "expected_tools": [
            "search_current_facts",
            "list_source_types",
            "create_document",
            "create_fact",
            "log_interaction",
        ],
    },
    {
        "name": "05 · List open todos",
        "user_message": "Show me all my open tasks.",
        "expected_tools": ["list_domains", "list_current_facts"],
    },
    {
        "name": "06 · Add a family member",
        "user_message": "My wife is Priya Sharma, born March 15 1988.",
        "expected_tools": [
            "search_persons",
            "create_person",
            "create_relationship",
        ],
    },
    {
        "name": "07 · Kinship query",
        "user_message": "What is Priya's relation to me? Give me the Hindi term.",
        "expected_tools": ["search_persons", "resolve_kinship"],
    },
    {
        "name": "08 · Store insurance details",
        "user_message": (
            "I have Aetna insurance, plan BlueShield PPO 500. "
            "Deductible $1,500 and monthly premium $420. "
            "Coverage runs from January 1 to December 31, 2026."
        ),
        "expected_tools": [
            "list_domains",
            "list_source_types",
            "create_document",
            "get_current_entity_type_schema",
            "create_fact",
            "log_interaction",
        ],
    },
    {
        "name": "09 · Answer question from stored data",
        "user_message": "What is my current health insurance deductible?",
        "expected_tools": ["list_domains", "search_current_facts"],
    },
    {
        "name": "10 · New entity type (gym membership)",
        "user_message": "I just got a gym membership at Equinox for $80 per month.",
        "expected_tools": [
            "list_domains",
            "list_entity_type_schemas",
            "propose_entity_type_schema",
        ],
    },
]
