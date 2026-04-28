import httpx
from client import _check


def create_fact(
    http: httpx.Client,
    document_id: str,
    schema_id: str,
    operation_type: str,
    fields: dict,
    entity_instance_id: str | None = None,
) -> dict:
    """Persist a single fact operation extracted from a document."""
    body: dict = {
        "documentId": document_id,
        "schemaId": schema_id,
        "operationType": operation_type,
        "fields": fields,
    }
    if entity_instance_id is not None:
        body["entityInstanceId"] = entity_instance_id
    resp = http.post("/api/v1/facts", json=body)
    _check(resp)
    return resp.json()


def list_current_facts(
    http: httpx.Client, schema_id: str, limit: int = 50, offset: int = 0
) -> dict:
    """List current entity states filtered by schema."""
    resp = http.get("/api/v1/facts/current",
                    params={"schemaId": schema_id, "limit": limit, "offset": offset})
    _check(resp)
    return resp.json()


def get_current_fact(http: httpx.Client, entity_id: str) -> dict:
    """Get the merged current state for a single entity instance."""
    resp = http.get(f"/api/v1/facts/{entity_id}/current")
    _check(resp)
    return resp.json()


def get_fact_history(http: httpx.Client, entity_id: str) -> dict:
    """Get the full operation history for an entity instance."""
    resp = http.get(f"/api/v1/facts/{entity_id}/history")
    _check(resp)
    return resp.json()


def search_facts(
    http: httpx.Client,
    query: str,
    domain: str | None = None,
    limit: int | None = None,
) -> dict:
    """Semantic search over current fact states."""
    body: dict = {"query": query}
    if domain is not None:
        body["domain"] = domain
    if limit is not None:
        body["limit"] = limit
    resp = http.post("/api/v1/facts/search", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def create_fact_tool(
        document_id: str,
        schema_id: str,
        operation_type: str,
        fields: dict,
        entity_instance_id: str | None = None,
    ) -> dict:
        """Persist a single fact operation extracted from a document."""
        return create_fact(http, document_id, schema_id, operation_type, fields, entity_instance_id)

    @mcp.tool()
    def list_current_facts_tool(schema_id: str, limit: int = 50, offset: int = 0) -> dict:
        """List current entity states filtered by schema."""
        return list_current_facts(http, schema_id, limit, offset)

    @mcp.tool()
    def get_current_fact_tool(entity_id: str) -> dict:
        """Get the merged current state for a single entity instance."""
        return get_current_fact(http, entity_id)

    @mcp.tool()
    def get_fact_history_tool(entity_id: str) -> dict:
        """Get the full operation history for an entity instance."""
        return get_fact_history(http, entity_id)

    @mcp.tool()
    def search_facts_tool(
        query: str, domain: str | None = None, limit: int | None = None
    ) -> dict:
        """Semantic search over current fact states."""
        return search_facts(http, query, domain, limit)
