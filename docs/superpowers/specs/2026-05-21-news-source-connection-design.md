# News Source Connection — Design Spec

**Date:** 2026-05-21
**Status:** Approved

---

## Overview

Migrate the existing `NewsPollHandler` from the legacy `scheduled_job` model to the `source_connections` model. The two-phase ranking algorithm, fact creation (news_event + news_article), and embedding logic are unchanged. Only the infrastructure layer changes: where config and secrets come from, and how run state is tracked.

---

## What Changes

### Scheduler — `NewsPollHandler`

Rewrite the handler to match the `PlaidPollHandler` contract:

**Signature:**
```python
def run(self, source_connection: dict, existing_run_id: str | None = None) -> None
```

**Secrets:** Fetch the NewsAPI key via `GET /api/v1/source-connections/{id}/secrets` — same endpoint used by Plaid for client_id/secret. The decrypted response contains `{"apiKey": "<newsapi.ai key>"}`.

**Config:** Read `categories` (required) and `sources` (optional) directly from `source_connection["config"]` — a dict with keys `categories` (JSON-encoded array of NewsAPI category URIs) and `sources` (JSON-encoded array of source URIs). No fact lookup at runtime.

**Run tracking:** Replace `scheduled_job_run` writes with `sync_runs` calls — same helpers Plaid uses:
- `_create_scheduled_run(connection_id)` → `str` run_id (when `existing_run_id` is None)
- `_patch_run(connection_id, run_id, status, stats, log_lines)`
- `_mark_synced(connection_id)`
- `_advance_next_run(connection_id, cron_expression)`

**Unchanged:** Two-phase ranking algorithm, budget distribution across categories, news_event fact creation, news_article fact creation, embedding calls.

### Scheduler — `main.py`

Add `"news_poll"` to `_SOURCE_CONN_TYPES`. This single change routes news through `_poll_source_connections` and `_poll_adhoc_runs`, retiring the legacy `_poll_scheduled_jobs` path for news.

### Frontend — `SourceConnectionForm.tsx`

Three additions when `sourceType === 'news_poll'`:

1. **Enable the chip** — remove the "coming soon" disabled state from the `news_poll` connector chip.

2. **Secrets field (create only)** — a single password input labeled "NewsAPI Key". Submitted as `{"apiKey": "<value>"}` in the `secrets` field of the create request. Write-only: not fetched back on edit.

3. **Config fields (create and edit):**
   - **Categories** (required) — text input, label "Categories (JSON array of NewsAPI category URIs)". Pre-populated from `conn.config.categories` on edit. Stored as a JSON-encoded string, e.g. `["dmoz/Business/Finance"]`.
   - **Sources** (optional) — text input, label "Sources (JSON array of NewsAPI source URIs, optional)". Pre-populated from `conn.config.sources` on edit.

Sync options (schedule, adhoc toggle, cron expression) work identically to Plaid connections.

---

## What Does Not Change

| Component | Status |
|---|---|
| Scala HTTP server | No changes — source_connections API already supports `news_poll` source_type |
| DB schema | No changes — no native `news.*` tables; facts go into generic `document`/`fact` tables |
| NewsAPI ranking algorithm | Unchanged |
| Fact creation (news_event, news_article) | Unchanged |
| Embedding calls | Unchanged |
| `news_preference` entity type schema | Retained in schema but no longer written to by the handler; existing facts remain queryable |

---

## Data Flow

```
SourceConnectionForm (create)
  → POST /api/v1/source-connections
      config: { categories: "[...]", sources: "[...]" }
      secrets: { apiKey: "..." }            ← encrypted at rest

Scheduler poll (_poll_source_connections)
  → GET /api/v1/source-connections/due
  → NewsPollHandler.run(conn)
      GET /api/v1/source-connections/{id}/secrets  → apiKey
      conn["config"]["categories"]                 → categories
      conn["config"]["sources"]                    → sources
      _create_scheduled_run()
      fetch_ranked_events(apiKey, categories, sources)
      POST /api/v1/documents  (one per news_event/news_article)
      POST /api/v1/facts
      _patch_run(status=success, stats={added:N})
      _mark_synced()
      _advance_next_run()

Scheduler poll (_poll_adhoc_runs)
  → existing_run_id passed in; same handler, skips _create_scheduled_run and _advance_next_run
```

---

## Secrets Format

The encrypted blob stored in `source_connections.secrets` for `news_poll`:

```json
{ "apiKey": "<newsapi.ai key>" }
```

This matches the pattern of the existing secrets endpoint — the handler fetches and decrypts via `GET /api/v1/source-connections/{id}/secrets`.

---

## Out of Scope

- Migrating existing `scheduled_job` rows to `source_connections` (users create new connections manually)
- A category picker UI (free-text JSON input is sufficient for now)
- Deduplication beyond what the existing handler already does (event_uri-based)
- Removing the `news_preference` entity type schema
