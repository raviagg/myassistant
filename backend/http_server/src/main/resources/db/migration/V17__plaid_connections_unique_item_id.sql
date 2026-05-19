-- ============================================================
-- V17__plaid_connections_unique_item_id.sql
-- Add UNIQUE constraint on plaid.connections.plaid_item_id so
-- the scheduler can use it as the ON CONFLICT target for the
-- incremental sync upsert performed each run.
--
-- A Plaid item_id is globally unique by definition, so an extra
-- DB-level UNIQUE constraint is safe and gives us a clean upsert
-- key for the connector worker. Without this, the upsert in
-- PlaidSyncRepository.upsertConnection fails with:
--   "there is no unique or exclusion constraint matching
--    the ON CONFLICT specification".
-- ============================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'plaid_connections_plaid_item_id_key'
    ) THEN
        ALTER TABLE plaid.connections
            ADD CONSTRAINT plaid_connections_plaid_item_id_key
            UNIQUE (plaid_item_id);
    END IF;
END $$;
