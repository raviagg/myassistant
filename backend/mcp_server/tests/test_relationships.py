import json
import pytest
import respx
import httpx
from tools.relationships import (
    create_relationship, list_relationships, get_relationship,
    update_relationship, delete_relationship, resolve_kinship,
)


def test_create_relationship(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/relationships").mock(
            return_value=httpx.Response(201, json={"id": "r1", "relationType": "father"})
        )
        result = create_relationship(http, from_person_id="p1", to_person_id="p2", relation_type="father")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"fromPersonId": "p1", "toPersonId": "p2", "relationType": "father"}
        assert result["relationType"] == "father"


def test_list_relationships(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/relationships").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 100, "offset": 0})
        )
        result = list_relationships(http, person_id="p1")
        assert "person_id=p1" in str(respx.calls[0].request.url)
        assert result["items"] == []


def test_get_relationship(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/relationships/p1/p2").mock(
            return_value=httpx.Response(200, json={"id": "r1", "relationType": "father"})
        )
        result = get_relationship(http, from_person_id="p1", to_person_id="p2")
        assert result["relationType"] == "father"


def test_update_relationship(http):
    with respx.mock:
        respx.patch("http://testserver/api/v1/relationships/p1/p2").mock(
            return_value=httpx.Response(200, json={"id": "r1", "relationType": "mother"})
        )
        result = update_relationship(http, from_person_id="p1", to_person_id="p2", relation_type="mother")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"relationType": "mother"}
        assert result["relationType"] == "mother"


def test_delete_relationship(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/relationships/p1/p2").mock(
            return_value=httpx.Response(204)
        )
        result = delete_relationship(http, from_person_id="p1", to_person_id="p2")
        assert result == {}


def test_resolve_kinship(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/relationships/p1/p2/kinship").mock(
            return_value=httpx.Response(200, json={"chain": ["father", "sister"], "alias": "bua"})
        )
        result = resolve_kinship(http, from_person_id="p1", to_person_id="p2")
        assert result["alias"] == "bua"


def test_resolve_kinship_with_language(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/relationships/p1/p2/kinship").mock(
            return_value=httpx.Response(200, json={"chain": ["father", "sister"], "alias": "bua"})
        )
        resolve_kinship(http, from_person_id="p1", to_person_id="p2", language="hindi")
        assert "language=hindi" in str(respx.calls[0].request.url)
