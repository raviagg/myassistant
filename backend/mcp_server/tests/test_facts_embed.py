import json
import respx
import httpx
from unittest.mock import patch
from tools.facts import create_fact, search_current_facts

_FAKE_EMBEDDING = [0.1] * 768


@respx.mock
def test_create_fact_generates_embedding_internally():
    """create_fact must NOT require an embedding param and must post a 768-dim vector."""
    captured = {}

    def capture(request, route):
        captured["body"] = json.loads(request.content)
        return httpx.Response(201, json={"id": "fact-1"})

    respx.post("http://test/api/v1/facts").mock(side_effect=capture)

    http = httpx.Client(base_url="http://test")
    with patch("tools.facts.embed", return_value=_FAKE_EMBEDDING):
        create_fact(
            http,
            document_id="doc-1",
            schema_id="schema-1",
            entity_instance_id="inst-1",
            operation_type="create",
            fields={"title": "Renew passport", "status": "pending"},
        )

    assert "embedding" in captured["body"]
    assert len(captured["body"]["embedding"]) == 768


@respx.mock
def test_search_current_facts_accepts_query_text():
    """search_current_facts takes query_text: str, not embedding: list."""
    captured = {}

    def capture(request, route):
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"results": []})

    respx.post("http://test/api/v1/facts/search").mock(side_effect=capture)

    http = httpx.Client(base_url="http://test")
    with patch("tools.facts.embed", return_value=_FAKE_EMBEDDING):
        search_current_facts(http, query_text="passport renewal task", person_id="p-1")

    assert "embedding" in captured["body"]
    assert len(captured["body"]["embedding"]) == 768
    assert "queryText" not in captured["body"]
