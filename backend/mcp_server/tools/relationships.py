import httpx
from client import _check


def create_relationship(
    http: httpx.Client, from_person_id: str, to_person_id: str, relation_type: str
) -> dict:
    resp = http.post("/api/v1/relationships", json={
        "fromPersonId": from_person_id,
        "toPersonId": to_person_id,
        "relationType": relation_type,
    })
    _check(resp)
    return resp.json()


def get_relationship(http: httpx.Client, from_person_id: str, to_person_id: str) -> dict:
    resp = http.get(f"/api/v1/relationships/{from_person_id}/{to_person_id}")
    _check(resp)
    return resp.json()


def list_relationships(http: httpx.Client, person_id: str) -> dict:
    resp = http.get("/api/v1/relationships", params={"personId": person_id})
    _check(resp)
    return resp.json()


def update_relationship(
    http: httpx.Client, from_person_id: str, to_person_id: str, relation_type: str
) -> dict:
    resp = http.patch(f"/api/v1/relationships/{from_person_id}/{to_person_id}",
                      json={"relationType": relation_type})
    _check(resp)
    return resp.json()


def delete_relationship(http: httpx.Client, from_person_id: str, to_person_id: str) -> dict:
    resp = http.delete(f"/api/v1/relationships/{from_person_id}/{to_person_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def resolve_kinship(http: httpx.Client, from_person_id: str, to_person_id: str) -> dict:
    resp = http.get(f"/api/v1/relationships/{from_person_id}/{to_person_id}/kinship")
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="create_relationship")
    def _create_tool(from_person_id: str, to_person_id: str, relation_type: str) -> dict:
        """Record a directed relationship between two persons."""
        return create_relationship(http, from_person_id, to_person_id, relation_type)

    @mcp.tool(name="get_relationship")
    def _get_tool(from_person_id: str, to_person_id: str) -> dict:
        """Fetch a single directed relationship."""
        return get_relationship(http, from_person_id, to_person_id)

    @mcp.tool(name="list_relationships")
    def _list_tool(person_id: str) -> dict:
        """List all relationships where the person appears as subject or object."""
        return list_relationships(http, person_id)

    @mcp.tool(name="update_relationship")
    def _update_tool(from_person_id: str, to_person_id: str, relation_type: str) -> dict:
        """Change the relation_type on an existing directed relationship."""
        return update_relationship(http, from_person_id, to_person_id, relation_type)

    @mcp.tool(name="delete_relationship")
    def _delete_tool(from_person_id: str, to_person_id: str) -> dict:
        """Remove a directed relationship between two persons."""
        return delete_relationship(http, from_person_id, to_person_id)

    @mcp.tool(name="resolve_kinship")
    def _resolve_tool(from_person_id: str, to_person_id: str) -> dict:
        """Derive the cultural kinship name between two persons across multiple hops."""
        return resolve_kinship(http, from_person_id, to_person_id)
