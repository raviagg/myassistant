-- ============================================================
-- 07_audit.sql
-- Audit log — interaction history for chat and system jobs
--
-- Every interaction with the system is logged here — whether
-- initiated by a human user via chat or by an automated
-- polling job. Each row captures the incoming message,
-- the outgoing response, and the tool calls made in between.
--
-- Chatbot interactions have person_id set (the user chatting).
-- System interactions have job_type set (the polling job type).
-- Exactly one of the two must be present — enforced by constraint.
--
-- Rows are append-only — never updated or deleted.
-- ============================================================


-- ------------------------------------------------------------
-- TABLES
-- ------------------------------------------------------------

CREATE TABLE audit_log (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  person_id   UUID        REFERENCES person(id),
  job_type    TEXT        REFERENCES source_type(name),
  message     TEXT        NOT NULL,
  response    TEXT,
  tool_calls  JSONB       NOT NULL DEFAULT '[]',
  status      TEXT        NOT NULL DEFAULT 'success'
    CHECK (status IN ('success', 'partial', 'failed')),
  error       TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- exactly one actor: either a person chatting or a system job
  -- never both, never neither
  CONSTRAINT exactly_one_actor CHECK (
    (person_id IS NOT NULL AND job_type IS NULL) OR
    (person_id IS NULL     AND job_type IS NOT NULL)
  )
);

CREATE INDEX idx_audit_log_person
  ON audit_log(person_id)
  WHERE person_id IS NOT NULL;

CREATE INDEX idx_audit_log_job_type
  ON audit_log(job_type)
  WHERE job_type IS NOT NULL;

CREATE INDEX idx_audit_log_created
  ON audit_log(created_at DESC);

CREATE INDEX idx_audit_log_status
  ON audit_log(status)
  WHERE status != 'success';


-- ------------------------------------------------------------
-- COMMENTS — TABLES
-- ------------------------------------------------------------

COMMENT ON TABLE audit_log IS
  'Append-only interaction log covering all activity in the system.
   Two kinds of interactions are recorded:
   1. Chatbot — a person typed a message and the agent responded.
      person_id is set, job_type is null.
   2. System — a polling job sent data to the agent for processing.
      job_type is set, person_id is null.
   Both kinds follow the same message/response/tool_calls pattern
   because polling jobs interact with the agent the same way a
   person does — by sending a natural language message and
   receiving an acknowledgment response.
   Rows are never updated or deleted — full interaction history
   is always preserved.
   Useful for: debugging, replaying failed interactions,
   understanding what the system did and why, usage analytics.';


-- ------------------------------------------------------------
-- COMMENTS — COLUMNS: audit_log
-- ------------------------------------------------------------

COMMENT ON COLUMN audit_log.id IS
  'Unique identifier for this interaction record.
   Example: "a7b8c9d0-e1f2-3456-abcd-567890123456"';

COMMENT ON COLUMN audit_log.person_id IS
  'The person who initiated this interaction via chat.
   Null for system/polling job interactions.
   Exactly one of person_id or job_type must be non-null.
   Foreign key to person.id.';

COMMENT ON COLUMN audit_log.job_type IS
  'The type of polling job that initiated this interaction.
   Null for chatbot interactions initiated by a person.
   Exactly one of person_id or job_type must be non-null.
   Foreign key to source_type.name.
   Example: "plaid_poll", "gmail_poll"';

COMMENT ON COLUMN audit_log.message IS
  'The incoming message that triggered this interaction.
   For chatbot: the exact text the user typed in chat.
   For system jobs: the formatted NL message constructed
   by the polling job summarising the fetched data.
   Example (chatbot): "I just got a raise, now making 140k at Acme"
   Example (plaid_poll): "Here are 12 new transactions from
   Chase Checking account from the last 24 hours: ..."';

COMMENT ON COLUMN audit_log.response IS
  'The agent''s response to the incoming message.
   For chatbot: what the agent replied to the user.
   For system jobs: the acknowledgment from the agent
   confirming what was stored.
   Null if the interaction failed before a response was generated.
   Example (chatbot): "Got it — updated your salary at Acme to $140,000."
   Example (plaid_poll): "Acknowledged. Stored 12 transactions,
   extracted 12 finance facts."';

COMMENT ON COLUMN audit_log.tool_calls IS
  'JSONB array of all MCP tool calls made during this interaction,
   in the order they were called. Each element captures the tool
   name, input parameters, and output result.
   Empty array if no tools were called (e.g. pure conversational reply).
   Useful for debugging, replaying failed interactions, and
   understanding the full orchestration behind any response.
   Example:
   [
     {
       "tool":   "create_document",
       "input":  {"person_id": "...", "content_text": "...", "source_type": "user_input"},
       "output": {"id": "...", "created_at": "..."}
     },
     {
       "tool":   "extract_facts_from_document",
       "input":  {"document_id": "..."},
       "output": {"proposed_facts": [...], "missing_mandatory_fields": []}
     },
     {
       "tool":   "create_fact",
       "input":  {"schema_id": "...", "operation_type": "create", "fields": {...}},
       "output": {"id": "...", "entity_instance_id": "..."}
     }
   ]';

COMMENT ON COLUMN audit_log.status IS
  'Outcome of this interaction.
   success — all tool calls completed, response delivered.
   partial — some tool calls succeeded, some failed.
             e.g. document created but fact extraction failed.
   failed  — interaction did not complete successfully.
             Check the error column for details.';

COMMENT ON COLUMN audit_log.error IS
  'Error message or stack trace if status is failed or partial.
   Null when status is success.
   Example: "Mandatory field ''provider'' missing from extracted facts.
   User did not respond to clarification prompt."';

COMMENT ON COLUMN audit_log.created_at IS
  'Timestamp when this interaction occurred.
   For chatbot: when the user sent their message.
   For system jobs: when the polling job ran.
   Indexed descending for efficient recent-history queries.';
