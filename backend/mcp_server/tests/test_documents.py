import json
import pytest
import respx
import httpx
from tools.documents import create_document, list_documents, get_document, search_documents

_EMBEDDING = [0.1, 0.2, 0.3]


def test_create_document_minimal(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(201, json={"id": "d1", "contentText": "hello"})
        )
        result = create_document(
            http, content_text="hello", source_type_id="st-uuid", embedding=_EMBEDDING
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["contentText"] == "hello"
        assert body["sourceTypeId"] == "st-uuid"
        assert body["embedding"] == _EMBEDDING
        assert body["files"] == []
        assert body["supersedesIds"] == []
        assert result["id"] == "d1"


def test_create_document_with_owner(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(201, json={"id": "d1"})
        )
        create_document(
            http, content_text="hi", source_type_id="st-uuid", embedding=_EMBEDDING,
            person_id="p1", household_id="hh1",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["personId"] == "p1"
        assert body["householdId"] == "hh1"


def test_list_documents(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        result = list_documents(http, person_id="p1", source_type_id="st-uuid")
        url = str(respx.calls[0].request.url)
        assert "personId=p1" in url
        assert "sourceTypeId=st-uuid" in url
        assert result["items"] == []


def test_list_documents_with_date_range(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        list_documents(http, created_after="2024-01-01T00:00:00Z", created_before="2024-12-31T23:59:59Z")
        url = str(respx.calls[0].request.url)
        assert "createdAfter=" in url
        assert "createdBefore=" in url


def test_get_document(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/documents/d1").mock(
            return_value=httpx.Response(200, json={"id": "d1", "contentText": "hello"})
        )
        result = get_document(http, document_id="d1")
        assert result["contentText"] == "hello"


def test_search_documents(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/documents/search").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0})
        )
        result = search_documents(http, embedding=_EMBEDDING)
        body = json.loads(respx.calls[0].request.content)
        assert body["embedding"] == _EMBEDDING
        assert body["limit"] == 10
        assert body["similarityThreshold"] == 0.7
        assert result["items"] == []


def test_search_documents_with_filters(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/documents/search").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0})
        )
        search_documents(
            http, embedding=_EMBEDDING, person_id="p1",
            source_type_id="st-uuid", similarity_threshold=0.9,
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["personId"] == "p1"
        assert body["sourceTypeId"] == "st-uuid"
        assert body["similarityThreshold"] == 0.9
