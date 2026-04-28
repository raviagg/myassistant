import json
import pytest
import respx
import httpx
from tools.audit import log_interaction


def test_log_interaction_for_person(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/audit/interactions").mock(
            return_value=httpx.Response(201, json={"id": "a1", "status": "success"})
        )
        result = log_interaction(
            http,
            message_text="hi",
            response_text="hello back",
            status="success",
            person_id="p1",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["messageText"] == "hi"
        assert body["responseText"] == "hello back"
        assert body["status"] == "success"
        assert body["personId"] == "p1"
        assert "toolCallsJson" not in body
        assert result["id"] == "a1"


def test_log_interaction_for_job(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/audit/interactions").mock(
            return_value=httpx.Response(201, json={"id": "a2"})
        )
        log_interaction(
            http,
            message_text="plaid sync",
            response_text="processed 5 transactions",
            status="success",
            job_type="plaid_poll",
            tool_calls_json=[{"tool": "create_fact", "args": {}}],
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["jobType"] == "plaid_poll"
        assert body["toolCallsJson"] == [{"tool": "create_fact", "args": {}}]
        assert "personId" not in body


def test_log_interaction_with_error(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/audit/interactions").mock(
            return_value=httpx.Response(201, json={"id": "a3"})
        )
        log_interaction(
            http,
            message_text="bad input",
            response_text="could not process",
            status="error",
            person_id="p1",
            error_message="Schema not found",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["status"] == "error"
        assert body["errorMessage"] == "Schema not found"
