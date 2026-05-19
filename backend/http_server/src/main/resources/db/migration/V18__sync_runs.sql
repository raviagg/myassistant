-- V18__sync_runs.sql
-- Per-execution history for all source connection sync runs.
-- Supersedes scheduled_job_run for all connector types.

CREATE TABLE sync_runs (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_connection_id  UUID        NOT NULL REFERENCES source_connections(id) ON DELETE CASCADE,
    run_type              TEXT        NOT NULL,
    status                TEXT        NOT NULL,
    started_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ,
    stats                 JSONB,
    log_lines             JSONB       NOT NULL DEFAULT '[]',
    CONSTRAINT sr_run_type_valid CHECK (
        run_type IN ('scheduled', 'adhoc', 're_extract')
    ),
    CONSTRAINT sr_status_valid CHECK (
        status IN ('running', 'success', 'warning', 'failed')
    ),
    CONSTRAINT sr_log_lines_array CHECK (
        jsonb_typeof(log_lines) = 'array'
    )
);

CREATE INDEX idx_sync_runs_connection
    ON sync_runs(source_connection_id, started_at DESC);

CREATE INDEX idx_sync_runs_started
    ON sync_runs(started_at DESC);
