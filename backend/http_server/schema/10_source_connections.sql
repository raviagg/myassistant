-- ============================================================
-- 10_source_connections.sql
-- source_connections table — unified connector registry
--
-- Every external data source that feeds the assistant is
-- registered here as a "source connection". This table
-- supersedes scheduled_job (09_news_scheduler.sql) and
-- generalises it to cover all connector types:
--   plaid, news, chatbot, bulk_file, bulk_image, gmail, …
--
-- A connection belongs to exactly one owner: either a person
-- or a household — never both, never neither.
--
-- Sync can be triggered two independent ways:
--   sync_scheduled — cron-driven background polling
--   sync_adhoc     — user-triggered on-demand run
-- Both flags are independent; a connection can support either
-- or both modes.
--
-- Secrets (tokens, refresh tokens, API keys) are stored
-- encrypted as an AES-256-GCM blob in the secrets column.
-- The plaintext is NEVER returned by the API — it is
-- decrypted only at sync time by the connector worker.
--
-- Non-secret config (institution name, client_id, account
-- filter settings etc.) lives in the config JSONB column
-- in plaintext.
-- ============================================================


-- ------------------------------------------------------------
-- TABLE
-- ------------------------------------------------------------

CREATE TABLE source_connections (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- connector identity
    source_type       TEXT        NOT NULL,
    connection_name   TEXT        NOT NULL,

    -- ownership: exactly one of person_id or household_id required
    person_id         UUID        REFERENCES person(id),
    household_id      UUID        REFERENCES household(id),

    -- configuration
    config            JSONB       NOT NULL DEFAULT '{}',
    secrets           TEXT,                          -- AES-256-GCM encrypted blob

    -- sync modes (independent — both may be true simultaneously)
    sync_scheduled    BOOLEAN     NOT NULL DEFAULT false,
    sync_adhoc        BOOLEAN     NOT NULL DEFAULT false,
    sync_schedule     TEXT,                          -- cron expression; null when sync_scheduled=false

    -- scheduling state
    next_run_at       TIMESTAMPTZ,                   -- when scheduler should next trigger this connection
    last_synced_at    TIMESTAMPTZ,                   -- timestamp of most recently completed run

    -- lifecycle
    status            TEXT        NOT NULL DEFAULT 'active',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- a connection must belong to exactly one owner
    CONSTRAINT sc_exactly_one_owner CHECK (
        (person_id IS NOT NULL AND household_id IS NULL) OR
        (person_id IS NULL AND household_id IS NOT NULL)
    ),

    -- status must be one of the defined lifecycle states
    CONSTRAINT sc_status_valid CHECK (status IN ('active', 'paused', 'error')),

    -- a cron expression may only be set when scheduled sync is enabled
    CONSTRAINT sc_schedule_requires_flag CHECK (
        sync_schedule IS NULL OR sync_scheduled = true
    )
);

CREATE INDEX idx_source_connections_person
    ON source_connections(person_id)
    WHERE person_id IS NOT NULL;

CREATE INDEX idx_source_connections_household
    ON source_connections(household_id)
    WHERE household_id IS NOT NULL;

-- Partial index used by the scheduler to find connections due for a run.
-- Only active, scheduled connections are included to keep the scan tight.
CREATE INDEX idx_source_connections_due
    ON source_connections(next_run_at)
    WHERE sync_scheduled = true AND status = 'active';


-- ------------------------------------------------------------
-- COMMENTS — TABLE
-- ------------------------------------------------------------

COMMENT ON TABLE source_connections IS
  'Unified registry of all external data source connections.
   Every connector that feeds data into the assistant —
   Plaid bank feeds, news polling, Gmail, bulk file uploads,
   chatbot re-extractions — is registered here as one row.

   This table supersedes the earlier scheduled_job table
   (09_news_scheduler.sql). New connectors use source_connections
   exclusively; scheduled_job is retained only for the legacy
   news poller until it is migrated.

   Ownership is exclusive: a connection belongs to exactly one
   person OR one household (sc_exactly_one_owner constraint).

   Sync can be triggered in two independent ways:
     sync_scheduled=true — cron-driven background polling
     sync_adhoc=true     — user-triggered on-demand run
   Both may be true simultaneously (e.g. Plaid: daily cron
   plus a "Refresh Now" button in the UI).

   Secrets (OAuth tokens, API keys) are stored AES-256-GCM
   encrypted in the secrets column. The plaintext is never
   returned by the API; it is decrypted only at sync time
   inside the connector worker process.

   Non-secret config (institution name, selected account IDs,
   filter preferences) is stored as plain JSONB in config.';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS
-- ------------------------------------------------------------

COMMENT ON COLUMN source_connections.id IS
  'Unique identifier for this source connection.
   Referenced by sync_runs.source_connection_id.
   Example: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"';

