import httpx
from client import _check


def query_unified_schema(
    http: httpx.Client,
    unified_schema_id: str,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    """Query rows from all sources contributing to a unified schema.
    Returns rows tagged with source_connection_id and source_type.
    Use list_unified_schemas first to discover available unified_schema_ids.
    """
    params = {"limit": limit, "offset": offset}
    resp = http.get(f"/api/v1/unified-schemas/{unified_schema_id}/data", params=params)
    _check(resp)
    return resp.json()


def list_unified_schemas(
    http: httpx.Client,
    person_id: str | None = None,
    household_id: str | None = None,
) -> dict:
    """List all unified schemas for a person or household."""
    params: dict = {}
    if person_id:
        params["personId"] = person_id
    if household_id:
        params["householdId"] = household_id
    resp = http.get("/api/v1/unified-schemas", params=params)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def list_unified_schemas_tool(
        person_id: str | None = None,
        household_id: str | None = None,
    ) -> dict:
        """List unified schemas for a person or household.
        Returns schemas with their field definitions and source mappings.
        """
        return list_unified_schemas(http, person_id=person_id, household_id=household_id)

    @mcp.tool()
    def query_unified_schema_tool(
        unified_schema_id: str,
        limit: int = 50,
        offset: int = 0,
    ) -> dict:
        """Query data rows from a unified schema — returns rows from all contributing sources
        (Plaid, bulk-file, chatbot) mapped to unified field names. Each row includes
        source_connection_id and source_type for provenance. Use list_unified_schemas_tool first
        to discover available schema IDs.
        """
        return query_unified_schema(http, unified_schema_id, limit=limit, offset=offset)
