-- ============================================================
-- 11_sync_runs.sql
-- sync_runs table — per-execution history for source connections
--
-- Every time a source_connection is triggered — whether by the
-- cron scheduler, a user-initiated "Refresh Now", or an AI
-- re-extraction pass — one row is inserted here with
-- status='running'. The connector worker updates the row to
-- success / warning / failed on completion.
--
-- This table supersedes scheduled_job_run (09_news_scheduler.sql).
-- Key improvements over the old table:
--   • run_type covers all sync scenarios, not just scheduled
--   • stats is connector-agnostic JSONB instead of a hardcoded
--     articles_stored INT
--   • log_lines is a structured JSONB array that powers the
--     "View Logs" modal in the UI
--   • status='running' as the initial state (old table inserted
--     with the final status and no intermediate state)
--
-- Rows are append-only. Completed rows are never updated.
-- ============================================================


-- ------------------------------------------------------------
-- TABLE
-- ------------------------------------------------------------

CREATE TABLE sync_runs (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- foreign key to the connection that was synced
    source_connection_id  UUID        NOT NULL REFERENCES source_connections(id) ON DELETE CASCADE,

    -- how and why this run was triggered
    run_type              TEXT        NOT NULL,

    -- lifecycle state of this individual run
    status                TEXT        NOT NULL,

    -- timing
    started_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ,

    -- connector-agnostic outcome metrics
    stats                 JSONB,

    -- structured log lines for the UI "View Logs" modal
    log_lines             JSONB       NOT NULL DEFAULT '[]',

    -- run_type must be one of the defined trigger kinds
    CONSTRAINT sr_run_type_valid CHECK (
        run_type IN ('scheduled', 'adhoc', 're_extract')
    ),

    -- status must be one of the defined run states
    CONSTRAINT sr_status_valid CHECK (
        status IN ('running', 'success', 'warning', 'failed')
    ),

    -- log_lines must always be a JSON array (never an object or scalar)
    CONSTRAINT sr_log_lines_array CHECK (
        jsonb_typeof(log_lines) = 'array'
    )
);

-- Covers the most common query: list all runs for a given connection,
-- newest first (used by the connection detail page in the UI).
CREATE INDEX idx_sync_runs_connection
    ON sync_runs(source_connection_id);

-- Covers time-range queries and admin dashboards that need recent
-- runs across all connections.
CREATE INDEX idx_sync_runs_started
    ON sync_runs(started_at DESC);


-- ------------------------------------------------------------
-- COMMENTS — TABLE
-- ------------------------------------------------------------

COMMENT ON TABLE sync_runs IS
  'Per-execution history for all source connection sync runs.
   Every trigger of a source_connection — whether cron-scheduled,
   user-initiated (adhoc), or an AI re-extraction pass — inserts
   one row here with status=''running''. The connector worker
   updates the row to success / warning / failed on completion.

   This table supersedes scheduled_job_run (09_news_scheduler.sql)
   and is connector-agnostic. New connectors (Plaid, Gmail, etc.)
   write here; scheduled_job_run is retained only for the legacy
   news poller until it is fully migrated.

   Key differences from scheduled_job_run:
     • run_type discriminates scheduled vs adhoc vs re_extract
     • stats is open JSONB instead of a hardcoded articles_stored INT
     • log_lines is a structured array powering the UI logs modal
     • status=''running'' is the initial insert state; the old table
       had no in-progress state

   Rows are append-only. Once completed_at is set the row is final
   and is never modified again.

   ON DELETE CASCADE: removing a source_connection wipes its run
   history too — history is only meaningful while the connection
   exists.';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS
-- ------------------------------------------------------------

COMMENT ON COLUMN sync_runs.id IS
  'Unique identifier for this sync run.
   Example: "f7e8d9c0-b1a2-3456-cdef-789012345678"';

COMMENT ON COLUMN sync_runs.source_connection_id IS
  'The source_connections row that was synced during this run.
   ON DELETE CASCADE ensures run history is removed when the
   parent connection is deleted.
   Use this FK to join with source_connections for connection
   metadata (source_type, connection_name, person_id etc.).';

COMMENT ON COLUMN sync_runs.run_type IS
  'How this run was triggered. Allowed values
   (sr_run_type_valid constraint):
     "scheduled"  — triggered by the cron scheduler because
                    next_run_at was due; corresponds to
                    source_connections.sync_scheduled=true
     "adhoc"      — triggered directly by the user via the
                    "Refresh Now" / "Sync Now" UI action or
                    POST /source-connections/{id}/sync API;
                    corresponds to sync_adhoc=true
     "re_extract" — triggered by the AI agent to re-process
                    an already-ingested document (e.g. to
                    extract facts with an updated schema)';

COMMENT ON COLUMN sync_runs.status IS
  'Lifecycle state of this individual run.
   Allowed values (sr_status_valid constraint):
     "running"  — run is currently in progress; inserted as
                  the initial status when the run starts
     "success"  — run completed with no errors; all data
                  was fetched and ingested successfully
     "warning"  — run completed but with non-fatal issues
                  (e.g. some records skipped, partial data);
                  stats and log_lines carry the details
     "failed"   — run terminated with an unrecoverable error;
                  log_lines will contain the error detail;
                  the connector worker may set
                  source_connections.status=''error'' after
                  repeated failures
   Transition: always starts as "running", then transitions
   to exactly one terminal state on completion.';

COMMENT ON COLUMN sync_runs.started_at IS
  'Timestamp when this run began (row inserted).
   Set to now() on insert; never updated.
   Use started_at to sort runs newest-first and to
   calculate run duration: completed_at - started_at.';

COMMENT ON COLUMN sync_runs.completed_at IS
  'Timestamp when this run finished (success, warning, or failed).
   Null while the run is still in status=''running''.
   Set by the connector worker when it writes the final status.
   Run duration = completed_at - started_at.';

COMMENT ON COLUMN sync_runs.stats IS
  'Connector-agnostic JSONB summary of what changed during
   this run. Null for runs that have not yet completed or
   for run types that do not produce metrics.
   The schema is loose — each connector populates the fields
   relevant to its domain:
     Plaid:    {"added": 23, "modified": 3, "removed": 0,
                "accounts_checked": 2}
     News:     {"added": 41, "skipped_duplicates": 7,
                "topics_queried": 3}
     Gmail:    {"emails_fetched": 100, "documents_created": 98,
                "skipped": 2}
     re_extract: {"documents_reprocessed": 5,
                  "facts_updated": 12}
   The UI should render these as a readable key-value list
   without assuming specific keys exist.';

COMMENT ON COLUMN sync_runs.log_lines IS
  'Structured array of log entries emitted during this run.
   Powers the "View Logs" modal in the UI.
   Always a JSON array (sr_log_lines_array constraint);
   starts as an empty array and is appended to by the
   connector worker via JSONB concatenation.
   Each element is an object with the shape:
     {
       "time":  "HH:MM:SS",      -- wall-clock time (UTC)
       "level": "info|warn|error",
       "msg":   "Human-readable log message"
     }
   Example value:
   [
     {"time": "06:00:01", "level": "info",  "msg": "Starting Plaid sync for Chase Checking"},
     {"time": "06:00:02", "level": "info",  "msg": "Fetched 25 transactions (2024-05-01 to 2024-05-15)"},
     {"time": "06:00:03", "level": "warn",  "msg": "Transaction txn_xyz has no category — storing as uncategorised"},
     {"time": "06:00:04", "level": "info",  "msg": "Sync complete: 23 added, 3 modified, 0 removed"}
   ]
   For failed runs the final entry will typically be level=''error''
   with the exception message or API error response.';
