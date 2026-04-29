import json
import pytest
import respx
import httpx
from tools.schemas import (
    list_entity_type_schemas, get_entity_type_schema, get_current_entity_type_schema,
    create_entity_type_schema, update_entity_type_schema, deactivate_entity_type_schema,
)

_DOMAIN_ID = "dom-uuid-1"
_FIELD_DEFS = [{"name": "title", "type": "text", "mandatory": True}]


def test_list_entity_type_schemas_default(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0})
        )
        result = list_entity_type_schemas(http)
        assert "activeOnly=true" in str(respx.calls[0].request.url)
        assert result["items"] == []


def test_list_entity_type_schemas_with_filters(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0})
        )
        list_entity_type_schemas(http, domain_id=_DOMAIN_ID, entity_type="todo_item", active_only=False)
        url = str(respx.calls[0].request.url)
        assert f"domainId={_DOMAIN_ID}" in url
        assert "entityType=todo_item" in url
        assert "activeOnly=false" in url


def test_get_entity_type_schema(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas/sc1").mock(
            return_value=httpx.Response(200, json={"id": "sc1", "entityType": "todo_item"})
        )
        result = get_entity_type_schema(http, schema_id="sc1")
        assert result["id"] == "sc1"


def test_get_current_entity_type_schema(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas/current").mock(
            return_value=httpx.Response(200, json={"id": "sc1", "entityType": "todo_item"})
        )
        result = get_current_entity_type_schema(http, domain_id=_DOMAIN_ID, entity_type="todo_item")
        url = str(respx.calls[0].request.url)
        assert f"domainId={_DOMAIN_ID}" in url
        assert "entityType=todo_item" in url
        assert result["id"] == "sc1"


def test_create_entity_type_schema(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(201, json={"id": "sc1", "entityType": "todo_item"})
        )
        result = create_entity_type_schema(
            http, domain_id=_DOMAIN_ID, entity_type="todo_item",
            field_definitions=_FIELD_DEFS, description="A task",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["domainId"] == _DOMAIN_ID
        assert body["entityType"] == "todo_item"
        assert body["fieldDefinitions"] == _FIELD_DEFS
        assert body["description"] == "A task"
        assert "extractionPrompt" not in body
        assert result["id"] == "sc1"


def test_update_entity_type_schema(http):
    with respx.mock:
        respx.post(f"http://testserver/api/v1/schemas/{_DOMAIN_ID}/todo_item/versions").mock(
            return_value=httpx.Response(201, json={"id": "sc2", "schemaVersion": 2})
        )
        result = update_entity_type_schema(
            http, domain_id=_DOMAIN_ID, entity_type="todo_item",
            field_definitions=_FIELD_DEFS,
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["fieldDefinitions"] == _FIELD_DEFS
        assert "extractionPrompt" not in body
        assert result["schemaVersion"] == 2


def test_deactivate_entity_type_schema(http):
    with respx.mock:
        respx.delete(f"http://testserver/api/v1/schemas/{_DOMAIN_ID}/todo_item/active").mock(
            return_value=httpx.Response(204)
        )
        result = deactivate_entity_type_schema(http, domain_id=_DOMAIN_ID, entity_type="todo_item")
        assert result == {}


def test_create_entity_type_schema_raises_on_conflict(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(409, json={"error": "conflict"})
        )
        with pytest.raises(ValueError, match="409"):
            create_entity_type_schema(
                http, domain_id=_DOMAIN_ID, entity_type="todo_item",
                field_definitions=_FIELD_DEFS,
            )
