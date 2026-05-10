import os
from datetime import datetime, timedelta, timezone
import httpx
from croniter import croniter
from handlers.base import BaseHandler
from providers.news_source import NewsSource, Article
from providers.newsapi_source import NewsApiSource
from providers.rss_source import RssSource


class NewsPollHandler(BaseHandler):
    def __init__(self, http: httpx.Client):
        self.http = http
        provider_name = os.environ.get("NEWS_SOURCE_PROVIDER", "newsapi").lower()
        if provider_name == "rss":
            self.source: NewsSource = RssSource()
        else:
            self.source: NewsSource = NewsApiSource()

    def run(self, job: dict) -> None:
        job_id = job["id"]
        person_id = job.get("personId")
        assert person_id, "news_poll jobs must have personId"

        # Find last run time
        runs_resp = self.http.get(f"/api/v1/scheduled-jobs/{job_id}/runs")
        runs = runs_resp.json().get("items", []) if runs_resp.is_success else []
        if runs:
            # Most recent run's startedAt
            last_run_at = datetime.fromisoformat(runs[0]["startedAt"].replace("Z", "+00:00"))
        else:
            last_run_at = datetime.now(timezone.utc) - timedelta(hours=24)

        # Get active news topics for this person
        topics_resp = self.http.get(
            "/api/v1/facts/current",
            params={"entityType": "news_topic", "personId": person_id},
        )
        topics_resp.raise_for_status()
        facts = topics_resp.json().get("facts", [])
        active_topics = [
            f["fields"]["name"]
            for f in facts
            if f.get("fields", {}).get("active", True) is not False
        ]

        articles_stored = 0
        errors = []
        for topic in active_topics:
            try:
                articles = self.source.fetch(topic, since=last_run_at)
                for article in articles:
                    # Store document
                    doc_resp = self.http.post("/api/v1/documents", json={
                        "sourceType": "news_poll",
                        "personId": person_id,
                        "contentText": f"{article.headline}\n\n{article.description or ''}",
                    })
                    doc_resp.raise_for_status()
                    doc_id = doc_resp.json()["id"]

                    # Store fact
                    fact_resp = self.http.post("/api/v1/facts", json={
                        "documentId": doc_id,
                        "personId": person_id,
                        "domain": "news",
                        "entityType": "news_article",
                        "fields": {
                            "headline": article.headline,
                            "source": article.source,
                            "url": article.url,
                            "published_date": article.published_date,
                            "topic": topic,
                            "description": article.description,
                        },
                    })
                    fact_resp.raise_for_status()
                    articles_stored += 1
            except Exception as e:
                errors.append(str(e))

        # Record the job run
        status = "error" if errors and articles_stored == 0 else ("partial" if errors else "success")
        self.http.post(f"/api/v1/scheduled-jobs/{job_id}/runs", json={
            "status": status,
            "articlesStored": articles_stored,
            "error": "; ".join(errors) if errors else None,
        })

        # Compute next run time from cron expression and update the job.
        # This prevents the job from re-triggering on every 60-second poll cycle.
        try:
            cron = croniter(job["cronExpression"], datetime.now(timezone.utc))
            next_run = cron.get_next(datetime)
        except (ValueError, KeyError):
            next_run = datetime.now(timezone.utc) + timedelta(hours=24)

        self.http.patch(f"/api/v1/scheduled-jobs/{job_id}", json={
            "nextRunAt": next_run.isoformat(),
        })
