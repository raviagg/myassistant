-- V21__plaid_source_conn_constraints.sql
-- 1. Unique partial indexes on source_connections.
--    One connection per (source_type, owner) — enforced now, droppable later.
--    Names must be unique per owner regardless of source_type.

CREATE UNIQUE INDEX uq_source_conn_type_person
    ON source_connections (source_type, person_id)
    WHERE person_id IS NOT NULL;

CREATE UNIQUE INDEX uq_source_conn_type_household
    ON source_connections (source_type, household_id)
    WHERE household_id IS NOT NULL;

CREATE UNIQUE INDEX uq_source_conn_name_person
    ON source_connections (connection_name, person_id)
    WHERE person_id IS NOT NULL;

CREATE UNIQUE INDEX uq_source_conn_name_household
    ON source_connections (connection_name, household_id)
    WHERE household_id IS NOT NULL;

-- 2. Per-item access_token on plaid.connections.
--    Each Plaid item has its own access_token (one per linked bank).
--    Stored as an AES-256-GCM encrypted blob (same scheme as source_connections.secrets).
--    COALESCE in upsert preserves existing value when caller passes NULL.

ALTER TABLE plaid.connections
    ADD COLUMN access_token TEXT;
