# News Source Connection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate `NewsPollHandler` from the legacy `scheduled_job` model to `source_connections`, storing the NewsAPI key in per-connection `secrets` and categories/sources in `config`.

**Architecture:** Four focused changes — add `api_key` parameter to `fetch_ranked_events`, rewrite `NewsPollHandler` to use source_connections run-lifecycle helpers (same pattern as `PlaidPollHandler`), one-line `_SOURCE_CONN_TYPES` update in `main.py`, and frontend form additions for the news_poll connector chip, API key input, and config fields.

**Tech Stack:** Python 3.11 / httpx / croniter (scheduler); React 18 / TypeScript (frontend). No new API endpoints, no DB migrations.

---

## Files

| Action | Path | What changes |
|---|---|---|
| Modify | `backend/scheduler/providers/newsapiai_source.py:138-151` | Add `api_key: str` parameter to `fetch_ranked_events`; remove env-var read inside |
| Modify | `backend/scheduler/handlers/news_poll.py` | Full rewrite to source_connections pattern |
| Modify | `backend/scheduler/main.py:12` | Add `"news_poll"` to `_SOURCE_CONN_TYPES` |
| Modify | `frontend/src/components/SourceConnectionForm.tsx` | Enable news_poll chip; add API key + config fields; update `handleSave` |

---

## Task 1 — Add `api_key` parameter to `fetch_ranked_events`

**Files:**
- Modify: `backend/scheduler/providers/newsapiai_source.py` — lines 138–151

The function currently reads `api_key = os.environ["NEWSAPIAI_KEY"]` on line 149. We move the key to a caller-supplied parameter so the handler can pass the per-connection key from secrets.

- [ ] **Step 1: Update the function signature and remove the env-var read**

Find the `fetch_ranked_events` function signature at line 138. Replace lines 138–151 with:

```python
def fetch_ranked_events(
    api_key: str,
    categories: list[str],
    sources: list[str],
    date_start: datetime,
    date_end: datetime,
) -> list[NewsEvent]:
    from eventregistry import (
        EventRegistry, RequestEventsInfo, ReturnInfo,
        EventInfoFlags, QueryEvent, RequestEventArticles, QueryItems,
    )

    er = EventRegistry(apiKey=api_key, allowUseOfArchive=False)
```

Everything after line 151 (the rest of the function body) is unchanged.

- [ ] **Step 2: Verify the import compiles**

```bash
cd backend/scheduler && python -c "from providers.newsapiai_source import fetch_ranked_events; print('ok')"
```

Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add backend/scheduler/providers/newsapiai_source.py
git commit -m "feat(scheduler): add api_key param to fetch_ranked_events"
```

---

## Task 2 — Rewrite `NewsPollHandler`

**Files:**
- Modify: `backend/scheduler/handlers/news_poll.py` — full file replacement

Remove the old `scheduled_job`-based implementation and replace with the `source_connections` pattern, mirroring `PlaidPollHandler`'s run lifecycle.

- [ ] **Step 1: Replace the entire file**

```python
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
        assert person_id, "news_poll connections must have personId"

        is_adhoc = existing_run_id is not None
        run_id   = existing_run_id or self._create_scheduled_run(connection_id)

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
                self._patch_run(connection_id, run_id, "warning", {"added": 0}, logs)
                self._mark_synced(connection_id)
                if not is_adhoc:
                    self._advance_next_run(connection_id, source_connection.get("syncSchedule"))
                return

            logs.append(_log_entry("info", f"categories={categories} sources={sources}"))

            date_end   = datetime.now(timezone.utc)
            date_start = date_end - timedelta(hours=24)

            ranked_events = fetch_ranked_events(api_key, categories, sources, date_start, date_end)
            logs.append(_log_entry("info", f"fetched {len(ranked_events)} ranked events"))

            if not ranked_events:
                logs.append(_log_entry("warn", "No events found for configured categories"))
                self._patch_run(connection_id, run_id, "warning", {"added": 0}, logs)
                self._mark_synced(connection_id)
                if not is_adhoc:
                    self._advance_next_run(connection_id, source_connection.get("syncSchedule"))
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
                        "documentId":       doc_id,
                        "schemaId":         schema_id_event,
                        "entityInstanceId": event_instance_id,
                        "operationType":    "create",
                        "fields":           event_fields,
                        "embedding":        embed(json.dumps(event_fields, sort_keys=True)),
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
                                "documentId":       art_doc_id,
                                "schemaId":         schema_id_article,
                                "entityInstanceId": str(uuid.uuid4()),
                                "operationType":    "create",
                                "fields":           art_fields,
                                "embedding":        embed(json.dumps(art_fields, sort_keys=True)),
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
            for err in errors[:5]:
                logs.append(_log_entry("error", err))
            logs.append(_log_entry("info", f"stored {events_stored} events, {articles_stored} articles"))

            if errors and events_stored == 0:
                status = "failed"
            elif errors:
                status = "warning"
            else:
                status = "success"

            stats: dict = {"added": events_stored + articles_stored}
            if errors:
                stats["errors"] = len(errors)

            print(f"[news_poll] {connection_id} status={status} events={events_stored} articles={articles_stored} errors={len(errors)}")
            self._patch_run(connection_id, run_id, status, stats, logs)
            self._mark_synced(connection_id)
            if not is_adhoc:
                self._advance_next_run(connection_id, source_connection.get("syncSchedule"))
