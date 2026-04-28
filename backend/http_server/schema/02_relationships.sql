-- ============================================================
-- 02_relationships.sql
-- Direct person-to-person relationships and kinship aliases
--
-- Relationships are stored as depth-1 atomic links only.
-- (father, mother, son, daughter, brother, sister, husband, wife)
-- All deeper relations (grandfather, aunt, cousin etc.) are
-- derived at query time by traversing the relationship graph.
-- Cultural and language-specific names for derived relations
-- are stored in kinship_alias as chain-to-name mappings.
-- ============================================================


-- ------------------------------------------------------------
-- TYPES
-- ------------------------------------------------------------

CREATE TYPE relation_type AS ENUM (
  'father',
  'mother',
  'son',
  'daughter',
  'brother',
  'sister',
  'husband',
  'wife'
);


-- ------------------------------------------------------------
-- TABLES
-- ------------------------------------------------------------

CREATE TABLE relationship (
  id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  person_id_a    UUID          NOT NULL REFERENCES person(id),
  person_id_b    UUID          NOT NULL REFERENCES person(id),
  relation_type  relation_type NOT NULL,
  created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

  -- one row per directed A->B relation
  UNIQUE (person_id_a, person_id_b, relation_type),

  -- a person cannot have a relationship with themselves
  CONSTRAINT no_self_relationship CHECK (person_id_a <> person_id_b)
);

CREATE INDEX idx_relationship_a ON relationship(person_id_a);
CREATE INDEX idx_relationship_b ON relationship(person_id_b);

CREATE TRIGGER relationship_updated_at
  BEFORE UPDATE ON relationship
  FOR EACH ROW
  EXECUTE FUNCTION update_updated_at();

-- ------------------------------------------------------------

CREATE TABLE kinship_alias (
  id              SERIAL        PRIMARY KEY,
  relation_chain  relation_type[] NOT NULL,
  language        TEXT          NOT NULL,
  alias           TEXT          NOT NULL,
  description     TEXT,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

  -- one alias per chain per language
  UNIQUE (relation_chain, language)
);


-- ------------------------------------------------------------
-- SEED DATA — kinship_alias
-- ------------------------------------------------------------

INSERT INTO kinship_alias
  (relation_chain, language, alias, description)
VALUES
  -- Hindi aliases
  (ARRAY['father', 'sister']::relation_type[],
    'hindi', 'bua',   'father''s sister'),
  (ARRAY['mother', 'brother']::relation_type[],
    'hindi', 'mama',  'mother''s brother'),
  (ARRAY['mother', 'sister']::relation_type[],
    'hindi', 'mausi', 'mother''s sister'),
  (ARRAY['father', 'brother']::relation_type[],
    'hindi', 'chacha','father''s brother'),
  (ARRAY['father', 'father']::relation_type[],
    'hindi', 'dada',  'father''s father'),
  (ARRAY['father', 'mother']::relation_type[],
    'hindi', 'dadi',  'father''s mother'),
  (ARRAY['mother', 'father']::relation_type[],
    'hindi', 'nana',  'mother''s father'),
  (ARRAY['mother', 'mother']::relation_type[],
    'hindi', 'nani',  'mother''s mother'),
  (ARRAY['brother', 'son']::relation_type[],
    'hindi', 'bhatija',  'brother''s son'),
  (ARRAY['brother', 'daughter']::relation_type[],
    'hindi', 'bhatiji',  'brother''s daughter'),

  -- English aliases
  (ARRAY['father', 'sister']::relation_type[],
    'english', 'paternal aunt',        'father''s sister'),
  (ARRAY['mother', 'brother']::relation_type[],
    'english', 'maternal uncle',       'mother''s brother'),
  (ARRAY['mother', 'sister']::relation_type[],
    'english', 'maternal aunt',        'mother''s sister'),
  (ARRAY['father', 'brother']::relation_type[],
    'english', 'paternal uncle',       'father''s brother'),
  (ARRAY['father', 'father']::relation_type[],
    'english', 'paternal grandfather', 'father''s father'),
  (ARRAY['father', 'mother']::relation_type[],
    'english', 'paternal grandmother', 'father''s mother'),
  (ARRAY['mother', 'father']::relation_type[],
    'english', 'maternal grandfather', 'mother''s father'),
  (ARRAY['mother', 'mother']::relation_type[],
    'english', 'maternal grandmother', 'mother''s mother');


-- ------------------------------------------------------------
-- COMMENTS — TYPES
-- ------------------------------------------------------------

COMMENT ON TYPE relation_type IS
  'Depth-1 atomic relationship types between two persons.
   Intentionally minimal — only direct relations are stored.
   All deeper relations are derived by graph traversal at query time.
   Example: grandfather = father->father chain traversal.';


-- ------------------------------------------------------------
-- COMMENTS — TABLES
-- ------------------------------------------------------------

COMMENT ON TABLE relationship IS
  'Stores direct depth-1 relationships between two persons.
   relation_type always describes A''s role toward B:
   "person_id_a IS [relation_type] OF person_id_b"
   Example: person_id_a=Raj, relation_type=father, person_id_b=Arjun
   means "Raj is father of Arjun".
   Deeper relations like grandfather, aunt, cousin are not stored —
   they are derived at query time by traversing the graph.
   Use kinship_alias to resolve derived chains to named terms.';

COMMENT ON TABLE kinship_alias IS
  'Maps a chain of depth-1 relation types to a named alias
   in a specific language. Supports any language — Hindi,
   English, Tamil, Bengali etc.
   Coverage is partial — not all chains need an alias.
   Unknown chains fall back to plain English description:
   [father, brother, son] -> "father''s brother''s son"
   Resolution flow:
   1. Traverse relationship graph to get chain
   2. Lookup chain + language in this table
   3. If no match, join chain with apostrophe-s as fallback';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS: relationship
-- ------------------------------------------------------------

COMMENT ON COLUMN relationship.id IS
  'Unique identifier for this relationship record.
   Example: "c3d4e5f6-a7b8-9012-cdef-123456789012"';

COMMENT ON COLUMN relationship.person_id_a IS
  'The person who holds the relation_type role.
   "A is [relation_type] of B"
   Example: if relation_type=father, this is the father.';

COMMENT ON COLUMN relationship.person_id_b IS
  'The person toward whom the relation_type is directed.
   "A is [relation_type] of B"
   Example: if relation_type=father, this is the child.';

COMMENT ON COLUMN relationship.relation_type IS
  'The relationship A holds toward B.
   Always directional — describes A''s role, not B''s.
   Example: father means "A is father of B".
   To get B''s role toward A, look up the inverse relation
   or derive it from the enum (father -> son/daughter etc.)';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS: kinship_alias
-- ------------------------------------------------------------

COMMENT ON COLUMN kinship_alias.relation_chain IS
  'Ordered array of depth-1 relation_type values representing
   the traversal path from person A to a related person.
   Example: [father, sister] means "A''s father''s sister"
   Example: [mother, father] means "A''s mother''s father"';

COMMENT ON COLUMN kinship_alias.language IS
  'Language of the alias. Lowercase, no spaces.
   Example: "hindi", "english", "tamil", "bengali"';

COMMENT ON COLUMN kinship_alias.alias IS
  'The culturally specific name for this kinship relation
   in the given language.
   Example: "bua" (Hindi for father''s sister)
   Example: "paternal aunt" (English for father''s sister)';

COMMENT ON COLUMN kinship_alias.description IS
  'Plain English description of what this relation chain means.
   Always in English regardless of the alias language.
   Example: "father''s sister", "mother''s brother''s son"';
