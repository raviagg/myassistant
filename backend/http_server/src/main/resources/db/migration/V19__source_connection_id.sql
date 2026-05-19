-- V19__source_connection_id.sql
-- Add source_connection_id FK to document and fact.
-- Nullable: existing rows predating source_connections have no connection.

ALTER TABLE document
    ADD COLUMN source_connection_id UUID REFERENCES source_connections(id) ON DELETE RESTRICT;

ALTER TABLE fact
    ADD COLUMN source_connection_id UUID REFERENCES source_connections(id) ON DELETE RESTRICT;

CREATE INDEX idx_document_source_connection
    ON document(source_connection_id)
    WHERE source_connection_id IS NOT NULL;

CREATE INDEX idx_fact_source_connection
    ON fact(source_connection_id)
    WHERE source_connection_id IS NOT NULL;
