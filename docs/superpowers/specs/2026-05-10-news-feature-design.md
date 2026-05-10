# News Feature — Design Spec

## Context

Ravi wants a personal news experience inside the existing assistant: subscribe to topics, receive daily/weekly digests in plain English (with jargon, names, agencies, and locations explained verbosely), drill down on specific articles or keywords via chat, and have everything stored as structured facts for later querying. Podcasts are out of scope for this iteration.

---

## Architecture

Five pieces, three new:

```
[NewsAPI]
    ↓
[scheduler service]   ← new Docker container (backend/scheduler/)
    ↓ POST /api/v1/documents + /api/v1/facts
[http-server]         ← new Flyway migrations + REST endpoints
    ↓
[PostgreSQL]          ← new tables + seeded entity type schemas

[chat UI]             ← unchanged
    ↓
[chatbot-server]      ← two new MCP tools: fetch_url, web_search
    ↓
[Claude on Bedrock]   ← unchanged
```

**What is unchanged:** the entire existing stack — http-server, chatbot-server, agent pipeline, frontend.

---

## Data Model

All changes delivered via a single new Flyway migration: `backend/http_server/schema/09_news_scheduler.sql`

### 1. Rename domain `news_preferences` → `news`

```sql
UPDATE domain SET id = 'news', description = 'News articles and topic preferences'
WHERE id = 'news_preferences';
```

### 2. Alter `source_type` — add `is_scheduled` flag + `news_poll`

```sql
ALTER TABLE source_type ADD COLUMN is_scheduled BOOLEAN NOT NULL DEFAULT false;
INSERT INTO source_type (id, description, is_scheduled)
VALUES ('news_poll', 'News articles fetched from NewsAPI', true);
```
Do NOT retroactively mark `plaid_poll`/`gmail_poll` — that is a separate cleanup.

### 3. New table: `scheduled_job`

```sql
CREATE TABLE scheduled_job (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_type_id  TEXT        NOT NULL REFERENCES source_type(id),
    person_id       UUID        REFERENCES person(id),
    household_id    UUID        REFERENCES household(id),
    cron_expression TEXT        NOT NULL,
    config          JSONB       NOT NULL DEFAULT '{}',
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT scheduled_job_scope CHECK (
        (person_id IS NOT NULL) != (household_id IS NOT NULL)
    )
);
```

`config` stores job-specific parameters (e.g. `{"page_size": 20}`). API keys are always env vars, never in `config`.

`news_poll` jobs must always have `person_id` set (news topics are personal preferences, not household-level).

### 4. New table: `scheduled_job_run`

```sql
CREATE TABLE scheduled_job_run (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id          UUID        NOT NULL REFERENCES scheduled_job(id),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    status          TEXT        NOT NULL CHECK (status IN ('success', 'error', 'partial')),
    error           TEXT,
    articles_stored INT         NOT NULL DEFAULT 0
);
```

### 5. New entity type schemas (seeded)

**`news_topic`** — stores the user's interests, managed via chat:
```
domain: news, entity_type: news_topic
mandatory_fields: ["name"]
field_definitions:
  name   (text)    — topic label, e.g. "AI", "climate change"
  active (boolean) — whether this topic is currently active
```

**`news_article`** — one fact per fetched article:
```
domain: news, entity_type: news_article
mandatory_fields: ["headline", "url", "published_date"]
field_definitions:
  headline       (text) — article title
  source         (text) — outlet name, e.g. "BBC News"
  url            (text) — direct link to article
  published_date (date) — ISO date YYYY-MM-DD
  topic          (text) — which news_topic this relates to
  description    (text) — 2-line summary from NewsAPI
```

---

## Scheduler Service (`backend/scheduler/`)

A new lightweight Python service — one Docker container, polls `scheduled_job` every 60 seconds.

### File structure

