import os
import httpx
from bs4 import BeautifulSoup


def fetch_url(url: str) -> str:
    """Fetch a webpage and return its readable plain text content."""
    resp = httpx.get(url, follow_redirects=True, timeout=15)
    resp.raise_for_status()
    soup = BeautifulSoup(resp.text, "html.parser")
    # Remove script/style tags before extracting text
    for tag in soup(["script", "style"]):
        tag.decompose()
    return soup.get_text(separator="\n", strip=True)


class DuckDuckGoSearch:
    def search(self, query: str, num_results: int) -> list[dict]:
        """Uses DDG Instant Answer API."""
        resp = httpx.get(
            "https://api.duckduckgo.com/",
            params={"q": query, "format": "json", "no_redirect": "1", "no_html": "1"},
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json()
        results = []
        # RelatedTopics contains result snippets
        for topic in data.get("RelatedTopics", [])[:num_results]:
            if "Text" in topic and "FirstURL" in topic:
                results.append({
                    "title": topic.get("Text", ""),
                    "snippet": topic.get("Text", ""),
                    "url": topic.get("FirstURL", ""),
                })
        return results


class BraveSearch:
    def search(self, query: str, num_results: int) -> list[dict]:
        """Uses Brave Search API. Requires BRAVE_API_KEY env var."""
        api_key = os.environ["BRAVE_API_KEY"]
        resp = httpx.get(
            "https://api.search.brave.com/res/v1/web/search",
            params={"q": query, "count": num_results},
            headers={"Accept": "application/json", "X-Subscription-Token": api_key},
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json()
        return [
            {
                "title": r.get("title", ""),
                "snippet": r.get("description", ""),
                "url": r.get("url", ""),
            }
            for r in data.get("web", {}).get("results", [])[:num_results]
        ]


def _get_provider() -> DuckDuckGoSearch | BraveSearch:
    provider = os.environ.get("WEB_SEARCH_PROVIDER", "duckduckgo").lower()
    if provider == "brave":
        return BraveSearch()
    return DuckDuckGoSearch()


def web_search(query: str, num_results: int = 5) -> list[dict]:
    """Search the web and return top results as [{title, snippet, url}]."""
    return _get_provider().search(query, num_results)


def register(mcp, http=None) -> None:
    """Register web tools with the MCP server. http parameter not used (external services)."""
    @mcp.tool(name="fetch_url")
    def _fetch_tool(url: str) -> str:
        """Fetch a webpage and return its readable plain text content."""
        return fetch_url(url)

    @mcp.tool(name="web_search")
    def _search_tool(query: str, num_results: int = 5) -> list[dict]:
        """Search the web and return top results as [{title, snippet, url}]."""
        return web_search(query, num_results)
