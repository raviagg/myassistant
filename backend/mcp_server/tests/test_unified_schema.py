import pytest
import respx
import httpx
from tools.unified_schema import list_unified_schemas, query_unified_schema


def test_list_unified_schemas_no_filter(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/unified-schemas").mock(
            return_value=httpx.Response(200, json={"items": []})
        )
        result = list_unified_schemas(http)
        assert result["items"] == []


def test_list_unified_schemas_with_person_id(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/unified-schemas").mock(
            return_value=httpx.Response(200, json={"items": [{"id": "u1", "name": "transaction"}]})
        )
        result = list_unified_schemas(http, person_id="p1")
        req = respx.calls[0].request
        assert b"personId=p1" in req.url.query
        assert result["items"][0]["id"] == "u1"


def test_list_unified_schemas_with_household_id(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/unified-schemas").mock(
            return_value=httpx.Response(200, json={"items": []})
        )
        list_unified_schemas(http, household_id="h1")
        req = respx.calls[0].request
        assert b"householdId=h1" in req.url.query


def test_query_unified_schema_default_params(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/unified-schemas/u1/data").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        result = query_unified_schema(http, unified_schema_id="u1")
        assert result["total"] == 0
        assert result["limit"] == 50


def test_query_unified_schema_custom_params(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/unified-schemas/u1/data").mock(
            return_value=httpx.Response(200, json={"items": [{"sourceType": "plaid_poll", "fields": {}}], "total": 1, "limit": 10, "offset": 5})
        )
        result = query_unified_schema(http, unified_schema_id="u1", limit=10, offset=5)
        req = respx.calls[0].request
        assert b"limit=10" in req.url.query
        assert b"offset=5" in req.url.query
        assert result["total"] == 1


def test_query_unified_schema_propagates_http_error(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/unified-schemas/bad/data").mock(
            return_value=httpx.Response(404, json={"error": "not_found"})
        )
        with pytest.raises(ValueError, match="404"):
            query_unified_schema(http, unified_schema_id="bad")
