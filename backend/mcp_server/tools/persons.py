import httpx
from client import _check


def list_persons(
    http: httpx.Client,
    household_id: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    """List persons, optionally filtered by household."""
    params: dict = {"limit": limit, "offset": offset}
    if household_id is not None:
        params["householdId"] = household_id
    resp = http.get("/api/v1/persons", params=params)
    _check(resp)
    return resp.json()


def create_person(
    http: httpx.Client,
    full_name: str,
    gender: str,
    date_of_birth: str | None = None,
    preferred_name: str | None = None,
    user_identifier: str | None = None,
) -> dict:
    """Register a new person."""
    body: dict = {"fullName": full_name, "gender": gender}
    if date_of_birth is not None:
        body["dateOfBirth"] = date_of_birth
    if preferred_name is not None:
        body["preferredName"] = preferred_name
    if user_identifier is not None:
        body["userIdentifier"] = user_identifier
    resp = http.post("/api/v1/persons", json=body)
    _check(resp)
    return resp.json()


def get_person(http: httpx.Client, person_id: str) -> dict:
    """Fetch a single person by ID."""
    resp = http.get(f"/api/v1/persons/{person_id}")
    _check(resp)
    return resp.json()


def update_person(
    http: httpx.Client,
    person_id: str,
    full_name: str | None = None,
    gender: str | None = None,
    date_of_birth: str | None = None,
    preferred_name: str | None = None,
    user_identifier: str | None = None,
) -> dict:
    """Update mutable fields on a person (PATCH semantics)."""
    body: dict = {}
    if full_name is not None:
        body["fullName"] = full_name
    if gender is not None:
        body["gender"] = gender
    if date_of_birth is not None:
        body["dateOfBirth"] = date_of_birth
    if preferred_name is not None:
        body["preferredName"] = preferred_name
    if user_identifier is not None:
        body["userIdentifier"] = user_identifier
    resp = http.patch(f"/api/v1/persons/{person_id}", json=body)
    _check(resp)
    return resp.json()


def delete_person(http: httpx.Client, person_id: str) -> dict:
    """Delete a person by ID."""
    resp = http.delete(f"/api/v1/persons/{person_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def list_persons_tool(
        household_id: str | None = None, limit: int = 50, offset: int = 0
    ) -> dict:
        """List persons, optionally filtered by household."""
        return list_persons(http, household_id, limit, offset)

    @mcp.tool()
    def create_person_tool(
        full_name: str,
        gender: str,
        date_of_birth: str | None = None,
        preferred_name: str | None = None,
        user_identifier: str | None = None,
    ) -> dict:
        """Register a new person."""
        return create_person(http, full_name, gender, date_of_birth, preferred_name, user_identifier)

    @mcp.tool()
    def get_person_tool(person_id: str) -> dict:
        """Fetch a single person by ID."""
        return get_person(http, person_id)

    @mcp.tool()
    def update_person_tool(
        person_id: str,
        full_name: str | None = None,
        gender: str | None = None,
        date_of_birth: str | None = None,
        preferred_name: str | None = None,
        user_identifier: str | None = None,
    ) -> dict:
        """Update mutable fields on a person."""
        return update_person(http, person_id, full_name, gender, date_of_birth, preferred_name, user_identifier)

    @mcp.tool()
    def delete_person_tool(person_id: str) -> dict:
        """Delete a person by ID."""
        return delete_person(http, person_id)
