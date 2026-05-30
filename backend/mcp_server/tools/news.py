import json as _json
import os


def search_news_categories(query: str, count: int = 10) -> dict:
    """Search newsapi.ai for category URIs matching query. Call this before update_news_preferences to get exact URIs."""
    from eventregistry import EventRegistry
    api_key = os.environ.get("NEWSAPIAI_KEY", "")
    if not api_key:
        return {"error": "NEWSAPIAI_KEY not configured"}
    er = EventRegistry(apiKey=api_key, allowUseOfArchive=False)
    results = er.suggestCategories(query, count=count)
    return {"items": results or []}


def search_news_sources(query: str, count: int = 10) -> dict:
    """Search newsapi.ai for news source URIs matching query. Call this before update_news_preferences to get exact URIs."""
    from eventregistry import EventRegistry
    api_key = os.environ.get("NEWSAPIAI_KEY", "")
    if not api_key:
        return {"error": "NEWSAPIAI_KEY not configured"}
    er = EventRegistry(apiKey=api_key, allowUseOfArchive=False)
    results = er.suggestNewsSources(query, count=count)
    return {"items": results or []}


def update_news_preferences(
    http,
    source_connection_id: str,
    categories: list[str],
    sources: list[str] | None = None,
) -> dict:
    """Update the news categories and sources for a news_poll source connection.

    Use search_news_categories / search_news_sources first to obtain valid URIs,
    then call this to persist them into the connection's config.
    """
    get_resp = http.get(f"/api/v1/source-connections/{source_connection_id}")
    get_resp.raise_for_status()
    conn = get_resp.json()

    config = dict(conn.get("config") or {})
    config["categories"] = _json.dumps(categories)
    if sources is not None:
        config["sources"] = _json.dumps(sources)

    put_resp = http.put(
        f"/api/v1/source-connections/{source_connection_id}",
        json={
            "sourceType":      conn["sourceType"],
            "connectionName":  conn["connectionName"],
            "personId":        conn.get("personId"),
            "householdId":     conn.get("householdId"),
            "syncScheduled":   conn["syncScheduled"],
            "syncAdhoc":       conn["syncAdhoc"],
            "syncSchedule":    conn.get("syncSchedule"),
            "config":          config,
        },
    )
    put_resp.raise_for_status()
    return put_resp.json()
