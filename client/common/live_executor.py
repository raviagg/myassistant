import pathlib
import sys

import httpx


def _build_tool_map() -> dict:
    """Import mcp_server tool functions and return a name→callable mapping.
    Functions are NOT bound to an http client — call as fn(http, **kwargs).
    """
    mcp_path = pathlib.Path(__file__).parents[2] / "backend" / "mcp_server"
    if str(mcp_path) not in sys.path:
        sys.path.insert(0, str(mcp_path))

    from tools import (
        persons, households, person_household, relationships,
        documents, facts, schemas, reference, audit, files,
    )

    return {
        # persons
        "create_person":               persons.create_person,
        "get_person":                  persons.get_person,
        "search_persons":              persons.search_persons,
        "update_person":               persons.update_person,
        "delete_person":               persons.delete_person,
        # households
        "create_household":            households.create_household,
        "get_household":               households.get_household,
        "search_households":           households.search_households,
        "update_household":            households.update_household,
        "delete_household":            households.delete_household,
        # person-household
        "add_person_to_household":     person_household.add_person_to_household,
        "remove_person_from_household":person_household.remove_person_from_household,
        "list_household_members":      person_household.list_household_members,
        "list_person_households":      person_household.list_person_households,
        # relationships
        "create_relationship":         relationships.create_relationship,
        "get_relationship":            relationships.get_relationship,
        "list_relationships":          relationships.list_relationships,
        "update_relationship":         relationships.update_relationship,
        "delete_relationship":         relationships.delete_relationship,
        "resolve_kinship":             relationships.resolve_kinship,
        # documents
        "create_document":             documents.create_document,
        "get_document":                documents.get_document,
        "list_documents":              documents.list_documents,
        "search_documents":            documents.search_documents,
        # facts
        "create_fact":                 facts.create_fact,
        "get_fact_history":            facts.get_fact_history,
        "get_current_fact":            facts.get_current_fact,
        "list_current_facts":          facts.list_current_facts,
        "search_current_facts":        facts.search_current_facts,
        # schemas
        "list_entity_type_schemas":    schemas.list_entity_type_schemas,
        "get_entity_type_schema":      schemas.get_entity_type_schema,
        "get_current_entity_type_schema": schemas.get_current_entity_type_schema,
        "create_entity_type_schema":   schemas.create_entity_type_schema,
        "update_entity_type_schema":   schemas.update_entity_type_schema,
        "deactivate_entity_type_schema": schemas.deactivate_entity_type_schema,
        # reference
        "list_domains":                reference.list_domains,
        "list_source_types":           reference.list_source_types,
        "list_kinship_aliases":        reference.list_kinship_aliases,
        # audit
        "log_interaction":             audit.log_interaction,
        # files
        "save_file":                   files.save_file,
        "extract_text_from_file":      files.extract_text_from_file,
        "get_file":                    files.get_file,
        "delete_file":                 files.delete_file,
    }


class LiveExecutor:
    """Routes tool calls to real mcp_server tool functions via a live http_server.

    A fresh httpx.Client is opened and closed for each call, avoiding any
    stale keep-alive connection issues after periods of idle time.
    """

    def __init__(self, base_url: str, auth_token: str):
        self._base_url   = base_url
        self._auth_token = auth_token
        self._tool_map   = _build_tool_map()

    def _make_http(self) -> httpx.Client:
        def _on_request(req: httpx.Request) -> None:
            print(f"[http] → {req.method} {req.url}", flush=True)

        def _on_response(resp: httpx.Response) -> None:
            cl  = resp.headers.get("content-length", "NONE")
            te  = resp.headers.get("transfer-encoding", "NONE")
            con = resp.headers.get("connection", "NONE")
            print(f"[http] ← {resp.status_code}  content-length={cl}  transfer-encoding={te}  connection={con}", flush=True)

        return httpx.Client(
            base_url=self._base_url,
            headers={"Authorization": f"Bearer {self._auth_token}"},
            timeout=30.0,
            event_hooks={"request": [_on_request], "response": [_on_response]},
        )

    def call(self, tool_name: str, tool_input: dict) -> dict:
        fn = self._tool_map.get(tool_name)
        if fn is None:
            return {"error": "unknown_tool", "tool_name": tool_name}
        with self._make_http() as http:
            return fn(http, **tool_input)

    def close(self):
        pass  # no persistent client to close
