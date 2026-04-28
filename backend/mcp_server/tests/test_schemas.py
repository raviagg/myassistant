import json
import pytest
import respx
import httpx
from tools.schemas import (
    list_schemas, create_schema, get_current_schemas,
    get_schema, add_schema_version, deactivate_schema,
)

_FIELD_DEFS = [{"name": "title", "type": "text", "required": True}]


def test_list_schemas(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 100, "offset": 0})
        )
        result = list_schemas(http)
        assert result["items"] == []


def test_create_schema(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(201, json={"id": "sc1", "entityType": "todo_item"})
        )
        result = create_schema(
            http, domain="todo", entity_type="todo_item",
            description="A task", field_definitions=_FIELD_DEFS,
            extraction_prompt="Extract todo items",
        )
        body = json.loads(respx.calls[0].request.content)
        assert body["domain"] == "todo"
        assert body["entityType"] == "todo_item"
        assert body["fieldDefinitions"] == _FIELD_DEFS
        assert result["id"] == "sc1"


def test_get_current_schemas(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas/current").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 100, "offset": 0})
        )
        result = get_current_schemas(http)
        assert result["items"] == []


def test_get_schema(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/schemas/sc1").mock(
            return_value=httpx.Response(200, json={"id": "sc1", "entityType": "todo_item"})
        )
        result = get_schema(http, schema_id="sc1")
        assert result["id"] == "sc1"


def test_add_schema_version(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/schemas/todo/todo_item/versions").mock(
            return_value=httpx.Response(201, json={"id": "sc2", "schemaVersion": 2})
        )
        result = add_schema_version(
            http, domain="todo", entity_type="todo_item",
            description="v2", field_definitions=_FIELD_DEFS,
            extraction_prompt="Extract todos v2",
        )
        assert result["schemaVersion"] == 2


def test_deactivate_schema(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/schemas/todo/todo_item/active").mock(
            return_value=httpx.Response(204)
        )
        result = deactivate_schema(http, domain="todo", entity_type="todo_item")
        assert result == {}


def test_create_schema_raises_on_conflict(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/schemas").mock(
            return_value=httpx.Response(409, json={"error": "conflict"})
        )
        with pytest.raises(ValueError, match="409"):
            create_schema(http, domain="todo", entity_type="todo_item",
                          description="A task", field_definitions=_FIELD_DEFS,
                          extraction_prompt="prompt")
