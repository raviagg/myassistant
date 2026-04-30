"""
Static mock responses for all 43 tools.
Realistic enough for the agentic loop to continue — Claude can chain
list_domains → get_current_entity_type_schema → create_fact etc.
using the UUIDs returned here.
"""
from datetime import datetime, timezone

# ── Fixed mock UUIDs ────────────────────────────────────────────────────
PERSON_ID   = "aaaaaaaa-0000-0000-0000-000000000001"  # Ravi Aggarwal
PERSON_ID_2 = "aaaaaaaa-0000-0000-0000-000000000002"  # Priya Aggarwal
PERSON_ID_3 = "aaaaaaaa-0000-0000-0000-000000000003"  # Vijay Aggarwal (Ravi's father)
HOUSEHOLD_ID = "bbbbbbbb-0000-0000-0000-000000000001"

DOMAIN_HEALTH_ID      = "cccc0001-0000-0000-0000-000000000000"
DOMAIN_FINANCE_ID     = "cccc0002-0000-0000-0000-000000000000"
DOMAIN_EMPLOYMENT_ID  = "cccc0003-0000-0000-0000-000000000000"
DOMAIN_PERSONAL_ID    = "cccc0004-0000-0000-0000-000000000000"
DOMAIN_TODO_ID        = "cccc0005-0000-0000-0000-000000000000"
DOMAIN_HOUSEHOLD_ID   = "cccc0006-0000-0000-0000-000000000000"
DOMAIN_NEWS_ID        = "cccc0007-0000-0000-0000-000000000000"

SOURCE_USER_INPUT_ID   = "dddd0001-0000-0000-0000-000000000000"
SOURCE_FILE_UPLOAD_ID  = "dddd0002-0000-0000-0000-000000000000"
SOURCE_AI_EXTRACTED_ID = "dddd0003-0000-0000-0000-000000000000"
SOURCE_PLAID_ID        = "dddd0004-0000-0000-0000-000000000000"
SOURCE_GMAIL_ID        = "dddd0005-0000-0000-0000-000000000000"

SCHEMA_TODO_ID        = "eeee0001-0000-0000-0000-000000000000"
SCHEMA_INSURANCE_ID   = "eeee0002-0000-0000-0000-000000000000"
SCHEMA_JOB_ID         = "eeee0003-0000-0000-0000-000000000000"
SCHEMA_PAYSLIP_ID     = "eeee0004-0000-0000-0000-000000000000"
NEW_SCHEMA_ID         = "eeee0099-0000-0000-0000-000000000000"

DOCUMENT_ID           = "ffff0001-0000-0000-0000-000000000000"
NEW_DOCUMENT_ID       = "ffff0099-0000-0000-0000-000000000000"
FACT_ID               = "11110001-0000-0000-0000-000000000000"
ENTITY_INSTANCE_ID    = "22220001-0000-0000-0000-000000000000"
AUDIT_ID              = "99990001-0000-0000-0000-000000000000"

NOW = datetime.now(timezone.utc).isoformat()


def _person(pid=PERSON_ID, name="Ravi Aggarwal", gender="male"):
    return {
        "id": pid,
        "full_name": name,
        "preferred_name": "Ravi" if name == "Ravi Aggarwal" else None,
        "gender": gender,
        "date_of_birth": "1985-03-22",
        "user_identifier": "raaggarw@adobe.com",
        "created_at": NOW,
        "updated_at": NOW,
    }


def _household():
    return {"id": HOUSEHOLD_ID, "name": "Aggarwal Family", "created_at": NOW, "updated_at": NOW}


def _domains():
    return [
        {"id": DOMAIN_HEALTH_ID,     "name": "health"},
        {"id": DOMAIN_FINANCE_ID,    "name": "finance"},
        {"id": DOMAIN_EMPLOYMENT_ID, "name": "employment"},
        {"id": DOMAIN_PERSONAL_ID,   "name": "personal_details"},
        {"id": DOMAIN_TODO_ID,       "name": "todo"},
        {"id": DOMAIN_HOUSEHOLD_ID,  "name": "household"},
        {"id": DOMAIN_NEWS_ID,       "name": "news_preferences"},
    ]


