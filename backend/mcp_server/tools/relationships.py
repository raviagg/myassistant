import httpx
from client import _check


def create_relationship(
    http: httpx.Client, from_person_id: str, to_person_id: str, relation_type: str
) -> dict:
    """Record a directed relationship between two persons."""
    resp = http.post("/api/v1/relationships", json={
        "fromPersonId": from_person_id,
        "toPersonId": to_person_id,
        "relationType": relation_type,
    })
    _check(resp)
    return resp.json()


def list_relationships(http: httpx.Client, person_id: str) -> dict:
    """List all relationships where the person appears as subject or object."""
    resp = http.get("/api/v1/relationships", params={"person_id": person_id})
    _check(resp)
    return resp.json()


def get_relationship(http: httpx.Client, from_person_id: str, to_person_id: str) -> dict:
    """Fetch a single directed relationship."""
    resp = http.get(f"/api/v1/relationships/{from_person_id}/{to_person_id}")
    _check(resp)
    return resp.json()


def update_relationship(
    http: httpx.Client, from_person_id: str, to_person_id: str, relation_type: str
) -> dict:
    """Change the relation type on an existing relationship."""
    resp = http.patch(f"/api/v1/relationships/{from_person_id}/{to_person_id}",
                      json={"relationType": relation_type})
    _check(resp)
    return resp.json()


def delete_relationship(http: httpx.Client, from_person_id: str, to_person_id: str) -> dict:
    """Delete a directed relationship."""
    resp = http.delete(f"/api/v1/relationships/{from_person_id}/{to_person_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def resolve_kinship(
    http: httpx.Client, from_person_id: str, to_person_id: str, language: str = "english"
) -> dict:
    """Derive the cultural kinship name between two persons."""
    resp = http.get(f"/api/v1/relationships/{from_person_id}/{to_person_id}/kinship",
                    params={"language": language})
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def create_relationship_tool(from_person_id: str, to_person_id: str, relation_type: str) -> dict:
        """Record a directed relationship between two persons."""
        return create_relationship(http, from_person_id, to_person_id, relation_type)

    @mcp.tool()
    def list_relationships_tool(person_id: str) -> dict:
        """List all relationships for a person."""
        return list_relationships(http, person_id)

    @mcp.tool()
    def get_relationship_tool(from_person_id: str, to_person_id: str) -> dict:
        """Fetch a single directed relationship."""
        return get_relationship(http, from_person_id, to_person_id)

    @mcp.tool()
    def update_relationship_tool(from_person_id: str, to_person_id: str, relation_type: str) -> dict:
        """Change the relation type on an existing relationship."""
        return update_relationship(http, from_person_id, to_person_id, relation_type)

    @mcp.tool()
    def delete_relationship_tool(from_person_id: str, to_person_id: str) -> dict:
        """Delete a directed relationship."""
        return delete_relationship(http, from_person_id, to_person_id)

    @mcp.tool()
    def resolve_kinship_tool(
        from_person_id: str, to_person_id: str, language: str = "english"
    ) -> dict:
        """Derive the cultural kinship name between two persons."""
        return resolve_kinship(http, from_person_id, to_person_id, language)
