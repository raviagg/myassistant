import httpx
from client import _check


def create_scheduled_job(
    http: httpx.Client,
    source_type: str,
    cron_expression: str,
    person_id: str | None = None,
    household_id: str | None = None,
    config: dict | None = None,
) -> dict:
    """Create a new scheduled job."""
    body: dict = {
        "sourceType": source_type,
        "cronExpression": cron_expression,
    }
    if person_id is not None:
        body["personId"] = person_id
    if household_id is not None:
        body["householdId"] = household_id
    if config is not None:
        body["config"] = config
    resp = http.post("/api/v1/scheduled-jobs", json=body)
    _check(resp)
    return resp.json()


def list_scheduled_jobs(
    http: httpx.Client,
    person_id: str | None = None,
    household_id: str | None = None,
) -> list[dict]:
    """List scheduled jobs for a person or household.
    Exactly one of person_id or household_id must be provided.
    """
    params: dict = {}
    if person_id is not None:
        params["personId"] = person_id
    if household_id is not None:
        params["householdId"] = household_id
    resp = http.get("/api/v1/scheduled-jobs", params=params)
    _check(resp)
    return resp.json()


def update_scheduled_job(
    http: httpx.Client,
    job_id: str,
    cron_expression: str | None = None,
    config: dict | None = None,
    enabled: bool | None = None,
) -> dict:
    """Update a scheduled job. Only provided fields are changed."""
    body: dict = {}
    if cron_expression is not None:
        body["cronExpression"] = cron_expression
    if config is not None:
        body["config"] = config
    if enabled is not None:
        body["enabled"] = enabled
    resp = http.patch(f"/api/v1/scheduled-jobs/{job_id}", json=body)
    _check(resp)
    return resp.json()


def delete_scheduled_job(
    http: httpx.Client,
    job_id: str,
) -> bool:
    """Delete a scheduled job. Returns True if deleted."""
    resp = http.delete(f"/api/v1/scheduled-jobs/{job_id}")
    if resp.status_code == 404:
        return False
    _check(resp)
    return True