def _source_types():
    return [
        {"id": SOURCE_USER_INPUT_ID,   "name": "user_input"},
        {"id": SOURCE_FILE_UPLOAD_ID,  "name": "file_upload"},
        {"id": SOURCE_AI_EXTRACTED_ID, "name": "ai_extracted"},
        {"id": SOURCE_PLAID_ID,        "name": "plaid_poll"},
        {"id": SOURCE_GMAIL_ID,        "name": "gmail_poll"},
    ]


def _todo_schema(sid=SCHEMA_TODO_ID, version=1, active=True):
    return {
        "id": sid,
        "domain": "todo",
        "domain_id": DOMAIN_TODO_ID,
        "entity_type": "todo_item",
        "schema_version": version,
        "description": "A task or reminder to be completed",
        "field_definitions": [
            {"name": "title",        "type": "text",    "mandatory": True,  "description": "Short description of what needs to be done"},
            {"name": "status",       "type": "text",    "mandatory": True,  "description": "open | in_progress | done"},
            {"name": "due_date",     "type": "date",    "mandatory": False, "description": "ISO date"},
            {"name": "priority",     "type": "text",    "mandatory": False, "description": "low | medium | high"},
            {"name": "is_recurring", "type": "boolean", "mandatory": False},
            {"name": "recurrence",   "type": "text",    "mandatory": False},
        ],
        "mandatory_fields": ["title", "status"],
        "extraction_prompt": "Extract todo items...",
        "is_active": active,
        "created_at": NOW,
    }


def _insurance_schema():
    return {
        "id": SCHEMA_INSURANCE_ID,
        "domain": "health",
        "domain_id": DOMAIN_HEALTH_ID,
        "entity_type": "insurance_card",
        "schema_version": 1,
        "description": "Health insurance card details",
        "field_definitions": [
            {"name": "provider",    "type": "text",   "mandatory": True,  "description": "Insurance provider name"},
            {"name": "plan_name",   "type": "text",   "mandatory": False},
            {"name": "member_id",   "type": "text",   "mandatory": False},
            {"name": "group_number","type": "text",   "mandatory": False},
            {"name": "deductible",  "type": "number", "mandatory": False},
            {"name": "premium",     "type": "number", "mandatory": False},
            {"name": "valid_from",  "type": "date",   "mandatory": False},
            {"name": "valid_to",    "type": "date",   "mandatory": False},
        ],
        "mandatory_fields": ["provider"],
        "is_active": True,
        "created_at": NOW,
    }


def _job_schema():
    return {
        "id": SCHEMA_JOB_ID,
        "domain": "employment",
        "domain_id": DOMAIN_EMPLOYMENT_ID,
        "entity_type": "job",
        "schema_version": 1,
        "description": "Employment record — employer, role, salary, dates",
        "field_definitions": [
            {"name": "employer",    "type": "text",   "mandatory": True},
            {"name": "role",        "type": "text",   "mandatory": True},
            {"name": "salary",      "type": "number", "mandatory": False},
            {"name": "start_date",  "type": "date",   "mandatory": False},
            {"name": "end_date",    "type": "date",   "mandatory": False},
        ],
        "mandatory_fields": ["employer", "role"],
        "is_active": True,
        "created_at": NOW,
    }


