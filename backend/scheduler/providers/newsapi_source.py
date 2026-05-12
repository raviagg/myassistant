import os
from datetime import datetime
import httpx
from providers.news_source import NewsSource, Article

# NewsAPI free tier only supports /v2/top-headlines.
# Set NEWSAPI_USE_EVERYTHING=true if you have a paid plan.
_USE_EVERYTHING = os.environ.get("NEWSAPI_USE_EVERYTHING", "").lower() == "true"


class NewsApiSource(NewsSource):
    def fetch(self, topic: str, since: datetime) -> list[Article]:
        api_key = os.environ["NEWSAPI_KEY"]
        # Sanitize topic: hyphens/slashes are query operators in NewsAPI
        query = topic.replace("-", " ").replace("/", " ")
        if _USE_EVERYTHING:
            url = "https://newsapi.org/v2/everything"
            params = {
                "q": query,
                "from": since.strftime("%Y-%m-%dT%H:%M:%S"),
                "sortBy": "publishedAt",
                "language": "en",
                "pageSize": 20,
                "apiKey": api_key,
            }
        else:
            url = "https://newsapi.org/v2/top-headlines"
            params = {
                "q": query,
                "language": "en",
                "pageSize": 20,
                "apiKey": api_key,
            }
        resp = httpx.get(url, params=params, timeout=15)
        resp.raise_for_status()
        articles = []
        for item in resp.json().get("articles", []):
            articles.append(Article(
                headline=item.get("title", ""),
                url=item.get("url", ""),
                published_date=item.get("publishedAt", "")[:10],
                source=item.get("source", {}).get("name"),
                description=item.get("description"),
            ))
        return articles
