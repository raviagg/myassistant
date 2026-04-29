import httpx
from client import _check


def log_interaction(
    http: httpx.Client,
    message_text: str,
    response_text: str,
    status: str,
    person_id: str | None = None,
    job_type: str | None = None,
    tool_calls_json: list | None = None,
    error_message: str | None = None,
) -> dict:
    body: dict = {
        "messageText": message_text,
        "responseText": response_text,
        "status": status,
    }
    if person_id is not None:
        body["personId"] = person_id
    if job_type is not None:
        body["jobType"] = job_type
    if tool_calls_json is not None:
        body["toolCallsJson"] = tool_calls_json
    if error_message is not None:
        body["errorMessage"] = error_message
    resp = http.post("/api/v1/audit/interactions", json=body)
    _check(resp)
    return resp.json()


def register(mcp, http: httpx.Client) -> None:
    @mcp.tool(name="log_interaction")
    def _log_tool(
        message_text: str,
        response_text: str,
        status: str,
        person_id: str | None = None,
        job_type: str | None = None,
        tool_calls_json: list | None = None,
        error_message: str | None = None,
    ) -> dict:
        """Persist a record of one interaction turn to the audit log. Exactly one of person_id or job_type must be set."""
        return log_interaction(http, message_text, response_text, status, person_id, job_type, tool_calls_json, error_message)
