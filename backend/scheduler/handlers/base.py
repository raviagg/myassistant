from abc import ABC, abstractmethod


class BaseHandler(ABC):
    @abstractmethod
    def run(self, job: dict) -> None:
        """Execute a scheduled job."""
        ...
