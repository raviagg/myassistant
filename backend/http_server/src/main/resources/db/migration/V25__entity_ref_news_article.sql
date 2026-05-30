-- Fix news_article.event_id: was typed 'text' but is actually an entity_ref to news_event.
-- Add refEntityType so the UI can draw FK arrows between news_article and news_event cards.
UPDATE entity_type_schema
SET field_definitions = (
  SELECT jsonb_agg(
    CASE WHEN elem->>'name' = 'event_id'
    THEN elem || '{"type": "entity_ref", "refEntityType": "news_event"}'::jsonb
    ELSE elem
    END
    ORDER BY ordinality
  )
  FROM jsonb_array_elements(field_definitions) WITH ORDINALITY AS t(elem, ordinality)
)
WHERE entity_type = 'news_article'
  AND is_active = true
  AND domain_id = (SELECT id FROM domain WHERE name = 'news');
