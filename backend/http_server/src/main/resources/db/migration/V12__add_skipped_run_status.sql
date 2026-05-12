-- V12__add_skipped_run_status.sql
-- Allow 'skipped' as a valid scheduled_job_run status (used when job runs but finds nothing to do)
ALTER TABLE scheduled_job_run
    DROP CONSTRAINT IF EXISTS scheduled_job_run_status_check,
    ADD CONSTRAINT scheduled_job_run_status_check
        CHECK (status IN ('success', 'error', 'partial', 'skipped'));