```

- [ ] **Step 2: Verify the import works**

```bash
cd backend/scheduler && python -c "from handlers.news_poll import NewsPollHandler; print('ok')"
```

Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add backend/scheduler/handlers/news_poll.py
git commit -m "feat(scheduler): rewrite NewsPollHandler for source_connections model"
```

---

## Task 3 — Register `news_poll` in `_SOURCE_CONN_TYPES`

**Files:**
- Modify: `backend/scheduler/main.py` — line 12

- [ ] **Step 1: Add `"news_poll"` to the set**

Find line 12:
```python
_SOURCE_CONN_TYPES = {"plaid_poll"}
```

Replace with:
```python
_SOURCE_CONN_TYPES = {"plaid_poll", "news_poll"}
```

- [ ] **Step 2: Verify import**

```bash
cd backend/scheduler && python -c "import main; print(main._SOURCE_CONN_TYPES)"
```

Expected: `{'plaid_poll', 'news_poll'}` (order may vary)

- [ ] **Step 3: Commit**

```bash
git add backend/scheduler/main.py
git commit -m "feat(scheduler): add news_poll to _SOURCE_CONN_TYPES"
```

---

## Task 4 — Frontend: news_poll form fields

**Files:**
- Modify: `frontend/src/components/SourceConnectionForm.tsx`

Four changes in this file: (a) fix the "(coming soon)" chip condition, (b) enable the news_poll chip, (c) add news state variables, (d) add news config section, (e) update `handleSave`.

- [ ] **Step 1: Fix "(coming soon)" label condition and enable news_poll chip**

Find the `ConnectorChip` inner JSX (line 123):

```tsx
      {disabled && value !== 'plaid_poll' && (
        <span style={{ color: T.textVeryMuted, fontSize: 11 }}>(coming soon)</span>
      )}
```

Replace with:

```tsx
      {disabled && value !== 'plaid_poll' && value !== 'news_poll' && (
        <span style={{ color: T.textVeryMuted, fontSize: 11 }}>(coming soon)</span>
      )}
```

Then find the news_poll `ConnectorChip` render (line 335):

```tsx
          <ConnectorChip
            icon="📰"
            label="News"
            value="news_poll"
            selected={sourceType === 'news_poll'}
            disabled={true}
            onSelect={setSourceType}
          />
```

Replace with:

```tsx
          <ConnectorChip
            icon="📰"
            label="News"
            value="news_poll"
            selected={sourceType === 'news_poll'}
            disabled={isEditing}
            onSelect={setSourceType}
          />
```

- [ ] **Step 2: Add news state variables**

Find the Plaid credential state declarations (around line 151):

```tsx
  // Plaid credentials — only used during create
  const [plaidClientId, setPlaidClientId] = useState('')
  const [plaidSecret, setPlaidSecret] = useState('')
```

Add news state immediately after:

```tsx
  // News credentials and config
  const [newsApiKey, setNewsApiKey] = useState('')
  const [newsCategories, setNewsCategories] = useState('')
  const [newsSources, setNewsSources] = useState('')
```