def _payslip_schema():
    return {
        "id": SCHEMA_PAYSLIP_ID,
        "domain": "finance",
        "domain_id": DOMAIN_FINANCE_ID,
        "entity_type": "payslip",
        "schema_version": 1,
        "description": "Payslip — pay period, gross, deductions, net pay",
        "field_definitions": [
            {"name": "employer",   "type": "text",   "mandatory": True},
            {"name": "pay_period", "type": "text",   "mandatory": True},
            {"name": "gross",      "type": "number", "mandatory": False},
            {"name": "tax",        "type": "number", "mandatory": False},
            {"name": "net",        "type": "number", "mandatory": False},
        ],
        "mandatory_fields": ["employer", "pay_period"],
        "is_active": True,
        "created_at": NOW,
    }


def _document(did=DOCUMENT_ID, content="", pid=PERSON_ID):
    return {
        "id": did,
        "person_id": pid,
        "household_id": None,
        "content_text": content,
        "source_type": "user_input",
        "files": [],
        "supersedes_ids": [],
        "created_at": NOW,
    }


def _current_fact(eid=ENTITY_INSTANCE_ID, sid=SCHEMA_TODO_ID, fields=None):
    return {
        "entity_instance_id": eid,
        "schema_id": sid,
        "document_id": DOCUMENT_ID,
        "current_fields": fields or {
            "title": "renew passport",
            "status": "open",
            "due_date": "2026-06-30",
        },
        "created_at": NOW,
        "updated_at": NOW,
    }


