import httpx
from client import _check


def log_interaction(
    http: httpx.Client,
    message: str,
    status: str,
    tool_calls: list,
    person_id: str | None = None,
    job_type: str | None = None,
    response: str | None = None,
    error: str | None = None,
) -> dict:
    """Persist a record of one interaction turn to the audit log."""
    body: dict = {"message": message, "status": status, "toolCalls": tool_calls}
    if person_id is not None:
        body["personId"] = person_id
    if job_type is not None:
        body["jobType"] = job_type
    if response is not None:
        body["response"] = response
    if error is not None:
        body["error"] = error
    resp = http.post("/api/v1/audit/interactions", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool()
    def log_interaction_tool(
        message: str,
        status: str,
        tool_calls: list,
        person_id: str | None = None,
        job_type: str | None = None,
        response: str | None = None,
        error: str | None = None,
    ) -> dict:
        """Persist a record of one interaction turn to the audit log."""
        return log_interaction(http, message, status, tool_calls, person_id, job_type, response, error)
