-- V11__news_scheduler.sql
-- Rename news_preferences domain to news
INSERT INTO domain (name, description)
VALUES ('news', 'News articles, topics and content preferences');

UPDATE entity_type_schema
SET domain_id = (SELECT id FROM domain WHERE name = 'news')
WHERE domain_id = (SELECT id FROM domain WHERE name = 'news_preferences');

DELETE FROM domain WHERE name = 'news_preferences';

-- Add is_scheduled flag to source_type
ALTER TABLE source_type ADD COLUMN is_scheduled BOOLEAN NOT NULL DEFAULT false;

INSERT INTO source_type (name, description, is_scheduled)
VALUES ('news_poll', 'News articles fetched from NewsAPI', true);

-- scheduled_job: stores all recurring job definitions
CREATE TABLE scheduled_job (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_type     TEXT        NOT NULL REFERENCES source_type(name),
    person_id       UUID        REFERENCES person(id),
    household_id    UUID        REFERENCES household(id),
    cron_expression TEXT        NOT NULL,
    config          JSONB       NOT NULL DEFAULT '{}',
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    next_run_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT scheduled_job_scope CHECK (
        (person_id IS NOT NULL) != (household_id IS NOT NULL)
    )
);

CREATE INDEX idx_scheduled_job_person_id ON scheduled_job(person_id) WHERE person_id IS NOT NULL;
CREATE INDEX idx_scheduled_job_next_run  ON scheduled_job(next_run_at) WHERE enabled = true;

-- scheduled_job_run: one row per execution of a scheduled_job
CREATE TABLE scheduled_job_run (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id          UUID        NOT NULL REFERENCES scheduled_job(id),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    status          TEXT        NOT NULL CHECK (status IN ('success', 'error', 'partial')),
    error           TEXT,
    articles_stored INT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_scheduled_job_run_job_id ON scheduled_job_run(job_id);

-- news_topic entity type schema
INSERT INTO entity_type_schema (domain_id, entity_type, schema_version, description, field_definitions)
SELECT id, 'news_topic', 1,
    'A news topic the user wants to follow',
    '[
        {"name": "name",   "type": "text",    "mandatory": true,  "description": "Topic label. Example: AI, climate change, personal finance"},
        {"name": "active", "type": "boolean", "mandatory": false, "description": "Whether this topic is currently active. Default true."}
    ]'::jsonb
FROM domain WHERE name = 'news';

-- news_article entity type schema
INSERT INTO entity_type_schema (domain_id, entity_type, schema_version, description, field_definitions)
SELECT id, 'news_article', 1,
    'A news article fetched by the scheduler',
    '[
        {"name": "headline",       "type": "text", "mandatory": true,  "description": "Article headline"},
        {"name": "source",         "type": "text", "mandatory": false, "description": "News outlet name. Example: BBC News, Reuters"},
        {"name": "url",            "type": "text", "mandatory": true,  "description": "Direct URL to the article"},
        {"name": "published_date", "type": "date", "mandatory": true,  "description": "Publication date YYYY-MM-DD"},
        {"name": "topic",          "type": "text", "mandatory": false, "description": "Which news_topic this article relates to"},
        {"name": "description",    "type": "text", "mandatory": false, "description": "Short 2-3 sentence summary from the news source"}
    ]'::jsonb
FROM domain WHERE name = 'news';
