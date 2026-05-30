-- V26__cleanup_legacy_schemas_and_txn_fk.sql

-- Deactivate vestigial entity_type_schemas:
--   news_preference: scheduler now reads from source_connection.config instead.
--   plaid_connection: replaced by native plaid.connections table (V20).
UPDATE entity_type_schema SET is_active = false
WHERE entity_type IN ('news_preference', 'plaid_connection');

-- Add bank_account_id entity_ref field to the active transaction schema.
-- The value written by plaid_poll is uuid5(NAMESPACE_URL, plaid_account_id),
-- matching the entityInstanceId used when writing bank_account facts.
UPDATE entity_type_schema
SET field_definitions = field_definitions || '[{
  "name":          "bank_account_id",
  "type":          "entity_ref",
  "mandatory":     false,
  "refEntityType": "bank_account",
  "description":   "entity_instance_id of the parent bank_account fact"
}]'::jsonb
WHERE entity_type = 'transaction'
  AND is_active   = true
  AND domain_id   = (SELECT id FROM domain WHERE name = 'finance');
