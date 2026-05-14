import os


def search_news_categories(query: str, count: int = 10) -> dict:
    """Search newsapi.ai for category URIs matching query. Use before writing news_preference categories."""
    from eventregistry import EventRegistry
    api_key = os.environ.get("NEWSAPIAI_KEY", "")
    if not api_key:
        return {"error": "NEWSAPIAI_KEY not configured"}
    er = EventRegistry(apiKey=api_key, allowUseOfArchive=False)
    results = er.suggestCategories(query, count=count)
    return {"items": results or []}


def search_news_sources(query: str, count: int = 10) -> dict:
    """Search newsapi.ai for news source URIs matching query. Use before writing news_preference sources."""
    from eventregistry import EventRegistry
    api_key = os.environ.get("NEWSAPIAI_KEY", "")
    if not api_key:
        return {"error": "NEWSAPIAI_KEY not configured"}
    er = EventRegistry(apiKey=api_key, allowUseOfArchive=False)
    results = er.suggestNewsSources(query, count=count)
    return {"items": results or []}
