-- ============================================================
-- V1__create_extensions.sql
-- PostgreSQL extensions required by the personal assistant schema.
--
-- uuid-ossp: provides gen_random_uuid() for primary keys.
-- vector:    pgvector extension for 1536-dim embedding columns
--            used for semantic search on documents and facts.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;
