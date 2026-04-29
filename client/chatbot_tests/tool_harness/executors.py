import pathlib
import sys
import httpx

from .mock_server import MockServer

# ── MockExecutor ─────────────────────────────────────────────────────────────

class MockExecutor:
    """Routes tool calls to static mock responses from mock_server.py."""

    def __init__(self):
        self._server = MockServer()

    def call(self, tool_name: str, tool_input: dict) -> dict:
        return self._server.handle(tool_name, tool_input)


# ── LiveExecutor ─────────────────────────────────────────────────────────────

def _build_dispatch(http: httpx.Client) -> dict:
    """Import mcp_server tool functions and build a name→callable dispatch table."""
    mcp_path = pathlib.Path(__file__).parents[3] / "backend" / "mcp_server"
    if str(mcp_path) not in sys.path:
        sys.path.insert(0, str(mcp_path))

    from tools import (
        persons, households, person_household, relationships,
        documents, facts, schemas, reference, audit, files,
    )

    return {
        # persons
        "create_person":               lambda i: persons.create_person(http, **i),
        "get_person":                  lambda i: persons.get_person(http, **i),
        "search_persons":              lambda i: persons.search_persons(http, **i),
        "update_person":               lambda i: persons.update_person(http, **i),
        "delete_person":               lambda i: persons.delete_person(http, **i),
        # households
        "create_household":            lambda i: households.create_household(http, **i),
        "get_household":               lambda i: households.get_household(http, **i),
        "search_households":           lambda i: households.search_households(http, **i),
        "update_household":            lambda i: households.update_household(http, **i),
        "delete_household":            lambda i: households.delete_household(http, **i),
        # person-household
        "add_person_to_household":     lambda i: person_household.add_person_to_household(http, **i),
        "remove_person_from_household":lambda i: person_household.remove_person_from_household(http, **i),
        "list_household_members":      lambda i: person_household.list_household_members(http, **i),
        "list_person_households":      lambda i: person_household.list_person_households(http, **i),
        # relationships
        "create_relationship":         lambda i: relationships.create_relationship(http, **i),
        "get_relationship":            lambda i: relationships.get_relationship(http, **i),
        "list_relationships":          lambda i: relationships.list_relationships(http, **i),
        "update_relationship":         lambda i: relationships.update_relationship(http, **i),
        "delete_relationship":         lambda i: relationships.delete_relationship(http, **i),
        "resolve_kinship":             lambda i: relationships.resolve_kinship(http, **i),
        # documents
        "create_document":             lambda i: documents.create_document(http, **i),
        "get_document":                lambda i: documents.get_document(http, **i),
        "list_documents":              lambda i: documents.list_documents(http, **i),
        "search_documents":            lambda i: documents.search_documents(http, **i),
        # facts
        "create_fact":                 lambda i: facts.create_fact(http, **i),
        "get_fact_history":            lambda i: facts.get_fact_history(http, **i),
        "get_current_fact":            lambda i: facts.get_current_fact(http, **i),
        "list_current_facts":          lambda i: facts.list_current_facts(http, **i),
        "search_current_facts":        lambda i: facts.search_current_facts(http, **i),
        # schemas
        "list_entity_type_schemas":    lambda i: schemas.list_entity_type_schemas(http, **i),
        "get_entity_type_schema":      lambda i: schemas.get_entity_type_schema(http, **i),
        "get_current_entity_type_schema": lambda i: schemas.get_current_entity_type_schema(http, **i),
        "create_entity_type_schema":   lambda i: schemas.create_entity_type_schema(http, **i),
        "update_entity_type_schema":   lambda i: schemas.update_entity_type_schema(http, **i),
        "deactivate_entity_type_schema": lambda i: schemas.deactivate_entity_type_schema(http, **i),
        # reference
        "list_domains":                lambda i: reference.list_domains(http, **i),
        "list_source_types":           lambda i: reference.list_source_types(http, **i),
        "list_kinship_aliases":        lambda i: reference.list_kinship_aliases(http, **i),
        # audit
        "log_interaction":             lambda i: audit.log_interaction(http, **i),
        # files
        "save_file":                   lambda i: files.save_file(http, **i),
        "extract_text_from_file":      lambda i: files.extract_text_from_file(http, **i),
        "get_file":                    lambda i: files.get_file(http, **i),
        "delete_file":                 lambda i: files.delete_file(http, **i),
    }


class LiveExecutor:
    """Routes tool calls to real mcp_server tool functions via a live http_server."""

    def __init__(self, base_url: str, auth_token: str):
        self._http = httpx.Client(
            base_url=base_url,
            headers={"Authorization": f"Bearer {auth_token}"},
            timeout=30.0,
        )
        self._dispatch = _build_dispatch(self._http)

    def call(self, tool_name: str, tool_input: dict) -> dict:
        fn = self._dispatch.get(tool_name)
        if fn is None:
            return {"error": "unknown_tool", "tool_name": tool_name}
        return fn(tool_input)

    def close(self):
        self._http.close()
