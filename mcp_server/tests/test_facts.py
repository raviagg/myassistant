import json
import pytest
import respx
import httpx
from tools.facts import create_fact, list_current_facts, get_current_fact, get_fact_history, search_facts


def test_create_fact(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/facts").mock(
            return_value=httpx.Response(201, json={"id": "f1", "operationType": "create"})
        )
        result = create_fact(http, document_id="d1", schema_id="s1",
                             operation_type="create", fields={"title": "test"})
        body = json.loads(respx.calls[0].request.content)
        assert body["documentId"] == "d1"
        assert body["schemaId"] == "s1"
        assert body["operationType"] == "create"
        assert body["fields"] == {"title": "test"}
        assert "entityInstanceId" not in body
        assert result["id"] == "f1"


def test_create_fact_with_entity_instance_id(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/facts").mock(
            return_value=httpx.Response(201, json={"id": "f2"})
        )
        create_fact(http, document_id="d1", schema_id="s1",
                    operation_type="update", fields={"status": "done"},
                    entity_instance_id="ei1")
        body = json.loads(respx.calls[0].request.content)
        assert body["entityInstanceId"] == "ei1"


def test_list_current_facts(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/current").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        result = list_current_facts(http, schema_id="s1")
        assert "schemaId=s1" in str(respx.calls[0].request.url)
        assert result["items"] == []


def test_get_current_fact(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/ei1/current").mock(
            return_value=httpx.Response(200, json={"entityInstanceId": "ei1", "fields": {}})
        )
        result = get_current_fact(http, entity_id="ei1")
        assert result["entityInstanceId"] == "ei1"


def test_get_fact_history(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/ei1/history").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 100, "offset": 0})
        )
        result = get_fact_history(http, entity_id="ei1")
        assert result["items"] == []


def test_search_facts(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/facts/search").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 20, "offset": 0})
        )
        result = search_facts(http, query="passport", domain="health")
        body = json.loads(respx.calls[0].request.content)
        assert body["query"] == "passport"
        assert body["domain"] == "health"
        assert result["items"] == []
