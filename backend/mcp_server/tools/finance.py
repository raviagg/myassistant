import httpx
from client import _check
from tools.embeddings import embed


def _get_finance_domain_id(http: httpx.Client, _cache: dict = {}) -> str:
    if "id" not in _cache:
        resp = http.get("/api/v1/reference/domains")
        resp.raise_for_status()
        match = next((d for d in resp.json().get("items", []) if d["name"] == "finance"), None)
        if match is None:
            raise RuntimeError("finance domain not found in reference data")
        _cache["id"] = match["id"]
    return _cache["id"]


def register(mcp, http: httpx.Client) -> None:

    @mcp.tool(name="search_transactions")
    def _search_transactions(
        query: str,
        person_id: str | None = None,
        household_id: str | None = None,
        limit: int = 20,
        similarity_threshold: float = 0.4,
    ) -> dict:
        """Semantic search over bank transactions. Use for questions like 'how much did I spend on coffee?' or 'find grocery purchases last month'."""
        body: dict = {
            "embedding": embed(query),
            "domainId": _get_finance_domain_id(http),
            "entityType": "transaction",
            "limit": limit,
            "similarityThreshold": similarity_threshold,
        }
        if person_id is not None:
            body["personId"] = person_id
        if household_id is not None:
            body["householdId"] = household_id
        resp = http.post("/api/v1/facts/search", json=body)
        _check(resp)
        return resp.json()

    @mcp.tool(name="list_accounts")
    def _list_accounts(
        person_id: str | None = None,
        household_id: str | None = None,
    ) -> dict:
        """List all linked bank accounts with current balances. Use for questions like 'what bank accounts do I have?' or 'what is my checking balance?'."""
        params: dict = {
            "domainId": _get_finance_domain_id(http),
            "entityType": "bank_account",
        }
        if person_id is not None:
            params["personId"] = person_id
        if household_id is not None:
            params["householdId"] = household_id
        resp = http.get("/api/v1/facts/current", params=params)
        _check(resp)
        return resp.json()
