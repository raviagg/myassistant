import httpx
from client import _check


def list_schemas(http: httpx.Client) -> dict:
    """List all schema definitions."""
    resp = http.get("/api/v1/schemas")
    _check(resp)
    return resp.json()


def create_schema(
    http: httpx.Client,
    domain: str,
    entity_type: str,
    description: str,
    field_definitions: list,
    extraction_prompt: str,
    change_description: str | None = None,
) -> dict:
    """Create a new entity type schema."""
    body: dict = {
        "domain": domain,
        "entityType": entity_type,
        "description": description,
        "fieldDefinitions": field_definitions,
        "extractionPrompt": extraction_prompt,
    }
    if change_description is not None:
        body["changeDescription"] = change_description
    resp = http.post("/api/v1/schemas", json=body)
    _check(resp)
    return resp.json()


def get_current_schemas(http: httpx.Client) -> dict:
    """List only the currently active schema per entity type."""
    resp = http.get("/api/v1/schemas/current")
    _check(resp)
    return resp.json()


def get_schema(http: httpx.Client, schema_id: str) -> dict:
    """Fetch a specific schema version by ID."""
    resp = http.get(f"/api/v1/schemas/{schema_id}")
    _check(resp)
    return resp.json()


def add_schema_version(
    http: httpx.Client,
    domain: str,
    entity_type: str,
    description: str,
    field_definitions: list,
    extraction_prompt: str,
    change_description: str | None = None,
) -> dict:
    """Create a new version of an existing schema."""
    body: dict = {
        "domain": domain,
        "entityType": entity_type,
        "description": description,
        "fieldDefinitions": field_definitions,
        "extractionPrompt": extraction_prompt,
    }
    if change_description is not None:
        body["changeDescription"] = change_description
    resp = http.post(f"/api/v1/schemas/{domain}/{entity_type}/versions", json=body)
    _check(resp)
    return resp.json()


def deactivate_schema(http: httpx.Client, domain: str, entity_type: str) -> dict:
    """Mark the active schema for a domain/entity_type as inactive."""
    resp = http.delete(f"/api/v1/schemas/{domain}/{entity_type}/active")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def list_schemas_tool() -> dict:
        """List all schema definitions."""
        return list_schemas(http)

    @mcp.tool()
    def create_schema_tool(
        domain: str, entity_type: str, description: str,
        field_definitions: list, extraction_prompt: str,
        change_description: str | None = None,
    ) -> dict:
        """Create a new entity type schema."""
        return create_schema(http, domain, entity_type, description, field_definitions, extraction_prompt, change_description)

    @mcp.tool()
    def get_current_schemas_tool() -> dict:
        """List only the currently active schema per entity type."""
        return get_current_schemas(http)

    @mcp.tool()
    def get_schema_tool(schema_id: str) -> dict:
        """Fetch a specific schema version by ID."""
        return get_schema(http, schema_id)

    @mcp.tool()
    def add_schema_version_tool(
        domain: str, entity_type: str, description: str,
        field_definitions: list, extraction_prompt: str,
        change_description: str | None = None,
    ) -> dict:
        """Create a new version of an existing schema."""
        return add_schema_version(http, domain, entity_type, description, field_definitions, extraction_prompt, change_description)

    @mcp.tool()
    def deactivate_schema_tool(domain: str, entity_type: str) -> dict:
        """Mark the active schema for a domain/entity_type as inactive."""
        return deactivate_schema(http, domain, entity_type)
