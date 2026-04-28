import httpx
from client import _check


def create_person(
    http: httpx.Client,
    full_name: str,
    gender: str,
    date_of_birth: str | None = None,
    preferred_name: str | None = None,
    user_identifier: str | None = None,
) -> dict:
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
    resp = http.get(f"/api/v1/persons/{person_id}")
    _check(resp)
    return resp.json()


def search_persons(
    http: httpx.Client,
    name: str | None = None,
    gender: str | None = None,
    date_of_birth: str | None = None,
    date_of_birth_from: str | None = None,
    date_of_birth_to: str | None = None,
    household_id: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    params: dict = {"limit": limit, "offset": offset}
    if name is not None:
        params["name"] = name
    if gender is not None:
        params["gender"] = gender
    if date_of_birth is not None:
        params["dateOfBirth"] = date_of_birth
    if date_of_birth_from is not None:
        params["dateOfBirthFrom"] = date_of_birth_from
    if date_of_birth_to is not None:
        params["dateOfBirthTo"] = date_of_birth_to
    if household_id is not None:
        params["householdId"] = household_id
    resp = http.get("/api/v1/persons", params=params)
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
    resp = http.delete(f"/api/v1/persons/{person_id}")
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="create_person")
    def _create_person_tool(
        full_name: str,
        gender: str,
        date_of_birth: str | None = None,
        preferred_name: str | None = None,
        user_identifier: str | None = None,
    ) -> dict:
        """Register a new person."""
        return create_person(http, full_name, gender, date_of_birth, preferred_name, user_identifier)

    @mcp.tool(name="get_person")
    def _get_person_tool(person_id: str) -> dict:
        """Fetch a single person by ID."""
        return get_person(http, person_id)

    @mcp.tool(name="search_persons")
    def _search_persons_tool(
        name: str | None = None,
        gender: str | None = None,
        date_of_birth: str | None = None,
        date_of_birth_from: str | None = None,
        date_of_birth_to: str | None = None,
        household_id: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict:
        """Find persons matching filter criteria. All filters are optional and ANDed together."""
        return search_persons(http, name, gender, date_of_birth, date_of_birth_from, date_of_birth_to, household_id, limit, offset)

    @mcp.tool(name="update_person")
    def _update_person_tool(
        person_id: str,
        full_name: str | None = None,
        gender: str | None = None,
        date_of_birth: str | None = None,
        preferred_name: str | None = None,
        user_identifier: str | None = None,
    ) -> dict:
        """Update mutable fields on a person (PATCH semantics)."""
        return update_person(http, person_id, full_name, gender, date_of_birth, preferred_name, user_identifier)

    @mcp.tool(name="delete_person")
    def _delete_person_tool(person_id: str) -> dict:
        """Delete a person by ID."""
        return delete_person(http, person_id)
