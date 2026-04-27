import httpx
from client import _check


def list_domains(http: httpx.Client) -> list:
    """Return all life domains."""
    resp = http.get("/api/v1/reference/domains")
    _check(resp)
    return resp.json()


def list_source_types(http: httpx.Client) -> list:
    """Return all registered source types."""
    resp = http.get("/api/v1/reference/source-types")
    _check(resp)
    return resp.json()


def list_kinship_aliases(http: httpx.Client) -> list:
    """Return all cultural kinship name mappings."""
    resp = http.get("/api/v1/reference/kinship-aliases")
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def list_domains_tool() -> list:
        """Return all life domains."""
        return list_domains(http)

    @mcp.tool()
    def list_source_types_tool() -> list:
        """Return all registered source types."""
        return list_source_types(http)

    @mcp.tool()
    def list_kinship_aliases_tool() -> list:
        """Return all cultural kinship name mappings."""
        return list_kinship_aliases(http)
