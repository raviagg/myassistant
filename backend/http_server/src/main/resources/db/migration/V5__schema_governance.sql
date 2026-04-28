-- ============================================================
-- 04_schema_governance.sql
-- Entity type schema registry and version history
--
-- entity_type_schema governs the structure of all fact data.
-- Each row defines a (domain, entity_type, schema_version) triple
-- with a JSONB field_definitions array describing the fields,
-- their types, whether they are mandatory, and descriptions.
--
-- New entity types are proposed by the AI and confirmed by the
-- user in chat — they are never created manually via DDL.
-- Schema evolution bumps schema_version. All versions are
-- retained for historical reference and re-extraction auditing.
--
-- The current active schema for any entity type is always the
-- row with the highest schema_version where is_active = true.
-- ============================================================


-- ------------------------------------------------------------
-- TABLES
-- ------------------------------------------------------------

CREATE TABLE entity_type_schema (
  id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  domain_id           UUID        NOT NULL REFERENCES domain(id),
  entity_type         TEXT        NOT NULL,
  schema_version      INT         NOT NULL DEFAULT 1,
  description         TEXT,
  field_definitions   JSONB       NOT NULL,
  mandatory_fields    TEXT[]      NOT NULL DEFAULT '{}',
  is_active           BOOLEAN     NOT NULL DEFAULT true,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

  UNIQUE (domain_id, entity_type, schema_version),

  -- field_definitions must be a non-empty JSON array
  CONSTRAINT field_definitions_valid CHECK (
    jsonb_typeof(field_definitions) = 'array' AND
    jsonb_array_length(field_definitions) > 0
  )
);

-- Trigger to keep mandatory_fields in sync with field_definitions on every write
CREATE OR REPLACE FUNCTION compute_mandatory_fields()
  RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  NEW.mandatory_fields := ARRAY(
    SELECT f->>'name'
    FROM jsonb_array_elements(NEW.field_definitions) f
    WHERE (f->>'required')::boolean = true
  );
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mandatory_fields
  BEFORE INSERT OR UPDATE OF field_definitions
  ON entity_type_schema
  FOR EACH ROW EXECUTE FUNCTION compute_mandatory_fields();

CREATE INDEX idx_entity_type_schema_domain
  ON entity_type_schema(domain_id);
CREATE INDEX idx_entity_type_schema_lookup
  ON entity_type_schema(domain_id, entity_type, schema_version);
CREATE INDEX idx_entity_type_schema_active
  ON entity_type_schema(is_active)
  WHERE is_active = true;


-- ------------------------------------------------------------
-- VIEWS
-- ------------------------------------------------------------

CREATE VIEW current_entity_type_schema AS
  SELECT DISTINCT ON (domain_id, entity_type) *
  FROM entity_type_schema
  WHERE is_active = true
  ORDER BY domain_id, entity_type, schema_version DESC;


-- ------------------------------------------------------------
-- SEED DATA
-- ------------------------------------------------------------

INSERT INTO entity_type_schema (
  domain_id, entity_type, schema_version,
  description, field_definitions
)
SELECT id, 'insurance_card', 1,
  'Health insurance card details including provider, plan name, deductible, premium and coverage dates.',
  '[
    {"name":"provider",    "type":"text",   "required":true,  "description":"Insurance provider company name. Example: BlueCross, Aetna, UnitedHealth"},
    {"name":"plan_name",   "type":"text",   "required":false, "description":"Name of the specific insurance plan. Example: BlueShield PPO 500, Gold Plan"},
    {"name":"member_id",   "type":"text",   "required":false, "description":"Member ID or policy number printed on the insurance card. Example: XYZ123456789"},
    {"name":"group_number","type":"text",   "required":false, "description":"Group number on the insurance card, typically from employer. Example: GRP-98765"},
    {"name":"deductible",  "type":"number", "required":false, "description":"Annual deductible amount in USD. Example: 500, 1500, 3000"},
    {"name":"premium",     "type":"number", "required":false, "description":"Monthly premium amount in USD. Example: 450, 820"},
    {"name":"valid_from",  "type":"date",   "required":false, "description":"Date coverage started. Example: 2024-01-01"},
    {"name":"valid_to",    "type":"date",   "required":false, "description":"Date coverage ends. Example: 2024-12-31"},
    {"name":"card_image",  "type":"file",   "required":false, "description":"Photo or scan of the physical insurance card front"}
  ]'::jsonb
FROM domain WHERE name = 'health';

INSERT INTO entity_type_schema (
  domain_id, entity_type, schema_version,
  description, field_definitions
)
SELECT id, 'todo_item', 1,
  'A task or reminder to be completed, either one-off or recurring.',
  '[
    {"name":"title",      "type":"text",    "required":true,  "description":"Short description of what needs to be done. Example: Renew passport"},
    {"name":"status",     "type":"text",    "required":true,  "description":"Current status of the task. Allowed values: open, in_progress, done"},
    {"name":"due_date",   "type":"date",    "required":false, "description":"Date by which the task should be completed. Example: 2024-06-01"},
    {"name":"priority",   "type":"text",    "required":false, "description":"Importance level. Allowed values: low, medium, high"},
    {"name":"is_recurring","type":"boolean","required":false, "description":"Whether this task repeats on a schedule"},
    {"name":"recurrence", "type":"text",    "required":false, "description":"Recurrence pattern if is_recurring is true. Example: daily, weekly"}
  ]'::jsonb
FROM domain WHERE name = 'todo';

