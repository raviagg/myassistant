-- ============================================================
-- 05_document.sql
-- Document table — immutable NL content store
--
-- Every piece of information entering the system becomes a
-- document first. Documents are immutable — they are never
-- updated or deleted. New information supersedes old documents
-- by referencing their IDs in supersedes_ids.
--
-- Documents can belong to a person, a household, or both.
-- They carry the raw natural language content, an optional
-- array of attached files, and a vector embedding for
-- semantic search.
--
-- Facts are always derived from documents — document_id on
-- every fact row provides full provenance.
-- ============================================================


-- ------------------------------------------------------------
-- TABLES
-- ------------------------------------------------------------

CREATE TABLE document (
  id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

  -- ownership: at least one of person_id or household_id required
  person_id       UUID        REFERENCES person(id),
  household_id    UUID        REFERENCES household(id),

  content_text    TEXT        NOT NULL,
  source_type     TEXT        NOT NULL REFERENCES source_type(name),

  files           JSONB       NOT NULL DEFAULT '[]',

  supersedes_ids  UUID[]      NOT NULL DEFAULT '{}',

  embedding       VECTOR(1536),

  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- every document must have at least one owner
  CONSTRAINT must_have_owner CHECK (
    person_id IS NOT NULL OR household_id IS NOT NULL
  ),

  -- files must be a valid array where every element
  -- has file_path and file_type
  CONSTRAINT files_valid CHECK (
    files = '[]'::jsonb OR (
      jsonb_typeof(files) = 'array' AND
      (SELECT bool_and(
        (f->>'file_path') IS NOT NULL AND
        (f->>'file_type') IS NOT NULL
      ) FROM jsonb_array_elements(files) f)
    )
  )
);

CREATE INDEX idx_document_person
  ON document(person_id)
  WHERE person_id IS NOT NULL;

CREATE INDEX idx_document_household
  ON document(household_id)
  WHERE household_id IS NOT NULL;

CREATE INDEX idx_document_supersedes
  ON document USING GIN(supersedes_ids);

CREATE INDEX idx_document_embedding
  ON document USING hnsw(embedding vector_cosine_ops);

CREATE INDEX idx_document_source
  ON document(source_type);


-- ------------------------------------------------------------
-- COMMENTS — TABLES
-- ------------------------------------------------------------

COMMENT ON TABLE document IS
  'Immutable store of all information that enters the system.
   Every piece of data — typed by a user, uploaded as a file,
   or polled from an external source — becomes a document.
   Documents are NEVER updated or deleted. When information
   changes, a new document is created with supersedes_ids
   pointing to the document(s) it replaces.
   Facts are always extracted from documents — fact.document_id
   provides full provenance back to the source content.
   Supports semantic search via pgvector embedding column.';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS: document
-- ------------------------------------------------------------

COMMENT ON COLUMN document.id IS
  'Unique identifier for this document.
   Referenced by fact.document_id and in supersedes_ids arrays.
   Example: "e5f6a7b8-c9d0-1234-efab-345678901234"';

COMMENT ON COLUMN document.person_id IS
  'The person this document belongs to.
   Null if document belongs to a household only.
   A document can belong to both a person and a household
   simultaneously (both columns populated).
   Example: a personal payslip has person_id set,
   a shared utility bill has household_id set,
   a family health record might have both.';

COMMENT ON COLUMN document.household_id IS
  'The household this document belongs to.
   Null if document belongs to a person only.
   A document can belong to both a person and a household
   simultaneously (both columns populated).';

COMMENT ON COLUMN document.content_text IS
  'The full raw text content of this document.
   For user_input: exactly what the user typed.
   For file_upload: text extracted from the file via OCR or parsing.
   For plaid_poll: formatted NL summary of the fetched data.
   For gmail_poll: email subject, body and metadata as text.
   This field is what gets embedded for semantic search.
   Example: "Started new job at Acme Corp as Senior Engineer,
   salary 120000 per year, start date March 1 2024"';

COMMENT ON COLUMN document.source_type IS
  'How this document entered the system.
   Foreign key to source_type.name.
   Example: "user_input", "file_upload", "plaid_poll", "gmail_poll"';

COMMENT ON COLUMN document.files IS
  'JSONB array of files attached to this document.
   Empty array if this is a pure text document with no attachments.
   Each element must have file_path and file_type.
   original_filename is optional but recommended.
   The file content is NOT stored here — files live in S3.
   file_path is the S3 key used to retrieve the file.
   Example:
   [
     {
       "file_path":         "s3://my-bucket/docs/p1/payslip_march_2024.pdf",
       "file_type":         "pdf",
       "original_filename": "payslip_march_2024.pdf"
     },
     {
       "file_path":         "s3://my-bucket/docs/p1/insurance_card_front.jpg",
       "file_type":         "image",
       "original_filename": "insurance_card.jpg"
     }
   ]';

COMMENT ON COLUMN document.supersedes_ids IS
  'Array of document IDs that this document supersedes (replaces).
   Empty array if this document does not replace any prior document.
   Superseded documents are NOT deleted — they remain immutable
   for historical reference and audit trail.
   The superseding relationship is set by the ingestion pipeline
   via semantic search: when a new document arrives, existing
   related documents are found and their IDs recorded here.
   Example: when a user updates their insurance, the new document
   carries supersedes_ids = ["old-insurance-doc-id"]
   To find the full history of a topic, follow the supersedes_ids
   chain backwards.';

COMMENT ON COLUMN document.embedding IS
  'Vector embedding of content_text. 1536 dimensions (OpenAI ada-002
   or equivalent). Used for semantic similarity search to:
   1. Answer NL questions by finding relevant documents
   2. Find related documents during ingestion for superseding
   Null until the embedding pipeline processes this document.
   Indexed with HNSW for fast approximate nearest-neighbour search.';

COMMENT ON COLUMN document.created_at IS
  'Timestamp when this document was created.
   For user_input: when the user sent the message.
   For polling sources: when the poll job ran.
   Never updated — documents are immutable.';
