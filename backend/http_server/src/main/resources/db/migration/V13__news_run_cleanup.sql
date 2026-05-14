-- V13__news_run_cleanup.sql
-- Replace articles_stored + error with generic status_detail TEXT.
-- Update status enum to: success, failure, skipped.

-- Step 1: drop old constraint + clean up columns (constraint must go first so the UPDATE can set 'failure')
ALTER TABLE scheduled_job_run
    DROP COLUMN IF EXISTS articles_stored,
    DROP COLUMN IF EXISTS error,
    ADD COLUMN IF NOT EXISTS status_detail TEXT,
    DROP CONSTRAINT IF EXISTS scheduled_job_run_status_check;

-- Step 2: remap legacy statuses now that no constraint is active
UPDATE scheduled_job_run
SET status = 'failure'
WHERE status IN ('error', 'partial');

-- Step 3: add the new constraint (only 'success'/'failure'/'skipped' rows now exist)
ALTER TABLE scheduled_job_run
    ADD CONSTRAINT scheduled_job_run_status_check
        CHECK (status IN ('success', 'failure', 'skipped'));
