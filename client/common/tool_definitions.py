"""
All 42 MCP tool definitions in Anthropic SDK format.
Derived from docs/mcp-tools.md.
"""

_RELATION_TYPES = ["father", "mother", "son", "daughter", "brother", "sister", "husband", "wife"]

ALL_TOOLS = [
    # ── Group 1a — Person ────────────────────────────────────────────────
    {
        "name": "create_person",
        "description": (
            "Register a new person. Must be called before documents, facts, or "
            "relationships can be attached to that person."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "full_name": {"type": "string", "description": "Legal or full name"},
                "gender": {
                    "type": "string",
                    "enum": ["male", "female"],
                    "description": "Used for kinship resolution (e.g. deriving husband ↔ wife inverses)",
                },
                "date_of_birth": {"type": "string", "description": "ISO 8601 date e.g. 1985-06-15"},
                "preferred_name": {"type": "string", "description": "Nickname or common name"},
                "user_identifier": {
                    "type": "string",
                    "description": "Unique login handle (email) if this person is a system user",
                },
            },
            "required": ["full_name", "gender"],
        },
    },
    {
        "name": "get_person",
        "description": "Fetch a single person by UUID. Use when you already know the person_id.",
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string", "description": "UUID of the person"},
            },
            "required": ["person_id"],
        },
    },
    {
        "name": "search_persons",
        "description": (
            "Find persons matching one or more filter criteria (ANDed). "
            "Use for both listing all persons and filtered lookups by name, DOB, or household membership."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Case-insensitive partial match on full_name or preferred_name",
                },
                "gender": {"type": "string", "enum": ["male", "female"]},
                "date_of_birth": {"type": "string", "description": "Exact match"},
                "date_of_birth_from": {"type": "string", "description": "Range lower bound (inclusive)"},
                "date_of_birth_to": {"type": "string", "description": "Range upper bound (inclusive)"},
                "household_id": {
                    "type": "string",
                    "description": "Only persons who are members of this household",
                },
                "limit": {"type": "integer", "description": "Default 50"},
                "offset": {"type": "integer", "description": "Default 0"},
            },
            "required": [],
        },
    },
    {
        "name": "update_person",
        "description": (
            "Update mutable fields on an existing person. "
            "Only supplied fields are changed; omitted fields are left unchanged (PATCH semantics)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string"},
                "full_name": {"type": "string"},
                "preferred_name": {"type": "string"},
                "date_of_birth": {"type": "string"},
                "gender": {"type": "string", "enum": ["male", "female"]},
                "user_identifier": {"type": "string"},
            },
            "required": ["person_id"],
        },
    },
    {
        "name": "delete_person",
        "description": (
            "Delete a person. Checks all FK references first. "
            "If references exist, returns a structured blocking error with counts by type "
            "so the caller can decide whether to cascade or abort."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string"},
            },
            "required": ["person_id"],
        },
    },
    # ── Group 1b — Household ─────────────────────────────────────────────
    {
        "name": "create_household",
        "description": (
            "Create a new household. Persons are linked separately via add_person_to_household."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": 'Human-readable label e.g. "Aggarwal Family"',
                },
            },
            "required": ["name"],
        },
    },
    {
        "name": "get_household",
        "description": "Fetch a single household by UUID.",
        "input_schema": {
            "type": "object",
            "properties": {
                "household_id": {"type": "string"},
            },
            "required": ["household_id"],
        },
    },
    {
        "name": "search_households",
        "description": "Find households by name. Case-insensitive partial match.",
        "input_schema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
            },
            "required": ["name"],
        },
    },
    {
        "name": "update_household",
        "description": "Rename a household.",
        "input_schema": {
            "type": "object",
            "properties": {
                "household_id": {"type": "string"},
                "name": {"type": "string"},
            },
            "required": ["household_id", "name"],
        },
    },
    {
        "name": "delete_household",
        "description": (
            "Delete a household. Returns a structured blocking error listing references "
            "(documents, facts, members) if any exist."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "household_id": {"type": "string"},
            },
            "required": ["household_id"],
        },
    },
    # ── Group 1c — Person-Household ──────────────────────────────────────
    {
        "name": "add_person_to_household",
        "description": "Link an existing person to a household. Idempotent.",
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string"},
                "household_id": {"type": "string"},
            },
            "required": ["person_id", "household_id"],
        },
    },
    {
        "name": "remove_person_from_household",
        "description": "Remove a person's membership from a household. Does not delete the person or household.",
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string"},
                "household_id": {"type": "string"},
            },
            "required": ["person_id", "household_id"],
        },
    },
    {
        "name": "list_household_members",
        "description": "Return all person IDs who are members of a household.",
        "input_schema": {
            "type": "object",
            "properties": {
                "household_id": {"type": "string"},
            },
            "required": ["household_id"],
        },
    },
    {
        "name": "list_person_households",
        "description": "Return all household IDs that a person belongs to.",
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string"},
            },
            "required": ["person_id"],
        },
    },
    # ── Group 1d — Relationship ──────────────────────────────────────────
    {
        "name": "create_relationship",
        "description": (
            "Record a directed depth-1 relationship between two persons. "
            "Only 8 atomic types are stored: father, mother, son, daughter, brother, sister, husband, wife. "
            "Both persons must already exist."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "from_person_id": {"type": "string", "description": "The subject person"},
                "to_person_id": {"type": "string", "description": "The object person"},
                "relation_type": {"type": "string", "enum": _RELATION_TYPES},
            },
            "required": ["from_person_id", "to_person_id", "relation_type"],
        },
    },
    {
        "name": "get_relationship",
        "description": "Fetch a single directed relationship between two persons.",
        "input_schema": {
            "type": "object",
            "properties": {
                "from_person_id": {"type": "string"},
                "to_person_id": {"type": "string"},
            },
            "required": ["from_person_id", "to_person_id"],
        },
    },
    {
        "name": "list_relationships",
        "description": (
            "Return all relationships where the given person appears as subject or object. "
            "Returns both directions for the full local graph."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string"},
            },
            "required": ["person_id"],
        },
    },
    {
        "name": "update_relationship",
        "description": "Change the relation_type on an existing directed relationship.",
        "input_schema": {
            "type": "object",
            "properties": {
                "from_person_id": {"type": "string"},
                "to_person_id": {"type": "string"},
                "relation_type": {"type": "string", "enum": _RELATION_TYPES},
            },
            "required": ["from_person_id", "to_person_id", "relation_type"],
        },
    },
    {
        "name": "delete_relationship",
        "description": "Remove a directed relationship between two persons.",
        "input_schema": {
            "type": "object",
            "properties": {
                "from_person_id": {"type": "string"},
                "to_person_id": {"type": "string"},
            },
            "required": ["from_person_id", "to_person_id"],
        },
    },
    {
        "name": "resolve_kinship",
        "description": (
            "Derive the cultural name (e.g. 'bua', 'mama', 'nana') for the relationship "
            "between two persons, potentially across multiple hops. "
            "The server does a BFS traversal using a recursive CTE and looks up the chain in kinship_alias. "
            "Use this instead of making multiple list_relationships calls for multi-hop queries."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "from_person_id": {"type": "string", "description": "The person asking ('who is X to me?')"},
                "to_person_id": {"type": "string", "description": "The person being described"},
            },
            "required": ["from_person_id", "to_person_id"],
        },
    },
    # ── Group 2a — Document ──────────────────────────────────────────────
    {
        "name": "create_document",
        "description": (
            "Persist a new immutable document. Every piece of information — typed by a user, "
            "uploaded as a file, or sent by a polling job — becomes a document first. "
            "Documents are never updated or deleted. "
            "Embedding is generated automatically from content_text. "
            "At least one of person_id or household_id must be provided."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "content_text": {"type": "string", "description": "Full natural language content"},
                "source_type_id": {
                    "type": "string",
                    "description": "UUID from list_source_types (e.g. user_input, gmail_poll)",
                },
                "person_id": {"type": "string", "description": "Owner person (at least one of person/household required)"},
                "household_id": {"type": "string"},
                "supersedes_ids": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "UUIDs of documents this one replaces",
                },
                "files": {
                    "type": "array",
                    "items": {"type": "object"},
                    "description": "Attached file references, each with file_path and file_type",
                },
            },
            "required": ["content_text", "source_type_id"],
        },
    },
    {
        "name": "get_document",
        "description": "Fetch a single document by UUID. Used for provenance lookups.",
        "input_schema": {
            "type": "object",
            "properties": {
                "document_id": {"type": "string"},
            },
            "required": ["document_id"],
        },
    },
    {
        "name": "list_documents",
        "description": "Filter-based listing of documents for a person or household.",
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string"},
                "household_id": {"type": "string"},
                "source_type_id": {"type": "string"},
                "created_after": {"type": "string", "description": "ISO 8601 timestamp"},
                "created_before": {"type": "string"},
                "limit": {"type": "integer", "description": "Default 50"},
                "offset": {"type": "integer", "description": "Default 0"},
            },
            "required": [],
        },
    },
    {
        "name": "search_documents",
        "description": (
            "Vector similarity search over documents. "
            "Used to find documents semantically related to a query — "
            "e.g. identifying which existing documents a new one might supersede."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "query_text": {
                    "type": "string",
                    "description": "Natural language search query",
                },
                "person_id": {"type": "string"},
                "household_id": {"type": "string"},
                "source_type_id": {"type": "string"},
                "limit": {"type": "integer", "description": "Default 10"},
                "similarity_threshold": {
                    "type": "number",
                    "description": "Minimum cosine similarity 0-1. Default 0.5",
                },
            },
            "required": ["query_text"],
        },
    },
    # ── Group 2b — Fact ──────────────────────────────────────────────────
    {
        "name": "create_fact",
        "description": (
            "Persist a single fact operation extracted from a document. "
            "Facts are append-only — never updated or deleted in place. "
            "For operation_type='create': generate a fresh UUID for entity_instance_id. "
            "For 'update' or 'delete': first resolve the entity_instance_id via search_current_facts. "
            "Embedding is generated automatically from the fields."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "document_id": {"type": "string", "description": "Source document UUID (provenance)"},
                "schema_id": {"type": "string", "description": "UUID from entity_type_schema"},
                "entity_instance_id": {
                    "type": "string",
                    "description": (
                        "Stable UUID identifying this logical entity. "
                        "Generate a new UUID for 'create'; reuse the same UUID for 'update'/'delete'."
                    ),
                },
                "operation_type": {
                    "type": "string",
                    "enum": ["create", "update", "delete"],
                },
                "fields": {
                    "type": "object",
                    "description": "JSONB field values. Only changed fields needed for updates.",
                },
            },
            "required": ["document_id", "schema_id", "entity_instance_id", "operation_type", "fields"],
        },
    },
    {
        "name": "get_fact_history",
        "description": (
            "Full operation history for an entity instance in chronological order. "
            "Used for temporal queries ('what was my salary in 2023?') and pipeline debugging."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "entity_instance_id": {"type": "string"},
            },
            "required": ["entity_instance_id"],
        },
    },
    {
        "name": "get_current_fact",
        "description": (
            "Retrieve the merged current state for a single entity instance. "
            "Returns not-found if the entity has been logically deleted."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "entity_instance_id": {"type": "string"},
            },
            "required": ["entity_instance_id"],
        },
    },
    {
        "name": "list_current_facts",
        "description": (
            "Filter-based listing of current entity states. "
            "Uses the current_facts view — only returns active (non-deleted) entities. "
            "Use for structured queries like 'show all my todo items'."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "person_id": {"type": "string"},
                "household_id": {"type": "string"},
                "domain_id": {"type": "string"},
                "entity_type": {"type": "string", "description": "e.g. todo_item, insurance_card"},
                "limit": {"type": "integer", "description": "Default 50"},
                "offset": {"type": "integer", "description": "Default 0"},
            },
            "required": [],
        },
    },
    {
        "name": "search_current_facts",
        "description": (
            "Vector similarity search over current entity states. "
            "PRIMARY tool for resolving entity_instance_id from natural language — "
            "use this when the user refers to an entity by description "
            "(e.g. 'my passport renewal', 'the Aetna policy'). "
            "Returns merged current state with similarity scores. Only active entities returned."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "query_text": {
                    "type": "string",
                    "description": "Natural language description of the entity to find",
                },
                "person_id": {"type": "string"},
                "household_id": {"type": "string"},
                "domain_id": {"type": "string"},
                "entity_type": {"type": "string"},
                "limit": {"type": "integer", "description": "Default 10"},
                "similarity_threshold": {"type": "number", "description": "Default 0.5"},
            },
            "required": ["query_text"],
        },
    },
    # ── Group 3 — Schema Governance ──────────────────────────────────────
    {
        "name": "list_entity_type_schemas",
        "description": (
            "List schema definitions. Call this first to discover what entity types are already known "
            "before deciding whether to create a new one."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "domain_id": {"type": "string"},
                "entity_type": {"type": "string", "description": "Exact match"},
                "active_only": {"type": "boolean", "description": "Default true — excludes superseded versions"},
            },
            "required": [],
        },
    },
    {
        "name": "get_entity_type_schema",
        "description": "Fetch a specific schema version by UUID. Used for provenance.",
        "input_schema": {
            "type": "object",
            "properties": {
                "schema_id": {"type": "string"},
            },
            "required": ["schema_id"],
        },
    },
    {
        "name": "get_current_entity_type_schema",
        "description": (
            "Fetch the latest active schema for a (domain, entity_type) pair. "
            "Use during fact extraction to know which fields to extract and which are mandatory."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "domain_id": {"type": "string"},
                "entity_type": {"type": "string"},
            },
            "required": ["domain_id", "entity_type"],
        },
    },
    {
        "name": "create_entity_type_schema",
        "description": (
            "Create a new active schema for a (domain, entity_type) pair. "
            "Call in the write turn after the user has confirmed the proposed fields. "
            "The schema is immediately active — facts of this type can be created right away."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "domain_id": {"type": "string"},
                "entity_type": {
                    "type": "string",
                    "description": "Snake_case name e.g. blood_pressure_reading, gym_membership",
                },
                "field_definitions": {
                    "type": "array",
                    "items": {"type": "object"},
                    "description": (
                        "Array of field objects each with: "
                        "name (str), type (text|number|date|boolean|file), mandatory (bool), description (str)"
                    ),
                },
                "description": {"type": "string", "description": "Human-readable description of this entity type"},
            },
            "required": ["domain_id", "entity_type", "field_definitions"],
        },
    },
    {
        "name": "update_entity_type_schema",
        "description": (
            "Add or modify fields on an existing entity type schema. "
            "Increments schema_version and activates the new version immediately. "
            "Provide the full field list (all existing fields plus any new ones) — not a diff."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "domain_id": {"type": "string"},
                "entity_type": {"type": "string"},
                "field_definitions": {
                    "type": "array",
                    "items": {"type": "object"},
                    "description": "Full field list for the new version (include all existing fields + changes)",
                },
                "description": {"type": "string"},
            },
            "required": ["domain_id", "entity_type", "field_definitions"],
        },
    },
    {
        "name": "deactivate_entity_type_schema",
        "description": (
            "Mark the current active schema for a (domain, entity_type) as inactive. "
            "Past schema versions and extracted facts are retained."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "domain_id": {"type": "string"},
                "entity_type": {"type": "string"},
            },
            "required": ["domain_id", "entity_type"],
        },
    },
    # ── Group 4 — Reference ──────────────────────────────────────────────
    {
        "name": "list_domains",
        "description": (
            "Return all life domains (health, finance, employment, todo, etc.). "
            "Call this to resolve a domain name to its UUID before calling domain-filtered tools."
        ),
        "input_schema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "list_source_types",
        "description": (
            "Return all registered source types (user_input, file_upload, gmail_poll, plaid_poll, etc.). "
            "Call this to resolve a source type name to its UUID when creating documents."
        ),
        "input_schema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "list_kinship_aliases",
        "description": (
            "Return all cultural kinship name mappings. "
            "Each entry maps a chain of atomic relation types to a cultural name in a given language "
            "(e.g. ['father', 'sister'] → 'bua' in Hindi)."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "language": {"type": "string", "description": "Filter by language e.g. hindi, english"},
            },
            "required": [],
        },
    },
    # ── Group 6 — File Handling ──────────────────────────────────────────
    {
        "name": "save_file",
        "description": (
            "Persist a file to the filesystem and return its path. "
            "The returned file_path is passed to create_document and/or extract_text_from_file."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "content_base64": {"type": "string", "description": "Base64-encoded file content"},
                "filename": {"type": "string", "description": "Original filename including extension"},
                "mime_type": {"type": "string", "description": "MIME type e.g. application/pdf, image/jpeg"},
            },
            "required": ["content_base64", "filename"],
        },
    },
    {
        "name": "extract_text_from_file",
        "description": (
            "Extract plain text from a saved file. "
            "Dispatches to PDF parser, OCR, or plain text reader based on file type. "
            "The extracted text becomes document.content_text and the basis for the embedding."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "Path returned by save_file"},
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "get_file",
        "description": "Retrieve a previously saved file. Returns base64-encoded content.",
        "input_schema": {
            "type": "object",
            "properties": {
                "file_path": {"type": "string"},
            },
            "required": ["file_path"],
        },
    },
    {
        "name": "delete_file",
        "description": (
            "Delete a file from the filesystem. "
            "Checks whether the file_path is still referenced in any document's files array. "
            "If references exist, returns a structured error listing the blocking document IDs."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "file_path": {"type": "string"},
            },
            "required": ["file_path"],
        },
    },
]

assert len(ALL_TOOLS) == 42, f"Expected 42 tools, got {len(ALL_TOOLS)}"
