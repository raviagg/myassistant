import json
import pytest
import respx
import httpx
from tools.persons import (
    list_persons, create_person, get_person, update_person, delete_person,
)

def test_list_persons_no_filter(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        result = list_persons(http)
        assert result["items"] == []
        assert respx.calls[0].request.headers["Authorization"] == "Bearer test-token"

def test_list_persons_with_household(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(200, json={"items": [], "total": 0, "limit": 50, "offset": 0})
        )
        list_persons(http, household_id="hh-1")
        assert "householdId=hh-1" in str(respx.calls[0].request.url)

def test_create_person_required_fields(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(201, json={"id": "p1", "fullName": "Alice", "gender": "female"})
        )
        result = create_person(http, full_name="Alice", gender="female")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"fullName": "Alice", "gender": "female"}
        assert result["id"] == "p1"

def test_create_person_optional_fields(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(201, json={"id": "p2"})
        )
        create_person(http, full_name="Bob", gender="male", date_of_birth="1990-01-01",
                      preferred_name="Bobby", user_identifier="bob@example.com")
        body = json.loads(respx.calls[0].request.content)
        assert body["dateOfBirth"] == "1990-01-01"
        assert body["preferredName"] == "Bobby"
        assert body["userIdentifier"] == "bob@example.com"

def test_get_person(http):
    with respx.mock:
        respx.get("http://testserver/api/v1/persons/p1").mock(
            return_value=httpx.Response(200, json={"id": "p1", "fullName": "Alice"})
        )
        result = get_person(http, person_id="p1")
        assert result["fullName"] == "Alice"

def test_update_person(http):
    with respx.mock:
        respx.patch("http://testserver/api/v1/persons/p1").mock(
            return_value=httpx.Response(200, json={"id": "p1", "fullName": "Alicia"})
        )
        result = update_person(http, person_id="p1", full_name="Alicia")
        body = json.loads(respx.calls[0].request.content)
        assert body == {"fullName": "Alicia"}
        assert result["fullName"] == "Alicia"

def test_delete_person(http):
    with respx.mock:
        respx.delete("http://testserver/api/v1/persons/p1").mock(
            return_value=httpx.Response(204)
        )
        result = delete_person(http, person_id="p1")
        assert result == {}

def test_create_person_raises_on_4xx(http):
    with respx.mock:
        respx.post("http://testserver/api/v1/persons").mock(
            return_value=httpx.Response(422, json={"error": "validation_error"})
        )
        with pytest.raises(ValueError, match="422"):
            create_person(http, full_name="X", gender="unknown")
