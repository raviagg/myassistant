import httpx
from client import _check


def create_household(http: httpx.Client, name: str) -> dict:
    resp = http.post("/api/v1/households", json={"name": name})
    _check(resp)
    return resp.json()


def get_household(http: httpx.Client, household_id: str) -> dict:
    resp = http.get(f"/api/v1/households/{household_id}")
    _check(resp)
    return resp.json()


def search_households(http: httpx.Client, name: str) -> dict:
    resp = http.get("/api/v1/households", params={"name": name})
    _check(resp)
    return resp.json()


def update_household(http: httpx.Client, household_id: str, name: str) -> dict:
    resp = http.patch(f"/api/v1/households/{household_id}", json={"name": name})
    _check(resp)
    return resp.json()


def delete_household(http: httpx.Client, household_id: str) -> dict:
    resp = http.delete(f"/api/v1/households/{household_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="create_household")
    def _create_household_tool(name: str) -> dict:
        """Create a new household."""
        return create_household(http, name)

    @mcp.tool(name="get_household")
    def _get_household_tool(household_id: str) -> dict:
        """Fetch a single household by ID."""
        return get_household(http, household_id)

    @mcp.tool(name="search_households")
    def _search_households_tool(name: str) -> dict:
        """Find households by name (case-insensitive partial match)."""
        return search_households(http, name)

    @mcp.tool(name="update_household")
    def _update_household_tool(household_id: str, name: str) -> dict:
        """Rename a household."""
        return update_household(http, household_id, name)

    @mcp.tool(name="delete_household")
    def _delete_household_tool(household_id: str) -> dict:
        """Delete a household by ID."""
        return delete_household(http, household_id)
