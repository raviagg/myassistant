import pathlib
import sys
import httpx


def _build_tool_map() -> dict:
    file_path = pathlib.Path(__file__)
    try:
        mcp_path = file_path.parents[3] / "backend" / "mcp_server"
        if not mcp_path.is_dir():
            raise IndexError
    except IndexError:
        mcp_path = file_path.parents[1] / "mcp_server"  # Docker: /app/mcp_server
    if str(mcp_path) not in sys.path:
        sys.path.insert(0, str(mcp_path))

    from tools import (
        persons, households, person_household, relationships,
        documents, facts, schemas, reference, audit, files,
    )

    return {
        "create_person":               persons.create_person,
        "get_person":                  persons.get_person,
        "search_persons":              persons.search_persons,
        "update_person":               persons.update_person,
        "delete_person":               persons.delete_person,
        "create_household":            households.create_household,
        "get_household":               households.get_household,
        "search_households":           households.search_households,
        "update_household":            households.update_household,
        "delete_household":            households.delete_household,
        "add_person_to_household":     person_household.add_person_to_household,
        "remove_person_from_household":person_household.remove_person_from_household,
        "list_household_members":      person_household.list_household_members,
        "list_person_households":      person_household.list_person_households,
        "create_relationship":         relationships.create_relationship,
        "get_relationship":            relationships.get_relationship,
        "list_relationships":          relationships.list_relationships,
        "update_relationship":         relationships.update_relationship,
        "delete_relationship":         relationships.delete_relationship,
        "resolve_kinship":             relationships.resolve_kinship,
        "create_document":             documents.create_document,
        "get_document":                documents.get_document,
        "list_documents":              documents.list_documents,
        "search_documents":            documents.search_documents,
        "create_fact":                 facts.create_fact,
        "get_fact_history":            facts.get_fact_history,
        "get_current_fact":            facts.get_current_fact,
        "list_current_facts":          facts.list_current_facts,
        "search_current_facts":        facts.search_current_facts,
        "list_entity_type_schemas":    schemas.list_entity_type_schemas,
        "get_entity_type_schema":      schemas.get_entity_type_schema,
        "get_current_entity_type_schema": schemas.get_current_entity_type_schema,
        "create_entity_type_schema":   schemas.create_entity_type_schema,
        "update_entity_type_schema":   schemas.update_entity_type_schema,
        "deactivate_entity_type_schema": schemas.deactivate_entity_type_schema,
        "list_domains":                reference.list_domains,
        "list_source_types":           reference.list_source_types,
        "list_kinship_aliases":        reference.list_kinship_aliases,
        "log_interaction":             audit.log_interaction,
        "save_file":                   files.save_file,
        "extract_text_from_file":      files.extract_text_from_file,
        "get_file":                    files.get_file,
        "delete_file":                 files.delete_file,
    }


class LiveExecutor:
    def __init__(self, base_url: str, auth_token: str):
        self._base_url   = base_url
        self._auth_token = auth_token
        self._tool_map   = _build_tool_map()

    def _make_http(self) -> httpx.Client:
        return httpx.Client(
            base_url=self._base_url,
            headers={"Authorization": f"Bearer {self._auth_token}"},
            timeout=30.0,
        )

    def call(self, tool_name: str, tool_input: dict) -> dict:
        fn = self._tool_map.get(tool_name)
        if fn is None:
            return {"error": "unknown_tool", "tool_name": tool_name}
        with self._make_http() as http:
            return fn(http, **tool_input)

    def close(self):
        pass