class MockServer:
    """Dispatch tool calls to static mock handlers.

    overrides: maps tool_name → fixed return value for the current scenario.
    Used to return empty search results for create-new flows where the default
    handler returns a high-similarity match that would confuse Claude.
    """

    def __init__(self, overrides: dict | None = None):
        self._overrides = overrides or {}

    def handle(self, tool_name: str, tool_input: dict) -> dict:
        if tool_name in self._overrides:
            return self._overrides[tool_name]
        handler = getattr(self, f"_h_{tool_name}", None)
        if handler is None:
            return {"error": "unknown_tool", "tool_name": tool_name}
        return handler(tool_input)

    # ── 1a Person ────────────────────────────────────────────────────────

    def _h_create_person(self, i):
        return _person(name=i.get("full_name", "Unknown"), gender=i.get("gender", "male"))

    def _h_get_person(self, i):
        pid = i.get("person_id")
        if pid == PERSON_ID_2:
            return _person(PERSON_ID_2, "Priya Aggarwal", "female")
        if pid == PERSON_ID_3:
            return {
                "id": PERSON_ID_3, "full_name": "Vijay Aggarwal", "preferred_name": "Dad",
                "gender": "male", "date_of_birth": "1955-07-10", "user_identifier": None,
                "created_at": NOW, "updated_at": NOW,
            }
        return _person()  # default: Ravi

    def _h_search_persons(self, i):
        name = (i.get("name") or "").lower()
        if "priya" in name:
            return [_person(PERSON_ID_2, "Priya Aggarwal", "female")]
        return [_person(), _person(PERSON_ID_2, "Priya Aggarwal", "female")]

    def _h_update_person(self, i):
        p = _person()
        p.update({k: v for k, v in i.items() if k != "person_id"})
        return p

    def _h_delete_person(self, i):
        return {"status": "deleted", "person_id": i["person_id"]}

    # ── 1b Household ─────────────────────────────────────────────────────

    def _h_create_household(self, i):
        return {**_household(), "name": i.get("name", "New Household")}

    def _h_get_household(self, i):
        return _household()

    def _h_search_households(self, i):
        return [_household()]

    def _h_update_household(self, i):
        return {**_household(), "name": i.get("name")}

    def _h_delete_household(self, i):
        return {"status": "deleted", "household_id": i["household_id"]}

    # ── 1c Person-Household ──────────────────────────────────────────────

    def _h_add_person_to_household(self, i):
        return {}

    def _h_remove_person_from_household(self, i):
        return {}

    def _h_list_household_members(self, i):
        return [PERSON_ID, PERSON_ID_2]

    def _h_list_person_households(self, i):
        return [HOUSEHOLD_ID]

    # ── 1d Relationship ──────────────────────────────────────────────────

    def _h_create_relationship(self, i):
        return {
            "from_person_id": i["from_person_id"],
            "to_person_id": i["to_person_id"],
            "relation_type": i["relation_type"],
            "created_at": NOW,
        }

    def _h_get_relationship(self, i):
        return {
            "from_person_id": i["from_person_id"],
            "to_person_id": i["to_person_id"],
            "relation_type": "wife",
            "created_at": NOW,
        }

    def _h_list_relationships(self, i):
        return [
            {"from_person_id": PERSON_ID,   "to_person_id": PERSON_ID_2, "relation_type": "wife"},
            {"from_person_id": PERSON_ID_2, "to_person_id": PERSON_ID,   "relation_type": "husband"},
        ]

    def _h_update_relationship(self, i):
        return {
            "from_person_id": i["from_person_id"],
            "to_person_id": i["to_person_id"],
            "relation_type": i["relation_type"],
            "created_at": NOW,
        }

    def _h_delete_relationship(self, i):
        return {}

    def _h_resolve_kinship(self, i):
        return {"chain": ["father", "sister"], "alias": "bua", "language": "hindi"}

    # ── 2a Document ──────────────────────────────────────────────────────

    def _h_create_document(self, i):
        return _document(
            did=NEW_DOCUMENT_ID,
            content=i.get("content_text", ""),
            pid=i.get("person_id", PERSON_ID),
        )

    def _h_get_document(self, i):
        return _document(did=i["document_id"])

    def _h_list_documents(self, i):
        return [_document()]

    def _h_search_documents(self, i):
        doc = _document()
        doc["similarity_score"] = 0.87
        return [doc]

    # ── 2b Fact ──────────────────────────────────────────────────────────

    def _h_create_fact(self, i):
        return {
            "id": FACT_ID,
            "document_id": i["document_id"],
            "schema_id": i["schema_id"],
            "entity_instance_id": i["entity_instance_id"],
            "operation_type": i["operation_type"],
            "fields": i["fields"],
            "created_at": NOW,
        }

    def _h_get_fact_history(self, i):
        eid = i["entity_instance_id"]
        return [
            {
                "id": FACT_ID,
                "entity_instance_id": eid,
                "operation_type": "create",
                "fields": {"title": "renew passport", "status": "open", "due_date": "2026-06-30"},
                "created_at": NOW,
            },
            {
                "id": "11110002-0000-0000-0000-000000000000",
                "entity_instance_id": eid,
                "operation_type": "update",
                "fields": {"status": "in_progress"},
                "created_at": NOW,
            },
        ]

    def _h_get_current_fact(self, i):
        return _current_fact(eid=i["entity_instance_id"])

    def _h_list_current_facts(self, i):
        entity_type = i.get("entity_type", "todo_item")
        if entity_type == "insurance_card":
            return [_current_fact(
                sid=SCHEMA_INSURANCE_ID,
                fields={"provider": "Aetna", "plan_name": "BlueShield PPO 500", "deductible": 1500},
            )]
        return [
            _current_fact(fields={"title": "renew passport", "status": "open", "due_date": "2026-06-30"}),
            _current_fact(
                eid="22220002-0000-0000-0000-000000000000",
                fields={"title": "book dentist appointment", "status": "open"},
            ),
        ]

    def _h_search_current_facts(self, i):
        cf = _current_fact()
        cf["similarity_score"] = 0.91
        return [cf]

    # ── 3 Schema Governance ──────────────────────────────────────────────

    def _h_list_entity_type_schemas(self, i):
        # Return summaries only — no field_definitions or extraction_prompt.
        # Claude must call get_current_entity_type_schema for the full field list.
        _summary = lambda s: {k: v for k, v in s.items()
                              if k not in ("field_definitions", "extraction_prompt")}
        domain = i.get("domain_id")
        if domain == DOMAIN_TODO_ID:
            return [_summary(_todo_schema())]
        if domain == DOMAIN_HEALTH_ID:
            return [_summary(_insurance_schema())]
        if domain == DOMAIN_EMPLOYMENT_ID:
            return [_summary(_job_schema())]
        if domain == DOMAIN_FINANCE_ID:
            return [_summary(_payslip_schema())]
        return [_summary(s) for s in [_todo_schema(), _insurance_schema(), _job_schema(), _payslip_schema()]]

    def _h_get_entity_type_schema(self, i):
        if i.get("schema_id") == SCHEMA_INSURANCE_ID:
            return _insurance_schema()
        return _todo_schema(sid=i["schema_id"])

    def _h_get_current_entity_type_schema(self, i):
        et = i.get("entity_type", "")
        if et == "insurance_card":
            return _insurance_schema()
        if et == "todo_item":
            return _todo_schema()
        if et == "job":
            return _job_schema()
        if et == "payslip":
            return _payslip_schema()
        return {"error": "not_found", "entity_type": et}

    def _h_create_entity_type_schema(self, i):
        return {
            "id": NEW_SCHEMA_ID,
            "domain_id": i["domain_id"],
            "entity_type": i["entity_type"],
            "schema_version": 1,
            "description": i.get("description", ""),
            "field_definitions": i["field_definitions"],
            "mandatory_fields": [f["name"] for f in i["field_definitions"] if f.get("mandatory")],
            "is_active": True,
            "created_at": NOW,
        }

    def _h_update_entity_type_schema(self, i):
        base = _todo_schema()
        return {
            **base,
            "id": NEW_SCHEMA_ID,
            "schema_version": base["schema_version"] + 1,
            "field_definitions": i.get("field_definitions", base["field_definitions"]),
            "is_active": True,
        }

    def _h_deactivate_entity_type_schema(self, i):
        return {}

    # ── 4 Reference ──────────────────────────────────────────────────────

    def _h_list_domains(self, i):
        return _domains()

    def _h_list_source_types(self, i):
        return _source_types()

    def _h_list_kinship_aliases(self, i):
        aliases = [
            {"chain": ["father", "sister"], "alias": "bua",  "language": "hindi"},
            {"chain": ["mother", "brother"],"alias": "mama", "language": "hindi"},
            {"chain": ["father", "father"], "alias": "dada", "language": "hindi"},
            {"chain": ["mother", "father"], "alias": "nana", "language": "hindi"},
            {"chain": ["father", "mother"], "alias": "dadi", "language": "hindi"},
            {"chain": ["mother", "mother"], "alias": "nani", "language": "hindi"},
        ]
        lang = i.get("language")
        return [a for a in aliases if a["language"] == lang] if lang else aliases

    # ── 5 Audit ──────────────────────────────────────────────────────────

    def _h_log_interaction(self, i):
        return {
            "id": AUDIT_ID,
            "person_id": i.get("person_id"),
            "job_type": i.get("job_type"),
            "message_text": i["message_text"],
            "response_text": i["response_text"],
            "status": i["status"],
            "created_at": NOW,
        }

    # ── 6 File Handling ──────────────────────────────────────────────────

    def _h_save_file(self, i):
        return {"file_path": f"/data/files/{PERSON_ID}/{i.get('filename', 'file.bin')}"}

    def _h_extract_text_from_file(self, i):
        return {
            "text": (
                "Provider: Aetna, Plan: BlueShield PPO 500, "
                "Member ID: XYZ123, Deductible: $1500, "
                "Premium: $420/month, Valid: 2026-01-01 to 2026-12-31"
            ),
            "extraction_method": "pdf_parser",
        }

    def _h_get_file(self, i):
        fname = i["file_path"].split("/")[-1]
        return {"content_base64": "JVBERi0xLjQ=", "mime_type": "application/pdf", "filename": fname}

    def _h_delete_file(self, i):
        return {"status": "deleted", "file_path": i["file_path"]}
