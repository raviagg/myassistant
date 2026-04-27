import httpx
from client import _check


def create_document(
    http: httpx.Client,
    content_text: str,
    source_type: str,
    person_id: str | None = None,
    household_id: str | None = None,
    files: list | None = None,
    supersedes_ids: list | None = None,
) -> dict:
    """Create a new immutable document."""
    body: dict = {
        "contentText": content_text,
        "sourceType": source_type,
        "files": files or [],
        "supersedesIds": supersedes_ids or [],
    }
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    resp = http.post("/api/v1/documents", json=body)
    _check(resp)
    return resp.json()


def list_documents(
    http: httpx.Client,
    person_id: str | None = None,
    household_id: str | None = None,
    source_type: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    """List documents with optional filters."""
    params: dict = {"limit": limit, "offset": offset}
    if person_id is not None:
        params["personId"] = person_id
    if household_id is not None:
        params["householdId"] = household_id
    if source_type is not None:
        params["sourceType"] = source_type
    resp = http.get("/api/v1/documents", params=params)
    _check(resp)
    return resp.json()


def get_document(http: httpx.Client, document_id: str) -> dict:
    """Fetch a single document by ID."""
    resp = http.get(f"/api/v1/documents/{document_id}")
    _check(resp)
    return resp.json()


def search_documents(
    http: httpx.Client,
    query: str,
    person_id: str | None = None,
    household_id: str | None = None,
    limit: int | None = None,
) -> dict:
    """Semantic search over documents."""
    body: dict = {"query": query}
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    if limit is not None:
        body["limit"] = limit
    resp = http.post("/api/v1/documents/search", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def create_document_tool(
        content_text: str,
        source_type: str,
        person_id: str | None = None,
        household_id: str | None = None,
        files: list | None = None,
        supersedes_ids: list | None = None,
    ) -> dict:
        """Create a new immutable document."""
        return create_document(http, content_text, source_type, person_id, household_id, files, supersedes_ids)

    @mcp.tool()
    def list_documents_tool(
        person_id: str | None = None,
        household_id: str | None = None,
        source_type: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict:
        """List documents with optional filters."""
        return list_documents(http, person_id, household_id, source_type, limit, offset)

    @mcp.tool()
    def get_document_tool(document_id: str) -> dict:
        """Fetch a single document by ID."""
        return get_document(http, document_id)

    @mcp.tool()
    def search_documents_tool(
        query: str,
        person_id: str | None = None,
        household_id: str | None = None,
        limit: int | None = None,
    ) -> dict:
        """Semantic search over documents."""
        return search_documents(http, query, person_id, household_id, limit)
