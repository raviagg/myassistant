import json
import pytest
import respx
import httpx
from tools.documents import create_document, list_documents, get_document, search_documents


def test_create_document_minimal(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(201, json={"id": "d1", "contentText": "hello"})
        )
        result = create_document(http, content_text="hello", source_type="user_input")
        body = json.loads(respx.calls[0].request.content)
        assert body["contentText"] == "hello"
        assert body["sourceType"] == "user_input"
        assert body["files"] == []
        assert body["supersedesIds"] == []
        assert result["id"] == "d1"


def test_create_document_with_owner(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(201, json={"id": "d1"})
        )
        create_document(http, content_text="hi", source_type="user_input",
                        person_id="p1", household_id="hh1")
        body = json.loads(respx.calls[0].request.content)
        assert body["personId"] == "p1"
        assert body["householdId"] == "hh1"


def test_list_documents(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/documents").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        result = list_documents(http, person_id="p1", source_type="user_input")
        url = str(respx.calls[0].request.url)
        assert "personId=p1" in url
        assert "sourceType=user_input" in url
        assert result["items"] == []


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
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 20, "offset": 0})
        )
        result = search_documents(http, query="passport")
        body = json.loads(respx.calls[0].request.content)
        assert body["query"] == "passport"
        assert result["items"] == []
