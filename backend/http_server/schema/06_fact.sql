-- ============================================================
-- 06_fact.sql
-- Fact table — structured append-only operation stream
--
-- Facts are the structured, queryable layer derived from
-- documents. Every fact is extracted from a document and
-- references back to it via document_id.
--
-- Facts are an operation stream — create, update, delete.
-- They are NEVER updated or deleted in place. Every change
-- to a real-world entity produces a new fact row.
--
-- entity_instance_id groups all operations on the same
-- logical entity (e.g. all updates to one TODO item).
-- Current state of an entity is derived by merging all
-- non-delete rows in chronological order, with later
-- field values overwriting earlier ones (patch semantics).
-- Null field values mean the field was explicitly removed.
--
-- The current_facts view materialises the merged current
-- state for all active (non-deleted) entity instances.
-- ============================================================


-- ------------------------------------------------------------
-- TYPES
-- ------------------------------------------------------------

CREATE TYPE operation_type AS ENUM (
  'create',
  'update',
  'delete'
);


-- ------------------------------------------------------------
-- TABLES
-- ------------------------------------------------------------

CREATE TABLE fact (
  id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id         UUID           NOT NULL REFERENCES document(id),
  schema_id           UUID           NOT NULL REFERENCES entity_type_schema(id),
  entity_instance_id  UUID           NOT NULL,
  operation_type      operation_type NOT NULL,
  fields              JSONB          NOT NULL DEFAULT '{}',
  embedding           VECTOR(1536),
  created_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_fact_document
  ON fact(document_id);

CREATE INDEX idx_fact_schema
  ON fact(schema_id);

CREATE INDEX idx_fact_entity_instance
  ON fact(entity_instance_id, created_at DESC);

CREATE INDEX idx_fact_instance_schema
  ON fact(schema_id, entity_instance_id, created_at DESC);

CREATE INDEX idx_fact_fields
  ON fact USING GIN(fields);

CREATE INDEX idx_fact_embedding
  ON fact USING hnsw(embedding vector_cosine_ops);


-- ------------------------------------------------------------
-- VIEWS
-- ------------------------------------------------------------

CREATE VIEW current_facts AS
WITH deleted AS (
  -- collect all entity instances that have been deleted
  SELECT DISTINCT entity_instance_id
  FROM fact
  WHERE operation_type = 'delete'
),
merged AS (
  -- merge all create/update rows per entity instance
  -- later field values overwrite earlier ones (patch semantics)
  -- null values mean the field was explicitly removed
  SELECT
    f.entity_instance_id,
    f.schema_id,
    f.document_id,
    jsonb_strip_nulls(
      jsonb_object_agg(
        kv.key, kv.value
        ORDER BY f.created_at ASC
      )
    ) AS current_fields,
    MIN(f.created_at) AS created_at,
    MAX(f.created_at) AS updated_at
  FROM fact f,
  jsonb_each(f.fields) kv
  WHERE f.operation_type != 'delete'
  GROUP BY
    f.entity_instance_id,
    f.schema_id,
    f.document_id
)
SELECT m.*
FROM merged m
WHERE m.entity_instance_id NOT IN (
  SELECT entity_instance_id FROM deleted
);


-- ------------------------------------------------------------
-- COMMENTS — TYPES
-- ------------------------------------------------------------

COMMENT ON TYPE operation_type IS
  'The kind of change this fact row represents.
   create — a new entity instance is being introduced.
            Full set of known fields should be provided.
            A new entity_instance_id is generated.
   update — an existing entity instance is being patched.
            Only the changed fields need to be provided.
            Same entity_instance_id as the create row.
            Null values mean the field is explicitly removed.
   delete — the entity instance no longer exists or is no longer active.
            The entity instance is excluded from current_facts.
            Same entity_instance_id as the create row.';


-- ------------------------------------------------------------
-- COMMENTS — TABLES
-- ------------------------------------------------------------

COMMENT ON TABLE fact IS
  'Structured, queryable facts extracted from documents.
   An append-only operation stream — rows are NEVER updated or deleted.
   Every change to a real-world entity produces a new row.

   Key concepts:
   - entity_instance_id groups all operations on one logical entity
     (e.g. all rows for one TODO item share the same entity_instance_id)
   - operation_type records whether this row creates, patches, or
     deletes the entity instance
   - fields is a JSONB patch — only changed fields need to be present
     on update rows. Current state is derived by merging all rows.
   - schema_id records which entity_type_schema version governed
     this extraction. Enables re-extraction if schema evolves.
   - document_id provides full provenance back to the source NL content.

   entity_instance_id is resolved by the backend via semantic search —
   users never provide UUIDs directly in chat. The system finds the
   matching entity instance from the user''s natural language.

   Example flow for a TODO:
   User: "remind me to renew passport by June"
   -> create row: {title: "renew passport", status: "open", due_date: "2024-06-01"}

   User: "I started working on the passport renewal"
   -> update row: {status: "in_progress"}  (same entity_instance_id)

   User: "passport renewed!"
   -> delete row: {}  (same entity_instance_id, excluded from current_facts)';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS: fact
-- ------------------------------------------------------------

COMMENT ON COLUMN fact.id IS
  'Unique identifier for this fact operation row.
   Note: this is NOT the identifier for the logical entity.
   Use entity_instance_id to identify the logical entity.
   Example: "f6a7b8c9-d0e1-2345-fabc-456789012345"';

COMMENT ON COLUMN fact.document_id IS
  'The document from which this fact was extracted.
   Provides full provenance — you can always trace a fact
   back to the original natural language content.
   Foreign key to document.id.';

COMMENT ON COLUMN fact.schema_id IS
  'The entity_type_schema version that governed this extraction.
   Records which schema version was active when this fact was written.
   Used to identify facts that need re-extraction after a schema evolves:
   SELECT * FROM fact WHERE schema_id != current_schema_id
   Foreign key to entity_type_schema.id.';

COMMENT ON COLUMN fact.entity_instance_id IS
  'Stable UUID that identifies a specific logical entity instance.
   All fact rows with the same entity_instance_id describe the
   same real-world entity (e.g. one insurance card, one TODO item).
   For create operations: a new UUID is generated by the system.
   For update/delete operations: same UUID as the create row.
   Resolved by the backend via semantic search — users never
   provide this UUID directly in chat.
   Example: all operations on "renew passport" TODO share one UUID.';

COMMENT ON COLUMN fact.operation_type IS
  'The kind of change this row represents.
   create: new entity instance introduced. Generate new entity_instance_id.
   update: patch applied to existing instance. Provide only changed fields.
   delete: entity instance is no longer active. Excluded from current_facts.
   See operation_type enum for full details.';

COMMENT ON COLUMN fact.fields IS
  'JSONB object containing the fact field values for this operation.
   For create: full set of known field values.
   For update: only the fields that changed (patch semantics).
               Unmentioned fields survive from earlier rows.
               Null values explicitly remove a field.
   For delete: typically empty {} — entity is just marked gone.
   Field names and types are governed by the referenced schema_id.
   Current state is computed by merging all rows for an entity_instance_id
   in chronological order. Use the current_facts view for this.
   Example create: {"title": "renew passport", "status": "open", "due_date": "2024-06-01"}
   Example update: {"status": "in_progress"}
   Example update with removal: {"due_date": null}  <- removes due_date';

COMMENT ON COLUMN fact.embedding IS
  'Vector embedding of the merged current fields at the time of
   this operation row, represented as a readable string.
   Used by the backend to resolve entity_instance_id from
   natural language — e.g. "that passport todo" is matched
   against embeddings to find the right entity instance.
   Format of embedded string:
   "[entity_type]: [field1_value], [field2_value], ..."
   Example: "todo: renew passport, status open, due 2024-06-01"
   1536 dimensions. Indexed with HNSW for fast ANN search.';

COMMENT ON COLUMN fact.created_at IS
  'Timestamp when this fact operation row was written.
   Used to establish chronological order when merging
   field values to compute current state.
   Never updated — fact rows are immutable.';


-- ------------------------------------------------------------
-- COMMENTS — VIEWS
-- ------------------------------------------------------------

COMMENT ON VIEW current_facts IS
  'Materialised current state of all active entity instances.
   Excludes any entity_instance_id that has a delete operation.
   Merges all create/update rows per entity_instance_id in
   chronological order — later field values overwrite earlier ones.
   Null field values are stripped (null means field was removed).
   Use this view for "what is the current state of X" queries.
   Use the raw fact table for full history queries.

   Example — current state of all open todos for a person:
   SELECT cf.*
   FROM current_facts cf
   JOIN entity_type_schema ets ON ets.id = cf.schema_id
   JOIN document d ON d.id = cf.document_id
   WHERE ets.domain = ''todo''
   AND ets.entity_type = ''todo_item''
   AND d.person_id = ''<person_uuid>''
   AND cf.current_fields->>''status'' != ''done'';';
