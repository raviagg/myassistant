import base64
import httpx
from client import _check


def save_file(
    http: httpx.Client,
    content_base64: str,
    filename: str,
    mime_type: str = "application/octet-stream",
    person_id: str | None = None,
    household_id: str | None = None,
) -> dict:
    """Upload a file from base64-encoded content and return its storage key."""
    raw = base64.b64decode(content_base64)
    params: dict = {"fileName": filename, "mimeType": mime_type}
    if person_id is not None:
        params["personId"] = person_id
    if household_id is not None:
        params["householdId"] = household_id
    resp = http.post("/api/v1/files", content=raw,
                     headers={"Content-Type": mime_type}, params=params)
    _check(resp)
    return resp.json()


def extract_text_from_file(http: httpx.Client, key: str, mime_type: str) -> dict:
    """Extract plain text from a stored file."""
    resp = http.post("/api/v1/files/extract-text", json={"key": key, "mimeType": mime_type})
    _check(resp)
    return resp.json()


def get_file(http: httpx.Client, key: str) -> dict:
    """Download a stored file and return its content as base64."""
    resp = http.get("/api/v1/files", params={"key": key})
    _check(resp)
    return {"content_base64": base64.b64encode(resp.content).decode()}


def delete_file(http: httpx.Client, key: str) -> dict:
    """Delete a stored file by its storage key."""
    resp = http.delete("/api/v1/files", params={"key": key})
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def save_file_tool(
        content_base64: str,
        filename: str,
        mime_type: str = "application/octet-stream",
        person_id: str | None = None,
        household_id: str | None = None,
    ) -> dict:
        """Upload a file and return its storage key."""
        return save_file(http, content_base64, filename, mime_type, person_id, household_id)

    @mcp.tool()
    def extract_text_from_file_tool(key: str, mime_type: str) -> dict:
        """Extract plain text from a stored file."""
        return extract_text_from_file(http, key, mime_type)

    @mcp.tool()
    def get_file_tool(key: str) -> dict:
        """Download a stored file as base64-encoded content."""
        return get_file(http, key)

    @mcp.tool()
    def delete_file_tool(key: str) -> dict:
        """Delete a stored file by its storage key."""
        return delete_file(http, key)
