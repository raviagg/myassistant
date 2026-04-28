import httpx
from client import _check


def add_person_to_household(http: httpx.Client, household_id: str, person_id: str) -> dict:
    """Link a person to a household."""
    resp = http.put(f"/api/v1/households/{household_id}/members/{person_id}")
    _check(resp)
    return resp.json() if resp.content else {}


def remove_person_from_household(http: httpx.Client, household_id: str, person_id: str) -> dict:
    """Remove a person's membership from a household."""
    resp = http.delete(f"/api/v1/households/{household_id}/members/{person_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def list_household_members(http: httpx.Client, household_id: str) -> dict:
    """Return all members of a household."""
    resp = http.get(f"/api/v1/households/{household_id}/members")
    _check(resp)
    return resp.json()


def list_person_households(http: httpx.Client, person_id: str) -> dict:
    """Return all households a person belongs to."""
    resp = http.get(f"/api/v1/persons/{person_id}/households")
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def add_person_to_household_tool(household_id: str, person_id: str) -> dict:
        """Link a person to a household."""
        return add_person_to_household(http, household_id, person_id)

    @mcp.tool()
    def remove_person_from_household_tool(household_id: str, person_id: str) -> dict:
        """Remove a person from a household."""
        return remove_person_from_household(http, household_id, person_id)

    @mcp.tool()
    def list_household_members_tool(household_id: str) -> dict:
        """List all members of a household."""
        return list_household_members(http, household_id)

    @mcp.tool()
    def list_person_households_tool(person_id: str) -> dict:
        """List all households a person belongs to."""
        return list_person_households(http, person_id)
