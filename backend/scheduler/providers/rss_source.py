from datetime import datetime
from providers.news_source import NewsSource, Article


class RssSource(NewsSource):
    def fetch(self, topic: str, since: datetime) -> list[Article]:
        raise NotImplementedError("RSS source not yet implemented")
