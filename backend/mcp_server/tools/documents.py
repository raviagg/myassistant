import httpx
from client import _check
from tools.embeddings import embed


def create_document(
    http: httpx.Client,
    content_text: str,
    source_type_id: str,
    person_id: str | None = None,
    household_id: str | None = None,
    supersedes_ids: list | None = None,
    files: list | None = None,
) -> dict:
    body: dict = {
        "contentText": content_text,
        "sourceTypeId": source_type_id,
        "embedding": embed(content_text),
        "supersedesIds": supersedes_ids or [],
        "files": files or [],
    }
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    resp = http.post("/api/v1/documents", json=body)
    _check(resp)
    return resp.json()


def get_document(http: httpx.Client, document_id: str) -> dict:
    resp = http.get(f"/api/v1/documents/{document_id}")
    _check(resp)
    return resp.json()


def list_documents(
    http: httpx.Client,
    person_id: str | None = None,
    household_id: str | None = None,
    source_type_id: str | None = None,
    created_after: str | None = None,
    created_before: str | None = None,
    limit: int = 50,
    offset: int = 0,
) -> dict:
    params: dict = {"limit": limit, "offset": offset}
    if person_id is not None:
        params["personId"] = person_id
    if household_id is not None:
        params["householdId"] = household_id
    if source_type_id is not None:
        params["sourceTypeId"] = source_type_id
    if created_after is not None:
        params["createdAfter"] = created_after
    if created_before is not None:
        params["createdBefore"] = created_before
    resp = http.get("/api/v1/documents", params=params)
    _check(resp)
    return resp.json()


def search_documents(
    http: httpx.Client,
    query_text: str,
    person_id: str | None = None,
    household_id: str | None = None,
    source_type_id: str | None = None,
    limit: int = 10,
    similarity_threshold: float = 0.5,
) -> dict:
    body: dict = {
        "embedding": embed(query_text),
        "limit": limit,
        "similarityThreshold": similarity_threshold,
    }
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    if source_type_id is not None:
        body["sourceTypeId"] = source_type_id
    resp = http.post("/api/v1/documents/search", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="create_document")
    def _create_tool(
        content_text: str,
        source_type_id: str,
        person_id: str | None = None,
        household_id: str | None = None,
        supersedes_ids: list | None = None,
        files: list | None = None,
    ) -> dict:
        """Persist a new immutable document. Embedding is generated automatically from content_text. At least one of person_id or household_id must be provided."""
        return create_document(http, content_text, source_type_id, person_id, household_id, supersedes_ids, files)

    @mcp.tool(name="get_document")
    def _get_tool(document_id: str) -> dict:
        """Fetch a single document by UUID."""
        return get_document(http, document_id)

    @mcp.tool(name="list_documents")
    def _list_tool(
        person_id: str | None = None,
        household_id: str | None = None,
        source_type_id: str | None = None,
        created_after: str | None = None,
        created_before: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> dict:
        """Filter-based listing of documents."""
        return list_documents(http, person_id, household_id, source_type_id, created_after, created_before, limit, offset)

    @mcp.tool(name="search_documents")
    def _search_tool(
        query_text: str,
        person_id: str | None = None,
        household_id: str | None = None,
        source_type_id: str | None = None,
        limit: int = 10,
        similarity_threshold: float = 0.5,
    ) -> dict:
        """Vector similarity search over documents using a natural language query."""
        return search_documents(http, query_text, person_id, household_id, source_type_id, limit, similarity_threshold)
