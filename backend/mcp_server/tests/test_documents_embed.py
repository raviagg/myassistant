import json
import respx
import httpx
from unittest.mock import patch
from tools.documents import create_document, search_documents

_FAKE_EMBEDDING = [0.1] * 768


@respx.mock
def test_create_document_generates_embedding_internally():
    """create_document must NOT require an embedding param and must post a 768-dim vector."""
    captured = {}

    def capture(request, route):
        captured["body"] = json.loads(request.content)
        return httpx.Response(201, json={"id": "doc-1", "contentText": "hello", "embedding": []})

    respx.post("http://test/api/v1/documents").mock(side_effect=capture)

    http = httpx.Client(base_url="http://test")
    with patch("tools.documents.embed", return_value=_FAKE_EMBEDDING):
        create_document(http, content_text="hello world", source_type_id="src-1", person_id="p-1")

    assert "embedding" in captured["body"]
    assert len(captured["body"]["embedding"]) == 768


@respx.mock
def test_search_documents_accepts_query_text():
    """search_documents takes query_text: str, not embedding: list."""
    captured = {}

    def capture(request, route):
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"results": []})

    respx.post("http://test/api/v1/documents/search").mock(side_effect=capture)

    http = httpx.Client(base_url="http://test")
    with patch("tools.documents.embed", return_value=_FAKE_EMBEDDING):
        search_documents(http, query_text="passport details", person_id="p-1")

    assert "embedding" in captured["body"]
    assert len(captured["body"]["embedding"]) == 768
    assert "queryText" not in captured["body"]
