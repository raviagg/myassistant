-- ============================================================
-- V22__unified_schema.sql
-- unified_schema — LLM-proposed cross-source schema definitions
--
-- Each row belongs to exactly one person OR one household
-- (enforced by CHECK constraint). field_definitions stores
-- the full schema definition as JSONB — array of field objects,
-- each with per-source mappings.
-- ============================================================

CREATE TABLE unified_schema (
  id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  person_id        UUID        REFERENCES person(id) ON DELETE CASCADE,
  household_id     UUID        REFERENCES household(id) ON DELETE CASCADE,
  name             TEXT        NOT NULL,
  description      TEXT,
  status           TEXT        NOT NULL DEFAULT 'proposed',
  field_definitions JSONB      NOT NULL DEFAULT '[]'::jsonb,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT usm_exactly_one_owner CHECK (
    (person_id IS NOT NULL)::int + (household_id IS NOT NULL)::int = 1
  ),
  CONSTRAINT usm_status_valid CHECK (status IN ('proposed', 'approved'))
);

CREATE INDEX idx_unified_schema_person    ON unified_schema(person_id)    WHERE person_id    IS NOT NULL;
CREATE INDEX idx_unified_schema_household ON unified_schema(household_id) WHERE household_id IS NOT NULL;

CREATE TRIGGER unified_schema_updated_at
  BEFORE UPDATE ON unified_schema
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();

COMMENT ON TABLE unified_schema IS
  'LLM-proposed, user-tunable unified schema definitions.
   Each row is scoped to exactly one person or household.
   field_definitions is a JSONB array: each element is
   {"name":str, "type":str, "status":"approved"|"pending"|"rejected",
    "sources":[{"source_connection_id":uuid, "source_table":str, "source_field":str}]}.
   Rejected fields are excluded from read-time UNION queries.';
