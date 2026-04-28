import httpx
from client import _check


def list_households(http: httpx.Client) -> dict:
    """List all households."""
    resp = http.get("/api/v1/households")
    _check(resp)
    return resp.json()


def create_household(http: httpx.Client, name: str) -> dict:
    """Create a new household."""
    resp = http.post("/api/v1/households", json={"name": name})
    _check(resp)
    return resp.json()


def get_household(http: httpx.Client, household_id: str) -> dict:
    """Fetch a single household by ID."""
    resp = http.get(f"/api/v1/households/{household_id}")
    _check(resp)
    return resp.json()


def update_household(http: httpx.Client, household_id: str, name: str | None = None) -> dict:
    """Rename a household."""
    body: dict = {}
    if name is not None:
        body["name"] = name
    resp = http.patch(f"/api/v1/households/{household_id}", json=body)
    _check(resp)
    return resp.json()


def delete_household(http: httpx.Client, household_id: str) -> dict:
    """Delete a household by ID."""
    resp = http.delete(f"/api/v1/households/{household_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def list_households_tool() -> dict:
        """List all households."""
        return list_households(http)

    @mcp.tool()
    def create_household_tool(name: str) -> dict:
        """Create a new household."""
        return create_household(http, name)

    @mcp.tool()
    def get_household_tool(household_id: str) -> dict:
        """Fetch a single household by ID."""
        return get_household(http, household_id)

    @mcp.tool()
    def update_household_tool(household_id: str, name: str | None = None) -> dict:
        """Rename a household."""
        return update_household(http, household_id, name)

    @mcp.tool()
    def delete_household_tool(household_id: str) -> dict:
        """Delete a household by ID."""
        return delete_household(http, household_id)
