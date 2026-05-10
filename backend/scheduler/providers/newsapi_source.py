import os
from datetime import datetime
import httpx
from providers.news_source import NewsSource, Article


class NewsApiSource(NewsSource):
    def fetch(self, topic: str, since: datetime) -> list[Article]:
        api_key = os.environ["NEWSAPI_KEY"]
        resp = httpx.get(
            "https://newsapi.org/v2/everything",
            params={
                "q": topic,
                "from": since.strftime("%Y-%m-%dT%H:%M:%S"),
                "sortBy": "publishedAt",
                "language": "en",
                "pageSize": 20,
                "apiKey": api_key,
            },
            timeout=15,
        )
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
