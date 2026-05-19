"""Auto-provision a chatbot source_connection per person/household.

On first MCP tool call per person/household within a session, checks
whether a source_connection with source_type='chatbot' already exists.
If not, creates one. The ID is cached in-memory for the session lifetime.
"""

import httpx

_cache: dict[str, str] = {}  # scope_key (person_id or household_id) -> source_connection_id


def get_or_create(
    http: httpx.Client,
    person_id: str | None,
    household_id: str | None,
) -> str | None:
    """Return source_connection_id for the chatbot connection, creating it if needed.

    Returns None if no person_id or household_id is provided, or if the
    provisioning call fails (best-effort -- never blocks the main tool call).
    """
    scope_key = person_id or household_id
    if not scope_key:
        return None
    if scope_key in _cache:
        return _cache[scope_key]

    # Look for an existing chatbot source_connection for this person/household.
    try:
        params: dict = {}
        if person_id:
            params["personId"] = person_id
        else:
            params["householdId"] = household_id
        resp = http.get("/api/v1/source-connections", params=params)
        if resp.is_success:
            for conn in resp.json().get("items", []):
                if conn.get("sourceType") == "chatbot":
                    conn_id = conn["id"]
                    _cache[scope_key] = conn_id
                    return conn_id
    except Exception:
        pass

    # Not found -- create a new chatbot source_connection.
    try:
        body: dict = {
            "sourceType":     "chatbot",
            "connectionName": "Chatbot",
            "syncScheduled":  False,
            "syncAdhoc":      False,
        }
        if person_id:
            body["personId"] = person_id
        else:
            body["householdId"] = household_id
        resp = http.post("/api/v1/source-connections", json=body)
        if resp.is_success:
            conn_id = resp.json()["id"]
            _cache[scope_key] = conn_id
            return conn_id
    except Exception:
        pass

    return None
