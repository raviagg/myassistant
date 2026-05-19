-- ============================================================
-- 14_entity_ref_field_type.sql
-- Add entity_ref to the allowed field types in entity_type_schema
--
-- entity_ref fields store a UUID referencing another entity's
-- entity_instance_id — enabling FK-like relationships in the
-- fact store. Validation is enforced at the application layer
-- (MCP server checks the referenced entity exists before write).
-- ============================================================

ALTER TABLE entity_type_schema
    DROP CONSTRAINT field_definitions_complete;

ALTER TABLE entity_type_schema
    ADD CONSTRAINT field_definitions_complete CHECK (
        (SELECT bool_and(
            (f->>'name')        IS NOT NULL AND
            (f->>'type')        IS NOT NULL AND
            (f->>'mandatory')   IS NOT NULL AND
            (f->>'description') IS NOT NULL AND
            (f->>'type') IN ('text', 'number', 'date', 'boolean', 'file', 'entity_ref')
        ) FROM jsonb_array_elements(field_definitions) f)
    );
