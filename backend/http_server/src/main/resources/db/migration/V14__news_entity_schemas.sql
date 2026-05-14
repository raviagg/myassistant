-- V14__news_entity_schemas.sql
-- Retire news_topic; add news_preference + news_event; update news_article to v2.

-- Retire news_topic (no longer used — replaced by news_preference)
UPDATE entity_type_schema
SET is_active = false
WHERE entity_type = 'news_topic'
  AND domain_id = (SELECT id FROM domain WHERE name = 'news');

-- Retire news_article v1 (topic field replaced by event_id in v2)
UPDATE entity_type_schema
SET is_active = false
WHERE entity_type = 'news_article'
  AND domain_id = (SELECT id FROM domain WHERE name = 'news');

-- news_preference: single row per person, stores newsapi.ai category/source URIs
INSERT INTO entity_type_schema (domain_id, entity_type, schema_version, description, field_definitions)
SELECT id, 'news_preference', 1,
    'User news preferences — categories and sources used by the news_poll scheduler',
    '[
        {"name": "categories", "type": "text", "mandatory": true,  "description": "JSON array of newsapi.ai category URIs. Example: [\"dmoz/Business/Finance\",\"dmoz/Computers\"]"},
        {"name": "sources",    "type": "text", "mandatory": false, "description": "JSON array of newsapi.ai source URIs to filter by. Example: [\"reuters.com\",\"bbc.co.uk\"]. Omit for all sources."}
    ]'::jsonb
FROM domain WHERE name = 'news';

-- news_event: one row per event cluster returned by newsapi.ai
INSERT INTO entity_type_schema (domain_id, entity_type, schema_version, description, field_definitions)
SELECT id, 'news_event', 1,
    'A newsapi.ai event cluster — a group of related articles covering the same real-world event',
    '[
        {"name": "title",          "type": "text",   "mandatory": true,  "description": "Event title from newsapi.ai"},
        {"name": "summary",        "type": "text",   "mandatory": false, "description": "Brief event summary"},
        {"name": "event_uri",      "type": "text",   "mandatory": true,  "description": "Stable event identifier from newsapi.ai. Example: eng-1234567"},
        {"name": "category",       "type": "text",   "mandatory": false, "description": "Category URI this event was fetched under. Example: dmoz/Business/Finance"},
        {"name": "article_count",  "type": "number", "mandatory": false, "description": "Total number of articles in this event cluster"},
        {"name": "social_score",   "type": "number", "mandatory": false, "description": "Social media engagement score from newsapi.ai"},
        {"name": "published_date", "type": "date",   "mandatory": true,  "description": "Event publication date YYYY-MM-DD"}
    ]'::jsonb
FROM domain WHERE name = 'news';

-- news_article v2: articles reference their parent event via event_id; topic field removed
INSERT INTO entity_type_schema (domain_id, entity_type, schema_version, description, field_definitions)
SELECT id, 'news_article', 2,
    'A news article fetched by the scheduler, linked to a parent news_event',
    '[
        {"name": "headline",       "type": "text", "mandatory": true,  "description": "Article headline"},
        {"name": "source",         "type": "text", "mandatory": false, "description": "News outlet name. Example: BBC News, Reuters"},
        {"name": "url",            "type": "text", "mandatory": true,  "description": "Direct URL to the article"},
        {"name": "published_date", "type": "date", "mandatory": true,  "description": "Publication date YYYY-MM-DD"},
        {"name": "event_id",       "type": "text", "mandatory": true,  "description": "entity_instance_id of the parent news_event fact"},
        {"name": "description",    "type": "text", "mandatory": false, "description": "Short excerpt or summary from the article body"}
    ]'::jsonb
FROM domain WHERE name = 'news';