COMMENT ON COLUMN source_connections.source_type IS
  'The type of connector this connection uses.
   Determines which worker handles sync runs and which
   fields are expected inside config / secrets.
   Known values (not enforced by FK to allow extension
   without migrations):
     "plaid"      — Plaid bank/investment feed
     "news"       — NewsAPI topic polling
     "gmail"      — Gmail polling
     "chatbot"    — Chatbot re-extraction pass
     "bulk_file"  — Batch file ingestion
     "bulk_image" — Batch image ingestion
   Example: "plaid"';

COMMENT ON COLUMN source_connections.connection_name IS
  'Human-readable label for this connection, shown in the UI.
   Not required to be unique — the same person may have two
   Plaid connections with different names for different banks.
   Example: "Chase Checking", "Vanguard Brokerage"';

COMMENT ON COLUMN source_connections.person_id IS
  'The person who owns this connection.
   Exactly one of person_id or household_id must be set
   (sc_exactly_one_owner constraint).
   Null when the connection is owned by a household.
   Example: personal Plaid bank account → person_id set.';

COMMENT ON COLUMN source_connections.household_id IS
  'The household that owns this connection.
   Exactly one of person_id or household_id must be set
   (sc_exactly_one_owner constraint).
   Null when the connection is owned by an individual person.
   Example: shared utility bill bulk-upload → household_id set.';

COMMENT ON COLUMN source_connections.config IS
  'Non-secret configuration for this connection stored as
   plaintext JSONB. Safe to return via API.
   Contents are connector-specific:
     Plaid:  {"institution_name": "Chase",
               "institution_id":   "ins_3",
               "account_ids":      ["acc_abc", "acc_def"]}
     News:   {"topics": ["AI", "climate"], "language": "en"}
     Gmail:  {"label_filter": "INBOX", "max_per_run": 50}
   Default is an empty object; workers must handle missing keys
   gracefully with sensible defaults.';

COMMENT ON COLUMN source_connections.secrets IS
  'AES-256-GCM encrypted blob containing sensitive credentials
   for this connection. NEVER returned by any API endpoint.
   Decrypted only at sync time by the connector worker.
   Contents before encryption are connector-specific JSON:
     Plaid:  {"access_token": "access-sandbox-…",
               "item_id":      "…"}
     Gmail:  {"refresh_token": "…", "access_token": "…"}
   Null for connectors that do not require secrets (e.g.
   public RSS feeds, local bulk file imports).
   The encryption key is held in the deployment environment
   and is never stored in the database.';

COMMENT ON COLUMN source_connections.sync_scheduled IS
  'Whether cron-driven background polling is enabled for
   this connection. When true, the scheduler polls
   idx_source_connections_due and triggers a run when
   next_run_at is due.
   Independent of sync_adhoc — both can be true.
   Example: a Plaid connection with daily scheduled sync
   AND a user-accessible "Refresh Now" button has both
   sync_scheduled=true and sync_adhoc=true.';

COMMENT ON COLUMN source_connections.sync_adhoc IS
  'Whether on-demand user-triggered sync is supported for
   this connection. When true, the API exposes a
   POST /source-connections/{id}/sync endpoint that
   immediately enqueues an adhoc run.
   Independent of sync_scheduled — both can be true.
   Example: bulk file import connections typically have
   sync_adhoc=true only (no scheduled polling makes sense).';

COMMENT ON COLUMN source_connections.sync_schedule IS
  'Cron expression describing how often the scheduler should
   trigger this connection. Null when sync_scheduled=false
   (sc_schedule_requires_flag constraint prevents a cron
   expression from being set without enabling scheduled sync).
   Standard 5-field UNIX cron syntax.
   Examples:
     "0 6 * * *"    — every day at 06:00 UTC
     "0 */6 * * *"  — every 6 hours
     "0 8 * * 1"    — every Monday at 08:00 UTC';

COMMENT ON COLUMN source_connections.next_run_at IS
  'Timestamp when the scheduler should next trigger a sync run
   for this connection. Populated (or updated) by the scheduler
   after each completed run: next_run_at = now() + interval
   derived from sync_schedule.
   The scheduler queries idx_source_connections_due
   (WHERE sync_scheduled=true AND status=''active'') and
   picks up rows where next_run_at <= now().
   Null until the first scheduled run has completed or
   the connection is first activated.';

COMMENT ON COLUMN source_connections.last_synced_at IS
  'Timestamp of the most recently completed sync run
   (success, warning, or failed). Updated by the connector
   worker on run completion regardless of outcome.
   Null if no sync has ever completed for this connection.
   Useful for the UI "Last synced X minutes ago" label.';

COMMENT ON COLUMN source_connections.status IS
  'Lifecycle state of this connection.
   Allowed values (sc_status_valid constraint):
     "active"  — connection is healthy and sync is permitted
     "paused"  — sync temporarily suspended by the user;
                 scheduler skips this connection
     "error"   — connector worker set this after repeated
                 failures; requires user attention to resolve
   Transitions:
     active → paused  (user pauses)
     paused → active  (user resumes)
     active → error   (worker detects unrecoverable failure)
     error  → active  (user re-authorises / fixes config)';

COMMENT ON COLUMN source_connections.created_at IS
  'Timestamp when this connection was first registered.
   Never updated — use last_synced_at to track activity.';