```
backend/scheduler/
  main.py
  handlers/
    base.py           — abstract BaseHandler with run(job) → None
    news_poll.py      — NewsAPI implementation
  providers/
    news_source.py    — abstract NewsSource with fetch(topic, since) → [Article]
    newsapi_source.py — NewsAPI implementation
    rss_source.py     — RSS stub (future)
  pyproject.toml
  Dockerfile
```

### Env vars

| Var | Default | Purpose |
|-----|---------|---------|
| `HTTP_SERVER_URL` | — | Base URL of http-server |
| `AUTH_TOKEN` | — | Bearer token |
| `NEWS_SOURCE_PROVIDER` | `newsapi` | `newsapi` or `rss` |
| `NEWSAPI_KEY` | — | NewsAPI.org API key |

### Dispatch loop (`main.py`)

Every 60 seconds:
1. Query `scheduled_job WHERE enabled = true AND next_run_at <= now()`
2. For each job: look up handler by `source_type_id`, call `handler.run(job)`
3. Compute `next_run_at` from cron expression, update row

Adding a new job type: add a handler class + register it in the handler map. Nothing else changes.

### `NewsPoller.run(job)` flow

1. Assert `job.person_id IS NOT NULL`
2. GET `current_facts` → filter `entity_type=news_topic, active=true, person_id=job.person_id`
3. Find `last_run_at` from latest `scheduled_job_run` for this job (or 24h ago if first run)
4. For each active topic: call `NewsSource.fetch(topic, since=last_run_at)`
5. For each article returned:
   - `POST /api/v1/documents` (`source_type_id=news_poll`, `person_id`, `content_text=headline + description`)
   - `POST /api/v1/facts` (`entity_type=news_article`, fields: headline, source, url, published_date, topic, description)
6. Insert `scheduled_job_run` (status, articles_stored, finished_at)

No LLM at ingestion. Raw data stored. Claude only runs when the user asks a question in chat.

### Provider abstraction

```python
class NewsSource(ABC):
    def fetch(self, topic: str, since: datetime) -> list[Article]: ...

class NewsApiSource(NewsSource): ...   # selected when NEWS_SOURCE_PROVIDER=newsapi
class RssSource(NewsSource): ...       # selected when NEWS_SOURCE_PROVIDER=rss
```

---

## Web Tools (`backend/mcp_server/tools/web.py`)

Two new MCP tools registered in `backend/chatbot_server/core/live_executor.py`.

### `fetch_url(url: str) → str`

Fetches a webpage and returns readable plain text (HTML stripped).

- Implementation: `httpx.get(url, follow_redirects=True)` + `BeautifulSoup(..., "html.parser").get_text()`
- Add `beautifulsoup4` to `backend/chatbot_server/pyproject.toml`
- Used when drilling into a specific article: agent retrieves the stored URL from the `news_article` fact, calls `fetch_url`, summarises in plain English

### `web_search(query: str, num_results: int = 5) → list[{title, snippet, url}]`

Searches the web and returns top results.

- Swappable via `WEB_SEARCH_PROVIDER=duckduckgo|brave` (default: `duckduckgo`)
- `DuckDuckGoSearch` — free, no API key, uses DDG Instant Answer API
- `BraveSearch` — add `BRAVE_API_KEY` env var; better quality, 2000 free queries/month
- Used for background Q&A: "What is quantitative easing?", "Who runs the Fed?"

```python
class WebSearchProvider(ABC):
    def search(self, query: str, num_results: int) -> list[dict]: ...

class DuckDuckGoSearch(WebSearchProvider): ...
class BraveSearch(WebSearchProvider): ...
```

---

## MCP Tools for Schedule Management

New file: `backend/mcp_server/tools/scheduled_jobs.py`

