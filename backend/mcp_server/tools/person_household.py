import httpx
from client import _check


def add_person_to_household(http: httpx.Client, person_id: str, household_id: str) -> dict:
    resp = http.put(f"/api/v1/households/{household_id}/members/{person_id}")
    _check(resp)
    return resp.json() if resp.content else {}


def remove_person_from_household(http: httpx.Client, person_id: str, household_id: str) -> dict:
    resp = http.delete(f"/api/v1/households/{household_id}/members/{person_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def list_household_members(http: httpx.Client, household_id: str) -> dict:
    resp = http.get(f"/api/v1/households/{household_id}/members")
    _check(resp)
    return resp.json()


def list_person_households(http: httpx.Client, person_id: str) -> dict:
    resp = http.get(f"/api/v1/persons/{person_id}/households")
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="add_person_to_household")
    def _add_tool(person_id: str, household_id: str) -> dict:
        """Link an existing person to an existing household. Idempotent."""
        return add_person_to_household(http, person_id, household_id)

    @mcp.tool(name="remove_person_from_household")
    def _remove_tool(person_id: str, household_id: str) -> dict:
        """Remove a person's membership from a household."""
        return remove_person_from_household(http, person_id, household_id)

    @mcp.tool(name="list_household_members")
    def _list_members_tool(household_id: str) -> dict:
        """Return all person IDs who are members of a household."""
        return list_household_members(http, household_id)

    @mcp.tool(name="list_person_households")
    def _list_households_tool(person_id: str) -> dict:
        """Return all household IDs that a person belongs to."""
        return list_person_households(http, person_id)