INSERT INTO entity_type_schema (
  domain_id, entity_type, schema_version,
  description, field_definitions
)
SELECT id, 'job', 1,
  'An employment record capturing a job held by a person.',
  '[
    {"name":"employer",   "type":"text",   "required":true,  "description":"Name of the employer or company. Example: Acme Corp, Google"},
    {"name":"role",       "type":"text",   "required":false, "description":"Job title or role at the employer. Example: Senior Engineer"},
    {"name":"salary",     "type":"number", "required":false, "description":"Annual salary in USD. Example: 120000"},
    {"name":"start_date", "type":"date",   "required":false, "description":"Date employment started. Example: 2022-03-01"},
    {"name":"end_date",   "type":"date",   "required":false, "description":"Date employment ended. Null if currently employed"}
  ]'::jsonb
FROM domain WHERE name = 'employment';

INSERT INTO entity_type_schema (
  domain_id, entity_type, schema_version,
  description, field_definitions
)
SELECT id, 'payslip', 1,
  'A payslip record capturing income for a specific pay period.',
  '[
    {"name":"employer",     "type":"text",   "required":true,  "description":"Name of the employer who issued this payslip. Example: Acme Corp"},
    {"name":"pay_period",   "type":"date",   "required":true,  "description":"The date or month this payslip covers. Example: 2024-03-31"},
    {"name":"gross_income", "type":"number", "required":true,  "description":"Total gross income before deductions in USD. Example: 10000"},
    {"name":"tax",          "type":"number", "required":false, "description":"Total tax deducted in USD. Example: 2200"},
    {"name":"net_income",   "type":"number", "required":false, "description":"Take-home pay after all deductions in USD. Example: 7800"},
    {"name":"payslip_file", "type":"file",   "required":false, "description":"The original payslip document — PDF or image"}
  ]'::jsonb
FROM domain WHERE name = 'finance';


-- ------------------------------------------------------------
-- COMMENTS — TABLES
-- ------------------------------------------------------------

COMMENT ON TABLE entity_type_schema IS
  'Registry of all entity types and their versioned schemas.
   Each row defines a (domain, entity_type, schema_version) triple.
   field_definitions describes the fields for that entity type —
   their names, types, whether mandatory, and descriptions.
   New entity types are proposed by the AI and confirmed by the user
   in chat. Schema evolution creates a new row with bumped schema_version.
   All versions are retained — history is never deleted.
   The current schema is always the highest schema_version row
   where is_active = true. Use the current_entity_type_schema
   view for convenience.
   fact.schema_id references this table to record which schema
   version was used when a fact was extracted.';

COMMENT ON VIEW current_entity_type_schema IS
  'Convenience view returning the current active schema for each
   (domain, entity_type) pair. Returns the highest schema_version
   row where is_active = true.
   Used by the ingestion pipeline to determine what fields to
   extract and which are mandatory for a given entity type.';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS: entity_type_schema
-- ------------------------------------------------------------

COMMENT ON COLUMN entity_type_schema.id IS
  'Unique identifier for this schema version record.
   Referenced by fact.schema_id to record which schema version
   was active when a fact was extracted.
   Example: "d4e5f6a7-b8c9-0123-defa-234567890123"';

COMMENT ON COLUMN entity_type_schema.domain IS
  'The life domain this entity type belongs to.
   Foreign key to domain.name.
   Example: "health", "finance", "employment", "todo"';

COMMENT ON COLUMN entity_type_schema.entity_type IS
  'Machine-readable name for this entity type within its domain.
   Snake_case, lowercase. Free text — not an enum — because
   new entity types are created dynamically at runtime.
   Example: "insurance_card", "todo_item", "job", "payslip"';

COMMENT ON COLUMN entity_type_schema.schema_version IS
  'Version number for this schema definition. Starts at 1.
   Incremented each time the field_definitions change.
   All versions are retained. Current = max version where is_active=true.
   Example: 1 (initial), 2 (added group_number field)';

COMMENT ON COLUMN entity_type_schema.description IS
  'Human-readable description of what this entity type captures.
   Written for a reader unfamiliar with the system.
   Example: "Health insurance card details including provider,
   plan name, deductible, premium and coverage dates."';

COMMENT ON COLUMN entity_type_schema.field_definitions IS
  'JSONB array defining the fields for this entity type.
   Each element is an object with:
     name        — snake_case field identifier
     type        — one of: text, number, date, boolean, file
     mandatory   — true if required, false if optional
     description — plain English explanation with examples
   Example:
   [
     {
       "name": "provider",
       "type": "text",
       "mandatory": true,
       "description": "Insurance provider. Example: BlueCross"
     }
   ]
   The file type means the field value is a file path reference
   pointing to a file in the parent document''s files array.';

COMMENT ON COLUMN entity_type_schema.mandatory_fields IS
  'Array of field names where mandatory=true in field_definitions.
   Maintained automatically by the trg_mandatory_fields trigger on
   every INSERT or UPDATE so it always mirrors field_definitions.
   Used by the ingestion pipeline to check completeness before
   writing facts and to know which fields to ask the user about
   if missing.
   Example: ["provider"] for insurance_card
   Example: ["title", "status"] for todo_item';

COMMENT ON COLUMN entity_type_schema.extraction_prompt IS
  'Prompt fragment given to the LLM during fact extraction.
   Describes what to look for in the source document and
   which fields are mandatory. Used by the ingestion pipeline
   when calling the extraction LLM for this entity type.
   Example: "Extract health insurance card details. Look for
   provider name, plan name, deductible amount..."';

COMMENT ON COLUMN entity_type_schema.is_active IS
  'Whether this schema version is currently in use.
   False = soft deleted or superseded by a newer version.
   Facts already extracted under an inactive schema are retained.
   The current_entity_type_schema view filters to is_active=true.';

COMMENT ON COLUMN entity_type_schema.change_description IS
  'Human-readable explanation of what changed in this schema version
   compared to the previous version. Null for version 1.
   Example: "Added group_number field after users reported needing it"
   Example: "Split address into street, city, state, zip"';
