import json
import os
import uuid
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
import httpx
from croniter import croniter

_SCHEDULER_TZ = ZoneInfo(os.environ.get("SCHEDULER_TIMEZONE", "UTC"))
from handlers.base import BaseHandler
from providers.news_source import NewsSource, Article
from providers.newsapi_source import NewsApiSource
from providers.rss_source import RssSource
from embed import embed


class NewsPollHandler(BaseHandler):
    def __init__(self, http: httpx.Client):
        self.http = http
        self._news_article_schema_id: str | None = None
        self._news_poll_source_type_id: str | None = None
        self._news_domain_id: str | None = None
        provider_name = os.environ.get("NEWS_SOURCE_PROVIDER", "newsapi").lower()
        if provider_name == "rss":
            self.source: NewsSource = RssSource()
        else:
            self.source: NewsSource = NewsApiSource()

    def _get_news_poll_source_type_id(self) -> str:
        if self._news_poll_source_type_id is None:
            resp = self.http.get("/api/v1/reference/source-types")
            resp.raise_for_status()
            match = next((st for st in resp.json().get("items", []) if st["name"] == "news_poll"), None)
            if match is None:
                raise RuntimeError("news_poll source type not found in reference data")
            self._news_poll_source_type_id = match["id"]
        return self._news_poll_source_type_id

    def _get_news_domain_id(self) -> str:
        if self._news_domain_id is None:
            resp = self.http.get("/api/v1/reference/domains")
            resp.raise_for_status()
            match = next((d for d in resp.json().get("items", []) if d["name"] == "news"), None)
            if match is None:
                raise RuntimeError("news domain not found in reference data")
            self._news_domain_id = match["id"]
        return self._news_domain_id

    def _get_news_article_schema_id(self) -> str:
        if self._news_article_schema_id is None:
            resp = self.http.get(
                "/api/v1/schemas/current",
                params={"domainId": self._get_news_domain_id(), "entityType": "news_article"},
            )
            resp.raise_for_status()
            self._news_article_schema_id = resp.json()["id"]
        return self._news_article_schema_id

    def run(self, job: dict) -> None:
        job_id = job["id"]
        person_id = job.get("personId")
        assert person_id, "news_poll jobs must have personId"

        articles_stored = 0
        errors = []
        try:
            # Find last run time
            runs_resp = self.http.get(f"/api/v1/scheduled-jobs/{job_id}/runs")
            runs = runs_resp.json().get("items", []) if runs_resp.is_success else []
            floor = datetime.now(timezone.utc) - timedelta(hours=48)
            if runs:
                last_run_at = min(
                    datetime.fromisoformat(runs[0]["startedAt"].replace("Z", "+00:00")),
                    floor,
                )
            else:
                last_run_at = floor

            # Get active news topics for this person
            topics_resp = self.http.get(
                "/api/v1/facts/current",
                params={"entityType": "news_topic", "personId": person_id},
            )
            topics_resp.raise_for_status()
            facts = topics_resp.json().get("items", [])
            active_topics = [
                f["fields"]["name"]
                for f in facts
                if f.get("fields", {}).get("active", True) is not False
            ]

            print(f"[news_poll] last_run_at={last_run_at.isoformat()}, topics={active_topics}")
            schema_id = self._get_news_article_schema_id()

            for topic in active_topics:
                try:
                    articles = self.source.fetch(topic, since=last_run_at)
                    print(f"[news_poll] topic={topic!r} → {len(articles)} articles")
                    for article in articles:
                        content_text = f"{article.headline}\n\n{article.description or ''}"

                        # Store document
                        doc_resp = self.http.post("/api/v1/documents", json={
                            "contentText": content_text,
                            "sourceTypeId": self._get_news_poll_source_type_id(),
                            "embedding": embed(content_text),
                            "supersedesIds": [],
                            "files": [],
                            "personId": person_id,
                        })
                        doc_resp.raise_for_status()
                        doc_id = doc_resp.json()["id"]

                        # Build fields dict
                        fields = {
                            "headline": article.headline,
                            "source": article.source,
                            "url": article.url,
                            "published_date": article.published_date,
                            "topic": topic,
                            "description": article.description,
                        }

                        # Store fact
                        fact_resp = self.http.post("/api/v1/facts", json={
                            "documentId": doc_id,
                            "schemaId": schema_id,
                            "entityInstanceId": str(uuid.uuid4()),
                            "operationType": "create",
                            "fields": fields,
                            "embedding": embed(json.dumps(fields, sort_keys=True)),
                        })
                        fact_resp.raise_for_status()
                        articles_stored += 1
                except Exception as e:
                    errors.append(str(e))
        except Exception as e:
            errors.append(str(e))
        finally:
            # Always record the run so the history is complete
            if errors and articles_stored == 0:
                status = "error"
            elif errors:
                status = "partial"
            elif articles_stored == 0:
                status = "skipped"
            else:
                status = "success"
            run_resp = self.http.post(f"/api/v1/scheduled-jobs/{job_id}/runs", json={
                "status": status,
                "articlesStored": articles_stored,
                "error": "; ".join(errors) if errors else None,
            })
            if not run_resp.is_success:
                print(f"[news_poll] WARNING: failed to record run: {run_resp.status_code} {run_resp.text}")

            # Always advance nextRunAt to prevent re-triggering every 60s
            try:
                cron = croniter(job["cronExpression"], datetime.now(_SCHEDULER_TZ))
                next_run = cron.get_next(datetime).replace(tzinfo=_SCHEDULER_TZ).astimezone(timezone.utc)
            except (ValueError, KeyError):
                next_run = datetime.now(timezone.utc) + timedelta(hours=24)
            self.http.patch(f"/api/v1/scheduled-jobs/{job_id}", json={
                "nextRunAt": next_run.isoformat(),
            })
