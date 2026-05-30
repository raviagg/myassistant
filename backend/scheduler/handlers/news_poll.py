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


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _log_entry(level: str, msg: str) -> dict:
    return {
        "time":  datetime.now(timezone.utc).strftime("%H:%M:%S"),
        "level": level,
        "msg":   msg,
    }


class NewsPollHandler(BaseHandler):

    def __init__(self, http: httpx.Client):
        self.http = http
        self._news_poll_source_type_id: str | None = None
        self._news_domain_id: str | None = None
        self._news_event_schema_id: str | None = None
        self._news_article_schema_id: str | None = None

    # ── Reference data (cached per handler instance) ──────────────────────

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

    def _get_news_event_schema_id(self) -> str:
        if self._news_event_schema_id is None:
            self._news_event_schema_id = self._get_schema_id("news_event")
        return self._news_event_schema_id

    def _get_news_article_schema_id(self) -> str:
        if self._news_article_schema_id is None:
            self._news_article_schema_id = self._get_schema_id("news_article")
        return self._news_article_schema_id

    # ── Sync run lifecycle ─────────────────────────────────────────────────

    def _create_scheduled_run(self, connection_id: str) -> str:
        resp = self.http.post(
            f"/api/v1/source-connections/{connection_id}/runs/create-scheduled",
            json={},
        )
        resp.raise_for_status()
        return resp.json()["id"]

    def _patch_run(
        self,
        connection_id: str,
        run_id: str,
        status: str,
        stats: dict,
        log_lines: list[dict],
    ) -> None:
        body = {
            "status":      status,
            "completedAt": _now_iso(),
            "stats":       stats,
            "logLines":    log_lines,
        }
        resp = self.http.patch(
            f"/api/v1/source-connections/{connection_id}/runs/{run_id}",
            json=body,
        )
        if not resp.is_success:
            print(f"[news_poll] WARNING: PATCH run failed: {resp.status_code} {resp.text[:200]}")

    def _mark_synced(self, connection_id: str) -> None:
        resp = self.http.post(
            f"/api/v1/source-connections/{connection_id}/mark-synced",
            json={"lastSyncedAt": _now_iso()},
        )
        if not resp.is_success:
            print(f"[news_poll] WARNING: mark-synced failed: {resp.status_code} {resp.text[:200]}")

    def _advance_next_run(self, connection_id: str, cron_expression: str | None) -> None:
        try:
            cron_expr = cron_expression or "0 2 * * *"
            cron = croniter(cron_expr, datetime.now(_SCHEDULER_TZ))
            next_run = cron.get_next(datetime).astimezone(timezone.utc)
        except Exception:
            next_run = datetime.now(timezone.utc) + timedelta(hours=24)
        resp = self.http.post(
            f"/api/v1/source-connections/{connection_id}/advance",
            json={"nextRunAt": next_run.isoformat()},
        )
        if not resp.is_success:
            print(f"[news_poll] WARNING: advance failed: {resp.status_code} {resp.text[:200]}")

    def _fetch_api_key(self, connection_id: str) -> str:
        resp = self.http.get(f"/api/v1/source-connections/{connection_id}/secrets")
        resp.raise_for_status()
        secrets = resp.json().get("secrets") or {}
        api_key = secrets.get("apiKey", "")
        if not api_key:
            raise ValueError(f"Missing NewsAPI key in source_connection {connection_id}")
        return api_key

    # ── Main entry point ──────────────────────────────────────────────────

    def run(self, source_connection: dict, existing_run_id: str | None = None) -> None:
        connection_id = source_connection["id"]
        person_id     = source_connection.get("personId")
        if not person_id:
            raise ValueError("news_poll connections must have personId")

        is_adhoc        = existing_run_id is not None
        run_id          = existing_run_id or self._create_scheduled_run(connection_id)
        terminal_status = "failed"

        logs: list[dict] = []
        events_stored   = 0
        articles_stored = 0
        errors: list[str] = []

        try:
            api_key = self._fetch_api_key(connection_id)

            config = source_connection.get("config") or {}
            try:
                categories = json.loads(config.get("categories", "[]"))
            except (json.JSONDecodeError, TypeError):
                categories = []
            try:
                sources = json.loads(config.get("sources", "[]"))
            except (json.JSONDecodeError, TypeError):
                sources = []

            if not categories:
                logs.append(_log_entry("warn", "No categories configured for this connection"))
                terminal_status = "warning"
                return

            logs.append(_log_entry("info", f"categories={categories} sources={sources}"))

            date_end   = datetime.now(timezone.utc)
            date_start = date_end - timedelta(hours=24)

            ranked_events = fetch_ranked_events(api_key, categories, sources, date_start, date_end)
            logs.append(_log_entry("info", f"fetched {len(ranked_events)} ranked events"))

            if not ranked_events:
                logs.append(_log_entry("warn", "No events found for configured categories"))
                terminal_status = "warning"
                return

            source_type_id    = self._get_news_poll_source_type_id()
            schema_id_event   = self._get_news_event_schema_id()
            schema_id_article = self._get_news_article_schema_id()

            from embed import embed

            for event in ranked_events:
                try:
                    event_instance_id = str(uuid.uuid4())

                    content_text = f"{event.title}\n\n{event.summary or ''}"
                    doc_resp = self.http.post("/api/v1/documents", json={
                        "contentText":   content_text,
                        "sourceTypeId":  source_type_id,
                        "embedding":     embed(content_text),
                        "supersedesIds": [],
                        "files":         [],
                        "personId":      person_id,
                    })
                    doc_resp.raise_for_status()
                    doc_id = doc_resp.json()["id"]

                    event_fields: dict = {
                        "title":          event.title,
                        "event_uri":      event.event_uri,
                        "published_date": event.published_date,
                    }
                    if event.summary:
                        event_fields["summary"] = event.summary
                    if event.category:
                        event_fields["category"] = event.category
                    if event.article_count is not None:
                        event_fields["article_count"] = event.article_count
                    if event.social_score is not None:
                        event_fields["social_score"] = event.social_score

                    fact_resp = self.http.post("/api/v1/facts", json={
                        "documentId":         doc_id,
                        "schemaId":           schema_id_event,
                        "entityInstanceId":   event_instance_id,
                        "operationType":      "create",
                        "fields":             event_fields,
                        "embedding":          embed(json.dumps(event_fields, sort_keys=True)),
                        "sourceConnectionId": connection_id,
                    })
                    fact_resp.raise_for_status()
                    events_stored += 1

                    for article in event.articles:
                        try:
                            art_content = f"{article.headline}\n\n{article.description or ''}"
                            art_doc_resp = self.http.post("/api/v1/documents", json={
                                "contentText":   art_content,
                                "sourceTypeId":  source_type_id,
                                "embedding":     embed(art_content),
                                "supersedesIds": [],
                                "files":         [],
                                "personId":      person_id,
                            })
                            art_doc_resp.raise_for_status()
                            art_doc_id = art_doc_resp.json()["id"]

                            art_fields: dict = {
                                "headline":       article.headline,
                                "url":            article.url,
                                "published_date": article.published_date,
                                "event_id":       event_instance_id,
                            }
                            if article.source:
                                art_fields["source"] = article.source
                            if article.description:
                                art_fields["description"] = article.description

                            art_fact_resp = self.http.post("/api/v1/facts", json={
                                "documentId":         art_doc_id,
                                "schemaId":           schema_id_article,
                                "entityInstanceId":   str(uuid.uuid4()),
                                "operationType":      "create",
                                "fields":             art_fields,
                                "embedding":          embed(json.dumps(art_fields, sort_keys=True)),
                                "sourceConnectionId": connection_id,
                            })
                            art_fact_resp.raise_for_status()
                            articles_stored += 1
                        except Exception as e:
                            errors.append(f"article {article.url!r}: {e}")

                except Exception as e:
                    errors.append(f"event {event.event_uri!r}: {e}")

            if errors and events_stored == 0:
                terminal_status = "failed"
            elif errors:
                terminal_status = "warning"
            else:
                terminal_status = "success"

        except Exception as e:
            errors.append(str(e))
            terminal_status = "failed"

        finally:
            for err in errors[:5]:
                logs.append(_log_entry("error", err))
            logs.append(_log_entry("info", f"stored {events_stored} events, {articles_stored} articles"))

            stats: dict = {"added": events_stored + articles_stored}
            if errors:
                stats["errors"] = len(errors)

            print(f"[news_poll] {connection_id} status={terminal_status} events={events_stored} articles={articles_stored} errors={len(errors)}")
            self._patch_run(connection_id, run_id, terminal_status, stats, logs)
            if terminal_status in ("success", "warning"):
                self._mark_synced(connection_id)
            if not is_adhoc and terminal_status in ("success", "warning"):
                self._advance_next_run(connection_id, source_connection.get("syncSchedule"))
