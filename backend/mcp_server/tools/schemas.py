import httpx
from client import _check


def list_entity_type_schemas(
    http: httpx.Client,
    domain_id: str | None = None,
    entity_type: str | None = None,
    active_only: bool = True,
) -> dict:
    params: dict = {"activeOnly": active_only}
    if domain_id is not None:
        params["domainId"] = domain_id
    if entity_type is not None:
        params["entityType"] = entity_type
    resp = http.get("/api/v1/schemas", params=params)
    _check(resp)
    return resp.json()


def get_entity_type_schema(http: httpx.Client, schema_id: str) -> dict:
    resp = http.get(f"/api/v1/schemas/{schema_id}")
    _check(resp)
    return resp.json()


def get_current_entity_type_schema(
    http: httpx.Client, domain_id: str, entity_type: str
) -> dict:
    resp = http.get("/api/v1/schemas/current",
                    params={"domainId": domain_id, "entityType": entity_type})
    _check(resp)
    return resp.json()


def create_entity_type_schema(
    http: httpx.Client,
    domain_id: str,
    entity_type: str,
    field_definitions: list,
    description: str | None = None,
) -> dict:
    body: dict = {
        "domainId": domain_id,
        "entityType": entity_type,
        "fieldDefinitions": field_definitions,
    }
    if description is not None:
        body["description"] = description
    resp = http.post("/api/v1/schemas", json=body)
    _check(resp)
    return resp.json()


def update_entity_type_schema(
    http: httpx.Client,
    domain_id: str,
    entity_type: str,
    field_definitions: list,
    description: str | None = None,
) -> dict:
    body: dict = {"fieldDefinitions": field_definitions}
    if description is not None:
        body["description"] = description
    resp = http.post(f"/api/v1/schemas/{domain_id}/{entity_type}/versions", json=body)
    _check(resp)
    return resp.json()


def deactivate_entity_type_schema(
    http: httpx.Client, domain_id: str, entity_type: str
) -> dict:
    resp = http.delete(f"/api/v1/schemas/{domain_id}/{entity_type}/active")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="list_entity_type_schemas")
    def _list_tool(
        domain_id: str | None = None,
        entity_type: str | None = None,
        active_only: bool = True,
    ) -> dict:
        """List schema definitions, optionally filtered by domain or entity type."""
        return list_entity_type_schemas(http, domain_id, entity_type, active_only)

    @mcp.tool(name="get_entity_type_schema")
    def _get_tool(schema_id: str) -> dict:
        """Fetch a specific schema version by UUID."""
        return get_entity_type_schema(http, schema_id)

    @mcp.tool(name="get_current_entity_type_schema")
    def _get_current_tool(domain_id: str, entity_type: str) -> dict:
        """Fetch the latest active schema for a (domain_id, entity_type) pair."""
        return get_current_entity_type_schema(http, domain_id, entity_type)

    @mcp.tool(name="create_entity_type_schema")
    def _create_tool(
        domain_id: str,
        entity_type: str,
        field_definitions: list,
        description: str | None = None,
    ) -> dict:
        """Create a new schema definition for a (domain_id, entity_type) pair that does not yet exist."""
        return create_entity_type_schema(http, domain_id, entity_type, field_definitions, description)

    @mcp.tool(name="update_entity_type_schema")
    def _update_tool(
        domain_id: str,
        entity_type: str,
        field_definitions: list,
        description: str | None = None,
    ) -> dict:
        """Create a new version of an existing active schema. Provide the full field list for the new version."""
        return update_entity_type_schema(http, domain_id, entity_type, field_definitions, description)

    @mcp.tool(name="deactivate_entity_type_schema")
    def _deactivate_tool(domain_id: str, entity_type: str) -> dict:
        """Mark the current active schema for a (domain_id, entity_type) pair as inactive."""
        return deactivate_entity_type_schema(http, domain_id, entity_type)