| MCP Tool | HTTP Method + Path |
|---|---|
| `create_scheduled_job(source_type_id, person_id, cron_expression, config)` | POST /api/v1/scheduled-jobs |
| `list_scheduled_jobs(person_id)` | GET /api/v1/scheduled-jobs?personId={id} |
| `update_scheduled_job(id, cron_expression?, config?, enabled?)` | PUT /api/v1/scheduled-jobs/{id} |
| `delete_scheduled_job(id)` | DELETE /api/v1/scheduled-jobs/{id} |

Registered in `backend/chatbot_server/core/live_executor.py`.

Corresponding Scala routes + models added to `backend/http_server/src/`.

---

## HTTP Contract Changes

New endpoints to add to `docs/http-contract.md`:

```
POST   /api/v1/scheduled-jobs
GET    /api/v1/scheduled-jobs?personId={uuid}
PUT    /api/v1/scheduled-jobs/{id}
DELETE /api/v1/scheduled-jobs/{id}
GET    /api/v1/scheduled-jobs/{id}/runs
```

---

## Chat Interaction Examples

```
Setup (one time):
"I want news about AI and climate every morning at 7am"
  → create_fact(news_topic, {name:"AI", active:true})
  → create_fact(news_topic, {name:"climate", active:true})
  → create_scheduled_job(source_type_id="news_poll", cron="0 7 * * *", person_id=...)

Daily reading:
"What's in the news today?"
  → search_current_facts(news_article, published_date=today)
  → LLM summarises in plain language, explains all jargon/names/agencies

Drill-down:
"Tell me more about the Fed rate article"
  → search_current_facts(news_article, "Fed rate") → gets URL
  → fetch_url(url) → full article text
  → LLM explains simply

Background question:
"What is quantitative easing?"
  → web_search("quantitative easing simple explanation")
  → LLM summarises in plain language

Managing preferences:
"Stop showing me climate news"
  → update_fact(news_topic climate, {active:false})

"Change my news to weekly on Mondays"
  → update_scheduled_job(id, cron="0 7 * * 1")

"What jobs are scheduled?"
  → list_scheduled_jobs(person_id=...)
```

---

## Files to Create / Modify

| File | Action |
|---|---|
| `backend/http_server/schema/09_news_scheduler.sql` | New Flyway migration |
| `backend/scheduler/main.py` | New |
| `backend/scheduler/handlers/base.py` | New |
| `backend/scheduler/handlers/news_poll.py` | New |
| `backend/scheduler/providers/news_source.py` | New |
| `backend/scheduler/providers/newsapi_source.py` | New |
| `backend/scheduler/providers/rss_source.py` | New (stub) |
| `backend/scheduler/pyproject.toml` | New |
| `backend/scheduler/Dockerfile` | New |
| `backend/mcp_server/tools/web.py` | New |
| `backend/mcp_server/tools/scheduled_jobs.py` | New |
| `backend/chatbot_server/core/live_executor.py` | Register 6 new tools |
| `backend/chatbot_server/pyproject.toml` | Add beautifulsoup4 |
| `backend/http_server/src/.../routes/ScheduledJobRoutes.scala` | New |
| `backend/http_server/src/.../models/ScheduledJob.scala` | New |
| `docker-compose.yml` | Add scheduler service |
| `docs/http-contract.md` | Add scheduled-jobs endpoints |
| `docs/mcp-tools.md` | Add 6 new tools |

---

## Verification

1. Insert a `scheduled_job` row manually → confirm scheduler fires it and `scheduled_job_run` is created with `status=success`
2. Ask "What's in the news today?" → confirm agent returns plain-language summaries with jargon explained
3. Ask "Tell me more about [article]" → confirm `fetch_url` returns full content and LLM explains it simply
4. Ask "What is inflation?" → confirm `web_search` returns results and LLM summarises plainly
5. Ask "Add tech to my news topics" → confirm `news_topic` fact created with `active=true`
6. Ask "What jobs are scheduled?" → confirm agent lists jobs via `list_scheduled_jobs`
7. Ask "Pause my news" → confirm `enabled=false` updated via `update_scheduled_job`
