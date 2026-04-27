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
            http, message="hi", status="success",
            tool_calls=[], person_id="p1",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["message"] == "hi"
        assert body["status"] == "success"
        assert body["personId"] == "p1"
        assert body["toolCalls"] == []
        assert result["id"] == "a1"


def test_log_interaction_for_job(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/audit/interactions").mock(
            return_value=httpx.Response(201, json={"id": "a2"})
        )
        log_interaction(
            http, message="plaid sync", status="success",
            tool_calls=[], job_type="plaid_poll",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["jobType"] == "plaid_poll"
        assert "personId" not in body
