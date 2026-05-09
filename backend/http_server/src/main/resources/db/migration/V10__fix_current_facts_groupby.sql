-- Fix current_facts view: document_id was in GROUP BY, causing facts from
-- different documents for the same entity instance to appear as separate rows
-- instead of being merged. Updates from a second chat message were not merged
-- with the original create, so only the changed fields were visible.

DROP VIEW IF EXISTS current_facts;

CREATE VIEW current_facts AS
WITH deleted AS (
  SELECT DISTINCT entity_instance_id
  FROM fact
  WHERE operation_type = 'delete'
),
merged AS (
  SELECT
    f.entity_instance_id,
    f.schema_id,
    (array_agg(f.document_id ORDER BY f.created_at ASC))[1] AS document_id,
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
    f.schema_id
)
SELECT m.*
FROM merged m
WHERE m.entity_instance_id NOT IN (
  SELECT entity_instance_id FROM deleted
);
