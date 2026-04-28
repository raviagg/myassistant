import json
import pytest
import respx
import httpx
from tools.facts import (
    create_fact, get_fact_history, get_current_fact,
    list_current_facts, search_current_facts,
)

_EMBEDDING = [0.1, 0.2, 0.3]


def test_create_fact(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/facts").mock(
            return_value=httpx.Response(201, json={"id": "f1", "operationType": "create"})
        )
        result = create_fact(
            http, document_id="d1", schema_id="s1",
            entity_instance_id="ei1", operation_type="create",
            fields={"title": "test"}, embedding=_EMBEDDING,
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["documentId"] == "d1"
        assert body["schemaId"] == "s1"
        assert body["entityInstanceId"] == "ei1"
        assert body["operationType"] == "create"
        assert body["fields"] == {"title": "test"}
        assert body["embedding"] == _EMBEDDING
        assert result["id"] == "f1"


def test_get_fact_history(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/ei1/history").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0})
        )
        result = get_fact_history(http, entity_instance_id="ei1")
        assert result["items"] == []


def test_get_current_fact(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/ei1/current").mock(
            return_value=httpx.Response(200, json={"entityInstanceId": "ei1", "fields": {}})
        )
        result = get_current_fact(http, entity_instance_id="ei1")
        assert result["entityInstanceId"] == "ei1"


def test_list_current_facts_no_filter(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/current").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        result = list_current_facts(http)
        assert result["items"] == []


def test_list_current_facts_with_filters(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/facts/current").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        list_current_facts(http, person_id="p1", domain_id="dom-uuid", entity_type="todo_item")
        url = str(respx.calls[0].request.url)
        assert "personId=p1" in url
        assert "domainId=dom-uuid" in url
        assert "entityType=todo_item" in url


def test_search_current_facts(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/facts/search").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0})
        )
        result = search_current_facts(http, embedding=_EMBEDDING)
        body = json.loads(respx.calls[0].request.content)
        assert body["embedding"] == _EMBEDDING
        assert body["limit"] == 10
        assert body["similarityThreshold"] == 0.7
        assert result["items"] == []


def test_search_current_facts_with_filters(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/facts/search").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0})
        )
        search_current_facts(
            http, embedding=_EMBEDDING, domain_id="dom-uuid",
            entity_type="todo_item", similarity_threshold=0.9,
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["domainId"] == "dom-uuid"
        assert body["entityType"] == "todo_item"
        assert body["similarityThreshold"] == 0.9
