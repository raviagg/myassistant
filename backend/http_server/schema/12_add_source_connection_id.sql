-- ============================================================
-- 12_add_source_connection_id.sql
-- Add source_connection_id FK to document and fact
--
-- Links every document and fact back to the source connection
-- that produced it. Nullable — existing rows predating the
-- source_connections table have no connection to reference.
--
-- The column enables "show me all documents / facts that came
-- from connection X" queries without a join through document.
-- Partial indexes (NULLs excluded) keep the index tight since
-- rows written before this migration have no connection.
-- ============================================================


-- ON DELETE RESTRICT: deleting a source_connections row is blocked while any
-- document or fact still references it. If a connection must be deleted after
-- data has already been ingested, first nullify the column on affected rows,
-- then delete the connection. This preserves the document/fact history.
-- (sync_runs uses ON DELETE CASCADE instead — run logs are disposable.)
ALTER TABLE document
    ADD COLUMN source_connection_id UUID REFERENCES source_connections(id) ON DELETE RESTRICT;

ALTER TABLE fact
    ADD COLUMN source_connection_id UUID REFERENCES source_connections(id) ON DELETE RESTRICT;


-- Partial indexes — NULLs excluded so pre-migration rows do not bloat the index.
-- Used to scope "show all documents from connection X" queries efficiently.
CREATE INDEX idx_document_source_connection
    ON document(source_connection_id)
    WHERE source_connection_id IS NOT NULL;

CREATE INDEX idx_fact_source_connection
    ON fact(source_connection_id)
    WHERE source_connection_id IS NOT NULL;


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS
-- ------------------------------------------------------------

COMMENT ON COLUMN document.source_connection_id IS
  'The source connection that produced this document.
   Foreign key to source_connections.id.
   Nullable — rows written before the source_connections table
   was introduced (migration 10) carry no connection reference;
   a null value means the document predates the connector
   framework or was created via a path that does not yet
   populate this column (e.g. direct user_input typed in chat).
   Use this column to scope queries to documents from a specific
   connection, e.g. "show all Plaid documents for connection X":
     SELECT * FROM document WHERE source_connection_id = ''<uuid>'';
   Partial index idx_document_source_connection makes this fast.';

COMMENT ON COLUMN fact.source_connection_id IS
  'The source connection that produced this fact operation row.
   Foreign key to source_connections.id.
   Nullable — rows written before the source_connections table
   was introduced (migration 10) carry no connection reference,
   and facts derived from user_input documents (which have no
   associated connector) will also be null.
   Populated by the connector worker at sync time alongside
   document.source_connection_id so both layers remain in sync.
   Use this column to scope structured queries to current state of
   entities produced by a specific connection, e.g.:
     SELECT * FROM current_facts
     WHERE entity_instance_id IN (
         SELECT DISTINCT entity_instance_id FROM fact
         WHERE source_connection_id = ''<uuid>''
     );
   Partial index idx_fact_source_connection makes this fast.';