- [ ] **Step 3: Populate news state on edit load**

Find the `if (conn.sourceType === 'plaid_poll')` block inside the `load` effect (around line 184):

```tsx
        if (conn.sourceType === 'plaid_poll') {
          setLoadingItems(true)
          listPlaidItems(editingId)
            .then(items => { if (mounted) setPlaidItems(items) })
            .catch(e => { if (mounted) setLoadError(e instanceof Error ? e.message : 'Failed to load linked banks') })
            .finally(() => { if (mounted) setLoadingItems(false) })
        }
```

Replace with:

```tsx
        if (conn.sourceType === 'plaid_poll') {
          setLoadingItems(true)
          listPlaidItems(editingId)
            .then(items => { if (mounted) setPlaidItems(items) })
            .catch(e => { if (mounted) setLoadError(e instanceof Error ? e.message : 'Failed to load linked banks') })
            .finally(() => { if (mounted) setLoadingItems(false) })
        }
        if (conn.sourceType === 'news_poll') {
          const cfg = conn.config as Record<string, string>
          if (cfg.categories) setNewsCategories(cfg.categories)
          if (cfg.sources) setNewsSources(cfg.sources)
        }
```

- [ ] **Step 4: Add news config section in the JSX**

Find the comment `{/* Section 4: Sync Options */}` (around line 504). Insert the news section immediately before it:

```tsx
      {/* Section 3b: News-specific */}
      {sourceType === 'news_poll' && (
        <div style={{ marginBottom: 24 }}>
          {!isEditing && (
            <>
              <span style={sectionLabel}>NewsAPI Key</span>
              <input
                type="password"
                value={newsApiKey}
                onChange={e => setNewsApiKey(e.target.value)}
                placeholder="NewsAPI.ai API key"
                style={{
                  width: '100%',
                  background: T.bgInput,
                  border: `1px solid ${T.border}`,
                  borderRadius: 8,
                  padding: '10px 12px',
                  color: T.textPrimary,
                  fontSize: 14,
                  outline: 'none',
                  boxSizing: 'border-box',
                  marginBottom: 8,
                }}
              />
              <div style={{ color: T.textMuted, fontSize: 12, marginBottom: 16 }}>
                Key is encrypted and stored securely.
              </div>
            </>
          )}
          {isEditing && (
            <div style={{ background: T.bgRunStrip, border: `1px solid ${T.borderRun}`, borderRadius: 8, padding: '10px 14px', color: T.textSecondary, fontSize: 13, marginBottom: 16 }}>
              API Key: ••••••• (stored encrypted)
            </div>
          )}
          <span style={sectionLabel}>Categories (JSON array of NewsAPI category URIs)</span>
          <input
            type="text"
            value={newsCategories}
            onChange={e => setNewsCategories(e.target.value)}
            placeholder='["dmoz/Business/Finance","dmoz/Computers/Internet"]'
            style={{
              width: '100%',
              background: T.bgInput,
              border: `1px solid ${T.border}`,
              borderRadius: 8,
              padding: '10px 12px',
              color: T.textPrimary,
              fontSize: 13,
              fontFamily: "'SF Mono', 'Fira Code', monospace",
              outline: 'none',
              boxSizing: 'border-box',
              marginBottom: 16,
            }}
          />
          <span style={sectionLabel}>Sources (optional — JSON array of NewsAPI source URIs)</span>
          <input
            type="text"
            value={newsSources}
            onChange={e => setNewsSources(e.target.value)}
            placeholder='["nytimes.com","bbc.co.uk"]'
            style={{
              width: '100%',
              background: T.bgInput,
              border: `1px solid ${T.border}`,
              borderRadius: 8,
              padding: '10px 12px',
              color: T.textPrimary,
              fontSize: 13,
              fontFamily: "'SF Mono', 'Fira Code', monospace",
              outline: 'none',
              boxSizing: 'border-box',
            }}
          />
        </div>
      )}
```

- [ ] **Step 5: Update `handleSave` for news_poll**

Find the `handleSave` function's `else if (sourceType === 'plaid_poll')` branch (around line 264):

