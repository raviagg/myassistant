from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime


@dataclass
class Article:
    headline: str
    url: str
    published_date: str      # ISO date YYYY-MM-DD
    source: str | None = None
    description: str | None = None


class NewsSource(ABC):
    @abstractmethod
    def fetch(self, topic: str, since: datetime) -> list[Article]:
        ...
