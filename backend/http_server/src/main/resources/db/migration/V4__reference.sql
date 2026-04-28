-- ============================================================
-- 03_reference.sql
-- Reference / lookup tables: source_type, domain
--
-- These tables act as governed vocabularies for values used
-- across the system. New values are added as rows rather than
-- requiring DDL changes (no enum ALTER needed).
-- Both tables are seeded with initial values and grow over
-- time as new integrations and domains are introduced.
-- ============================================================


-- ------------------------------------------------------------
-- TABLES
-- ------------------------------------------------------------

CREATE TABLE source_type (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  name        TEXT        NOT NULL UNIQUE,
  description TEXT        NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------

CREATE TABLE domain (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  name        TEXT        NOT NULL UNIQUE,
  description TEXT        NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- ------------------------------------------------------------
-- SEED DATA — source_type
-- ------------------------------------------------------------

INSERT INTO source_type (name, description) VALUES
  ('user_input',
    'Information typed directly by a user in the chat interface'),
  ('file_upload',
    'File uploaded by a user — PDF, image, CSV etc. Text is extracted and stored'),
  ('ai_extracted',
    'Information extracted by the AI pipeline from another document'),
  ('plaid_poll',
    'Financial transactions and account data polled from the Plaid banking API'),
  ('gmail_poll',
    'Emails and attachments polled from Gmail');


-- ------------------------------------------------------------
-- SEED DATA — domain
-- ------------------------------------------------------------

INSERT INTO domain (name, description) VALUES
  ('health',
    'Health related information including insurance cards, medications, '
    'conditions, doctor visits and lab results'),
  ('finance',
    'Financial information including income, expenses, bank accounts, '
    'transactions, investments and tax documents'),
  ('employment',
    'Employment history including jobs, roles, salary changes, '
    'employers and work-related documents'),
  ('personal_details',
    'Personal details that change over time — contact information, '
    'addresses, preferences and identity documents'),
  ('todo',
    'Tasks and reminders both one-off and recurring, '
    'with status tracking and due dates'),
  ('household',
    'Household level information shared across family members — '
    'shared expenses, utilities, property and home documents'),
  ('news_preferences',
    'News topics, sources and content consumption preferences '
    'used to personalise news digests');


-- ------------------------------------------------------------
-- COMMENTS — TABLES
-- ------------------------------------------------------------

COMMENT ON TABLE source_type IS
  'Governed vocabulary of all data sources that can produce documents.
   New sources (e.g. calendar_poll, apple_health_poll) are added as rows.
   No DDL change needed to introduce a new source.
   Referenced by document.source_type and audit_log.job_type.';

COMMENT ON TABLE domain IS
  'Governed vocabulary of life domains covered by the personal assistant.
   Each domain groups related entity types and fact tables.
   New domains are added as rows — no DDL change needed.
   Referenced by entity_type_schema.domain.
   Example domains: health, finance, employment, todo';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS: source_type
-- ------------------------------------------------------------

COMMENT ON COLUMN source_type.name IS
  'Short machine-readable identifier for the source type.
   Used as foreign key in document and audit_log tables.
   Snake_case, lowercase.
   Example: "user_input", "plaid_poll", "gmail_poll"';

COMMENT ON COLUMN source_type.description IS
  'Human-readable explanation of what this source type represents
   and how documents from this source are produced.
   Example: "Financial transactions and account data polled from Plaid"';

COMMENT ON COLUMN source_type.created_at IS
  'Timestamp when this source type was registered in the system.';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS: domain
-- ------------------------------------------------------------

COMMENT ON COLUMN domain.name IS
  'Short machine-readable identifier for the domain.
   Used as foreign key in entity_type_schema.
   Snake_case, lowercase.
   Example: "health", "finance", "employment", "todo"';

COMMENT ON COLUMN domain.description IS
  'Human-readable explanation of what life area this domain covers
   and what kinds of information it contains.
   Example: "Health related information including insurance cards,
   medications, conditions, doctor visits and lab results"';

COMMENT ON COLUMN domain.created_at IS
  'Timestamp when this domain was introduced to the system.';
