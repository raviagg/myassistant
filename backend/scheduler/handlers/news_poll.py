import json
import os
import uuid
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
import httpx
from croniter import croniter

_SCHEDULER_TZ = ZoneInfo(os.environ.get("SCHEDULER_TIMEZONE", "UTC"))
from handlers.base import BaseHandler
from providers.newsapiai_source import fetch_ranked_events


class NewsPollHandler(BaseHandler):
    def __init__(self, http: httpx.Client):
        self.http = http
        self._news_poll_source_type_id: str | None = None
        self._news_domain_id: str | None = None
        self._news_preference_schema_id: str | None = None
        self._news_event_schema_id: str | None = None
        self._news_article_schema_id: str | None = None

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

    def _get_schema_id(self, entity_type: str) -> str:
        resp = self.http.get(
            "/api/v1/schemas/current",
            params={"domainId": self._get_news_domain_id(), "entityType": entity_type},
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def _get_news_preference_schema_id(self) -> str:
        if self._news_preference_schema_id is None:
            self._news_preference_schema_id = self._get_schema_id("news_preference")
        return self._news_preference_schema_id

    def _get_news_event_schema_id(self) -> str:
        if self._news_event_schema_id is None:
            self._news_event_schema_id = self._get_schema_id("news_event")
        return self._news_event_schema_id

    def _get_news_article_schema_id(self) -> str:
        if self._news_article_schema_id is None:
            self._news_article_schema_id = self._get_schema_id("news_article")
        return self._news_article_schema_id

    def _record_run(self, job_id: str, status: str, status_detail: str) -> None:
        run_resp = self.http.post(f"/api/v1/scheduled-jobs/{job_id}/runs", json={
            "status": status,
            "statusDetail": status_detail,
        })
        if not run_resp.is_success:
            print(f"[news_poll] WARNING: failed to record run: {run_resp.status_code} {run_resp.text}")

    def _advance_next_run(self, job_id: str, job: dict) -> None:
        try:
            cron = croniter(job["cronExpression"], datetime.now(_SCHEDULER_TZ))
            next_run = cron.get_next(datetime).replace(tzinfo=_SCHEDULER_TZ).astimezone(timezone.utc)
        except (ValueError, KeyError):
            next_run = datetime.now(timezone.utc) + timedelta(hours=24)
        self.http.patch(f"/api/v1/scheduled-jobs/{job_id}", json={
            "nextRunAt": next_run.isoformat(),
        })

    def run(self, job: dict) -> None:
        job_id = job["id"]
        person_id = job.get("personId")
        assert person_id, "news_poll jobs must have personId"

        events_stored = 0
        articles_stored = 0
        errors: list[str] = []

        try:
            # Load news_preference
            prefs_resp = self.http.get(
                "/api/v1/facts/current",
                params={"entityType": "news_preference", "personId": person_id},
            )
            prefs_resp.raise_for_status()
            prefs_facts = prefs_resp.json().get("items", [])

            if not prefs_facts:
                self._record_run(job_id, "skipped", "No news_preference configured for this person")
                self._advance_next_run(job_id, job)
                return

            pref_fields = prefs_facts[0].get("fields", {})
            try:
                categories = json.loads(pref_fields.get("categories", "[]"))
            except (json.JSONDecodeError, TypeError):
                categories = []
            try:
                sources = json.loads(pref_fields.get("sources", "[]"))
            except (json.JSONDecodeError, TypeError):
                sources = []

            if not categories:
                self._record_run(job_id, "skipped", "news_preference has no categories configured")
                self._advance_next_run(job_id, job)
                return

            print(f"[news_poll] categories={categories}, sources={sources}")

            date_end = datetime.now(timezone.utc)
            date_start = date_end - timedelta(hours=24)

            ranked_events = fetch_ranked_events(categories, sources, date_start, date_end)
            print(f"[news_poll] fetched {len(ranked_events)} ranked events")

            if not ranked_events:
                self._record_run(job_id, "skipped", "No events found for configured categories")
                self._advance_next_run(job_id, job)
                return

            source_type_id = self._get_news_poll_source_type_id()
            schema_id_event = self._get_news_event_schema_id()
            schema_id_article = self._get_news_article_schema_id()

            from embed import embed

            for event in ranked_events:
                try:
                    event_instance_id = str(uuid.uuid4())

                    content_text = f"{event.title}\n\n{event.summary or ''}"
                    doc_resp = self.http.post("/api/v1/documents", json={
                        "contentText": content_text,
                        "sourceTypeId": source_type_id,
                        "embedding": embed(content_text),
                        "supersedesIds": [],
                        "files": [],
                        "personId": person_id,
                    })
                    doc_resp.raise_for_status()
                    doc_id = doc_resp.json()["id"]

                    event_fields: dict = {"title": event.title, "event_uri": event.event_uri, "published_date": event.published_date}
                    if event.summary:
                        event_fields["summary"] = event.summary
                    if event.category:
                        event_fields["category"] = event.category
                    if event.article_count is not None:
                        event_fields["article_count"] = event.article_count
                    if event.social_score is not None:
                        event_fields["social_score"] = event.social_score

                    fact_resp = self.http.post("/api/v1/facts", json={
                        "documentId": doc_id,
                        "schemaId": schema_id_event,
                        "entityInstanceId": event_instance_id,
                        "operationType": "create",
                        "fields": event_fields,
                        "embedding": embed(json.dumps(event_fields, sort_keys=True)),
                    })
                    fact_resp.raise_for_status()
                    events_stored += 1

                    for article in event.articles:
                        try:
                            art_content = f"{article.headline}\n\n{article.description or ''}"
                            art_doc_resp = self.http.post("/api/v1/documents", json={
                                "contentText": art_content,
                                "sourceTypeId": source_type_id,
                                "embedding": embed(art_content),
                                "supersedesIds": [],
                                "files": [],
                                "personId": person_id,
                            })
                            art_doc_resp.raise_for_status()
                            art_doc_id = art_doc_resp.json()["id"]

                            art_fields: dict = {
                                "headline": article.headline,
                                "url": article.url,
                                "published_date": article.published_date,
                                "event_id": event_instance_id,
                            }
                            if article.source:
                                art_fields["source"] = article.source
                            if article.description:
                                art_fields["description"] = article.description

                            art_fact_resp = self.http.post("/api/v1/facts", json={
                                "documentId": art_doc_id,
                                "schemaId": schema_id_article,
                                "entityInstanceId": str(uuid.uuid4()),
                                "operationType": "create",
                                "fields": art_fields,
                                "embedding": embed(json.dumps(art_fields, sort_keys=True)),
                            })
                            art_fact_resp.raise_for_status()
                            articles_stored += 1
                        except Exception as e:
                            errors.append(f"article {article.url!r}: {e}")

                except Exception as e:
                    errors.append(f"event {event.event_uri!r}: {e}")

        except Exception as e:
            errors.append(str(e))

        finally:
            if errors and events_stored == 0:
                status = "failure"
            elif events_stored == 0:
                status = "skipped"
            else:
                status = "success"

            detail_parts: list[str] = []
            if events_stored > 0:
                detail_parts.append(f"Stored {events_stored} events, {articles_stored} articles")
            if errors:
                sample = "; ".join(errors[:3])
                detail_parts.append(f"{len(errors)} error(s): {sample}")
            if not detail_parts:
                detail_parts.append("No new events found")
            status_detail = ". ".join(detail_parts)

            print(f"[news_poll] status={status}: {status_detail}")
            self._record_run(job_id, status, status_detail)
            self._advance_next_run(job_id, job)
