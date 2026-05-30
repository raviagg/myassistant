ALTER TABLE entity_type_schema
  ADD COLUMN connector_managed boolean NOT NULL DEFAULT false;

-- Mark schemas written by connectors (plaid_poll, news_poll) as connector-managed.
-- These are read-only via the API; changes require a code + migration.
UPDATE entity_type_schema ets
SET connector_managed = true
FROM domain d
WHERE ets.domain_id = d.id
  AND d.name = 'finance'
  AND ets.entity_type IN ('transaction', 'bank_account');

UPDATE entity_type_schema ets
SET connector_managed = true
FROM domain d
WHERE ets.domain_id = d.id
  AND d.name = 'news'
  AND ets.entity_type IN ('news_event', 'news_article');
