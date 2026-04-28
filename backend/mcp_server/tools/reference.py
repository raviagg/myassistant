import httpx
from client import _check


def list_domains(http: httpx.Client) -> list:
    resp = http.get("/api/v1/reference/domains")
    _check(resp)
    return resp.json()


def list_source_types(http: httpx.Client) -> list:
    resp = http.get("/api/v1/reference/source-types")
    _check(resp)
    return resp.json()


def list_kinship_aliases(http: httpx.Client, language: str | None = None) -> list:
    params: dict = {}
    if language is not None:
        params["language"] = language
    resp = http.get("/api/v1/reference/kinship-aliases", params=params)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="list_domains")
    def _list_domains_tool() -> list:
        """Return all life domains with their UUIDs."""
        return list_domains(http)

    @mcp.tool(name="list_source_types")
    def _list_source_types_tool() -> list:
        """Return all registered source types with their UUIDs."""
        return list_source_types(http)

    @mcp.tool(name="list_kinship_aliases")
    def _list_kinship_aliases_tool(language: str | None = None) -> list:
        """Return all cultural kinship name mappings, optionally filtered by language."""
        return list_kinship_aliases(http, language)
