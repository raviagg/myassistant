-- V17__source_connections.sql
-- Unified connector registry: source_connections table.
-- Supersedes scheduled_job for all connector types.

CREATE TABLE source_connections (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type       TEXT        NOT NULL REFERENCES source_type(name),
    connection_name   TEXT        NOT NULL,
    person_id         UUID        REFERENCES person(id),
    household_id      UUID        REFERENCES household(id),
    config            JSONB       NOT NULL DEFAULT '{}',
    secrets           TEXT,
    sync_scheduled    BOOLEAN     NOT NULL DEFAULT false,
    sync_adhoc        BOOLEAN     NOT NULL DEFAULT false,
    sync_schedule     TEXT,
    next_run_at       TIMESTAMPTZ,
    last_synced_at    TIMESTAMPTZ,
    status            TEXT        NOT NULL DEFAULT 'active',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sc_exactly_one_owner CHECK (
        (person_id IS NOT NULL AND household_id IS NULL) OR
        (person_id IS NULL AND household_id IS NOT NULL)
    ),
    CONSTRAINT sc_status_valid CHECK (status IN ('active', 'paused', 'error')),
    CONSTRAINT sc_schedule_requires_flag CHECK (
        (sync_scheduled = false AND sync_schedule IS NULL) OR
        (sync_scheduled = true  AND sync_schedule IS NOT NULL)
    )
);

CREATE INDEX idx_source_connections_person
    ON source_connections(person_id)
    WHERE person_id IS NOT NULL;

CREATE INDEX idx_source_connections_household
    ON source_connections(household_id)
    WHERE household_id IS NOT NULL;

CREATE INDEX idx_source_connections_due
    ON source_connections(next_run_at)
    WHERE sync_scheduled = true AND status = 'active';

CREATE TRIGGER source_connections_updated_at
    BEFORE UPDATE ON source_connections
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- Seed new connector source types not present in earlier migrations
INSERT INTO source_type (name, description) VALUES
  ('chatbot',    'Documents and facts extracted by the chatbot agent'),
  ('bulk_file',  'Batch ingestion of file uploads'),
  ('bulk_image', 'Batch ingestion of image uploads with visual extraction')
ON CONFLICT (name) DO NOTHING;
