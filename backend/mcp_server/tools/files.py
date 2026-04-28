import base64
import httpx
from client import _check


def save_file(
    http: httpx.Client,
    content_base64: str,
    filename: str,
    mime_type: str = "application/octet-stream",
) -> dict:
    raw = base64.b64decode(content_base64)
    params: dict = {"fileName": filename, "mimeType": mime_type}
    resp = http.post("/api/v1/files", content=raw,
                     headers={"Content-Type": mime_type}, params=params)
    _check(resp)
    return resp.json()


def extract_text_from_file(http: httpx.Client, file_path: str) -> dict:
    resp = http.post("/api/v1/files/extract-text", json={"filePath": file_path})
    _check(resp)
    return resp.json()


def get_file(http: httpx.Client, file_path: str) -> dict:
    resp = http.get("/api/v1/files", params={"path": file_path})
    _check(resp)
    return {"content_base64": base64.b64encode(resp.content).decode()}


def delete_file(http: httpx.Client, file_path: str) -> dict:
    resp = http.delete("/api/v1/files", params={"path": file_path})
    _check(resp)
    return {} if resp.status_code == 204 else resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="save_file")
    def _save_tool(
        content_base64: str,
        filename: str,
        mime_type: str = "application/octet-stream",
    ) -> dict:
        """Persist a file from base64-encoded content and return its file_path."""
        return save_file(http, content_base64, filename, mime_type)

    @mcp.tool(name="extract_text_from_file")
    def _extract_tool(file_path: str) -> dict:
        """Extract plain text from a saved file."""
        return extract_text_from_file(http, file_path)

    @mcp.tool(name="get_file")
    def _get_tool(file_path: str) -> dict:
        """Retrieve a previously saved file as base64-encoded content."""
        return get_file(http, file_path)

    @mcp.tool(name="delete_file")
    def _delete_tool(file_path: str) -> dict:
        """Delete a file from the filesystem."""
        return delete_file(http, file_path)
