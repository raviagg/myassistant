-- V9__resize_embeddings.sql
-- Resize vector columns from 1536 (OpenAI ada-002 placeholder) to 768
-- (BAAI/bge-base-en-v1.5). pgvector does not support ALTER COLUMN for
-- vector types, so we drop and re-add. Existing embeddings were all
-- [0.1, 0.2, 0.3] placeholders — intentional data loss.

-- document
DROP INDEX IF EXISTS idx_document_embedding;
ALTER TABLE document DROP COLUMN IF EXISTS embedding;
ALTER TABLE document ADD COLUMN embedding VECTOR(768);
CREATE INDEX idx_document_embedding
  ON document USING hnsw(embedding vector_cosine_ops);

-- fact
DROP INDEX IF EXISTS idx_fact_embedding;
ALTER TABLE fact DROP COLUMN IF EXISTS embedding;
ALTER TABLE fact ADD COLUMN embedding VECTOR(768);
CREATE INDEX idx_fact_embedding
  ON fact USING hnsw(embedding vector_cosine_ops);