```tsx
      } else if (sourceType === 'plaid_poll') {
        if (!plaidClientId.trim() || !plaidSecret.trim()) {
          setSaveError('Plaid Client ID and Secret are required.')
          setSaving(false)
          return
        }
        await createSourceConnection({
          sourceType,
          connectionName: connectionName.trim(),
          personId: session.personId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : undefined,
          secrets: JSON.stringify({ client_id: plaidClientId.trim(), secret: plaidSecret.trim() }),
        })
      } else {
        await createSourceConnection({
          sourceType,
          connectionName: connectionName.trim(),
          personId: session.personId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : undefined,
        })
      }
```

Replace with:

```tsx
      } else if (sourceType === 'plaid_poll') {
        if (!plaidClientId.trim() || !plaidSecret.trim()) {
          setSaveError('Plaid Client ID and Secret are required.')
          setSaving(false)
          return
        }
        await createSourceConnection({
          sourceType,
          connectionName: connectionName.trim(),
          personId: session.personId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : undefined,
          secrets: JSON.stringify({ client_id: plaidClientId.trim(), secret: plaidSecret.trim() }),
        })
      } else if (sourceType === 'news_poll') {
        if (!newsApiKey.trim()) {
          setSaveError('NewsAPI key is required.')
          setSaving(false)
          return
        }
        if (!newsCategories.trim()) {
          setSaveError('At least one category is required.')
          setSaving(false)
          return
        }
        await createSourceConnection({
          sourceType,
          connectionName: connectionName.trim(),
          personId: session.personId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : undefined,
          secrets: JSON.stringify({ apiKey: newsApiKey.trim() }),
          config: { categories: newsCategories.trim(), sources: newsSources.trim() },
        })
      } else {
        await createSourceConnection({
          sourceType,
          connectionName: connectionName.trim(),
          personId: session.personId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : undefined,
        })
      }
```

Also update the edit path's `config` field. Find the `updateSourceConnection` call in the `isEditing` branch (around line 254):

```tsx
        await updateSourceConnection(editingId, {
          sourceType: existingConn.sourceType,
          connectionName: connectionName.trim(),
          personId: existingConn.personId,
          householdId: existingConn.householdId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : null,
          config: existingConn.config,
        })
```

Replace with:

```tsx
        await updateSourceConnection(editingId, {
          sourceType: existingConn.sourceType,
          connectionName: connectionName.trim(),
          personId: existingConn.personId,
          householdId: existingConn.householdId,
          syncScheduled,
          syncAdhoc,
          syncSchedule: syncScheduled ? syncSchedule : null,
          config: existingConn.sourceType === 'news_poll'
            ? { categories: newsCategories.trim(), sources: newsSources.trim() }
            : existingConn.config,
        })
```

- [ ] **Step 6: TypeScript check**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -30
```

Expected: no errors.

- [ ] **Step 7: Manual UI verification**

With the dev server running (`cd frontend && npm run dev`):
- Navigate to Source Connections → Add Connection
- Confirm the 📰 News chip is now selectable
- Select it and verify the NewsAPI Key + Categories + Sources fields appear
- Enter dummy values and confirm Save sends the right request (check Network tab in browser DevTools)
- Create a real news connection with valid NewsAPI key and category URIs
- Confirm the card appears in the list with "Sync Now" / schedule toggles working

- [ ] **Step 8: Commit**

```bash
git add frontend/src/components/SourceConnectionForm.tsx
git commit -m "feat(ui): enable news_poll connector — API key, categories, sources fields"
```

---

## Self-review checklist (completed)

- ✅ **Spec coverage:** `api_key` param (Task 1), handler rewrite with `_fetch_api_key` + `config` read + sync run lifecycle (Task 2), `_SOURCE_CONN_TYPES` (Task 3), chip enable + API key + categories + sources + save logic (Task 4)
- ✅ **No placeholders:** All code blocks are complete and self-contained
- ✅ **Type consistency:** `fetch_ranked_events(api_key, categories, sources, date_start, date_end)` — `api_key` added as first param in Task 1 and called that way in Task 2; `_patch_run`/`_mark_synced`/`_advance_next_run` signatures match between Task 2 and `PlaidPollHandler` reference; `newsApiKey`/`newsCategories`/`newsSources` state names consistent across Steps 2–5 of Task 4
- ✅ **Edit config update:** Task 4 Step 5 updates both the create path AND the edit `updateSourceConnection` call to write the news config on save
