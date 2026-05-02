import json
import httpx
from client import _check
from tools.embeddings import embed


def create_fact(
    http: httpx.Client,
    document_id: str,
    schema_id: str,
    entity_instance_id: str,
    operation_type: str,
    fields: dict,
) -> dict:
    body: dict = {
        "documentId": document_id,
        "schemaId": schema_id,
        "entityInstanceId": entity_instance_id,
        "operationType": operation_type,
        "fields": fields,
        "embedding": embed(json.dumps(fields, sort_keys=True)),
    }
    resp = http.post("/api/v1/facts", json=body)
    _check(resp)
    return resp.json()


def get_fact_history(http: httpx.Client, entity_instance_id: str) -> dict:
    resp = http.get(f"/api/v1/facts/{entity_instance_id}/history")
    _check(resp)
    return resp.json()


def get_current_fact(http: httpx.Client, entity_instance_id: str) -> dict:
    resp = http.get(f"/api/v1/facts/{entity_instance_id}/current")
    _check(resp)
    return resp.json()


def list_current_facts(
    http: httpx.Client,
    person_id: str | None = None,
    household_id: str | None = None,
    domain_id: str | None = None,
    entity_type: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    params: dict = {"limit": limit, "offset": offset}
    if person_id is not None:
        params["personId"] = person_id
    if household_id is not None:
        params["householdId"] = household_id
    if domain_id is not None:
        params["domainId"] = domain_id
    if entity_type is not None:
        params["entityType"] = entity_type
    resp = http.get("/api/v1/facts/current", params=params)
    _check(resp)
    return resp.json()


def search_current_facts(
    http: httpx.Client,
    query_text: str,
    person_id: str | None = None,
    household_id: str | None = None,
    domain_id: str | None = None,
    entity_type: str | None = None,
    limit: int = 10,
    similarity_threshold: float = 0.7,
) -> dict:
    body: dict = {
        "embedding": embed(query_text),
        "limit": limit,
        "similarityThreshold": similarity_threshold,
    }
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    if domain_id is not None:
        body["domainId"] = domain_id
    if entity_type is not None:
        body["entityType"] = entity_type
    resp = http.post("/api/v1/facts/search", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="create_fact")
    def _create_tool(
        document_id: str,
        schema_id: str,
        entity_instance_id: str,
        operation_type: str,
        fields: dict,
    ) -> dict:
        """Persist a single fact operation. Embedding is generated automatically from fields. For 'update'/'delete': resolve entity_instance_id first via search_current_facts."""
        return create_fact(http, document_id, schema_id, entity_instance_id, operation_type, fields)

    @mcp.tool(name="get_fact_history")
    def _history_tool(entity_instance_id: str) -> dict:
        """Retrieve the full operation history for a single entity instance."""
        return get_fact_history(http, entity_instance_id)

    @mcp.tool(name="get_current_fact")
    def _current_tool(entity_instance_id: str) -> dict:
        """Retrieve the merged current state for a single entity instance."""
        return get_current_fact(http, entity_instance_id)

    @mcp.tool(name="list_current_facts")
    def _list_tool(
        person_id: str | None = None,
        household_id: str | None = None,
        domain_id: str | None = None,
        entity_type: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict:
        """Filter-based listing of current entity states."""
        return list_current_facts(http, person_id, household_id, domain_id, entity_type, limit, offset)

    @mcp.tool(name="search_current_facts")
    def _search_tool(
        query_text: str,
        person_id: str | None = None,
        household_id: str | None = None,
        domain_id: str | None = None,
        entity_type: str | None = None,
        limit: int = 10,
        similarity_threshold: float = 0.7,
    ) -> dict:
        """Vector similarity search over current entity states using a natural language query. PRIMARY tool for resolving entity_instance_id by description."""
        return search_current_facts(http, query_text, person_id, household_id, domain_id, entity_type, limit, similarity_threshold)
